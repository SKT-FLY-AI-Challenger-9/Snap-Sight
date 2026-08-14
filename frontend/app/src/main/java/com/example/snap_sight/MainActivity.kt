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
import com.example.snap_sight.cv.Detection
import com.example.snap_sight.cv.LoggingFrameProcessor
import com.example.snap_sight.cv.YoloFrameProcessor
import com.example.snap_sight.network.FrameUploader
import com.example.snap_sight.ux.CaptureScreen
import com.example.snap_sight.ui.theme.SnapSightTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val cameraController by lazy { CameraController(this) }
    private val sessionManager by lazy { CaptureSessionManager(this, cameraController) }
    private val frameUploader = FrameUploader()

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

        // 모델 에셋이 있으면 YOLO26n 탐지, 없으면 기존 fps 로거로 폴백
        val yoloProcessor = YoloFrameProcessor.createIfAvailable(this)
        yoloProcessor?.listener = object : YoloFrameProcessor.DetectionListener {
            override fun onDetections(detections: List<Detection>, inferenceMs: Long) {
                // TODO: ③ 판정 로직 연동 지점 — docs/detection-api-design.md 페이로드로 전달 예정.
                // 현재는 파이프라인 검증용 로그만 남긴다 (분석 스레드에서 호출됨).
            }
        }
        cameraController.setFrameProcessor(yoloProcessor ?: LoggingFrameProcessor())
        sessionManager.listener = object : CaptureSessionManager.Listener {
            override fun onStateChanged(state: SessionState) {
                statusText = state.description
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
    }

    private companion object {
        const val TAG = "SnapSight"
    }
}
