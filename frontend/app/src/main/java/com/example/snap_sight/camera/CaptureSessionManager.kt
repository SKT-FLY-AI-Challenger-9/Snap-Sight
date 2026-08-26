// 이 파일: 촬영 한 번을 "세션"이라는 진행표로 관리한다.
// 대기 → 발화 듣기 → 해석 → 조준 → 촬영 → 저장 순서로 상태를 옮기고,
// 볼륨 버튼이 눌리면 지금 상태에 맞는 동작(시작/발화 종료/셔터)으로 바꿔준다.
package com.example.snap_sight.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.snap_sight.stt.SpeechToTextRecognizer
import java.util.UUID

/** 촬영 세션 상태. docs/screen-design.md 의 S3 상태 정의와 1:1 대응한다. */
enum class SessionState(val description: String) {
    // 화면 상태 카드·세 번 탭 상태 낭독에 쓰이는 문구 — 탭 문법(#84) 기준 (볼륨 버튼은 제거됨)
    IDLE("두 번 탭하고 말하기 — 촬영·설정·갤러리"),
    LISTENING("무엇을 찍을까요? 말한 뒤 두 번 탭"),
    PARSING("음성을 확인하고 있어요…"),
    AIMING("조준 중 — 두 번 탭으로 촬영"),
    CAPTURING("촬영 중…"),
    SAVED("저장 완료"),
    ERROR("오류가 발생했어요. 두 번 탭하면 처음으로"),
}

/** 대표 컷과 후보가 동일 세션에서 모두 모인 뒤에만 외부로 전달되는 불변 묶음. */
data class CaptureBundle(
    val sessionId: String,
    val representative: Uri,
    val candidates: List<RingFrameBuffer.Frame>,
)

internal data class CaptureSessionToken(val sessionId: String, val generation: Long)

internal data class AssembledCapture<R, C>(val representative: R, val candidates: C)

/** 순서가 다른 두 비동기 결과를 토큰이 일치할 때만 정확히 한 번 조립한다. */
internal class CaptureBundleAssembler<R, C> {
    private var token: CaptureSessionToken? = null
    private var representative: R? = null
    private var candidates: C? = null
    private var emitted = false

    @Synchronized
    fun begin(next: CaptureSessionToken) {
        token = next
        representative = null
        candidates = null
        emitted = false
    }

    @Synchronized
    fun cancel() {
        token = null
        representative = null
        candidates = null
        emitted = false
    }

    @Synchronized
    fun putRepresentative(expected: CaptureSessionToken, value: R): AssembledCapture<R, C>? {
        if (token != expected || emitted) return null
        representative = value
        return assembleIfReady()
    }

    @Synchronized
    fun putCandidates(expected: CaptureSessionToken, value: C): AssembledCapture<R, C>? {
        if (token != expected || emitted) return null
        candidates = value
        return assembleIfReady()
    }

    private fun assembleIfReady(): AssembledCapture<R, C>? {
        val readyRepresentative = representative ?: return null
        val readyCandidates = candidates ?: return null
        emitted = true
        return AssembledCapture(readyRepresentative, readyCandidates)
    }
}

internal fun newCaptureSessionId(): String = UUID.randomUUID().toString()

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

        /**
         * 세션별 대표 컷·후보가 모두 준비된 단일 전달 지점. 기존 구현 호환을 위해 기본 구현은
         * 두 레거시 콜백을 연달아 호출한다. 새 wiring은 이 메서드를 직접 override하는 편이 안전하다.
         */
        fun onCaptureBundleReady(bundle: CaptureBundle) {
            onPhotoCaptured(bundle.sessionId, bundle.representative)
            onCandidatesCollected(bundle.sessionId, bundle.candidates)
        }
    }

    var listener: Listener? = null

    /**
     * LISTENING 진입 안내 TTS와 인식 시작의 순서 조율 훅 (실사용 피드백 2026-08-22).
     * 안내와 인식이 동시에 시작되면 앱 자신의 안내 음성("말씀해 주세요")이 마이크로 들어가
     * 발화로 인식된다. 이 훅이 설정돼 있으면 인식 시작을 안내가 끝난 뒤로 미룬다.
     *
     * 구현부(MainActivity)는 `(isRetry, onDone)`을 받아 안내를 재생하고, 끝나면 [onDone]을
     * 메인 스레드에서 정확히 1회 호출해야 한다. null 이면 안내 없이 즉시 인식을 시작한다.
     */
    var listeningPrompt: ((isRetry: Boolean, onDone: () -> Unit) -> Unit)? = null

    var state: SessionState = SessionState.IDLE
        private set

    /**
     * 방금 완료된(또는 진행 중인) 촬영이 CV 자동촬영([autoShutter])이었는지 — 수동 두 번 탭이면
     * false. SAVED 진입 시점에 ⑥이 안내 경로(온디바이스 즉시 요약 vs LLM 설명)를 가르는 데 쓴다
     * (사용자 요청 2026-08-26).
     */
    var lastCaptureWasAuto: Boolean = false
        private set

    /** 현재 세션 식별자. 백엔드 API 의 session_id 로 그대로 사용한다. */
    var sessionId: String = ""
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    // PARSING 무한대기 방지 타임아웃 (finishListening 참고). 콜백 도착·취소 시 해제된다.
    private var parsingTimeout: Runnable? = null

    // 이번 LISTENING 턴에서 인식기가 실제로 시작됐는지 — 안내 TTS 재생 중(게이트 대기)에
    // 발화 종료가 눌리면 stop() 할 인식기가 없어 PARSING 타임아웃까지 기다리게 되므로 구분한다.
    private var recognizerStarted = false

    private var generation = 0L
    private var activeToken: CaptureSessionToken? = null
    private val bundleAssembler =
        CaptureBundleAssembler<Uri, List<RingFrameBuffer.Frame>>()

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

    /**
     * CV 자동촬영 경로 (메인 스레드) — 두 번 탭과 같은 셔터지만, 판정 시점의 세션이
     * 아직 조준 중일 때만 동작한다. 판정(분석 스레드)과 셔터 사이에 세션이 취소·교체될
     * 수 있어 세션 ID 를 함께 검사한다. 발동했으면 true.
     *
     * 자동촬영은 [AUTO_CAPTURE_BURST_COUNT]장 연사 후 가장 선명한 한 장만 저장한다
     * (손이 계속 움직이는 조준 중에 터지는 셔터라 흔들린 컷 확률이 높다, 2026-08-24).
     * 수동 두 번 탭은 기존처럼 1장이다.
     */
    fun autoShutter(expectedSessionId: String): Boolean {
        if (state != SessionState.AIMING) return false
        if (activeToken?.sessionId != expectedSessionId) return false
        shutter(burstCount = AUTO_CAPTURE_BURST_COUNT, isAuto = true)
        return true
    }

    /** 볼륨 버튼 길게 누름 = 세션 취소. */
    fun cancel() {
        if (state == SessionState.LISTENING || state == SessionState.PARSING) speechRecognizer.cancel()
        invalidateActiveSession()
        cameraController.cancelPendingCapture()
        ringBuffer.disable()
        mainHandler.removeCallbacksAndMessages(null)
        clearParsingTimeout()
        recognizerStarted = false
        if (state != SessionState.IDLE) moveTo(SessionState.IDLE)
    }

    private fun startSession() {
        // wall-clock 초 단위 ID는 연속 촬영에서 충돌할 수 있으므로 UUID를 사용한다.
        invalidateActiveSession()
        sessionId = newCaptureSessionId()
        val token = CaptureSessionToken(sessionId, generation)
        activeToken = token

        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (hasMic && speechRecognizer.isAvailable) {
            beginListening(isRetry = false)
            return
        }
        Log.w(TAG, "마이크 권한 없음 또는 음성 인식 미지원 — 발화 단계를 건너뜀")
        // 발화 없이 조준으로 직행 (타겟 스펙 없는 일반 촬영 모드). 재시도 소진 실패와 같은
        // 콜백(text=null)을 태워 보내 안내 문구 결정이 항상 한 곳(MainActivity의 스펙 적용
        // 지점)에서만 이뤄지게 한다 — 안 그러면 이 경로만 AIMING 진입 즉시 안내가 나가
        // 뒤이은 판정 안내와 겹친다 (사용자 요청 2026-08-27).
        moveTo(SessionState.AIMING)
        listener?.onUtteranceRecognized(sessionId, null)
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
        val token = activeToken ?: return
        moveTo(SessionState.LISTENING)
        recognizerStarted = false
        val prompt = listeningPrompt
        if (prompt == null) {
            startRecognizer(isRetry, token)
            return
        }
        // 안내 TTS가 끝난 뒤에 인식 시작 — 안내가 마이크로 들어가는 것을 막는다.
        // 안내 재생 중 사용자가 취소했거나 새 세션이 시작됐으면 인식을 시작하지 않는다.
        prompt(isRetry) {
            if (state == SessionState.LISTENING && isActive(token)) {
                startRecognizer(isRetry, token)
            }
        }
    }

    private fun startRecognizer(isRetry: Boolean, token: CaptureSessionToken) {
        if (!isActive(token)) return
        recognizerStarted = true
        speechRecognizer.start(object : SpeechToTextRecognizer.Listener {
            override fun onRecognized(text: String) {
                if (!isActive(token)) return
                clearParsingTimeout()
                moveToAimingAfterRecognitionEndTone(token) {
                    listener?.onUtteranceRecognized(token.sessionId, text)
                }
            }

            override fun onError(message: String) {
                if (!isActive(token)) return
                clearParsingTimeout()
                if (!isRetry) {
                    Log.w(TAG, "발화 인식 실패, 1회 재시도: $message")
                    listener?.onRecognitionRetry(token.sessionId)
                    beginListening(isRetry = true)
                    return
                }
                Log.w(TAG, "재시도도 발화 인식 실패: $message")
                // 인식 실패해도 타겟 스펙 없는 일반 촬영 모드로 계속 진행
                moveToAimingAfterRecognitionEndTone(token) {
                    listener?.onUtteranceRecognized(token.sessionId, null)
                }
            }
        })
    }

    /**
     * AIMING 전환(및 뒤이은 화면 안내 음성)을 [RECOGNITION_END_TONE_GUARD_MS]만큼 늦춘다 —
     * 인식 종료 신호음(SpeechToTextRecognizer의 onEndOfSpeech 톤)이 뒤따르는 안내 음성에
     * 묻히지 않게 한 박자 쉬어준다 (사용자 요청 2026-08-27 — "음성인식 끝나는 소리가 묻혀").
     */
    private fun moveToAimingAfterRecognitionEndTone(token: CaptureSessionToken, onArmed: () -> Unit) {
        mainHandler.postDelayed({
            if (!isActive(token)) return@postDelayed
            moveTo(SessionState.AIMING)
            onArmed()
        }, RECOGNITION_END_TONE_GUARD_MS)
    }

    private fun finishListening() {
        val token = activeToken ?: return
        if (!recognizerStarted) {
            // 안내가 끝나기 전에 발화 종료를 눌렀다 — 들은 발화가 없으므로
            // 마이크 없는 세션과 동일하게 스펙 없는 일반 촬영 모드로 바로 진행한다
            moveTo(SessionState.AIMING)
            return
        }
        moveTo(SessionState.PARSING)
        speechRecognizer.stop()

        // 안전장치: 기기에 따라 stop() 후 인식 콜백이 영영 안 오는 경우가 있다
        // (갤럭시 S24 실기기에서 관측 — 이슈 #42 실측). 타임아웃 없이는 PARSING에 갇혀
        // 시각장애인 사용자가 멈춘 화면을 영문도 모른 채 기다리게 되므로,
        // 일정 시간 응답이 없으면 인식 실패로 간주하고 일반 촬영 모드로 진행한다.
        parsingTimeout = Runnable {
            if (state != SessionState.PARSING || !isActive(token)) return@Runnable
            Log.w(TAG, "발화 인식 응답 없음(${PARSING_TIMEOUT_MS}ms) — 실패로 간주하고 진행 [${token.sessionId}]")
            speechRecognizer.cancel()
            moveTo(SessionState.AIMING)
            listener?.onUtteranceRecognized(token.sessionId, null)
        }.also { mainHandler.postDelayed(it, PARSING_TIMEOUT_MS) }
    }

    private fun clearParsingTimeout() {
        parsingTimeout?.let { mainHandler.removeCallbacks(it) }
        parsingTimeout = null
    }

    private fun shutter(burstCount: Int = 1, isAuto: Boolean = false) {
        val token = activeToken ?: return
        lastCaptureWasAuto = isAuto
        bundleAssembler.begin(token)
        moveTo(SessionState.CAPTURING)
        val accepted = ringBuffer.requestCandidates(SystemClock.elapsedRealtime()) { candidates ->
            if (!isActive(token) || state !in setOf(SessionState.CAPTURING, SessionState.SAVED)) {
                Log.i(TAG, "취소된 세션의 후보 콜백 무시 [${token.sessionId}]")
                return@requestCandidates
            }
            Log.i(TAG, "후보 프레임 ${candidates.size}장 수집 [${token.sessionId}]")
            bundleAssembler.putCandidates(token, candidates.toList())?.let {
                completeBundle(token, it)
            }
        }
        if (!accepted) {
            handleCaptureError(token, IllegalStateException("후보 프레임 요청이 이미 진행 중임"))
            return
        }
        cameraController.takePhoto(token.sessionId, burstCount)
    }

    // ---- CaptureEventListener (CameraController 가 메인 스레드에서 호출) ----

    override fun onShutter() {
        // ⑥ 셔터 사운드/진동 타이밍 — Listener.onStateChanged(CAPTURING)에서 처리 가능
    }

    override fun onPhotoSaved(uri: Uri) {
        val token = activeToken ?: return
        handlePhotoSaved(token, uri)
    }

    override fun onPhotoSaved(sessionId: String?, uri: Uri) {
        val token = activeToken ?: return
        if (sessionId != token.sessionId) {
            Log.i(TAG, "다른/취소된 세션의 사진 저장 콜백 무시 [$sessionId]")
            return
        }
        handlePhotoSaved(token, uri)
    }

    private fun handlePhotoSaved(token: CaptureSessionToken, uri: Uri) {
        if (!isActive(token) || state != SessionState.CAPTURING) return
        bundleAssembler.putRepresentative(token, uri)?.let { completeBundle(token, it) }
    }

    override fun onCaptureError(error: Throwable) {
        val token = activeToken ?: return
        handleCaptureError(token, error)
    }

    override fun onCaptureError(sessionId: String?, error: Throwable) {
        val token = activeToken ?: return
        if (sessionId != token.sessionId) {
            Log.i(TAG, "다른/취소된 세션의 촬영 오류 콜백 무시 [$sessionId]")
            return
        }
        handleCaptureError(token, error)
    }

    private fun handleCaptureError(token: CaptureSessionToken, error: Throwable) {
        if (!isActive(token)) return
        Log.e(TAG, "촬영 실패 [${token.sessionId}]", error)
        invalidateActiveSession()
        cameraController.cancelPendingCapture()
        ringBuffer.disable()
        moveTo(SessionState.ERROR)
    }

    private fun completeBundle(
        token: CaptureSessionToken,
        assembled: AssembledCapture<Uri, List<RingFrameBuffer.Frame>>,
    ) {
        if (!isActive(token)) return
        val bundle = CaptureBundle(
            sessionId = token.sessionId,
            representative = assembled.representative,
            candidates = assembled.candidates.toList(),
        )
        bundleAssembler.cancel()
        // SAVED는 MediaStore와 post-window 후보가 모두 준비됐다는 뜻이다. 이 시점까지
        // CAPTURING을 유지해야 상태 기반 camera analysis OFF가 후보 수집을 중간에 끊지 않는다.
        moveTo(SessionState.SAVED)
        if (!isActive(token)) return
        listener?.onCaptureBundleReady(bundle)
        mainHandler.postDelayed({
            if (state == SessionState.SAVED && isActive(token)) {
                invalidateActiveSession()
                moveTo(SessionState.IDLE)
            }
        }, SAVED_DISPLAY_MS)
    }

    private fun moveTo(next: SessionState) {
        if (state == next) return
        state = next
        when (next) {
            SessionState.AIMING -> {
                ringBuffer.startPreCapture()
                // listener가 연결되지 않은 기본 구성에서는 start()가 false이며 센서를 등록하지 않는다.
                tiltMonitor.start()
            }
            SessionState.CAPTURING, SessionState.SAVED -> tiltMonitor.stop()
            else -> {
                tiltMonitor.stop()
                ringBuffer.disable()
            }
        }
        Log.i(TAG, "세션 상태: $next")
        listener?.onStateChanged(next)
    }

    private fun isActive(token: CaptureSessionToken): Boolean = activeToken == token

    private fun invalidateActiveSession() {
        generation++
        activeToken = null
        bundleAssembler.cancel()
    }

    private companion object {
        const val TAG = "CaptureSession"

        /** 자동촬영 연사 장수 — 이 중 가장 선명한 한 장만 저장된다 ([CameraController.takePhoto]). */
        const val AUTO_CAPTURE_BURST_COUNT = 3

        // 발화 종료 후 인식 결과를 기다리는 최대 시간. 정상 인식은 1~3초 내 도착하고,
        // 이 시간을 넘기면 기기 인식 서비스가 응답하지 않는 상태로 본다 (실측: S24 무한대기 관측).
        const val PARSING_TIMEOUT_MS = 8_000L
        const val SAVED_DISPLAY_MS = 2_000L
        // 인식 종료 신호음이 다 들리도록 AIMING 전환(및 그 안내 음성)을 늦추는 시간
        // (사용자 요청 2026-08-27)
        const val RECOGNITION_END_TONE_GUARD_MS = 500L
    }
}
