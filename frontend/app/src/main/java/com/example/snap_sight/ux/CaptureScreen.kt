// 이 파일: S3 촬영(조준) 화면 — Figma Make 시안(v31, #80). 상단 "요청" 발화 카드 + 음성 안내 칩,
// 카메라 미리보기(+탐지 오버레이), 하단 "촬영 상태 / 안내" 카드와 촬영 취소 버튼.
// 가로모드 (2026-08-31): 기기를 눕히면 갤럭시 카메라처럼 레이아웃은 그대로 두고 요소만 제자리
// 회전한다 — 셀카 칩·디버그 라벨은 그 자리에서 돌고, 하단 카드는 한 줄 안내로 눕혀 세운다 (OrientationUi.kt).
package com.example.snap_sight.ux

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.snap_sight.camera.CameraController
import com.example.snap_sight.cv.TrackedObject

/**
 * @param rawText      현재 세션 발화 원문 — 상단 "요청" 카드에 표시 (없으면 카드 숨김)
 * @param guidanceText 하단 방향 안내 문구 (예: "카메라를 조금 왼쪽으로 이동해주세요")
 * @param showOverlays 조준 UI(요청 카드·안내 카드·취소) 노출 여부 — 홈이 위에 떠 있을 땐 숨긴다
 * @param onLensChanged 전/후면 전환 직후 호출 — true = 전면(셀카). MainActivity 가 시선
 *                      판정·안내 모드를 켜고 끄는 데 쓴다
 * @param onShutterTap  탭 셔터 (#84) — null 이 아니면 미리보기를 "촬영" 접근성 노드로 노출하고
 *                      TalkBack 두 번 탭(=클릭 액션)도 같은 콜백으로 수렴시킨다
 */
@Composable
fun CaptureScreen(
    controller: CameraController,
    statusText: String,
    rawText: String,
    guidanceText: String,
    onCancel: () -> Unit,
    cvObjects: List<TrackedObject> = emptyList(),
    /** track_id → 등록 인물·사물 이름 — 해당 상자를 초록색+이름으로 구분해 그린다 */
    identities: Map<Int, String> = emptyMap(),
    showOverlays: Boolean = true,
    onLensChanged: (isFront: Boolean) -> Unit = {},
    onShutterTap: (() -> Unit)? = null,
    // 3x3 구도 그리드 (2026-08-23) — 미리보기 전용 오버레이, 찍힌 사진에는 남지 않는다
    gridEnabled: Boolean = false,
    gridColorArgb: Int = DEFAULT_GRID_COLOR,
    gridThicknessDp: Float = GridThickness.DEFAULT.dp,
    /** 인물/사물/풍경 내부 판정 확인용 — 사용자에게는 말하지 않고 화면에만 작게 표시한다
     *  (사용자 요청 2026-08-27). 빈 문자열이면 표시하지 않는다. */
    modeDebugLabel: String = "",
    /** 서류 모드 외곽 (2026-08-31) — null 이 아니면 파란 사각형으로 그린다 (엣지 확정 변은 실선). */
    documentOutline: DocumentObservation? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // FIT_CENTER: 촬영 프레임(센서 3:4)을 자르지 않고 그대로 보여준다 — "화면과 찍힌 사진이 다르다" 피드백 반영.
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    var isFrontLens by remember {
        mutableStateOf(controller.lensFacing == CameraSelector.LENS_FACING_FRONT)
    }
    // 가로모드 (2026-08-31) — 갤럭시 카메라처럼 레이아웃은 세로 고정 그대로 두고 요소만 제자리
    // 회전. 촬영 회전(targetRotation)과 같은 센서 판정이라 찍히는 방향과 항상 일치한다.
    val deviceRotation by controller.deviceRotationFlow.collectAsState()
    val isLandscape = isLandscapeUi(deviceRotation)
    // +90 = 기기 상단이 왼쪽(일반 가로 파지) → 가로모드의 "아래"는 세로 레이아웃의 왼쪽 끝
    val landscapeClockwise = uiRotationDegrees(deviceRotation) > 0f

    DisposableEffect(Unit) {
        controller.start(lifecycleOwner, previewView)
        onDispose { controller.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize().background(SnapPalette.Background)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    // 조준 중엔 미리보기 전체가 "촬영" 단일 접근성 노드 (#84 탭 셔터).
                    // TalkBack의 두 번 탭(클릭 액션)과 일반 두 번 탭이 같은 콜백으로 수렴한다.
                    if (onShutterTap != null) {
                        contentDescription = "촬영. 화면을 두 번 탭하면 사진을 찍습니다"
                        onClick(label = "촬영") {
                            onShutterTap()
                            true
                        }
                    } else {
                        contentDescription = "카메라 미리보기"
                    }
                },
        )

        // 3x3 그리드 — 미리보기와 같은 3:4 프레임(FIT_CENTER)에 맞춰 중앙 정렬로 그린다.
        // 촬영 파이프라인과 무관한 순수 UI 오버레이라 사진에는 절대 남지 않는다.
        if (gridEnabled) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                ) {
                    val color = Color(gridColorArgb)
                    val stroke = gridThicknessDp.dp.toPx()
                    for (i in 1..2) {
                        val x = size.width * i / 3f
                        val y = size.height * i / 3f
                        drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
                        drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
                    }
                }
            }
        }

        DetectionOverlay(
            objects = cvObjects,
            mirrored = isFrontLens,
            deviceRotation = deviceRotation,
            identities = identities,
        )
        DocumentOutlineOverlay(
            outline = documentOutline,
            mirrored = isFrontLens,
            deviceRotation = deviceRotation,
        )

        // 셀카 모드 전환 — 조준 UI 와 무관하게 항상 접근 가능 (오른쪽 위).
        // 가로모드에선 위치는 그대로(가로 기준 모서리) 두고 칩만 제자리 회전한다.
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SnapPalette.Card.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 12.dp)
                .rotateWithDevice(deviceRotation)
                .clickable {
                    controller.toggleLens(lifecycleOwner, previewView)
                    isFrontLens = controller.lensFacing == CameraSelector.LENS_FACING_FRONT
                    onLensChanged(isFrontLens)
                }
                .semantics {
                    contentDescription = if (isFrontLens) "후면 카메라로 전환" else "셀카 모드로 전환"
                },
        ) {
            Text(
                text = if (isFrontLens) "🔄 후면" else "🤳 셀카",
                color = SnapPalette.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // 인물/사물/풍경 내부 판정 확인용 — 사용자에게는 말하지 않고 화면에만 작게 표시한다
        // (사용자 요청 2026-08-27). showOverlays 와 무관하게 확인 가능해야 하므로 항상 노출.
        if (modeDebugLabel.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SnapPalette.Card.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 12.dp)
                    .rotateWithDevice(deviceRotation),
            ) {
                Text(
                    text = modeDebugLabel,
                    color = SnapPalette.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        if (showOverlays) {
            // 상단 "요청" 카드 — 발화 원문을 계속 보여줘 무엇을 찍는 중인지 확인할 수 있게 한다.
            // 가로모드에선 숨긴다 — 세로 전폭 카드는 눕혀 놓을 자리가 없고, 안내는 음성이 주 채널이다.
            if (rawText.isNotBlank() && !isLandscape) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SnapPalette.Card.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                        .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(14.dp)),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            text = "요청",
                            color = SnapPalette.Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "“$rawText”",
                            color = SnapPalette.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            if (!isLandscape) {
                // 하단 상태/안내 카드 + 촬영 취소 — 음성·햅틱(⑥)과 같은 판정을 화면 텍스트로도 보여준다 (#80)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SnapPalette.Card.copy(alpha = 0.92f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(14.dp)),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = statusText,
                                color = SnapPalette.TextSecondary,
                                fontSize = 13.sp,
                            )
                            Text(
                                text = guidanceText.ifBlank { "피사체를 찾고 있어요" },
                                color = SnapPalette.TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    Text(
                        text = "촬영 취소",
                        color = SnapPalette.TextSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clickable(onClick = onCancel)
                            .semantics {
                                contentDescription = "촬영 취소. 화면을 길게 눌러도 취소됩니다"
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            } else {
                // 가로모드 (2026-08-31) — 갤럭시 카메라 방식: 세로 레이아웃의 하단 띠는 가로 기준
                // 화면 아래쪽 끝(ROTATION_90 이면 왼쪽 끝)이 되므로, 그 자리에 안내를 한 줄로
                // 눕혀 세운다. 상태 줄(statusText)은 생략하고 방향 안내만 남긴다.
                QuarterRotated(
                    clockwise = landscapeClockwise,
                    modifier = Modifier
                        .align(if (landscapeClockwise) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(12.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = SnapPalette.Card.copy(alpha = 0.92f),
                            modifier = Modifier
                                .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(14.dp)),
                        ) {
                            Text(
                                text = guidanceText.ifBlank { "피사체를 찾고 있어요" },
                                color = SnapPalette.TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                        Text(
                            text = "촬영 취소",
                            color = SnapPalette.TextSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clickable(onClick = onCancel)
                                .semantics {
                                    contentDescription = "촬영 취소. 화면을 길게 눌러도 취소됩니다"
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
