// 이 파일: 촬영 결과 화면 (S4, #80 — Make 시안 v31) — "✓ 촬영 완료" + 사진 + 한 줄 요약 +
// 판정 표 + 음성 다시 듣기·라벨 붙이기 큰 버튼 두 개 (2026-08-25: 상세 설명 접이식과
// "사진은 저장됐어요" 문구는 뺐다).
package com.example.snap_sight.ux

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * @param headline 온디바이스 즉시 요약 (예: "사람 2명 사진을 찍었어요") — 서버 설명보다 먼저 채워진다
 * @param details  판정 표 행 (라벨 to 값) — 온디바이스에서 아는 값만 전달한다
 */
@Composable
fun ResultScreen(
    photo: Bitmap?,
    rawText: String,
    onReplayDescription: () -> Unit,
    /** TalkBack 전용 다시 촬영 경로 — 전역 탭 문법은 TalkBack이 가로채므로 접근성 액션으로 노출한다. */
    onRetake: (() -> Unit)? = null,
    headline: String? = null,
    details: List<Pair<String, String>> = emptyList(),
    /** "이 사진 라벨 붙이기" — 음성으로 커스텀 라벨을 부착한다 (기능 3). null 이면 버튼 숨김. */
    onAddLabel: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SnapPalette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
            .semantics {
                // TalkBack: 사진 저장 안내 문구를 없앤 자리를 대신해, 화면 전체에 다시 촬영
                // 접근성 액션을 남긴다 (일반 터치에는 반응하지 않는다) — 2026-08-25.
                if (onRetake != null) {
                    onClick(label = "다시 촬영") {
                        onRetake()
                        true
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier.padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "✓ ", color = SnapPalette.Success, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "촬영 완료",
                color = SnapPalette.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (photo != null) {
            // 원본 비율 유지 (사용자 요청 2026-08-31) — 고정 틀에 맞춰 자르지 않고 그대로 축소한다.
            // 높이 300dp 를 기준으로 사진 비율만큼 폭을 잡아 가운데 정렬 (세로 사진은 좁게,
            // 가로 사진은 화면 폭 한도 안에서 넓게).
            val photoAspect = photo.width.toFloat() / photo.height.coerceAtLeast(1)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = "방금 촬영한 사진",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(300.dp)
                        .aspectRatio(photoAspect)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }
        }

        if (!headline.isNullOrBlank()) {
            Text(
                text = headline,
                color = SnapPalette.TextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        if (rawText.isNotBlank()) {
            Text(
                text = "요청: “$rawText”",
                color = SnapPalette.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (details.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 14.dp)) {
                details.forEachIndexed { index, (label, value) ->
                    if (index > 0) HorizontalDivider(color = SnapPalette.CardBorder, thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            color = SnapPalette.AccentLight,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = value,
                            color = SnapPalette.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // 상세 설명 접이식과 "사진은 저장됐어요" 문구는 제거 (사용자 요청 2026-08-25) —
        // 대신 음성 다시 듣기·라벨 붙이기를 큰 버튼 두 개로 남긴다.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ResultActionButton(
                emoji = "🔊",
                label = "음성 다시 듣기",
                description = "설명 다시 듣기",
                onClick = onReplayDescription,
                modifier = Modifier.weight(1f),
            )
            if (onAddLabel != null) {
                ResultActionButton(
                    emoji = "🏷",
                    label = "라벨 붙이기",
                    description = "이 사진에 음성으로 라벨 붙이기. 나중에 그 이름으로 찾을 수 있어요",
                    onClick = onAddLabel,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 결과 화면의 큰 액션 버튼 — 아이콘 위, 글자 아래 (좁은 폭에서 줄바꿈돼도 겹치지 않게). */
@Composable
private fun ResultActionButton(
    emoji: String,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 원래 크기로 복원 (사용자 요청 2026-08-26 — 줄여야 했던 건 갤러리의 "말해서 찾기" 쪽이었다)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(SnapPalette.Card, RoundedCornerShape(16.dp))
            .border(2.dp, SnapPalette.Accent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 20.dp)
            .semantics { contentDescription = description },
    ) {
        Text(text = emoji, fontSize = 22.sp)
        Text(
            text = label,
            color = SnapPalette.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
