// 이 파일: 홈 화면 (S2, #80 — Make 시안 v31) — "어떤 순간을 남기고 싶으세요?" + 마이크 버튼 +
// 예시 발화 + 사진 찾기. 듣는 중에는 마이크가 파랗게 차오르고 인식된 발화·"이해하는 중…"을 보여준다.
package com.example.snap_sight.ux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val EXAMPLES = listOf(
    "“앞에 있는 두 사람 같이 찍어줘”",
    "“바다를 배경으로 친구 전신을 찍어줘”",
    "“식탁 위 음식 전체가 나오게 찍어줘”",
)

/**
 * @param isListening    세션이 발화 청취/해석 중 — 시안처럼 홈 위에서 마이크가 파랗게 활성화된다
 * @param recognizedText 인식된 발화 원문 (도착 전엔 빈 문자열)
 */
@Composable
fun HomeScreen(
    onStartSession: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    isListening: Boolean = false,
    recognizedText: String = "",
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SnapPalette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SnapPalette.Card,
                modifier = Modifier
                    .clickable(onClick = onOpenSettings)
                    .semantics { contentDescription = "설정 화면 열기" },
            ) {
                Text(
                    text = "⚙ 설정",
                    color = SnapPalette.TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Text(
            text = "어떤 순간을\n남기고 싶으세요?",
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 35.sp,
            color = SnapPalette.TextPrimary,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = "찍고 싶은 장면을 말해주세요.\nAI가 알아서 도와드려요.",
            color = SnapPalette.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 8.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            val micModifier = if (isListening) {
                Modifier
                    .size(160.dp)
                    .border(6.dp, SnapPalette.AccentSoft, CircleShape)
                    .background(SnapPalette.Accent, CircleShape)
            } else {
                Modifier
                    .size(160.dp)
                    .border(1.dp, SnapPalette.CardBorder, CircleShape)
                    .background(SnapPalette.Card, CircleShape)
            }
            Box(
                modifier = micModifier
                    .clickable(onClick = onStartSession)
                    .semantics {
                        contentDescription = if (isListening) "듣고 있어요"
                        else "말해서 시작하기. 화면을 두 번 탭하거나 볼륨 버튼으로도 시작할 수 있습니다"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "🎤", fontSize = 46.sp)
            }
        }

        // 듣는 중엔 시안처럼 라벨 자리가 발화 원문 + "이해하는 중…"으로 바뀐다
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !isListening -> Text(
                    text = "말해서 시작하기",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = SnapPalette.TextPrimary,
                    textAlign = TextAlign.Center,
                )
                recognizedText.isBlank() -> Text(
                    text = "듣고 있어요…",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = SnapPalette.TextPrimary,
                    textAlign = TextAlign.Center,
                )
                else -> {
                    Text(
                        text = "“$recognizedText”",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        color = SnapPalette.TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "이해하는 중…",
                        fontSize = 13.sp,
                        color = SnapPalette.Accent,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "이렇게 말해보세요",
            color = SnapPalette.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(top = 14.dp),
        ) {
            EXAMPLES.forEach { example ->
                Text(
                    text = example,
                    color = SnapPalette.TextTertiary,
                    fontSize = 14.sp,
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SnapPalette.Card,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(14.dp))
                .semantics { contentDescription = "사진 찾기 화면 열기" },
            onClick = onOpenGallery,
        ) {
            Text(
                text = "🖼  사진 찾기",
                color = SnapPalette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
