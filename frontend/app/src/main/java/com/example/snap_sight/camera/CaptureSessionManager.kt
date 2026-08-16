package com.example.snap_sight.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.snap_sight.stt.SpeechToTextRecognizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 촬영 세션 상태. docs/screen-design.md 의 S3 상태 정의와 1:1 대응한다. */
enum class SessionState(val description: String) {
    IDLE("볼륨 버튼을 눌러 시작"),
    LISTENING("무엇을 찍을까요? 말한 뒤 볼륨 버튼"),
    PARSING("음성을 확인하고 있어요…"),
    AIMING("조준 중 — 볼륨 버튼으로 촬영"),
    CAPTURING("촬영 중…"),
    SAVED("저장 완료"),
    ERROR("오류가 발생했어요. 볼륨 버튼으로 처음으로"),
}

/**
 * README 파이프라인의 트리거 흐름을 구현하는 세션 상태 머신.
 *
 * 볼륨 버튼 짧게 누름의 의미가 상태마다 다르다:
 *  IDLE → 세션 시작(발화 녹음 시작) / LISTENING → 발화 종료 / AIMING → 셔터 / ERROR → 초기화
 * 길게 누름은 어느 상태에서든 세션 취소([cancel]).
 *
 * 촬영 이벤트는 [CaptureEventListener] 구현으로 직접 수신해
 * CAPTURING → SAVED → (2초 후) IDLE 전환을 처리한다.
 */
class CaptureSessionManager(
    private val context: Context,
    private val cameraController: CameraController,
    private val speechRecognizer: SpeechToTextRecognizer = SpeechToTextRecognizer(context),
    private val ringBuffer: RingFrameBuffer = RingFrameBuffer(),
) : CaptureEventListener {

    /** 조준 루프 동안만 동작하는 기울기 센서. ③ 판정·⑥ 수평 피드백이 소비. */
    val tiltMonitor: TiltSensorMonitor = TiltSensorMonitor(context)

    interface Listener {
        /** 상태가 바뀔 때마다 호출. ⑥은 여기서 낭독/햅틱/사운드를 렌더링한다. */
        fun onStateChanged(state: SessionState)

        /**
         * 발화 인식 완료(성공/실패 모두, 재시도까지 소진한 뒤 호출됨). ① 슬롯 파서 연결
         * 지점 — [text]가 null이면 인식 실패. 성공 시 [text]를 백엔드로 전송하면
         * ai/slot_parser.py가 타겟 스펙으로 변환한다. 실패(null) 시에는 백엔드 호출 없이
         * `TargetSpec(status=FAILED)`를 직접 만들어 진행하는 걸 권장한다 (이슈 #32).
         */
        fun onUtteranceRecognized(sessionId: String, text: String?) {}

        /**
         * 발화 인식이 처음 실패해서 자동으로 1회 재시도하기 직전 호출됨. ⑥이 여기서
         * "다시 말씀해주세요" 같은 짧은 사운드 신호를 재생하면 됨 (이슈 #32).
         */
        fun onRecognitionRetry(sessionId: String) {}

        /** 대표 컷 저장 완료. ④ 업로드 연결 지점. */
        fun onPhotoCaptured(sessionId: String, uri: Uri) {}

        /** 셔터 전후 1초 후보 프레임 수집 완료. ④ 업로드 연결 지점. */
        fun onCandidatesCollected(sessionId: String, candidates: List<RingFrameBuffer.Frame>) {}
    }

    var listener: Listener? = null

    var state: SessionState = SessionState.IDLE
        private set

    /** 현재 세션 식별자. 백엔드 API 의 session_id 로 그대로 사용한다. */
    var sessionId: String = ""
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        cameraController.captureEventListener = this
        cameraController.setFrameSink(ringBuffer)
    }

    /** 볼륨 버튼 짧게 누름. 처리했으면 true. */
    fun onVolumePressed(): Boolean {
        when (state) {
            SessionState.IDLE -> startSession()
            SessionState.LISTENING -> finishListening()
            SessionState.AIMING -> shutter()
            SessionState.ERROR -> moveTo(SessionState.IDLE)
            // PARSING은 인식 결과 콜백을 기다리는 중이라 볼륨 입력을 받지 않음
            SessionState.PARSING, SessionState.CAPTURING, SessionState.SAVED -> return false
        }
        return true
    }

    /** 볼륨 버튼 길게 누름 = 세션 취소. */
    fun cancel() {
        if (state == SessionState.IDLE) return
        if (state == SessionState.LISTENING || state == SessionState.PARSING) speechRecognizer.cancel()
        ringBuffer.flush()
        mainHandler.removeCallbacksAndMessages(null)
        moveTo(SessionState.IDLE)
    }

    private fun startSession() {
        sessionId = "s_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (hasMic && speechRecognizer.isAvailable) {
            beginListening(isRetry = false)
            return
        }
        Log.w(TAG, "마이크 권한 없음 또는 음성 인식 미지원 — 발화 단계를 건너뜀")
        // 발화 없이 조준으로 직행 (타겟 스펙 없는 일반 촬영 모드)
        moveTo(SessionState.AIMING)
    }

    /**
     * 발화 인식을 시작(또는 재시도)한다.
     *
     * 실패([isRetry]가 false일 때)하면 [Listener.onRecognitionRetry]를 통지하고 LISTENING으로
     * 되돌아가 자동으로 딱 1회만 재시도한다. 재시도까지 실패하면 [Listener.onUtteranceRecognized]에
     * null을 통지하며 조준으로 진행한다 — 상호작용 횟수를 최소화하기 위해 재시도는 1회로
     * 제한한다(이슈 #32, docs/research/blind-camera-ux-notes.md 참고).
     *
     * AIMING 전환([moveTo])을 항상 [Listener.onUtteranceRecognized] 통지보다 먼저 하는 게
     * 중요하다 — MainActivity의 `onStateChanged(AIMING)`이 `cvProcessor.startNewSession(spec=null)`로
     * 초기화한 뒤에, `onUtteranceRecognized`가 (성공 시엔 비동기로 나중에, 실패 시엔 이 함수
     * 안에서 곧바로) 실제 결과로 한 번 더 갱신하는 순서를 보장하기 위함이다. 순서가 뒤바뀌면
     * 실패 시 만든 스펙을 곧이어 실행되는 초기화가 덮어써버린다.
     *
     * [moveTo]`(LISTENING)`을 함수 **맨 앞**에 두는 것도 중요하다 — 끝에 두면
     * [SpeechToTextRecognizer.start]가 (예: 재시도 시점에 인식기가 갑자기 unavailable해지는
     * 것처럼) 드물게 콜백을 동기적으로 호출하는 경우, `beginListening(isRetry=true)`가 이미
     * AIMING까지 다 끝낸 뒤에 바깥쪽 호출의 "꼬리" 코드로 남아있던 `moveTo(LISTENING)`이
     * 재실행되며 상태를 도로 LISTENING으로 덮어쓰는 버그가 생긴다. 맨 앞에 두면 재귀 호출이
     * 끝난 뒤 실행될 코드가 아예 없어 이 문제가 구조적으로 발생하지 않는다.
     */
    private fun beginListening(isRetry: Boolean) {
        moveTo(SessionState.LISTENING)
        speechRecognizer.start(object : SpeechToTextRecognizer.Listener {
            override fun onRecognized(text: String) {
                moveTo(SessionState.AIMING)
                listener?.onUtteranceRecognized(sessionId, text)
            }

            override fun onError(message: String) {
                if (!isRetry) {
                    Log.w(TAG, "발화 인식 실패, 1회 재시도: $message")
                    listener?.onRecognitionRetry(sessionId)
                    beginListening(isRetry = true)
                    return
                }
                Log.w(TAG, "재시도도 발화 인식 실패: $message")
                // 인식 실패해도 타겟 스펙 없는 일반 촬영 모드로 계속 진행
                moveTo(SessionState.AIMING)
                listener?.onUtteranceRecognized(sessionId, null)
            }
        })
    }

    private fun finishListening() {
        moveTo(SessionState.PARSING)
        speechRecognizer.stop()
    }

    private fun shutter() {
        moveTo(SessionState.CAPTURING)
        val shutterSessionId = sessionId
        ringBuffer.requestCandidates(System.currentTimeMillis()) { candidates ->
            Log.i(TAG, "후보 프레임 ${candidates.size}장 수집 [$shutterSessionId]")
            listener?.onCandidatesCollected(shutterSessionId, candidates)
        }
        cameraController.takePhoto()
    }

    // ---- CaptureEventListener (CameraController 가 메인 스레드에서 호출) ----

    override fun onShutter() {
        // ⑥ 셔터 사운드/진동 타이밍 — Listener.onStateChanged(CAPTURING)에서 처리 가능
    }

    override fun onPhotoSaved(uri: Uri) {
        listener?.onPhotoCaptured(sessionId, uri)
        moveTo(SessionState.SAVED)
        mainHandler.postDelayed({ if (state == SessionState.SAVED) moveTo(SessionState.IDLE) }, 2000)
    }

    override fun onCaptureError(error: Throwable) {
        Log.e(TAG, "촬영 실패", error)
        moveTo(SessionState.ERROR)
    }

    private fun moveTo(next: SessionState) {
        if (state == next) return
        state = next
        if (next == SessionState.AIMING) tiltMonitor.start() else tiltMonitor.stop()
        Log.i(TAG, "세션 상태: $next")
        listener?.onStateChanged(next)
    }

    private companion object {
        const val TAG = "CaptureSession"
    }
}
