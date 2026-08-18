package com.example.snap_sight.ux

import android.content.Context
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
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
 * [DeviationListener] 구현체 — 판정 결과(⑥ [GuidanceState])를 음성·진동·톤으로 렌더링한다.
 *
 * "언제 무엇을"은 [GuidancePolicy](순수 로직, 단위 테스트)가 정하고, 이 클래스는 Android 채널
 * (`TextToSpeech` / `Vibrator` / `ToneGenerator` / `MediaActionSound`)로 **재생만** 한다.
 * 정책 요약은 [GuidancePolicy] KDoc 과 docs/ux/feedback-mapping.md 참고.
 *
 * 세션 이벤트 안내([announceSessionStart] 등)는 즉시성이 중요해 백엔드 TTS 를 거치지 않고
 * 내장 TTS 로 바로 말한다.
 *
 * **알려진 제약(landscape) — 인터페이스 blocker, correctness 문제**:
 * [DeviationListener.onDeviation]은 [DeviationResult]만 받고 `subjectType`을 받지 않는다.
 * `SpecDeviationCalculator`는 landscape 세션에서도 `subjectDetected=false`를 반환하므로,
 * 이 클래스는 **landscape(피사체 의도 없음)와 실제 LOST(놓침)를 구분하지 못한다** — LOST 디바운스·
 * 경고음 전환으로 빈도는 크게 줄었지만 landscape 세션에서 경고음/안내가 잘못 나갈 수 있다.
 * 해결하려면 `DeviationListener`에 subjectType을 함께 전달하도록 인터페이스 변경이 필요하다
 * (이슈 #45 참고, 범위 제외 항목).
 *
 * 스레딩: [onDeviation]은 CV 분석 스레드에서 호출된다. `TextToSpeech`/`Vibrator`/`ToneGenerator`는
 * 자체적으로 스레드 안전하게 큐잉되므로 별도 스레드 전환 없이 직접 호출한다.
 */
class GuidanceFeedback(context: Context) : DeviationListener {

    private val appContext = context.applicationContext
    private val policy = GuidancePolicy()

    /**
     * true 를 돌려주면 "너무 작음(CLOSER)"은 자동 줌인이 처리 중인 것으로 보고 "가까이"를 말하지 않는다.
     * MainActivity 가 `AutoZoomController.canZoomIn` 으로 연결한다. 분석 스레드에서 호출된다.
     */
    @Volatile
    var zoomHandlesDistance: (() -> Boolean)? = null

    @Volatile
    private var ttsReady = false

    // S5 설정에서 조절하는 값들 — applySettings() 전까지는 기본값(최대 강도·기본 속도)으로 동작한다.
    @Volatile
    private var vibrationIntensity = 1f
    @Volatile
    private var soundVolume = 1f
    @Volatile
    private var pendingSpeechRate = 1f

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(pendingSpeechRate)
        } else {
            Log.w(TAG, "TTS 초기화 실패 — 음성 안내가 비활성화됩니다")
        }
    }

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /** LOST 경고음. 볼륨은 [applySettings] 의 soundVolume 을 따르며 0 이면 만들지 않는다. */
    @Volatile
    private var toneGenerator: ToneGenerator? = null

    private val shutterSound: MediaActionSound by lazy {
        MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }
    }

    /**
     * S5 설정값을 실제 렌더링에 반영한다 (이슈 #54).
     * - vibrationIntensity → 진동 진폭(1..255)
     * - soundVolume → LOST 경고음(ToneGenerator) 볼륨(0..100). 0 이면 경고음 없음
     * - speechRate → [TextToSpeech.setSpeechRate]
     */
    fun applySettings(settings: SettingsUiState) {
        vibrationIntensity = GuidanceFeedbackSettingsMapper.clampVibrationIntensity(settings.vibrationIntensity)
        soundVolume = settings.soundVolume.coerceIn(0f, 1f)
        pendingSpeechRate = GuidanceFeedbackSettingsMapper.speechRate(settings)
        if (ttsReady) tts.setSpeechRate(pendingSpeechRate)
        rebuildToneGenerator()
    }

    /** 새 촬영 세션(AIMING 진입) — 이전 세션의 "이미 말했음" 상태를 지운다. */
    fun resetSession() = policy.reset()

    override fun onDeviation(result: DeviationResult) {
        val state = GuidanceStateMapper.from(result)
        val zoomHandles = zoomHandlesDistance?.invoke() == true
        val actions = policy.onJudgment(state, result, System.currentTimeMillis(), zoomHandlesDistance = zoomHandles)
        for (action in actions) {
            when (action) {
                is GuidanceAction.Speak -> speak(action.text)
                GuidanceAction.Vibrate -> vibrateShort()
                GuidanceAction.WarningTone -> playWarningTone()
            }
        }
    }

    // ---- 세션 이벤트 안내 (MainActivity 가 호출) ----

    /** 셔터 순간: 셔터음 + 짧은 진동. */
    fun playShutter() {
        runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
        vibrateShort()
    }

    /**
     * 임의 안내 문구를 내장 TTS로 즉시 재생한다 (세션 시작/완료/실패 안내, 백엔드 TTS 폴백).
     * 설정된 음성 속도([applySettings])를 그대로 따른다.
     */
    fun announce(text: String) = speak(text)

    private fun speak(text: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

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

    private fun playWarningTone() {
        val generator = toneGenerator ?: rebuildToneGenerator() ?: return
        runCatching { generator.startTone(WARNING_TONE, WARNING_TONE_MS) }
    }

    @Synchronized
    private fun rebuildToneGenerator(): ToneGenerator? {
        toneGenerator?.release()
        toneGenerator = null
        val volume = GuidanceFeedbackSettingsMapper.toneVolume(soundVolume) ?: return null
        return runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, volume) }
            .onFailure { Log.w(TAG, "ToneGenerator 생성 실패 — 경고음 없이 진행", it) }
            .getOrNull()
            .also { toneGenerator = it }
    }

    /** Activity onDestroy 등에서 호출 — TTS/톤 리소스 해제. */
    fun release() {
        tts.stop()
        tts.shutdown()
        toneGenerator?.release()
        toneGenerator = null
        runCatching { shutterSound.release() }
    }

    private companion object {
        const val TAG = "GuidanceFeedback"
        const val SHORT_VIBRATION_MS = 80L
        /** 짧은 2연타 비프 — 음성보다 덜 거슬리는 "놓침" 신호. */
        const val WARNING_TONE = ToneGenerator.TONE_PROP_BEEP2
        const val WARNING_TONE_MS = 250
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

    /** ToneGenerator 볼륨(1..100). soundVolume 0 이면 null(경고음 없음). */
    fun toneVolume(soundVolume: Float): Int? {
        val clamped = soundVolume.coerceIn(0f, 1f)
        if (clamped <= 0f) return null
        return (clamped * 100).toInt().coerceIn(1, 100)
    }

    /**
     * [TextToSpeech.setSpeechRate]에 그대로 전달할 값. 별도 변환·clamp는 하지 않는다 —
     * 유효 범위는 `SettingsScreen`의 슬라이더(`SPEECH_RATE_RANGE`)가 이미 보장한다.
     */
    fun speechRate(settings: SettingsUiState): Float = settings.speechRate
}
