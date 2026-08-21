package com.example.snap_sight.ux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * S5 설정 화면 (`docs/screen-design.md` 4절, `docs/ux/design-principles.md` 원칙 6).
 *
 * TalkBack 낭독 순서 = Column 배치 순서(항목 순서대로). 각 항목은 라벨 Text와 Slider 양쪽에
 * 현재값을 문구로 넣어 "현재값 포함 낭독" 요구사항을 만족한다.
 *
 * **범위 밖 (이번 작업에 포함 안 됨)**:
 *  - 값 영속화(SharedPreferences 등) — 지금은 [SettingsUiState]를 그대로 받아 보여주기만 한다
 *  - 조절값을 실제 [GuidanceFeedback]에 반영하는 것 — 진동/사운드 강도·TTS 속도를
 *    GuidanceFeedback이 읽어들이는 구조가 아직 없음 (이슈 #45 범위 밖, 후속 이슈 필요)
 *  - "슬라이더도 볼륨 버튼으로 조절 가능"(`docs/screen-design.md` 4절) — 현재 MainActivity의
 *    볼륨 버튼은 전역적으로 세션 상태 전환에만 쓰인다(`onKeyDown`/`onKeyUp`). 설정 화면이 떠 있는
 *    동안 그 입력을 가로채 포커스된 슬라이더로 대신 전달하는 메커니즘이 없어 TODO로 남긴다
 *
 * **MainActivity 연결 시 필요한 것 (TODO)**:
 *  - S5로 진입/이탈시키는 화면 전환 상태 ([OnboardingScreen] 문서의 TODO와 동일한 방식으로 관리)
 *  - [onBack] → S2(홈)로 복귀
 */
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text(text = "설정", style = MaterialTheme.typography.headlineSmall)

        SettingSlider(
            label = "진동 강도",
            value = state.vibrationIntensity,
            onValueChange = onVibrationIntensityChange,
        )
        SettingSlider(
            label = "사운드 강도",
            value = state.soundVolume,
            onValueChange = onSoundVolumeChange,
        )
        SettingSlider(
            label = "음성 속도",
            value = state.speechRate,
            onValueChange = onSpeechRateChange,
            valueRange = SPEECH_RATE_RANGE,
            formatValue = { "${(it * 100).roundToInt()}퍼센트 속도" },
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = "백엔드 서버 주소", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                singleLine = true,
                placeholder = { Text("예: 192.168.10.104:8000 (비우면 빌드 기본값)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "백엔드 서버 주소 입력. 비우면 빌드 기본값을 사용합니다" },
            )
            Text(
                text = "다른 Wi-Fi로 옮기면 PC의 새 IP를 입력하세요. 돌아가기를 누르면 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (onEnrollFace != null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "가족·지인 등록", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "등록하면 \"우리 아들 찍어줘\"처럼 이름으로 찾을 수 있어요. " +
                        "얼굴 정보는 이 기기에만 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = onEnrollFace,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "새 얼굴 등록 시작. 카메라 화면으로 이동해 이름을 말하고 얼굴을 3초간 비춥니다"
                        },
                ) {
                    Text("＋ 새 얼굴 등록")
                }
                registeredPeople.forEach { name ->
                    TextButton(
                        onClick = { onDeletePerson(name) },
                        modifier = Modifier.semantics {
                            contentDescription = "등록된 $name 삭제. 얼굴 정보가 기기에서 완전히 지워집니다"
                        },
                    ) {
                        Text("$name 삭제")
                    }
                }
            }
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "설정 닫고 이전 화면으로 돌아가기" },
        ) {
            Text("돌아가기")
        }
    }
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

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    formatValue: (Float) -> String = { "${(it * 100).roundToInt()}퍼센트" },
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "$label, 현재 ${formatValue(value)}", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "$label 조절, 현재 ${formatValue(value)}" },
        )
    }
}
