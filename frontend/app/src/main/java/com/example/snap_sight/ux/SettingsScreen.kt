// 이 파일: S5 설정 화면 — Figma Make 시안(v31, #80). 상단 ‹ 설정 바 + "안내 피드백"/"음성 안내"
// 섹션의 카드형 슬라이더(라벨·파란 현재값·설명·최소/최대 라벨) + 접이식 "안내 방식" 노트.
// 백엔드 서버 주소 입력(시연장 Wi-Fi 대비)은 앱 전용 기능이라 시안에 없지만 같은 카드 스타일로 둔다.
package com.example.snap_sight.ux

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onVibrationIntensityChange: (Float) -> Unit,
    onSoundVolumeChange: (Float) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    serverAiDescriptionEnabled: Boolean = true,
    onServerAiDescriptionEnabledChange: (Boolean) -> Unit = {},
    /** 안내 목소리 프리셋 변경 ([VoicePreset.key]) — 호출부가 적용·저장·미리듣기를 담당한다. */
    onVoicePresetChange: (String) -> Unit = {},
    // 촬영 그리드 (2026-08-23) — 잔존시력·조력자용 시각 보조
    onGridEnabledChange: (Boolean) -> Unit = {},
    onGridColorChange: (Int) -> Unit = {},
    onGridThicknessChange: (Float) -> Unit = {},
    /** 슬라이더·선택 조작이 끝날 때 바뀐 값을 음성으로 알린다 — 화면을 보지 않는 조작 지원. */
    onAnnounceValue: (String) -> Unit = {},
    onBack: () -> Unit,
    // 백엔드 서버 주소 재정의 (시연장 Wi-Fi 변경 대비) — 적용·저장 시점은 호출부(돌아가기) 책임
    serverUrl: String = "",
    onServerUrlChange: (String) -> Unit = {},
    // 기능 2: 가족·지인 얼굴 등록 흐름 진입 (홈 화면으로 전환해 카메라를 켜고 진행)
    registeredPeople: List<String> = emptyList(),
    onEnrollFace: (() -> Unit)? = null,
    onDeletePerson: (String) -> Unit = {},
    // 사물 등록 — 얼굴과 같은 흐름, 파이프라인만 분리 (ObjectIdentifier)
    registeredObjects: List<String> = emptyList(),
    onEnrollObject: (() -> Unit)? = null,
    onDeleteObject: (String) -> Unit = {},
) {
    // 시안의 "안내 방식" 접이식 노트 — 기본 접힘 (Progressive Disclosure)
    var helpExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SnapPalette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            // 키보드가 올라오면 그 높이만큼 줄여서, 맨 아래 서버 주소 입력란이 가려지지 않고
            // 스크롤로 키보드 위에 보이게 한다 (실사용 피드백 2026-08-22)
            .imePadding(),
    ) {
        // 상단 대형 헤더 (#84 시각 채널) — 뒤로가기가 서버 주소 적용 시점이기도 하다
        SatelliteHeader(
            title = "설정",
            onBack = onBack,
            backDescription = "설정 닫고 홈으로 돌아가기",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // 최하단 전폭 복귀 버튼이 항상 보이도록 스크롤 영역을 나머지 높이로 제한
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionLabel("안내 피드백")

            SliderCard(
                label = "진동 강도",
                description = "방향 안내 햅틱의 세기",
                valueText = "${(state.vibrationIntensity * 100).roundToInt()}%",
                value = state.vibrationIntensity,
                valueRange = 0f..1f,
                steps = 19,
                lowLabel = "약하게",
                highLabel = "강하게",
                onValueChange = onVibrationIntensityChange,
                onAnnounce = onAnnounceValue,
            )
            Spacer(Modifier.height(12.dp))
            SliderCard(
                label = "사운드 강도",
                description = "방향 안내 사운드의 음량",
                valueText = "${(state.soundVolume * 100).roundToInt()}%",
                value = state.soundVolume,
                valueRange = 0f..1f,
                steps = 19,
                lowLabel = "작게",
                highLabel = "크게",
                onValueChange = onSoundVolumeChange,
                onAnnounce = onAnnounceValue,
            )

            SectionLabel("음성 안내", topPadding = 20.dp)

            // 안내 목소리 프리셋 3종 (기획: 선택 시 실사용 문구로 즉시 미리듣기) —
            // 아리아·올리버는 SKT A.X TTS 프리캐싱 음원, 기본은 내장 TTS.
            ChoiceCard(
                label = "안내 목소리",
                description = "촬영 안내 목소리를 고르세요. 고르면 바로 들려드려요",
                options = VoicePreset.entries.map { it.label },
                selectedIndex = VoicePreset.entries.indexOf(VoicePreset.fromKey(state.voicePreset)),
                onSelect = { index -> onVoicePresetChange(VoicePreset.entries[index].key) },
            )

            Spacer(Modifier.height(12.dp))

            // 슬라이더 대신 3단계 버튼 — 화면을 보지 않고 조작할 때 "몇 배인지"보다 "느리게/보통/
            // 빠르게" 중 하나를 고르는 편이 훨씬 확실하다. 저장값은 계속 배속(Float)이라 예전에
            // 슬라이더로 저장한 값도 [SpeechSpeed.fromRate]가 가장 가까운 단계로 받아준다.
            ChoiceCard(
                label = "음성 속도",
                description = "주요 상태 음성 안내의 말하기 속도",
                options = SpeechSpeed.entries.map { it.label },
                selectedIndex = SpeechSpeed.entries.indexOf(SpeechSpeed.fromRate(state.speechRate)),
                onSelect = { index ->
                    val speed = SpeechSpeed.entries[index]
                    onSpeechRateChange(speed.rate)
                    onAnnounceValue("음성 속도 ${speed.label}")
                },
            )

            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SnapPalette.Card,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(20.dp)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "서버 AI 사진 설명",
                            color = SnapPalette.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "켜면 촬영 사진과 익명 탐지 정보를 설정한 Snap-Sight 서버로 보내고, " +
                                "서버가 연결한 설명 API에서 상세 설명과 검색 라벨을 받아옵니다. " +
                                "촬영 요청 문장은 등록 이름을 가린 뒤 전송하며, 등록 이름과 얼굴·사물 " +
                                "임베딩은 전송하지 않습니다.",
                            color = SnapPalette.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = serverAiDescriptionEnabled,
                        onCheckedChange = onServerAiDescriptionEnabledChange,
                        colors = SwitchDefaults.colors(checkedTrackColor = SnapPalette.Accent),
                        modifier = Modifier.semantics {
                            contentDescription = if (serverAiDescriptionEnabled) {
                                "서버 AI 사진 설명 켜짐. 누르면 끄기"
                            } else {
                                "서버 AI 사진 설명 꺼짐. 사진 전송에 동의하고 켜기"
                            }
                        },
                    )
                }
            }

            SectionLabel("촬영 화면", topPadding = 20.dp)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SnapPalette.Card,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(20.dp)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "3\u00d73 그리드",
                                color = SnapPalette.TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "촬영 화면에 구도 격자를 표시합니다. 미리보기에만 보이고 " +
                                    "찍힌 사진에는 남지 않아요. 음성으로도 켜고 끌 수 있어요 " +
                                    "(\"그리드 켜줘, 그리드 꺼줘\").",
                                color = SnapPalette.TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Switch(
                            checked = state.gridEnabled,
                            onCheckedChange = onGridEnabledChange,
                            colors = SwitchDefaults.colors(checkedTrackColor = SnapPalette.Accent),
                            modifier = Modifier.semantics {
                                contentDescription = if (state.gridEnabled) {
                                    "촬영 그리드 켜짐. 누르면 끄기"
                                } else {
                                    "촬영 그리드 꺼짐. 누르면 켜기"
                                }
                            },
                        )
                    }
                    AnimatedVisibility(visible = state.gridEnabled) {
                        Column {
                            Text(
                                text = "색상",
                                color = SnapPalette.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                            Row(modifier = Modifier.padding(top = 8.dp)) {
                                GRID_COLOR_CHOICES.forEach { (name, argb) ->
                                    val selected = state.gridColorArgb == argb
                                    Box(
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(36.dp)
                                            .background(
                                                Color(argb).copy(alpha = 1f),
                                                RoundedCornerShape(18.dp),
                                            )
                                            .border(
                                                width = if (selected) 3.dp else 1.dp,
                                                color = if (selected) SnapPalette.Accent
                                                else SnapPalette.CardBorder,
                                                shape = RoundedCornerShape(18.dp),
                                            )
                                            .clickable {
                                                onGridColorChange(argb)
                                                onAnnounceValue("그리드 색 $name")
                                            }
                                            .semantics {
                                                contentDescription = if (selected) {
                                                    "그리드 색 $name, 선택됨"
                                                } else {
                                                    "그리드 색을 $name(으)로 바꾸기"
                                                }
                                            },
                                    )
                                }
                            }
                            Text(
                                text = "굵기",
                                color = SnapPalette.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                            // 카드 안이라 [ChoiceCard]의 Surface 없이 버튼 줄만 쓴다 (카드 중첩 방지).
                            ChoiceRow(
                                label = "그리드 굵기",
                                options = GridThickness.entries.map { it.label },
                                selectedIndex = GridThickness.entries
                                    .indexOf(GridThickness.fromDp(state.gridThicknessDp)),
                                onSelect = { index ->
                                    val thickness = GridThickness.entries[index]
                                    onGridThicknessChange(thickness.dp)
                                    onAnnounceValue("그리드 굵기 ${thickness.label}")
                                },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // 접이식 "안내 방식" 설명 — 시안의 파란 노트 카드
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, SnapPalette.CardBorder, RoundedCornerShape(14.dp))
                    .semantics {
                        contentDescription =
                            if (helpExpanded) "안내 방식 설명 숨기기" else "안내 방식 설명 보기"
                    },
                onClick = { helpExpanded = !helpExpanded },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                ) {
                    Text(
                        text = "안내 방식",
                        color = SnapPalette.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (helpExpanded) "▲" else "▼",
                        fontSize = 11.sp,
                        color = SnapPalette.TextSecondary,
                    )
                }
            }
            AnimatedVisibility(visible = helpExpanded) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0A2040),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .border(1.dp, Color(0x400A84FF), RoundedCornerShape(16.dp)),
                ) {
                    Text(
                        text = "촬영 중 연속적인 방향·거리 안내는 음성 대신 사운드와 진동으로 전달됩니다. " +
                            "대상을 찾았을 때, 촬영 준비가 됐을 때, 촬영이 완료됐을 때 같은 " +
                            "주요 순간에만 짧은 음성이 사용됩니다.",
                        color = Color(0xD1FFFFFF),
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    )
                }
            }

            // 기능 2: 가족·지인 얼굴 등록 — 시안 카드 스타일로 통일 (얼굴 정보는 기기에만 저장)
            if (onEnrollFace != null) {
                SectionLabel("가족·지인 등록", topPadding = 20.dp)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SnapPalette.Card,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(20.dp)),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Text(
                            text = "등록하면 \"우리 아들 찍어줘\"처럼 이름으로 찾을 수 있어요. " +
                                "얼굴 정보는 이 기기에만 저장됩니다.",
                            color = SnapPalette.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .border(1.5.dp, SnapPalette.Accent, RoundedCornerShape(12.dp))
                                .semantics {
                                    contentDescription =
                                        "새 얼굴 등록 시작. 카메라 화면으로 이동해 이름을 말하고 얼굴을 3초간 비춥니다"
                                },
                            onClick = onEnrollFace,
                        ) {
                            Text(
                                text = "＋ 새 얼굴 등록",
                                color = SnapPalette.Accent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            )
                        }
                        registeredPeople.forEach { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                            ) {
                                Text(
                                    text = name,
                                    color = SnapPalette.TextPrimary,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "삭제",
                                    color = SnapPalette.WarningStrong,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { onDeletePerson(name) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                        .semantics {
                                            contentDescription =
                                                "등록된 $name 삭제. 얼굴 정보가 기기에서 완전히 지워집니다"
                                        },
                                )
                            }
                        }
                    }
                }
            }

            // 사물 등록 — 얼굴 카드와 같은 스타일 (임베딩은 기기에만 저장)
            if (onEnrollObject != null) {
                SectionLabel("내 사물 등록", topPadding = 20.dp)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SnapPalette.Card,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(20.dp)),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Text(
                            text = "자주 찍는 사물을 등록하면 \"내 텀블러 찍어줘\"처럼 부를 수 있어요. " +
                                "정보는 이 기기에만 저장됩니다.",
                            color = SnapPalette.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .border(1.5.dp, SnapPalette.Accent, RoundedCornerShape(12.dp))
                                .semantics {
                                    contentDescription =
                                        "새 사물 등록 시작. 카메라 화면으로 이동해 이름을 말하고 사물을 3초간 비춥니다"
                                },
                            onClick = onEnrollObject,
                        ) {
                            Text(
                                text = "＋ 새 사물 등록",
                                color = SnapPalette.Accent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            )
                        }
                        registeredObjects.forEach { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                            ) {
                                Text(
                                    text = name,
                                    color = SnapPalette.TextPrimary,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "삭제",
                                    color = SnapPalette.WarningStrong,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { onDeleteObject(name) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                        .semantics {
                                            contentDescription =
                                                "등록된 $name 삭제. 정보가 기기에서 완전히 지워집니다"
                                        },
                                )
                            }
                        }
                    }
                }
            }

            // 앱 전용: 백엔드 서버 주소 (시연장 Wi-Fi 이동 대비) — 시안에 없는 항목이라 맨 아래
            SectionLabel("백엔드 서버", topPadding = 20.dp)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SnapPalette.Card,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(20.dp)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Text(
                        text = "서버 주소",
                        color = SnapPalette.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        singleLine = true,
                        placeholder = {
                            Text("예: 192.168.10.104:8000", color = SnapPalette.TextSecondary)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SnapPalette.TextPrimary,
                            unfocusedTextColor = SnapPalette.TextPrimary,
                            focusedBorderColor = SnapPalette.Accent,
                            unfocusedBorderColor = SnapPalette.InputBorder,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .semantics {
                                contentDescription = "백엔드 서버 주소 입력. 비우면 빌드 기본값을 사용합니다"
                            },
                    )
                    Text(
                        text = "다른 Wi-Fi로 옮기면 새 주소를 입력하세요. 뒤로가기를 누르면 적용됩니다.",
                        color = SnapPalette.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }

        // 최하단 전폭 복귀 버튼 (#84 배치 문법 통일) — 제스처·뒤로가기와 같은 공통 복귀 경로
        HomeReturnButton(
            onClick = onBack,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String, topPadding: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(
        text = text,
        color = Color(0xFFB8B8BD),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = topPadding, bottom = 8.dp),
    )
}

/** 시안의 카드형 슬라이더 — 라벨 + 파란 현재값, 설명, 슬라이더, 최소/최대 라벨. */
@Composable
private fun SliderCard(
    label: String,
    description: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    lowLabel: String,
    highLabel: String,
    onValueChange: (Float) -> Unit,
    /** 조작이 끝나면 "라벨 현재값"을 알린다 — 화면을 보지 않고도 결과를 확인할 수 있게. */
    onAnnounce: ((String) -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SnapPalette.Card,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(20.dp)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = SnapPalette.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = valueText,
                    color = SnapPalette.Accent,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = description,
                color = Color(0xFFB8B8BD),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onAnnounce?.let { announce -> { announce("$label $valueText") } },
                valueRange = valueRange,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = SnapPalette.Accent,
                    inactiveTrackColor = Color(0xFF6E6E73),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .semantics { contentDescription = "$label. 현재 $valueText" },
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = lowLabel, color = Color(0xFFB8B8BD), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(text = highLabel, color = Color(0xFFB8B8BD), fontSize = 12.sp)
            }
        }
    }
}

/**
 * 3단계 선택 카드 — 라벨 + 설명 + 가로로 꽉 채운 버튼 줄.
 *
 * 슬라이더는 화면을 보며 미세 조정할 때나 쓸 만하다. 값이 실질적으로 몇 단계뿐인 설정은
 * 버튼으로 나누는 편이 낫다: 터치 표적이 크고, 각 버튼이 TalkBack 에서 "3개 중 2번째 보통"
 * 처럼 자기 위치를 스스로 말해 준다.
 */
@Composable
private fun ChoiceCard(
    label: String,
    description: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SnapPalette.Card,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SnapPalette.CardBorder, RoundedCornerShape(20.dp)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                text = label,
                color = SnapPalette.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                color = Color(0xFFB8B8BD),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            ChoiceRow(
                label = label,
                options = options,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * [ChoiceCard]의 버튼 줄만 떼어낸 것 — 이미 카드 안에 있는 설정(그리드 굵기)에서 쓴다.
 *
 * 선택 상태를 색으로만 알리지 않도록 글자 굵기도 함께 바꾸고, 각 버튼의 [Role.RadioButton]과
 * contentDescription 으로 "무엇의 몇 번째 선택지인지"를 낭독하게 한다.
 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (selected) SnapPalette.Accent else SnapPalette.Card,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (index == options.lastIndex) 0.dp else 8.dp)
                    // 48dp — 화면을 보지 않고 누르는 표적의 최소 크기.
                    .heightIn(min = 48.dp)
                    .border(
                        width = if (selected) 0.dp else 1.dp,
                        color = SnapPalette.CardBorder,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .semantics {
                        contentDescription = "$label, 현재 ${options[selectedIndex]}, " +
                            "${options.size}개 중 ${index + 1}번째 $option"
                    },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = option,
                        color = if (selected) SnapPalette.Background else SnapPalette.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
            }
        }
    }
}

/** [SettingsScreen]이 그리는 값 — 영속화·기본값 결정은 호출부(MainActivity 연결 시) 책임. */
data class SettingsUiState(
    /** 0f(무음)..1f(최대) */
    val vibrationIntensity: Float,
    /** 0f(무음)..1f(최대) */
    val soundVolume: Float,
    /**
     * [android.speech.tts.TextToSpeech.setSpeechRate]와 동일 단위(1f = 기본 속도).
     * 설정 화면은 [SpeechSpeed] 3단계로만 고르지만, 저장·전달은 계속 배속 값으로 한다 —
     * 예전에 슬라이더로 저장된 값도 그대로 읽히고 TTS 에도 바로 넘길 수 있다.
     */
    val speechRate: Float,
    /** 설정한 Snap-Sight 서버의 설명 API에 사진 업로드를 허용했을 때 true. */
    val serverAiDescriptionEnabled: Boolean = true,
    /** 촬영 미리보기 3×3 그리드 표시 — 잔존시력·조력자용 시각 보조 (2026-08-23). */
    val gridEnabled: Boolean = true,
    /** 그리드 선 색 (ARGB, 반투명 알파 포함). */
    val gridColorArgb: Int = DEFAULT_GRID_COLOR,
    /** 그리드 선 굵기 (dp). 설정 화면은 [GridThickness] 3단계로 고른다. */
    val gridThicknessDp: Float = GridThickness.DEFAULT.dp,
    /** 안내 목소리 프리셋 키 ([VoicePreset.key]) — system / aria / oliver. */
    val voicePreset: String = VoicePreset.DEFAULT.key,
)

/** 기본 그리드 색 — 흰색 70% (밝은·어두운 장면 모두에서 무난). */
const val DEFAULT_GRID_COLOR = 0xB3FFFFFF.toInt()

/** 그리드 색 선택지 — 이름은 TalkBack 낭독용. */
val GRID_COLOR_CHOICES: List<Pair<String, Int>> = listOf(
    "흰색" to DEFAULT_GRID_COLOR,
    "노랑" to 0xB3FFD60A.toInt(),
    "빨강" to 0xB3FF453A.toInt(),
    "파랑" to 0xB30A84FF.toInt(),
    "검정" to 0xB3000000.toInt(),
)

/**
 * 안내 목소리 프리셋 3종 — 기본(내장 TTS) + SKT A.X TTS 프리캐싱 음원 2종.
 *
 * [assetDir]가 null 이 아니면 [SpeechCatalog]의 확정 문장을 그 assets 디렉터리의 mp3 로
 * 재생한다 (내비게이션식 즉시 재생). 카탈로그 밖 동적 문장은 어느 프리셋이든 내장 TTS.
 */
enum class VoicePreset(val key: String, val label: String, val assetDir: String?) {
    SYSTEM("system", "기본", null),
    ARIA("aria", "아리아", "tts/aria"),
    OLIVER("oliver", "올리버", "tts/oliver"),
    ;

    companion object {
        val DEFAULT = SYSTEM

        fun fromKey(key: String?): VoicePreset = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * 음성 안내 말하기 속도 3단계.
 *
 * [rate]는 [android.speech.tts.TextToSpeech.setSpeechRate] 단위라 저장값을 그대로 TTS 에 넘긴다.
 */
enum class SpeechSpeed(val label: String, val rate: Float) {
    SLOW("느리게", 0.8f),
    NORMAL("보통", 1.0f),
    FAST("빠르게", 1.5f),
    ;

    companion object {
        val DEFAULT = NORMAL

        /** 저장된 배속에 가장 가까운 단계. 연속 슬라이더 시절에 저장된 값도 받아준다. */
        fun fromRate(rate: Float): SpeechSpeed = entries.minBy { abs(it.rate - rate) }
    }
}

/**
 * 3×3 그리드 선 굵기 3단계.
 *
 * [dp]는 그대로 [SettingsUiState.gridThicknessDp]에 저장되므로, 예전 슬라이더로 저장된
 * 임의의 값(1f~5f)도 [fromDp]가 가장 가까운 단계로 매핑해 준다.
 */
enum class GridThickness(val label: String, val dp: Float) {
    THIN("얇게", 1.5f),
    MEDIUM("보통", 3f),
    THICK("굵게", 5f),
    ;

    companion object {
        val DEFAULT = THIN

        fun fromDp(dp: Float): GridThickness = entries.minBy { abs(it.dp - dp) }
    }
}
