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
import com.example.snap_sight.camera.CaptureEventListener
import com.example.snap_sight.cv.LoggingFrameProcessor
import com.example.snap_sight.ux.CaptureScreen
import com.example.snap_sight.ui.theme.SnapSightTheme

class MainActivity : ComponentActivity() {

    private val cameraController by lazy { CameraController(this) }

    private var permissionsGranted by mutableStateOf(false)
    private var statusText by mutableStateOf("카메라 준비 중…")

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
        cameraController.captureEventListener = object : CaptureEventListener {
            override fun onShutter() {
                statusText = "촬영!"
            }

            override fun onPhotoSaved(uri: Uri) {
                statusText = "저장 완료"
                Log.i(TAG, "사진 저장됨: $uri")
            }

            override fun onCaptureError(error: Throwable) {
                statusText = "촬영 실패: ${error.message}"
            }
        }

        setContent {
            SnapSightTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (permissionsGranted) {
                        CaptureScreen(
                            controller = cameraController,
                            statusText = statusText,
                            sessionButtonLabel = "촬영",
                            onSessionButton = { cameraController.takePhoto() },
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

    /** 볼륨 버튼 = 셔터. 화면을 보지 않고도 촬영할 수 있는 물리 트리거. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (permissionsGranted && cameraController.isBound) {
                cameraController.takePhoto()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val TAG = "SnapSight"
    }
}
