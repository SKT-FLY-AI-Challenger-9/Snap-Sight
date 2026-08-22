// 이 파일: 촬영 결과 화면 (S4, #80 — Make 시안 v31) — "✓ 촬영 완료" + 사진 + 한 줄 요약 +
// 판정 표 + 접이식 상세 설명(서버 생성이 끝나는 대로 갱신) + 저장/다시 촬영.
package com.example.snap_sight.ux

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    description: String?,
    onReplayDescription: () -> Unit,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    headline: String? = null,
    details: List<Pair<String, String>> = emptyList(),
    /** "이 사진 라벨 붙이기" — 음성으로 커스텀 라벨을 부착한다 (기능 3). null 이면 버튼 숨김. */
    onAddLabel: (() -> Unit)? = null,
) {
    // 시안의 "상세 설명" 접이식 — 기본 접힘. TalkBack 사용자는 즉시 요약·음성 안내가 우선이다.
    var descriptionExpanded by remember { mutableStateOf(false) }

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
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "방금 촬영한 사진",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
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

        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (descriptionExpanded) "상세 설명 ▲" else "상세 설명 ▼",
                color = SnapPalette.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { descriptionExpanded = !descriptionExpanded }
                    .padding(vertical = 10.dp)
                    .semantics {
                        contentDescription = if (descriptionExpanded) "상세 설명 접기" else "상세 설명 보기"
                    },
            )
            Spacer(Modifier.width(24.dp))
            Text(
                text = "🔊 음성 다시 듣기",
                color = SnapPalette.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onReplayDescription)
                    .padding(vertical = 10.dp)
                    .semantics { contentDescription = "설명 다시 듣기" },
            )
            if (onAddLabel != null) {
                Spacer(Modifier.width(24.dp))
                Text(
                    text = "🏷 라벨 붙이기",
                    color = SnapPalette.TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable(onClick = onAddLabel)
                        .padding(vertical = 10.dp)
                        .semantics {
                            contentDescription = "이 사진에 음성으로 라벨 붙이기. 나중에 그 이름으로 찾을 수 있어요"
                        },
                )
            }
        }
        AnimatedVisibility(visible = descriptionExpanded) {
            Text(
                text = description ?: "설명을 만드는 중…",
                color = SnapPalette.TextTertiary,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SnapPalette.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .semantics { contentDescription = "저장하고 홈으로 돌아가기" },
            ) {
                Text("저장하기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onRetake,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .semantics { contentDescription = "다시 촬영하기" },
            ) {
                Text("↺  다시 촬영", color = SnapPalette.TextPrimary, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
