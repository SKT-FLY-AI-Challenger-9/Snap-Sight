// 이 파일: S1 온보딩 화면 — Figma Make 시안(v31, #80). 타이틀 + "작동 방식" 접이식 카드 +
// 하단 고정 권한 버튼·나중에 설정. 건너뛰거나 거부하면 시안의 앰버 경고 카드로 재안내한다.
// TalkBack 낭독 순서 = 배치 순서, 권한 GRANTED 시 onContinue 자동 호출은 기존 계약 그대로.
package com.example.snap_sight.ux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class FeatureItem(val icon: String, val title: String, val body: String)

private val FEATURES = listOf(
    FeatureItem("🎚", "사운드로 방향 안내", "촬영 중에는 음성을 최소화해 주변 소리를 들을 수 있습니다."),
    FeatureItem("📳", "진동으로 거리 안내", "왼쪽·오른쪽·가까이·멀리를 햅틱 패턴으로 알려드립니다."),
    // #84 탭 우선: 탭을 먼저, 볼륨 버튼은 병행 수단으로 나중에 말한다
    FeatureItem("👆", "화면 탭으로 촬영", "화면 아무 곳이나 한 번 탭해 화면을 보지 않고 찍을 수 있습니다. 두 번 탭은 뒤로 가기예요."),
)

@Composable
fun OnboardingScreen(
    permissionState: OnboardingPermissionState,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    LaunchedEffect(permissionState) {
        if (permissionState == OnboardingPermissionState.GRANTED) onContinue()
    }

    // 시안의 "작동 방식" 접이식 카드 — 기본 접힘, 시각 사용자용 부가 설명이라 상태 저장은 안 한다.
    var howItWorksExpanded by remember { mutableStateOf(false) }
    // "나중에 설정"을 눌렀으면 시안처럼 앰버 경고 카드로 안내를 전환한다.
    var skippedOnce by remember { mutableStateOf(false) }
    val showWarningCard = skippedOnce || permissionState == OnboardingPermissionState.DENIED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SnapPalette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "원하는 순간을 직접\n찍도록 도와드릴게요",
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            lineHeight = 38.sp,
            color = SnapPalette.TextPrimary,
            modifier = Modifier.padding(top = 32.dp),
        )
        Text(
            text = "찍고 싶은 장면을 말하면,\n방향과 거리를 사운드와 진동으로 안내합니다.",
            color = SnapPalette.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 12.dp),
        )

        Spacer(Modifier.height(24.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SnapPalette.Card,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        if (howItWorksExpanded) "작동 방식 설명 접기" else "작동 방식 설명 펼치기"
                },
            onClick = { howItWorksExpanded = !howItWorksExpanded },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "작동 방식",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = SnapPalette.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (howItWorksExpanded) "▲" else "▼",
                    fontSize = 11.sp,
                    color = SnapPalette.TextSecondary,
                )
            }
        }
        AnimatedVisibility(visible = howItWorksExpanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                FEATURES.forEach { feature ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SnapPalette.Card,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.padding(16.dp)) {
                            Text(text = feature.icon, fontSize = 20.sp, color = SnapPalette.Accent)
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    text = feature.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = SnapPalette.TextPrimary,
                                )
                                Text(
                                    text = feature.body,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = SnapPalette.TextSecondary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))

        if (showWarningCard) {
            // 시안의 권한 경고 카드 — 앰버 테두리, 제목/본문, 주황 "다시 허용하기" 버튼
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SnapPalette.Card,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SnapPalette.WarningStrong, RoundedCornerShape(14.dp))
                    // 상태 전환이 바뀔 때마다 TalkBack이 즉시 재낭독하도록
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "권한이 필요합니다",
                        color = SnapPalette.WarningStrong,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = "카메라와 마이크 접근을 허용해야 앱을 사용할 수 있어요.",
                        color = SnapPalette.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Button(
                        onClick = onRequestPermissions,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SnapPalette.WarningStrong,
                            contentColor = SnapPalette.Background,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "카메라·마이크 권한 다시 허용하기" },
                    ) {
                        Text("다시 허용하기", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (permissionState == OnboardingPermissionState.DENIED) {
                TextButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "앱 설정 화면에서 권한 직접 허용하기" },
                ) {
                    Text("설정에서 직접 허용", color = SnapPalette.Accent)
                }
            }
        } else {
            Text(
                text = "카메라와 마이크 접근을 허용해야 합니다.",
                color = SnapPalette.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(
                onClick = onRequestPermissions,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SnapPalette.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .heightIn(min = 52.dp)
                    .semantics { contentDescription = "카메라와 마이크 권한 허용하기" },
            ) {
                Text("카메라·마이크 권한 허용", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(
                onClick = { skippedOnce = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "나중에 설정하기" },
            ) {
                Text("나중에 설정", color = SnapPalette.TextSecondary, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 요청 전/거부됨/허용됨 3단계로 구분해야 "거부 시 재안내" 경로가 나머지와 다르게 표시된다. */
enum class OnboardingPermissionState { NOT_REQUESTED, DENIED, GRANTED }
