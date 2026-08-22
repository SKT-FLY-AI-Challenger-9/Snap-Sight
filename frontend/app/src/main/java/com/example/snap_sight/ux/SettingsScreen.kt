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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    onBack: () -> Unit,
    // 백엔드 서버 주소 재정의 (시연장 Wi-Fi 변경 대비) — 적용·저장 시점은 호출부(돌아가기) 책임
    serverUrl: String = "",
    onServerUrlChange: (String) -> Unit = {},
    // 기능 2: 가족·지인 얼굴 등록 흐름 진입 (홈 화면으로 전환해 카메라를 켜고 진행)
    registeredPeople: List<String> = emptyList(),
    onEnrollFace: (() -> Unit)? = null,
    onDeletePerson: (String) -> Unit = {},
) {
    // 시안의 "안내 방식" 접이식 노트 — 기본 접힘 (Progressive Disclosure)
    var helpExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SnapPalette.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
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

            SliderCard(
                label = "음성 속도",
                description = "주요 상태 음성 안내의 말하기 속도",
                valueText = formatSpeechRate(state.speechRate),
                value = state.speechRate,
                valueRange = SPEECH_RATE_RANGE,
                steps = 14,
                lowLabel = "느리게",
                highLabel = "빠르게",
                onValueChange = onSpeechRateChange,
            )

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

/** 1.0 → "1×", 1.2 → "1.2×" — 시안 표기와 동일하게 소수점 0은 감춘다. */
private fun formatSpeechRate(rate: Float): String {
    val rounded = (rate * 10).roundToInt() / 10f
    return if (rounded % 1f == 0f) "${rounded.toInt()}×" else "$rounded×"
}

/** [SettingsScreen]이 그리는 값 — 영속화·기본값 결정은 호출부(MainActivity 연결 시) 책임. */
data class SettingsUiState(
    /** 0f(무음)..1f(최대) */
    val vibrationIntensity: Float,
    /** 0f(무음)..1f(최대) */
    val soundVolume: Float,
    /** [SPEECH_RATE_RANGE] 범위 — [android.speech.tts.TextToSpeech.setSpeechRate]와 동일 단위(1f = 기본 속도) */
    val speechRate: Float,
)

private val SPEECH_RATE_RANGE = 0.5f..2f
