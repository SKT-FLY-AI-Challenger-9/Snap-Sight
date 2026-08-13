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
import com.example.snap_sight.camera.SessionState
import com.example.snap_sight.cv.LoggingFrameProcessor
import com.example.snap_sight.ux.CaptureScreen
import com.example.snap_sight.ui.theme.SnapSightTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val cameraController by lazy { CameraController(this) }
    private val sessionManager by lazy { CaptureSessionManager(this, cameraController) }

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

        cameraController.setFrameProcessor(LoggingFrameProcessor())
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
                Log.i(TAG, "대표 컷 저장 [$sessionId]: $uri") // TODO: ④ 프레임 업로드
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
