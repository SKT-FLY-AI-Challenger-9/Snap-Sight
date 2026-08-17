package com.example.snap_sight.ux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
