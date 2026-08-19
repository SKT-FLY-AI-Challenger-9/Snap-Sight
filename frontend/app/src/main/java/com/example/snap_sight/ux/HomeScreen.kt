// 이 파일: 홈 화면 (S2, #80) — "어떤 순간을 남기고 싶으세요?" + 마이크 버튼 + 예시 발화.
// Figma 시안 그대로: 다크 배경, 원형 마이크가 세션 시작, 하단에 사진 찾기 진입.
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
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

@Composable
fun HomeScreen(
    onStartSession: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Snap-Sight",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = SnapPalette.TextPrimary,
            )
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
            text = "어떤 순간을 남기고 싶으세요?",
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            color = SnapPalette.TextPrimary,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            text = "찍고 싶은 장면을 말해주세요. AI가 알아서 도와드려요.",
            color = SnapPalette.TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .border(1.dp, SnapPalette.CardBorder, CircleShape)
                    .background(SnapPalette.Card, CircleShape)
                    .clickable(onClick = onStartSession)
                    .semantics {
                        contentDescription = "말해서 시작하기. 볼륨 버튼으로도 시작할 수 있습니다"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "🎤", fontSize = 44.sp)
            }
        }
        Text(
            text = "말해서 시작하기",
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = SnapPalette.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )

        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EXAMPLES.forEach { example ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SnapPalette.Card,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = example,
                        color = SnapPalette.TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            text = "🖼 사진 찾기",
            color = SnapPalette.TextPrimary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenGallery)
                .padding(vertical = 16.dp)
                .semantics { contentDescription = "사진 찾기 화면 열기" },
        )
    }
}
