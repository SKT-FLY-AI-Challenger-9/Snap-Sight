package com.example.snap_sight.ux

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.snap_sight.cv.DeviationListener
import com.example.snap_sight.cv.DeviationResult
import java.util.Locale

/**
 * [DeviationListener] 구현체 — 판정 결과(⑥ [GuidanceState])를 사운드·햅틱·TTS로 렌더링한다.
 *
 * 정책 (docs/ux/feedback-mapping.md, 2026-08-19 개정):
 *  - 연속 피드백(LEFT/RIGHT/CLOSER/FARTHER): 사운드·햅틱(비언어), 판정마다 갱신
 *  - 주요 상태 변화(READY/LOST **진입 시점**): TTS 1회만, 반복 재생하지 않음 — [MajorStateTracker] 참고
 *
 * **알려진 제약(landscape) — 인터페이스 blocker, correctness 문제**:
 * [DeviationListener.onDeviation]은 [DeviationResult]만 받고 `subjectType`을 받지 않는다.
 * `SpecDeviationCalculator`는 landscape 세션에서도 `subjectDetected=false`를 반환하므로,
 * 이 클래스는 **landscape(피사체 의도 없음)와 실제 LOST(놓침)를 구분하지 못한다** — 지금은
 * landscape 세션에서도 "피사체를 찾지 못했습니다" TTS가 잘못 나갈 수 있다.
 * 단순 기능 누락이 아니라 잘못된 안내가 나가는 correctness 문제이므로 방치하면 안 된다.
 * 해결하려면 `DeviationListener`에 subjectType을 함께 전달하도록(예: `GuidanceInput(deviation, subjectType)`
 * 래핑) 인터페이스 변경이 필요하다 — ⑤ 담당자와 협의 후 후속 적용 (이슈 #45 참고, 범위 제외 항목).
 *
 * 스레딩: [onDeviation]은 CV 분석 스레드에서 호출된다. `TextToSpeech`/`Vibrator`는 자체적으로
 * 스레드 안전하게 큐잉되므로 별도 스레드 전환 없이 직접 호출한다.
 *
 * 책임 범위: 이 클래스는 [DeviationListener]만 구현한다. 셔터음·촬영 실패 안내는
 * `CaptureEventListener`의 몫이며 의도적으로 여기 섞지 않는다.
 */
class GuidanceFeedback(context: Context) : DeviationListener {

    private val appContext = context.applicationContext
    private val majorStateTracker = MajorStateTracker()

    @Volatile
    private var ttsReady = false

    // S5 설정에서 조절하는 값들 — applySettings() 전까지는 기본값(최대 강도·기본 속도)으로 동작한다.
    @Volatile
    private var vibrationIntensity = 1f
    @Volatile
    private var pendingSpeechRate = 1f

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(pendingSpeechRate)
        } else {
            Log.w(TAG, "TTS 초기화 실패 — 상태 변화 음성 안내가 비활성화됩니다")
        }
    }

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /**
     * S5 설정값을 실제 렌더링에 반영한다 (이슈 #54).
     *
     * [SettingsUiState.vibrationIntensity]는 진동 진폭(1..255)으로, [SettingsUiState.speechRate]는
     * [TextToSpeech.setSpeechRate]로 각각 매핑한다.
     *
     * **[SettingsUiState.soundVolume]은 아직 적용 대상이 없다** — 이 클래스는 연속 피드백을
     * 진동으로만 렌더링하고, 비언어 사운드(톤/비프) 채널 자체가 구현돼 있지 않다(KDoc 상단의
     * "사운드·햅틱"은 설계 의도이지 현재 구현 상태가 아니다). 사운드 채널을 추가하기 전까지는
     * 이 값을 받아만 두고 아무 효과도 없다 — 후속 이슈에서 사운드 채널 구현과 함께 연결한다.
     */
    fun applySettings(settings: SettingsUiState) {
        vibrationIntensity = GuidanceFeedbackSettingsMapper.clampVibrationIntensity(settings.vibrationIntensity)
        pendingSpeechRate = GuidanceFeedbackSettingsMapper.speechRate(settings)
        if (ttsReady) tts.setSpeechRate(pendingSpeechRate)
    }

    override fun onDeviation(result: DeviationResult) {
        val state = GuidanceStateMapper.from(result)
        renderMajorStateIfChanged(state)
        renderContinuousFeedback(state)
    }

    /** READY/LOST는 상태에 "새로 진입"할 때만 1회 안내한다 — 매 프레임 반복 금지. */
    private fun renderMajorStateIfChanged(state: GuidanceState) {
        val newlyEntered = majorStateTracker.onNewState(state) ?: return
        when (newlyEntered) {
            MajorState.READY -> speak("지금 촬영하셔도 됩니다")
            MajorState.LOST -> speak("피사체를 찾지 못했습니다")
        }
    }

    /**
     * LEFT/RIGHT/CLOSER/FARTHER — 사운드·햅틱으로 판정마다 갱신, TTS는 사용하지 않는다.
     *
     * 방향별 패턴 구분(예: 왼쪽=1회, 오른쪽=2회 진동)은 아직 선행 근거·실측 데이터가 없어
     * [추정]으로도 넣지 않았다 — 지금은 "벗어남/맞음"만 구분되는 단일 패턴이다.
     * 세부 패턴은 이슈 #45 "범위 제외"(진동 강도·패턴 튜닝) 대상.
     */
    private fun renderContinuousFeedback(state: GuidanceState) {
        if (!state.detected) return
        if (state.horizontal == HorizontalAlignment.CENTERED && state.distance == DistanceAlignment.CENTERED) return
        vibrateShort()
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /**
     * 임의 안내 문구를 내장 TTS로 재생한다 — 백엔드 TTS(ElevenLabs, TTS-1)가
     * 실패했을 때의 폴백 채널 (예: 개발 환경에 API 키가 없을 때).
     * 설정된 음성 속도([applySettings])를 그대로 따른다.
     */
    fun announce(text: String) = speak(text)

    private fun vibrateShort() {
        // null = 진동 강도 0(무음 설정) — 아예 울리지 않는다
        val amplitude = GuidanceFeedbackSettingsMapper.vibrationAmplitude(vibrationIntensity) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(SHORT_VIBRATION_MS, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(SHORT_VIBRATION_MS)
        }
    }

    /** Activity onDestroy 등에서 호출 — TTS 리소스 해제. */
    fun release() {
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "GuidanceFeedback"
        const val SHORT_VIBRATION_MS = 80L
    }
}

internal enum class MajorState { READY, LOST }

/**
 * READY/LOST 같은 주요 상태가 "새로 진입"했는지 판정한다 — 매 프레임 반복 안내를 막기 위한
 * 순수 상태 추적 로직. Android 의존성이 없어 단위 테스트가 쉽다.
 */
internal class MajorStateTracker {
    private var last: MajorState? = null

    /** @return 새로 진입한 주요 상태. 직전과 같으면(반복이면) null. */
    fun onNewState(state: GuidanceState): MajorState? {
        val current = majorStateOf(state)
        if (current == last) return null
        last = current
        return current
    }

    companion object {
        fun majorStateOf(state: GuidanceState): MajorState? = when {
            !state.detected -> MajorState.LOST
            state.isReady -> MajorState.READY
            else -> null
        }
    }
}

/**
 * [GuidanceFeedback.applySettings]가 S5 설정값을 실제 재생값으로 바꾸는 계산 부분만 뽑아낸 것.
 * `TextToSpeech`/`Vibrator` 호출 자체는 [GuidanceFeedback]이 담당하고, 이 객체는 "무엇을 보낼지"만
 * 계산한다 — Android 의존성이 없어 단위 테스트가 쉽다.
 */
internal object GuidanceFeedbackSettingsMapper {

    /** [SettingsUiState.vibrationIntensity]를 0..1로 clamp한다. */
    fun clampVibrationIntensity(intensity: Float): Float = intensity.coerceIn(0f, 1f)

    /**
     * 진동 진폭(1..255). 강도가 0 이하로 clamp되면 null — 이 경우 [GuidanceFeedback]은
     * 아예 진동을 울리지 않는다(무음 설정).
     */
    fun vibrationAmplitude(intensity: Float): Int? {
        val clamped = clampVibrationIntensity(intensity)
        if (clamped <= 0f) return null
        return (clamped * 255).toInt().coerceIn(1, 255)
    }

    /**
     * [TextToSpeech.setSpeechRate]에 그대로 전달할 값. 별도 변환·clamp는 하지 않는다 —
     * 유효 범위는 `SettingsScreen`의 슬라이더(`SPEECH_RATE_RANGE`)가 이미 보장한다.
     */
    fun speechRate(settings: SettingsUiState): Float = settings.speechRate
}
