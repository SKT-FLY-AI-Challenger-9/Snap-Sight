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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.snap_sight.camera.GalleryPhoto

/** "전체" 폴더 이름 — 라벨 필터 없이 전체 목록을 여는 특수 폴더 (MainActivity 필터와 공유). */
const val GALLERY_ALL_FOLDER = "전체"

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
 * @param onPhotoClick    카드 탭 — 사진 뷰어를 열어 크게 보여주고 상세 설명을 낭독한다
 * @param onReadResults   "결과 듣기" — 지금 목록의 사진들을 훑어 낭독한다 ("목록 읽어줘"와 동일)
 */
@Composable
fun GalleryScreen(
    photos: List<GalleryPhoto>?,
    onBack: () -> Unit,
    selectedFolder: String? = null,
    onFolderSelect: (String?) -> Unit = {},
    onVoiceSearch: () -> Unit = {},
    filterSummaries: List<String> = emptyList(),
    onResetFilters: () -> Unit = {},
    onPhotoClick: (GalleryPhoto) -> Unit = {},
    onReadResults: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    // 기본은 갤럭시 갤러리처럼 라벨 폴더 화면 — 폴더를 고르거나, 음성 필터·검색어가 생기면 목록.
    val listMode = selectedFolder != null || filterSummaries.isNotEmpty() || query.isNotBlank()
    val filtered = photos?.filter { photo ->
        val matchesQuery = query.isBlank() || photo.title.contains(query) ||
            photo.description.contains(query) || photo.dateText.contains(query) ||
            photo.labels.any { it.contains(query) } // 라벨·인물 이름으로도 찾아지게
        val matchesFolder = selectedFolder == null || selectedFolder == GALLERY_ALL_FOLDER ||
            selectedFolder in photo.labels
        matchesQuery && matchesFolder
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GalleryBackground)
            .statusBarsPadding()
            .navigationBarsPadding() // 최하단 복귀 버튼이 시스템 내비 바에 가리지 않게
            .padding(horizontal = 20.dp),
    ) {
        // 상단 대형 헤더 (#84 시각 채널) — 위성 화면 공통 크롬
        SatelliteHeader(
            title = "갤러리",
            onBack = onBack,
            backDescription = "갤러리 닫고 홈으로 돌아가기",
            modifier = Modifier.padding(top = 4.dp),
        )

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

        // 선택된 폴더 표시 + 폴더 화면으로 돌아가는 칩 (한 번 탭 = 일반 터치 UI 몫)
        if (selectedFolder != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                Text(
                    text = "‹ 분류",
                    color = GalleryAccent,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(GalleryCard, RoundedCornerShape(20.dp))
                        .grammarClickable { onFolderSelect(null) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                        .semantics { contentDescription = "분류 화면으로 돌아가기" },
                )
                Text(
                    text = "  $selectedFolder · ${filtered?.size ?: 0}장",
                    color = GalleryTextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 내용은 나머지 높이만 차지 — 최하단 전폭 복귀 버튼이 항상 보이게 (#84 배치 문법 통일)
        Box(Modifier.weight(1f)) {
            when {
                photos == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GalleryAccent)
                }

                photos.isEmpty() -> Text(
                    text = "아직 찍은 사진이 없어요. 촬영하면 여기에 모여요.",
                    color = GalleryTextSecondary,
                    modifier = Modifier.padding(top = 24.dp),
                )

                !listMode -> FolderGrid(photos, onFolderSelect)

                filtered.isNullOrEmpty() -> Text(
                    text = if (query.isBlank()) "이 조건에 맞는 사진을 못 찾았어요."
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

        HomeReturnButton(
            onClick = onBack,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

/**
 * 사진 뷰어 (2026-08-23) — 잔존시력 사용자를 위해 찍은 사진을 크게 보여주고,
 * LLM 상세 설명을 화면·음성으로 함께 제공한다. 결과 화면(S4)과 같은 역할의 사후 버전.
 * 진입 시 낭독은 호출부(MainActivity.openPhotoViewer)가 담당한다.
 * 탭 문법: 두 번·세 번 탭 = 설명 다시 듣기, 길게 누르기 = 목록으로 (호출부 배선).
 *
 * @param fullBitmap 원본 해상도 디코딩 결과 — 끝나기 전엔 null 이고 썸네일이 자리를 지킨다
 */
@Composable
fun PhotoViewerScreen(
    photo: GalleryPhoto,
    fullBitmap: android.graphics.Bitmap?,
    description: String?,
    onReplayDescription: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GalleryBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        SatelliteHeader(
            title = "사진 보기",
            onBack = onClose,
            backDescription = "사진 닫고 목록으로 돌아가기",
            modifier = Modifier.padding(top = 4.dp),
        )

        // 사진 크게 — 남는 높이를 전부 쓴다. 원본 디코딩 전에는 썸네일로 즉시 표시.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
        ) {
            val bitmap = fullBitmap ?: photo.thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = description ?: photo.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(color = GalleryAccent)
            }
        }

        Text(
            text = photo.title,
            color = GalleryTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        if (photo.labels.isNotEmpty()) {
            Text(
                text = photo.labels.joinToString("  ") { "#$it" },
                color = GalleryAccent,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = description ?: "설명을 준비 중이에요",
            color = GalleryTextSecondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier
                .padding(top = 6.dp)
                .heightIn(max = 120.dp)
                .verticalScroll(rememberScrollState()),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VoiceActionButton(
                emoji = "🔊",
                label = "설명 듣기",
                description = "설명 다시 듣기",
                onClick = onReplayDescription,
                modifier = Modifier.weight(1f),
            )
            HomeReturnButton(
                onClick = onClose,
                label = "목록으로",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 라벨 폴더 한 칸 — 이름, 장수, 대표(최신) 썸네일. */
private data class LabelFolder(
    val name: String,
    val count: Int,
    val thumbnail: android.graphics.Bitmap?,
)

/**
 * 사진 목록에서 라벨 폴더를 만든다. "전체"가 항상 맨 앞, 나머지는 장수 많은 순.
 * 목록이 최신순이므로 각 폴더의 첫 사진 썸네일 = 그 라벨의 최신 사진.
 */
private fun buildFolders(photos: List<GalleryPhoto>): List<LabelFolder> {
    val byLabel = LinkedHashMap<String, MutableList<GalleryPhoto>>()
    photos.forEach { photo ->
        photo.labels.forEach { label -> byLabel.getOrPut(label) { mutableListOf() }.add(photo) }
    }
    return buildList {
        add(LabelFolder(GALLERY_ALL_FOLDER, photos.size, photos.firstOrNull()?.thumbnail))
        byLabel.entries
            .sortedByDescending { it.value.size }
            .forEach { (name, group) -> add(LabelFolder(name, group.size, group.first().thumbnail)) }
    }
}

/** 갤럭시 갤러리처럼 라벨별 큰 정사각 폴더 2열 그리드 — 사진 찾기의 기본 화면. */
@Composable
private fun FolderGrid(photos: List<GalleryPhoto>, onFolderSelect: (String?) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(buildFolders(photos), key = { it.name }) { folder ->
            FolderCard(folder) { onFolderSelect(folder.name) }
        }
    }
}

@Composable
private fun FolderCard(folder: LabelFolder, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(GalleryCard)
            .grammarClickable(onClick)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${folder.name} 사진 ${folder.count}장. 누르면 이 분류의 사진 목록을 보여드려요"
            },
    ) {
        if (folder.thumbnail != null) {
            Image(
                bitmap = folder.thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 하단 스크림 — 잔존시력 사용자를 위한 큰 라벨명 + 장수
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = folder.name,
                color = GalleryTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = "${folder.count}장", color = GalleryTextSecondary, fontSize = 13.sp)
        }
    }
}

// 대분류 키워드 판정 — 예전 라벨 캐시(category 필드)가 검색 인덱스로 대체되면서(기능 3)
// 칩 필터는 제목·설명 텍스트의 키워드 규칙으로 판정한다 (cb27405의 분류 규칙과 동일).
private val PERSON_KEYWORDS = listOf(
    "사람", "아들", "딸", "아이", "아기", "가족", "친구", "남성", "여성",
    "남자", "여자", "인물", "얼굴", "커플", "부모", "엄마", "아빠",
)
private val FOOD_KEYWORDS = listOf(
    "음식", "커피", "라떼", "아메리카노", "녹차", "홍차", "찻잔", "음료", "주스",
    "케이크", "빵", "디저트", "밥", "식사", "요리", "접시", "식탁", "메뉴",
    "과일", "파스타", "피자", "치킨", "샐러드", "맥주", "와인",
)

private fun categoryOf(photo: GalleryPhoto): String {
    val text = "${photo.title} ${photo.description}"
    return when {
        PERSON_KEYWORDS.any { text.contains(it) } -> "인물"
        FOOD_KEYWORDS.any { text.contains(it) } -> "음식"
        else -> "추억"
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
            .grammarClickable(onClick)
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
        modifier = Modifier
            .fillMaxWidth()
            .grammarClickable(onClick)
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
                Text(
                    text = categoryOf(photo),
                    color = GalleryAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
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
