package com.example.snap_sight.ux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp

/**
 * S1 온보딩 화면 (`docs/screen-design.md` 1·4절).
 *
 * TalkBack 낭독 순서 = Column 배치 순서: 앱 소개 → 볼륨 버튼 사용법 → 권한 상태/요청 버튼.
 * 권한이 [OnboardingPermissionState.GRANTED]가 되면 [onContinue]를 자동 호출한다 — 화면은
 * "언제 다음으로 넘어가야 하는지"만 알리고, 실제 화면 전환(= S2로 이동)은 호출부 책임이다.
 *
 * **MainActivity 연결 시 필요한 것 (TODO, 이번 작업 범위 아님)**:
 *  - "온보딩을 이미 봤는지" 기억할 저장소(SharedPreferences 등) — 최초 실행 1회만 노출
 *  - 현재 화면(S1/S2/S3/S5)을 추적하는 상태. 기존 `MainActivity.permissionsGranted`는
 *    카메라 권한 여부만 나타내는 값이라 화면 전환 상태로 재사용하지 않는다 — 별도 상태(enum 등) 필요
 *  - [onRequestPermissions] → 실제 `ActivityResultContracts.RequestMultiplePermissions` 런처 연결
 *  - [onOpenAppSettings] → 영구 거부 시 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 인텐트 연결
 *  - [permissionState] 산출 로직 — 최초 요청 전(NOT_REQUESTED)과 거부 후(DENIED)를 구분해야
 *    "재안내 경로"가 성립함(`shouldShowRequestPermissionRationale` 등, Activity 의존)
 */
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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Snap-Sight에 오신 것을 환영합니다",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "시각장애인의 촬영을 돕는 앱입니다. 화면을 보지 않아도 볼륨 버튼만으로 사용할 수 있습니다.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "볼륨 버튼을 짧게 누르면 다음 단계로 진행하고, 길게 누르면 현재 동작을 취소합니다.",
            style = MaterialTheme.typography.bodyLarge,
        )

        val statusText = when (permissionState) {
            OnboardingPermissionState.NOT_REQUESTED -> "촬영을 시작하려면 카메라와 마이크 권한이 필요합니다"
            OnboardingPermissionState.DENIED -> "권한이 거부되었습니다. 다시 시도하거나 설정에서 직접 허용해주세요"
            OnboardingPermissionState.GRANTED -> "권한이 모두 허용되었습니다. 잠시 후 이동합니다"
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            // 상태 전환(NOT_REQUESTED→DENIED→GRANTED)이 바뀔 때마다 TalkBack이 즉시 재낭독하도록
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "카메라와 마이크 권한 허용하기" },
        ) {
            Text(if (permissionState == OnboardingPermissionState.DENIED) "다시 요청하기" else "권한 허용하기")
        }

        if (permissionState == OnboardingPermissionState.DENIED) {
            TextButton(
                onClick = onOpenAppSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "앱 설정 화면에서 권한 직접 허용하기" },
            ) {
                Text("설정으로 이동")
            }
        }
    }
}

/** 요청 전/거부됨/허용됨 3단계로 구분해야 "거부 시 재안내" 경로가 나머지와 다르게 표시된다. */
enum class OnboardingPermissionState { NOT_REQUESTED, DENIED, GRANTED }
