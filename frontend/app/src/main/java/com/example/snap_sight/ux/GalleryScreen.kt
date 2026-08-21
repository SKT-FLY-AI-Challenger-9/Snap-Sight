// 이 파일: "사진 찾기" 화면 (#78) — 찍은 사진을 AI 설명 카드로 훑어보고 텍스트로 거르는 1차 구현.
// 디자인은 Figma Make 시안(v31) 다크 테마 기준. 음성 필터 스택·결과 듣기·카드 탭 낭독은 기능 3-C.
package com.example.snap_sight.ux

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.snap_sight.camera.GalleryPhoto

// Figma Make 시안(v31) 팔레트 — 다른 화면과 동일한 SnapPalette 값을 쓴다
private val GalleryBackground = SnapPalette.Background
private val GalleryCard = SnapPalette.Card
private val GalleryAccent = SnapPalette.Accent
private val GalleryTextPrimary = SnapPalette.TextPrimary
private val GalleryTextSecondary = SnapPalette.TextSecondary
// 시안의 "말해서 찾기" 버튼 — 짙은 파랑 배경 + 파란 테두리
private val GalleryVoiceBg = SnapPalette.AccentSoft

/**
 * 사진 찾기 화면.
 *
 * @param photos    최신순 사진 목록 — 음성 필터 스택이 이미 적용된 결과 (로딩 완료 전엔 null)
 * @param onBack    헤더 뒤로가기
 * @param onVoiceSearch   "말해서 찾기" — 음성 질의를 받아 필터 스택에 누적한다 (기능 3-C)
 * @param filterSummaries 누적된 음성 필터 조건 요약 (비어 있으면 칩 영역 숨김)
 * @param onResetFilters  필터 전체 해제
 * @param onPhotoClick    카드 탭 — 상세 설명(long_desc)을 음성으로 낭독한다
 * @param onReadResults   "결과 듣기" — 지금 목록의 사진들을 훑어 낭독한다 ("목록 읽어줘"와 동일)
 */
@Composable
fun GalleryScreen(
    photos: List<GalleryPhoto>?,
    onBack: () -> Unit,
    onVoiceSearch: () -> Unit = {},
    filterSummaries: List<String> = emptyList(),
    onResetFilters: () -> Unit = {},
    onPhotoClick: (GalleryPhoto) -> Unit = {},
    onReadResults: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf("전체") }
    val filtered = photos?.filter { photo ->
        val matchesQuery = query.isBlank() || photo.title.contains(query) ||
            photo.description.contains(query) || photo.dateText.contains(query)
        val matchesCategory = categoryFilter == "전체" || photo.category == categoryFilter
        matchesQuery && matchesCategory
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GalleryBackground)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "뒤로 가기" },
            ) {
                Text(text = "‹", color = GalleryAccent, style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                text = "사진 찾기",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = GalleryTextPrimary,
            )
        }

        Text(
            text = "어떤 사진을 찾고 싶으세요?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = GalleryTextPrimary,
            modifier = Modifier.padding(top = 8.dp),
        )

        // 시안(v31) 스타일의 음성 버튼 2분할 — 말해서 찾기 · 결과 듣기 (기능 3-C)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VoiceActionButton(
                emoji = "🎤",
                label = "말해서 찾기",
                description = "말해서 찾기. 음성으로 사진을 검색합니다",
                onClick = onVoiceSearch,
                modifier = Modifier.weight(1f),
            )
            VoiceActionButton(
                emoji = "🔊",
                label = "결과 듣기",
                description = "결과 듣기. 지금 목록에 있는 사진들을 순서대로 읽어드립니다",
                onClick = onReadResults,
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("예: 바닷가에서 찍은 사진", color = GalleryTextSecondary) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = GalleryTextPrimary,
                unfocusedTextColor = GalleryTextPrimary,
                focusedBorderColor = GalleryAccent,
                unfocusedBorderColor = GalleryCard,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .semantics { contentDescription = "사진 검색어 입력" },
        )

        Text(
            text = "AI가 사진 속 사람·장소·상황을 기준으로 자동 정리했어요.",
            style = MaterialTheme.typography.bodySmall,
            color = GalleryTextSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
        )

        // 대분류 필터 (추억/음식/인물) — 라벨링 결과 category와 1:1
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            listOf("전체", "추억", "음식", "인물").forEach { name ->
                val selected = categoryFilter == name
                Text(
                    text = name,
                    color = if (selected) GalleryBackground else GalleryTextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(
                            if (selected) GalleryAccent else GalleryCard,
                            RoundedCornerShape(20.dp),
                        )
                        .clickable { categoryFilter = name }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                        .semantics { contentDescription = "$name 사진 보기" },
                )
            }
        }

        // 음성 필터 스택 (점진 좁히기) — 누적된 조건과 해제 버튼 (기능 3-C)
        if (filterSummaries.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            "적용된 검색 조건: ${filterSummaries.joinToString(", ")}. 조건 지우기 버튼 있음"
                    },
            ) {
                Text(
                    text = "🔎 " + filterSummaries.joinToString(" + "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GalleryAccent,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onResetFilters, shape = RoundedCornerShape(10.dp)) {
                    Text("조건 지우기", color = GalleryTextSecondary)
                }
            }
        }

        when {
            filtered == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GalleryAccent)
            }

            filtered.isEmpty() -> Text(
                text = if (query.isBlank()) "아직 찍은 사진이 없어요. 촬영하면 여기에 모여요."
                else "\"$query\"에 맞는 사진을 못 찾았어요.",
                color = GalleryTextSecondary,
                modifier = Modifier.padding(top = 24.dp),
            )

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filtered, key = { it.uri }) { photo ->
                    PhotoCard(photo, onClick = { onPhotoClick(photo) })
                }
            }
        }
    }
}

/** 시안(v31)의 파란 테두리 음성 버튼 — 짙은 파랑 배경, 마이크/스피커 + 라벨. */
@Composable
private fun VoiceActionButton(
    emoji: String,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .background(GalleryVoiceBg, RoundedCornerShape(16.dp))
            .border(2.dp, GalleryAccent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 16.dp)
            .semantics { contentDescription = description },
    ) {
        Text(text = emoji, color = GalleryAccent)
        Text(
            text = "  $label",
            color = GalleryTextPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PhotoCard(photo: GalleryPhoto, onClick: () -> Unit = {}) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GalleryCard),
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            // 카드 전체가 한 번에 낭독되도록 하나의 접근성 단위로 묶는다
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${photo.title}, ${photo.dateText}, ${photo.description}. 누르면 자세한 설명을 들려드려요"
            },
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (photo.thumbnail != null) {
                Image(
                    bitmap = photo.thumbnail.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(Modifier.size(72.dp).background(GalleryBackground, RoundedCornerShape(12.dp)))
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                photo.category?.let { category ->
                    Text(
                        text = category,
                        color = GalleryAccent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = photo.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = GalleryTextPrimary,
                )
                Text(
                    text = photo.dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = GalleryTextSecondary,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                Text(
                    text = "“${photo.description}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GalleryTextPrimary,
                )
            }
        }
    }
}
