// 이 파일: 홈 화면 (S2, #80 — Make 시안 v31) — "어떤 순간을 남기고 싶으세요?" + 마이크 버튼 +
// 예시 발화 + 사진 찾기. 듣는 중에는 마이크가 파랗게 차오르고 인식된 발화·"이해하는 중…"을 보여준다.
package com.example.snap_sight.ux

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.snap_sight.R

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
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            // 카멜레온 로고 (배경 제거) — 화면 왼쪽 위 (사용자 요청 2026-08-25).
            // 설정 버튼이 커서(3배) 로고까지 40dp면 한 줄에 다 안 들어가 서로 붙어버린다
            // (실기기 확인 2026-08-25) — 24dp로 줄여 여유를 둔다.
            Image(
                painter = painterResource(R.drawable.logo_chameleon),
                contentDescription = null,
                contentScale = ContentScale.FillHeight,
                modifier = Modifier.height(24.dp),
            )
            Spacer(Modifier.weight(1f))
            // 위치는 그대로, 크기만 약 3배로 키웠다 (사용자 요청 2026-08-25).
            // 테두리가 배경(카드색)에 묻히지 않고 항상 보이도록 흰색 보더라인을 그린다.
            Surface(
                shape = RoundedCornerShape(30.dp),
                color = SnapPalette.Accent,
                border = BorderStroke(1.5.dp, Color.White),
                modifier = Modifier
                    .clickable(onClick = onOpenSettings)
                    .semantics { contentDescription = "설정 화면 열기" },
            ) {
                Text(
                    text = "⚙ 설정",
                    color = SnapPalette.TextPrimary,
                    fontSize = 39.sp,
                    modifier = Modifier.padding(horizontal = 36.dp, vertical = 24.dp),
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
                // 테두리가 배경에 묻히지 않고 항상 보이도록 흰색 보더라인 (사용자 요청 2026-08-25).
                Modifier
                    .size(160.dp)
                    .border(1.5.dp, Color.White, CircleShape)
                    .background(SnapPalette.Card, CircleShape)
            }
            Box(
                modifier = micModifier
                    .clickable(onClick = onStartSession)
                    .semantics {
                        contentDescription = if (isListening) "듣고 있어요"
                        else "말해서 시작하기. 촬영뿐 아니라 설정, 갤러리라고 말해 이동할 수도 있어요"
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
                // 글자 크기 50% 확대 (17sp → 25.5sp, 사용자 요청 2026-08-25)
                !isListening -> Text(
                    text = "말해서 시작하기",
                    fontWeight = FontWeight.Bold,
                    fontSize = 25.5.sp,
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
        // 설정 칩과 같은 clickable 패턴 — Surface(onClick) + 🖼 이모지 조합이 일부 기기에서
        // 글자가 안 보이는 문제(실사용 피드백 2026-08-22)가 있어 교체했다
        // grammarClickable — 빠르게 두 번 탭하면 갤러리로 넘어가지 않고 화면의 전역
        // 두 번 탭 문법(홈: 말해서 시작하기)으로 위임된다. 한 번 탭만 갤러리를 연다.
        // 시작 위치는 그대로 두고 화면 맨 아래까지 채우도록 키웠다 (사용자 요청 2026-08-25).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 16.dp)
                .background(SnapPalette.Accent, RoundedCornerShape(14.dp))
                // 테두리가 배경에 묻히지 않고 항상 보이도록 흰색 보더라인 (사용자 요청 2026-08-25).
                .border(1.5.dp, Color.White, RoundedCornerShape(14.dp))
                .grammarClickable(onClick = onOpenGallery)
                .semantics { contentDescription = "갤러리 열기" },
        ) {
            // 설정 글씨 크기(39sp)와 맞췄다 (사용자 요청 2026-08-25)
            Text(
                text = "갤러리",
                color = SnapPalette.TextPrimary,
                fontSize = 39.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
