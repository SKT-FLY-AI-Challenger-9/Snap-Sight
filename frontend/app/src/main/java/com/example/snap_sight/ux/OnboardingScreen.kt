// 이 파일: S1 온보딩 화면 — Figma 시안(#80) 다크 테마. 로고·소개·기능 카드 3개·권한 버튼.
// TalkBack 낭독 순서 = 배치 순서, 권한 GRANTED 시 onContinue 자동 호출은 기존 계약 그대로.
package com.example.snap_sight.ux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    FeatureItem("🎚", "비언어 사운드로 안내", "촬영 중에는 주변 소리를 들을 수 있도록 음성 안내를 최소화합니다."),
    FeatureItem("📳", "햅틱으로 방향 전달", "왼쪽·오른쪽·가까이·멀리 — 진동 패턴으로 연속적으로 알려드립니다."),
    FeatureItem("🔊", "물리 버튼으로 촬영", "볼륨 버튼(▲)을 눌러 사진을 찍을 수 있습니다. 화면을 보지 않아도 됩니다."),
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SnapPalette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(SnapPalette.Success, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("📷", fontSize = 18.sp) }
            Text(
                text = "  Snap-Sight",
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = SnapPalette.TextPrimary,
            )
        }

        Text(
            text = "원하는 순간을\n직접 찍을 수 있도록\n도와드릴게요.",
            fontWeight = FontWeight.Bold,
            fontSize = 27.sp,
            lineHeight = 36.sp,
            color = SnapPalette.TextPrimary,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            text = "찍고 싶은 장면을 말하면, 방향과 거리를 사운드와 진동으로 안내합니다.",
            color = SnapPalette.TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 12.dp),
        )

        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        val statusText = when (permissionState) {
            OnboardingPermissionState.NOT_REQUESTED -> "카메라와 마이크 접근을 허용해야 합니다."
            OnboardingPermissionState.DENIED -> "권한이 거부되었습니다. 다시 시도하거나 설정에서 직접 허용해주세요."
            OnboardingPermissionState.GRANTED -> "권한이 모두 허용되었습니다. 잠시 후 이동합니다."
        }
        Text(
            text = statusText,
            color = SnapPalette.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 20.dp)
                // 상태 전환이 바뀔 때마다 TalkBack이 즉시 재낭독하도록
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
            Text(
                text = if (permissionState == OnboardingPermissionState.DENIED) "다시 요청하기"
                else "카메라·마이크 권한 허용",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (permissionState == OnboardingPermissionState.DENIED) {
            TextButton(
                onClick = onOpenAppSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "앱 설정 화면에서 권한 직접 허용하기" },
            ) {
                Text("설정으로 이동", color = SnapPalette.Accent)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 요청 전/거부됨/허용됨 3단계로 구분해야 "거부 시 재안내" 경로가 나머지와 다르게 표시된다. */
enum class OnboardingPermissionState { NOT_REQUESTED, DENIED, GRANTED }
