package com.example.snap_sight

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.snap_sight.camera.CameraController
import com.example.snap_sight.camera.CaptureSessionManager
import com.example.snap_sight.camera.RingFrameBuffer
import com.example.snap_sight.camera.SessionState
import com.example.snap_sight.cv.CvFrameOutput
import com.example.snap_sight.cv.SnapSightFrameProcessor
import com.example.snap_sight.cv.TrackedObject
import com.example.snap_sight.cv.TargetSpec
import com.example.snap_sight.network.FrameUploader
import com.example.snap_sight.network.UtteranceClient
import com.example.snap_sight.ux.CaptureScreen
import com.example.snap_sight.ui.theme.SnapSightTheme

/**
 * 모듈 배선 호스트.
 *
 * 소유·연결하는 것:
 *  - 카메라(⑤): [CameraController] + 세션 상태 머신 [CaptureSessionManager] (볼륨 버튼 트리거)
 *  - CV(②): [SnapSightFrameProcessor] — 탐지·추적 결과를 디버그 오버레이와 성능 로그로 소비
 *  - STT(①): 발화 인식 결과를 [UtteranceClient]로 보내 타겟 스펙 수신
 *  - 업로드(⑤→④): 대표 컷 + 후보 프레임을 [FrameUploader]로 백엔드 전송
 *
 * 화면(⑥ 임시)은 [CaptureScreen]이 담당하고, 이 클래스는 상태 배선만 한다.
 */
class MainActivity : ComponentActivity() {

    private val cameraController by lazy { CameraController(this) }
    private val sessionManager by lazy { CaptureSessionManager(this, cameraController) }
    private val frameUploader = FrameUploader()
    private val utteranceClient = UtteranceClient()

    /** ② 온디바이스 CV. 결과는 분석 스레드에서 도착 — 오버레이 갱신·성능 집계는 [onCvFrameResult] 참고. */
    private val cvProcessor by lazy {
        SnapSightFrameProcessor.create(this, listener = { output -> onCvFrameResult(output) })
    }
    private var lastCvLogMs = 0L

    // 대표 컷(MediaStore)과 후보 프레임(링 버퍼)은 비동기로 따로 도착하므로
    // 둘 다 모이면 업로드한다.
    private var pendingSessionId: String? = null
    private var pendingRepresentative: Uri? = null
    private var pendingCandidates: List<RingFrameBuffer.Frame>? = null

    private var permissionsGranted by mutableStateOf(false)
    private var statusText by mutableStateOf(SessionState.IDLE.description)
    private var buttonLabel by mutableStateOf("세션 시작")

    // 디버그 오버레이용 최신 추적 객체 (정식 화면에서는 음성·햅틱으로 대체)
    private var cvObjects by mutableStateOf<List<TrackedObject>>(emptyList())

    // CV 성능 측정용 롤링 창 (분석 스레드에서 갱신, 로그 시점에 집계)
    private val cvLatencies = ArrayDeque<Long>()
    private var cvAnalyzedCount = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            permissionsGranted = results[Manifest.permission.CAMERA] == true
            if (!permissionsGranted) {
                statusText = "카메라 권한이 필요합니다"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraController.setFrameProcessor(cvProcessor)
        sessionManager.listener = object : CaptureSessionManager.Listener {
            override fun onStateChanged(state: SessionState) {
                statusText = state.description
                // 조준 시작 = 새 추적 세션. track_id 가 이전 세션과 섞이지 않도록 초기화한다.
                // TargetSpec은 아직 백엔드 응답 전이라 일단 null로 시작 — onUtteranceRecognized의
                // UtteranceClient 콜백이 도착하면 같은 세션이 아직 AIMING일 때 한 번 더 갱신한다.
                if (state == SessionState.AIMING) cvProcessor.startNewSession(spec = null)
                buttonLabel = when (state) {
                    SessionState.IDLE -> "세션 시작"
                    SessionState.LISTENING -> "발화 종료"
                    SessionState.PARSING, SessionState.CAPTURING, SessionState.SAVED -> "잠시만요"
                    SessionState.AIMING -> "촬영"
                    SessionState.ERROR -> "처음으로"
                }
            }

            override fun onRecognitionRetry(sessionId: String) {
                // TODO(⑥): 여기서 "다시 말씀해주세요" 류 짧은 사운드 신호 재생.
                // PARSING의 "처리 중 루프" 사운드와 명확히 구분돼야 함 (이슈 #32).
                Log.i(TAG, "발화 인식 재시도 [$sessionId]")
            }

            override fun onUtteranceRecognized(sessionId: String, text: String?) {
                if (text == null) {
                    // 재시도까지 소진한 뒤 호출됨(CaptureSessionManager 참고). "의도 자체가
                    // 없었던" 경우(spec=null, 마이크 권한 없음 등)와 구분하기 위해
                    // status=FAILED인 TargetSpec을 직접 만든다 — 백엔드에 보낼 텍스트가
                    // 애초에 없으므로 UtteranceClient 왕복 없이 로컬에서 바로 처리한다.
                    Log.w(TAG, "발화 인식 실패(재시도 포함) [$sessionId] — FAILED 스펙으로 진행")
                    val failedSpec = TargetSpec(
                        sessionId = sessionId,
                        rawText = "",
                        source = "ondevice",
                        schemaVersion = "0.2",
                        status = TargetSpec.Status.FAILED,
                    )
                    applyTargetSpecIfStillAiming(sessionId, failedSpec)
                    return
                }
                Log.i(TAG, "발화 인식 완료 [$sessionId]: $text")
                utteranceClient.sendUtterance(
                    sessionId = sessionId,
                    rawText = text,
                    callback = object : UtteranceClient.Callback {
                        override fun onSuccess(spec: TargetSpec?) {
                            Log.i(TAG, "타겟 스펙 수신 [$sessionId]: $spec")
                            applyTargetSpecIfStillAiming(sessionId, spec)
                        }

                        override fun onFailure(error: Throwable) {
                            // 타겟 스펙 요청 실패해도 촬영 흐름은 계속 진행 (일반 촬영 모드로 대체)
                            Log.w(TAG, "타겟 스펙 요청 실패 [$sessionId]", error)
                        }
                    },
                )
            }

            override fun onPhotoCaptured(sessionId: String, uri: Uri) {
                Log.i(TAG, "대표 컷 저장 [$sessionId]: $uri")
                pendingSessionId = sessionId
                pendingRepresentative = uri
                maybeUploadFrames()
            }

            override fun onCandidatesCollected(
                sessionId: String,
                candidates: List<RingFrameBuffer.Frame>,
            ) {
                Log.i(TAG, "후보 프레임 ${candidates.size}장 [$sessionId]")
                pendingCandidates = candidates
                maybeUploadFrames()
            }
        }

        setContent {
            SnapSightTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (permissionsGranted) {
                        CaptureScreen(
                            controller = cameraController,
                            statusText = statusText,
                            sessionButtonLabel = buttonLabel,
                            onSessionButton = { sessionManager.onVolumePressed() },
                            cvObjects = cvObjects,
                        )
                    } else {
                        Text(
                            text = statusText,
                            modifier = Modifier.padding(innerPadding).padding(24.dp),
                        )
                    }
                }
            }
        }

        checkOrRequestPermissions()
    }

    /**
     * 타겟 스펙이 (네트워크 지연으로) AIMING 시작보다 늦게 도착했을 때 CV 세션에 반영한다.
     *
     * [SpeechToTextRecognizer][com.example.snap_sight.stt.SpeechToTextRecognizer] 인식 →
     * [UtteranceClient] 응답은 비동기라 AIMING 진입 시점엔 아직 없는 게 보통이다(spec=null로 시작).
     * 응답이 왔을 때 사용자가 이미 촬영을 마쳤거나 세션을 취소·재시작했다면(다른 sessionId,
     * 또는 더 이상 AIMING이 아님) 엉뚱한 세션에 적용하면 안 되므로 여기서 막는다.
     */
    private fun applyTargetSpecIfStillAiming(sessionId: String, spec: TargetSpec?) {
        if (sessionManager.sessionId != sessionId || sessionManager.state != SessionState.AIMING) {
            Log.i(TAG, "타겟 스펙 도착했지만 세션이 이미 지나감 [$sessionId] — 무시")
            return
        }
        cvProcessor.startNewSession(spec = spec)
    }

    /**
     * ② CV 결과 수신 지점 (분석 스레드).
     *
     * 여기서 나오는 `output.objectsJson()` 이 ③ 편차 계산 / ⑥ 햅틱·사운드 렌더링의 입력이다.
     * 매 프레임 로그는 너무 많으므로 1초에 한 번만 남긴다.
     * Logcat 필터: `tag:SnapSightCV`
     */
    private fun onCvFrameResult(output: CvFrameOutput) {
        if (!output.analyzed) return
        runOnUiThread { cvObjects = output.objects } // 디버그 오버레이 갱신

        // 성능 측정: emit 은 처리 종료 직후 동기 호출이므로 now - timestampMs = 파이프라인 지연
        val latencyMs = System.currentTimeMillis() - output.timestampMs
        synchronized(cvLatencies) {
            cvLatencies.addLast(latencyMs)
            if (cvLatencies.size > 120) cvLatencies.removeFirst()
            cvAnalyzedCount++
        }

        val now = output.timestampMs
        if (now - lastCvLogMs < 1000) return
        val windowMs = now - lastCvLogMs
        lastCvLogMs = now

        val (p50, p95, fps) = synchronized(cvLatencies) {
            val sorted = cvLatencies.sorted()
            val p50 = sorted[sorted.size / 2]
            val p95 = sorted[minOf(sorted.size - 1, sorted.size * 95 / 100)]
            val fps = cvAnalyzedCount * 1000f / windowMs.coerceAtLeast(1)
            cvAnalyzedCount = 0
            Triple(p50, p95, fps)
        }
        Log.d(CV_TAG, "성능: %.1f fps, 지연 %dms (p50 %d / p95 %d, 최근 %d프레임 창)".format(
            fps, latencyMs, p50, p95, cvLatencies.size))
        Log.d(CV_TAG, "객체 ${output.objects.size}개 ${output.objectsJson()}")
    }

    /** 대표 컷과 후보 프레임이 모두 모이면 백엔드로 업로드 (⑤→④). */
    private fun maybeUploadFrames() {
        val sessionId = pendingSessionId ?: return
        val representative = pendingRepresentative ?: return
        val candidates = pendingCandidates ?: return
        pendingSessionId = null
        pendingRepresentative = null
        pendingCandidates = null

        frameUploader.uploadCaptureFrames(
            sessionId = sessionId,
            representativeJpegProvider = {
                contentResolver.openInputStream(representative)?.use { it.readBytes() }
                    ?: throw IllegalStateException("대표 컷을 읽을 수 없음: $representative")
            },
            candidates = candidates,
            callback = object : FrameUploader.Callback {
                override fun onSuccess(result: FrameUploader.UploadResult) {
                    Log.i(TAG, "업로드 완료 [${result.sessionId}] 후보 ${result.receivedCandidateCount}장")
                }

                override fun onFailure(error: Throwable) {
                    // 업로드 실패는 촬영 성공과 무관 — 사진은 이미 기기에 저장됨 (재시도는 추후)
                    Log.w(TAG, "업로드 실패 (사진은 기기에 저장됨)", error)
                }
            },
        )
    }

    private fun checkOrRequestPermissions() {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val allGranted = required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            permissionsGranted = true
        } else {
            permissionLauncher.launch(required)
        }
    }

    // 볼륨 버튼: 짧게 = 상태별 동작(시작/발화종료/셔터), 길게(≈1초) = 세션 취소.
    // onKeyDown 에서 startTracking() 해야 onKeyLongPress 가 동작한다.

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode) && permissionsGranted && cameraController.isBound) {
            event?.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode) && permissionsGranted && cameraController.isBound) {
            if (event?.isCanceled != true) {
                sessionManager.onVolumePressed()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode)) {
            sessionManager.cancel()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.cancel()
        // 분리 시 onDetached() 가 불려 TFLite 인터프리터가 해제된다.
        cameraController.setFrameProcessor(null)
    }

    private companion object {
        const val TAG = "SnapSight"
        const val CV_TAG = "SnapSightCV"
    }
}
