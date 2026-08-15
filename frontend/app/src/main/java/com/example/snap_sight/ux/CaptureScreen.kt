package com.example.snap_sight.ux

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.snap_sight.camera.CameraController
import com.example.snap_sight.cv.TrackedObject

/**
 * ⑤ 모듈 동작 확인용 임시 화면.
 * 정식 접근성 UI/온보딩은 ⑥ 담당 — docs/screen-design.md 의 S3 화면으로 교체 예정.
 */
@Composable
fun CaptureScreen(
    controller: CameraController,
    statusText: String,
    sessionButtonLabel: String,
    onSessionButton: () -> Unit,
    cvObjects: List<TrackedObject> = emptyList(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        controller.start(lifecycleOwner, previewView)
        onDispose { controller.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "카메라 미리보기" },
        )

        DetectionOverlay(objects = cvObjects)

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            tonalElevation = 4.dp,
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Button(
                onClick = onSessionButton,
                modifier = Modifier.semantics {
                    contentDescription = "$sessionButtonLabel. 볼륨 버튼으로도 조작할 수 있습니다"
                },
            ) {
                Text(sessionButtonLabel)
            }
            Button(
                onClick = { controller.toggleLens(lifecycleOwner, previewView) },
                modifier = Modifier.semantics { contentDescription = "전면 후면 카메라 전환" },
            ) {
                Text("렌즈 전환")
            }
        }
    }
}
