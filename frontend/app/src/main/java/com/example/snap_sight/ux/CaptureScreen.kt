// 이 파일: S3 촬영(조준) 화면 — Figma 시안(#80). 상단 "현재 요청" 발화 카드,
// 카메라 미리보기(+탐지 오버레이), 하단 방향 안내 카드와 취소 버튼.
package com.example.snap_sight.ux

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.snap_sight.camera.CameraController
import com.example.snap_sight.cv.TrackedObject

/**
 * @param rawText      현재 세션 발화 원문 — 상단 "현재 요청" 카드에 표시 (없으면 상태 문구)
 * @param guidanceText 하단 방향 안내 문구 (예: "카메라를 조금 왼쪽으로 이동해주세요")
 * @param showOverlays 조준 UI(요청 카드·안내 카드·취소) 노출 여부 — 홈이 위에 떠 있을 땐 숨긴다
 */
@Composable
fun CaptureScreen(
    controller: CameraController,
    statusText: String,
    rawText: String,
    guidanceText: String,
    onCancel: () -> Unit,
    cvObjects: List<TrackedObject> = emptyList(),
    showOverlays: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // FIT_CENTER: 촬영 프레임(센서 3:4)을 자르지 않고 그대로 보여준다 — "화면과 찍힌 사진이 다르다" 피드백 반영.
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

    DisposableEffect(Unit) {
        controller.start(lifecycleOwner, previewView)
        onDispose { controller.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize().background(SnapPalette.Background)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "카메라 미리보기" },
        )

        DetectionOverlay(objects = cvObjects)

        if (showOverlays) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xCC16191F),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = "현재 요청",
                            color = SnapPalette.Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (rawText.isNotBlank()) "“$rawText”" else statusText,
                            color = SnapPalette.TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xCC16191F)) {
                    Text(
                        text = "🔊 음성 안내 중",
                        color = SnapPalette.TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (guidanceText.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xE60E1116),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SnapPalette.Warning, RoundedCornerShape(14.dp)),
                    ) {
                        Text(
                            text = guidanceText,
                            color = SnapPalette.Warning,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 24.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SnapPalette.Card,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "촬영 취소. 볼륨 버튼을 길게 눌러도 취소됩니다" },
                    onClick = onCancel,
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "✕  취소",
                            color = SnapPalette.TextPrimary,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
