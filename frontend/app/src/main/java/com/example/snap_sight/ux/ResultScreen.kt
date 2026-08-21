// 이 파일: 촬영 결과 화면 (S4, #80) — 완료 표시 + 사진 + 요청·AI 설명 + 저장/다시 촬영.
// 설명은 서버 생성이 끝나는 대로 갱신된다 (그 전엔 "설명을 만드는 중…").
package com.example.snap_sight.ux

import android.graphics.Bitmap
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap

@Composable
fun ResultScreen(
    photo: Bitmap?,
    rawText: String,
    description: String?,
    onReplayDescription: () -> Unit,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
) {
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
                text = "촬영이 완료되었습니다",
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

        if (rawText.isNotBlank()) {
            Text(
                text = "요청: “$rawText”",
                color = SnapPalette.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text(
            text = description ?: "설명을 만드는 중…",
            color = SnapPalette.TextPrimary,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "🔊 설명 다시 듣기",
            color = SnapPalette.Accent,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(onClick = onReplayDescription)
                .padding(12.dp)
                .semantics { contentDescription = "설명 다시 듣기" },
        )

        Spacer(Modifier.height(20.dp))
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
