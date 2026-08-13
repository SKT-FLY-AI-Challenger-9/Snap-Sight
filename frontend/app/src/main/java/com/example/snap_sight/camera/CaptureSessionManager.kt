package com.example.snap_sight.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.snap_sight.camera.audio.WavAudioRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 촬영 세션 상태. docs/screen-design.md 의 S3 상태 정의와 1:1 대응한다.
 * (PARSING 은 ① STT/NLU 연동 전까지 스킵 — LISTENING → AIMING 직행)
 */
enum class SessionState(val description: String) {
    IDLE("볼륨 버튼을 눌러 시작"),
    LISTENING("무엇을 찍을까요? 말한 뒤 볼륨 버튼"),
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
    private val audioRecorder: WavAudioRecorder = WavAudioRecorder(),
    private val ringBuffer: RingFrameBuffer = RingFrameBuffer(),
) : CaptureEventListener {

    interface Listener {
        /** 상태가 바뀔 때마다 호출. ⑥은 여기서 낭독/햅틱/사운드를 렌더링한다. */
        fun onStateChanged(state: SessionState)

        /** 의도 발화 녹음 완료. ① STT 파이프라인 연결 지점. */
        fun onUtteranceRecorded(sessionId: String, wav: File) {}

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
            SessionState.CAPTURING, SessionState.SAVED -> return false // 전환 중에는 무시
        }
        return true
    }

    /** 볼륨 버튼 길게 누름 = 세션 취소. */
    fun cancel() {
        if (state == SessionState.IDLE) return
        if (audioRecorder.isRecording) audioRecorder.stop()
        ringBuffer.flush()
        mainHandler.removeCallbacksAndMessages(null)
        moveTo(SessionState.IDLE)
    }

    private fun startSession() {
        sessionId = "s_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            val out = File(context.cacheDir, "utterance_$sessionId.wav")
            if (audioRecorder.start(out)) {
                moveTo(SessionState.LISTENING)
                return
            }
            Log.w(TAG, "녹음 시작 실패 — 발화 단계를 건너뜀")
        }
        // 마이크를 못 쓰면 발화 없이 조준으로 직행 (타겟 스펙 없는 일반 촬영 모드)
        moveTo(SessionState.AIMING)
    }

    private fun finishListening() {
        val wav = audioRecorder.stop()
        if (wav != null) {
            listener?.onUtteranceRecorded(sessionId, wav)
        }
        // TODO(①): STT/의도 파싱 붙으면 PARSING 상태 경유
        moveTo(SessionState.AIMING)
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
        Log.i(TAG, "세션 상태: $next")
        listener?.onStateChanged(next)
    }

    private companion object {
        const val TAG = "CaptureSession"
    }
}
