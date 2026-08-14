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
import com.example.snap_sight.network.FrameUploader
import com.example.snap_sight.ux.CaptureScreen
import com.example.snap_sight.ui.theme.SnapSightTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val cameraController by lazy { CameraController(this) }
    private val sessionManager by lazy { CaptureSessionManager(this, cameraController) }
    private val frameUploader = FrameUploader()

    /** ② 온디바이스 CV. 결과는 분석 스레드에서 도착하므로 여기서는 로그만 남긴다. */
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
                // TODO(①): STT 파싱이 붙으면 여기에 TargetSpec 을 넘긴다 (지금은 의도 없음 = null).
                if (state == SessionState.AIMING) cvProcessor.startNewSession(spec = null)
                buttonLabel = when (state) {
                    SessionState.IDLE -> "세션 시작"
                    SessionState.LISTENING -> "발화 종료"
                    SessionState.AIMING -> "촬영"
                    SessionState.CAPTURING, SessionState.SAVED -> "잠시만요"
                    SessionState.ERROR -> "처음으로"
                }
            }

            override fun onUtteranceRecorded(sessionId: String, wav: File) {
                Log.i(TAG, "발화 녹음 완료 [$sessionId]: ${wav.absolutePath}") // TODO: ① STT 업로드
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
     * ② CV 결과 수신 지점 (분석 스레드).
     *
     * 여기서 나오는 `output.objectsJson()` 이 ③ 편차 계산 / ⑥ 햅틱·사운드 렌더링의 입력이다.
     * 매 프레임 로그는 너무 많으므로 1초에 한 번만 남긴다.
     * Logcat 필터: `tag:SnapSightCV`
     */
    private fun onCvFrameResult(output: CvFrameOutput) {
        if (!output.analyzed) return
        val now = output.timestampMs
        if (now - lastCvLogMs < 1000) return
        lastCvLogMs = now
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
