// 이 파일: 카메라 미리보기와 버튼(세션 시작·렌즈 전환)을 그리는 화면.
// 개발 확인용 임시 화면이라 최소한만 있다.
// 정식 접근성 화면(⑥ 담당)이 완성되면 통째로 교체된다.
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
 *
 * @param cvObjects ② CV 스트림의 최신 추적 객체 — [DetectionOverlay]가 미리보기 위에
 *                  박스로 그린다 (개발·데모 검증용, 정식 화면에서는 음성·햅틱으로 대체)
 */
@Composable
fun CaptureScreen(
    controller: CameraController,
    statusText: String,
    sessionButtonLabel: String,
    onSessionButton: () -> Unit,
    cvObjects: List<TrackedObject> = emptyList(),
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // FIT_CENTER: 촬영 프레임(센서 3:4)을 자르지 않고 그대로 보여준다(위아래 검은 띠).
    // 기본값 FILL_CENTER 는 화면을 채우려고 프레임 위아래를 잘라내서 "화면과 찍힌 사진이 다르다"는
    // 피드백의 원인이었다. CV 판정·자동 줌·저장되는 사진은 모두 이 전체 프레임 기준이다.
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

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
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { contentDescription = "설정 화면 열기" },
            ) {
                Text("설정")
            }
        }
    }
}
