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
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onVibrationIntensityChange: (Float) -> Unit,
    onSoundVolumeChange: (Float) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    /** 안내 목소리 프리셋 선택. 포커스가 가면 호출부가 해당 목소리로 미리듣기를 재생한다. */
    onVoicePresetChange: (String) -> Unit = {},
    /** 촬영 화면 구도선 단계 변경. */
    onGridModeChange: (GridMode) -> Unit = {},
    serverAiDescriptionEnabled: Boolean = true,
    onServerAiDescriptionEnabledChange: (Boolean) -> Unit = {},
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
        // 상단 바: ‹ (뒤로) + 설정 — 뒤로가기가 서버 주소 적용 시점이기도 하다
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "설정 닫고 홈으로 돌아가기" },
            ) {
                Text(text = "‹", color = SnapPalette.Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "설정",
                color = SnapPalette.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
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
            )

            SectionLabel("음성 안내", topPadding = 20.dp)

            ChoiceCard(
                label = "안내 목소리",
                description = "고르면 그 목소리로 안내합니다",
                options = VOICE_PRESETS.map { it.second },
                selectedIndex = VOICE_PRESETS.indexOfFirst { it.first == state.voicePreset }
                    .coerceAtLeast(0),
                onSelect = { onVoicePresetChange(VOICE_PRESETS[it].first) },
            )

            Spacer(Modifier.height(12.dp))
            ChoiceCard(
                label = "말하기 속도",
                description = "안내 음성이 말하는 빠르기",
                options = SpeechSpeed.entries.map { it.label },
                selectedIndex = SpeechSpeed.entries.indexOf(state.speechSpeed),
                onSelect = { onSpeechRateChange(SpeechSpeed.entries[it].rate) },
            )

            SectionLabel("화면", topPadding = 20.dp)

            ChoiceCard(
                label = "격자",
                description = "촬영 화면의 3×3 구도선",
                options = GridMode.entries.map { it.label },
                selectedIndex = GridMode.entries.indexOf(state.gridMode),
                onSelect = { onGridModeChange(GridMode.entries[it]) },
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
    }
}

/**
 * 3지선다 설정 항목 — 안내 목소리·말하기 속도·격자처럼 단계가 정해진 값에 쓴다.
 *
 * TalkBack 낭독은 "{항목명}, 현재 {값}, {n}개 중 {i}번째" 형식을 따른다
 * ("최종 기획 정리" 동작 규칙 4). 터치 영역은 최소 48dp 를 지킨다.
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
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(text = label, color = SnapPalette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(text = description, color = SnapPalette.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    val selected = index == selectedIndex
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) SnapPalette.Accent else SnapPalette.Card,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = if (index == options.lastIndex) 0.dp else 8.dp)
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
                                contentDescription =
                                    "$label, 현재 ${options[selectedIndex]}, ${options.size}개 중 ${index + 1}번째 $option"
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
 * 말하기 속도 3단계 — "최종 기획 정리" 음성 안내의 느림 / 보통 / 빠름.
 *
 * 값은 재생 배속이자 [android.speech.tts.TextToSpeech.setSpeechRate] 값이다(1f = 기본).
 * 프리캐싱 음원은 속도별로 굽지 않고 재생 시 배속을 적용하므로 두 경로가 같은 값을 쓴다.
 */
enum class SpeechSpeed(val label: String, val rate: Float) {
    SLOW("느림", 0.8f),
    NORMAL("보통", 1.0f),
    FAST("빠름", 1.5f),
    ;

    companion object {
        val DEFAULT = NORMAL

        /** 저장된 배속값에 가장 가까운 단계. 연속 슬라이더로 저장된 예전 값도 받아준다. */
        fun fromRate(rate: Float): SpeechSpeed =
            entries.minBy { kotlin.math.abs(it.rate - rate) }
    }
}

/**
 * 안내 목소리 프리셋 목록 — (id, 화면 라벨).
 *
 * id 는 `ai/voice/script.json` 의 presets 및 assets/voice/<id>/ 폴더명과 일치해야 한다.
 * 음원이 없는 프리셋을 고르면 그 프리셋은 시스템 TTS 로 안내된다(안내가 끊기지는 않는다).
 */
private val VOICE_PRESETS = listOf(
    "preset1" to "1번",
    "preset2" to "2번",
    "preset3" to "3번",
)

/** [SettingsScreen]이 그리는 값 — 영속화·기본값 결정은 호출부(MainActivity 연결 시) 책임. */
data class SettingsUiState(
    /** 0f(무음)..1f(최대) */
    val vibrationIntensity: Float,
    /** 0f(무음)..1f(최대) */
    val soundVolume: Float,
    /** [android.speech.tts.TextToSpeech.setSpeechRate]와 동일 단위(1f = 기본 속도). [SpeechSpeed] 참고 */
    val speechRate: Float,
    /** 설정한 Snap-Sight 서버의 설명 API에 사진 업로드를 허용했을 때 true. */
    val serverAiDescriptionEnabled: Boolean = true,
    /**
     * 안내 목소리 프리셋 id — 선택 시 해당 목소리의 프리캐싱 음원 세트로 전환한다.
     * 값은 `ai/voice/script.json`의 presets id 와 assets/voice/<id>/ 폴더명과 같아야 한다.
     */
    val voicePreset: String = com.example.snap_sight.voice.VoiceAssetIndex.DEFAULT_PRESET,
    /** 촬영 화면 3×3 구도선 표시 단계. */
    val gridMode: GridMode = GridMode.DEFAULT,
) {
    val speechSpeed: SpeechSpeed get() = SpeechSpeed.fromRate(speechRate)
}
