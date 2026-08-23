// 이 파일: 위성 화면(설정·사진 찾기) 공통 크롬 — #84 v1 4-3의 "시각 헤더"(3번째 전환 확인 채널)와
// 4-4의 "배치 문법 통일"(최하단 전폭 복귀 버튼)을 한 곳에서 구현한다.
// 청각(earcon)·촉각(진동)에 이어 잔존시력 사용자를 위한 시각 채널: 위성 화면은 상단에
// 큰 제목 + 액센트 톤 배경 밴드가 항상 보인다. 복귀는 어느 화면이든 "최하단 전폭 버튼"으로도 가능
// (제스처를 모르는 사용자의 대체 경로 — docs/ux/design-principles.md 원칙 5).
package com.example.snap_sight.ux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 위성 화면 상단 대형 헤더 — 액센트 톤 밴드 위에 큰 제목. 잔존시력 사용자가
 * "지금 홈이 아닌 다른 화면에 있다"를 색과 크기로 즉시 알 수 있게 한다.
 *
 * @param backDescription ‹ 버튼의 TalkBack 설명 (화면별 복귀 의미를 정확히)
 */
@Composable
fun SatelliteHeader(
    title: String,
    onBack: () -> Unit,
    backDescription: String = "뒤로 가기, 홈으로 돌아갑니다",
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(SnapPalette.AccentSoft, RoundedCornerShape(16.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .grammarClickable(onBack)
                .semantics { contentDescription = backDescription },
        ) {
            Text(text = "‹", color = SnapPalette.AccentLight, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = title,
            color = SnapPalette.TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 최하단 전폭 복귀 버튼 — 모든 위성 화면에서 같은 위치·같은 문구. 탭 문법(길게 누르기)을
 * 모르는 사용자의 대체 복귀 경로다. 호출부는 각 화면의 공통 복귀 경로(onBack)에 연결한다.
 */
@Composable
fun HomeReturnButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "홈으로 돌아가기",
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(SnapPalette.Accent, RoundedCornerShape(16.dp))
            .grammarClickable(onClick)
            .padding(vertical = 16.dp)
            .semantics { contentDescription = label },
    ) {
        Text(
            text = label,
            color = SnapPalette.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
