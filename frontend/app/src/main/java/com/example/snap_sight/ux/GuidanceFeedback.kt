package com.example.snap_sight.ux

import android.content.Context
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.snap_sight.cv.DeviationListener
import com.example.snap_sight.cv.DeviationResult
import com.example.snap_sight.cv.ReadinessVerdict
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
 * 풍경처럼 bbox 조준 대상이 없는 모드는 MainActivity에서 이 진입점을 호출하지 않고 별도 장면
 * 안내를 사용한다. 따라서 `subjectDetected=false`인 실제 타겟 세션만 LOST 정책으로 들어온다.
 *
 * 스레딩: [onDeviation]은 CV 분석 스레드에서 호출된다. `TextToSpeech`/`Vibrator`/`ToneGenerator`는
 * 자체적으로 스레드 안전하게 큐잉되므로 별도 스레드 전환 없이 직접 호출한다.
 */
class GuidanceFeedback(context: Context) : DeviationListener {

    enum class SpeechPriority(val level: Int) {
        AMBIENT(0), STATUS(1), ADJUSTMENT(2), READY(3), CAPTURE(4),
    }

    private val appContext = context.applicationContext
    private val policy = GuidancePolicy()

    /**
     * true 를 돌려주면 "너무 작음(CLOSER)"은 자동 줌인이 처리 중인 것으로 보고 "가까이"를 말하지 않는다.
     * MainActivity 가 `AutoZoomController.canZoomIn` 으로 연결한다. 분석 스레드에서 호출된다.
     */
    @Volatile
    var zoomHandlesDistance: (() -> Boolean)? = null

    /**
     * READY("지금 촬영하세요")를 보류할 사유를 돌려주는 훅 — null 이면 정상 READY.
     * 셀카 모드의 시선 판정(MainActivity → SelfieGazeMonitor)이 연결한다. 분석 스레드에서 호출된다.
     */
    @Volatile
    var readyGate: (() -> String?)? = null

    @Volatile
    private var ttsReady = false

    // S5 설정에서 조절하는 값들 — applySettings() 전까지는 기본값(최대 강도·기본 속도)으로 동작한다.
    @Volatile
    private var vibrationIntensity = 1f
    @Volatile
    private var soundVolume = 1f
    @Volatile
    private var pendingSpeechRate = 1f

    @Volatile
    private var ttsInitFailed = false

    // TTS 엔진 초기화(비동기, 보통 수백 ms)가 끝나기 전에 들어온 안내 — 예전엔 조용히 버려져서
    // 앱 첫 진입 환영 멘트가 안 나왔다 (2026-08-22). 마지막 1건만 보관했다가 초기화되면 말한다.
    private val initLock = Any()
    private data class PendingSpeech(
        val text: String,
        val onDone: (() -> Unit)?,
        val priority: SpeechPriority,
    )
    private var pendingWhileInit: PendingSpeech? = null

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        val ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(pendingSpeechRate)
            tts.setOnUtteranceProgressListener(utteranceListener)
        } else {
            Log.w(TAG, "TTS 초기화 실패 — 음성 안내가 비활성화됩니다")
            ttsInitFailed = true
        }
        ttsReady = ready
        val pending = synchronized(initLock) { pendingWhileInit.also { pendingWhileInit = null } }
        if (pending != null) {
            if (ready) announce(pending.text, pending.onDone, pending.priority)
            else pending.onDone?.let { done -> transitionHandler.post { done() } }
        }
    }

    // announce(text, onDone) 의 완료 콜백 — utteranceId 로 매칭한다. 끝나든(onDone)
    // 다른 발화에 밀려 끊기든(onStop) 오류가 나든 반드시 한 번 호출해 호출부가
    // 다음 단계로 진행할 수 있게 한다 (게이트가 영영 안 열리는 상황 방지).
    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val utterancePriorities = ConcurrentHashMap<String, SpeechPriority>()
    private val utteranceCounter = AtomicLong()
    @Volatile private var activeUtteranceId: String? = null
    @Volatile private var activeSpeechPriority: SpeechPriority? = null

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            activeUtteranceId = utteranceId
            activeSpeechPriority = utteranceId?.let(utterancePriorities::get)
        }
        override fun onDone(utteranceId: String?) = complete(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = complete(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId)

        private fun complete(utteranceId: String?) {
            if (utteranceId == null) return
            utterancePriorities.remove(utteranceId)
            if (activeUtteranceId == utteranceId) {
                activeUtteranceId = null
                activeSpeechPriority = null
            }
            val callback = utteranceCallbacks.remove(utteranceId) ?: return
            transitionHandler.postDelayed(callback, TTS_ECHO_GUARD_MS)
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
    fun resetSession() {
        policy.reset()
        // A target/session generation change invalidates queued movement and READY speech.
        // Keeping it alive would let guidance for the previous target continue after the
        // new intent has already become visible in the UI.
        synchronized(initLock) {
            val pending = pendingWhileInit
            if (pending?.priority == SpeechPriority.ADJUSTMENT ||
                pending?.priority == SpeechPriority.READY
            ) {
                pendingWhileInit = null
            }
        }
        val hasQueuedTargetGuidance = utterancePriorities.values.any {
            it == SpeechPriority.ADJUSTMENT || it == SpeechPriority.READY
        }
        if (hasQueuedTargetGuidance) runCatching { tts.stop() }
    }

    override fun onDeviation(result: DeviationResult) {
        processDeviation(result, System.currentTimeMillis())
    }

    /**
     * 음성·햅틱 액션을 렌더링하고, **그 액션을 만든 동일 evaluator 호출**의 readiness를 반환한다.
     * MainActivity는 별도 evaluator를 돌리지 말고 이 반환값으로 안내 UI/셔터 게이트를 갱신한다.
     */
    fun processDeviation(
        result: DeviationResult,
        nowMs: Long = System.currentTimeMillis(),
    ): ReadinessVerdict {
        val state = GuidanceStateMapper.from(result)
        val zoomHandles = zoomHandlesDistance?.invoke() == true
        val decision = policy.processJudgment(
            state, result, nowMs,
            zoomHandlesDistance = zoomHandles,
            readyBlockedReason = readyGate?.invoke(),
        )
        for (action in decision.actions) {
            when (action) {
                is GuidanceAction.Speak -> speak(
                    action.text,
                    if (action.text == GuidancePolicy.READY_UTTERANCE) SpeechPriority.READY
                    else SpeechPriority.ADJUSTMENT,
                )
                GuidanceAction.Vibrate -> vibrateShort()
                GuidanceAction.WarningTone -> playWarningTone()
            }
        }
        return decision.verdict
    }

    // ---- 세션 이벤트 안내 (MainActivity 가 호출) ----

    /** 셔터 순간: 셔터음 + 짧은 진동. */
    fun playShutter() {
        runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
        vibrateShort()
    }

    // ---- 화면 전환 earcon (#84 전환 확인 3채널) ----
    // 외울 소리는 2개뿐: 2음 상승 = 화면 진입, 2음 하강 = 홈 복귀. 어느 화면인지는 TTS가 말한다.

    private val transitionHandler = Handler(Looper.getMainLooper())

    /** 위성 화면(설정·사진 찾기 등) 진입 — 상승 2음 + 짧은 진동. */
    fun playScreenEnter() = playTransitionTone(rising = true)

    /** 홈 복귀 — 하강 2음 + 짧은 진동. */
    fun playScreenExit() = playTransitionTone(rising = false)

    // ---- 등록 스캔 구간 알림 (2026-08-22) — 스캔이 "지금 시작/지금 끝"임을 말 없이 알린다 ----

    /** 스캔 시작 — 단음 비프 + 진동. 이 소리 뒤부터 프레임이 수집된다. */
    fun playScanStart() {
        vibrateShort()
        val generator = toneGenerator ?: rebuildToneGenerator() ?: return
        runCatching { generator.startTone(SCAN_START_TONE, SCAN_TONE_MS) }
    }

    /** 스캔 중간 — 아주 짧은 틱 (진동 없음). 긴 스캔에서 "절반 지났다"는 진행감만 준다. */
    fun playScanTick() {
        val generator = toneGenerator ?: rebuildToneGenerator() ?: return
        runCatching { generator.startTone(SCAN_START_TONE, SCAN_TICK_MS) }
    }

    /** 스캔 종료 — 확인음(ACK) + 진동. 이 소리 뒤에 결과 안내가 이어진다. */
    fun playScanEnd() {
        vibrateShort()
        val generator = toneGenerator ?: rebuildToneGenerator() ?: return
        runCatching { generator.startTone(SCAN_END_TONE, SCAN_TONE_MS) }
    }

    private fun playTransitionTone(rising: Boolean) {
        vibrateShort()
        val generator = toneGenerator ?: rebuildToneGenerator() ?: return
        val (first, second) =
            if (rising) NAV_TONE_LOW to NAV_TONE_HIGH else NAV_TONE_HIGH to NAV_TONE_LOW
        runCatching { generator.startTone(first, NAV_TONE_MS) }
        transitionHandler.postDelayed(
            { runCatching { toneGenerator?.startTone(second, NAV_TONE_MS) } },
            NAV_TONE_GAP_MS,
        )
    }

    /**
     * 임의 안내 문구를 내장 TTS로 즉시 재생한다 (세션 시작/완료/실패 안내, 백엔드 TTS 폴백).
     * 설정된 음성 속도([applySettings])를 그대로 따른다.
     *
     * [onDone]을 주면 발화가 끝난 뒤(짧은 잔향 여유 포함) 메인 스레드에서 1회 호출된다.
     * 안내 음성이 마이크로 들어가 발화로 인식되는 것을 막기 위한 순서 조율용
     * (실사용 피드백 2026-08-22 — "요청에 '말해주세요'가 들어간다").
     * TTS를 못 쓰는 상태여도 onDone은 반드시 호출된다.
     */
    fun announce(
        text: String,
        onDone: (() -> Unit)? = null,
        priority: SpeechPriority = SpeechPriority.STATUS,
    ) {
        if (!ttsReady) {
            if (!ttsInitFailed) {
                // 초기화 중 — 보관했다가 준비되면 말한다 (나중 것이 먼저 것을 대체)
                synchronized(initLock) { pendingWhileInit = PendingSpeech(text, onDone, priority) }
            } else {
                onDone?.let { done -> transitionHandler.post { done() } }
            }
            return
        }
        val active = activeSpeechPriority
        if (active != null && active.level > priority.level && onDone == null) {
            // 신원·상태 알림이 진행 중인 이동/READY/촬영 안내를 자르거나 뒤늦게 재생되지 않게 버린다.
            return
        }
        val id = "announce_${utteranceCounter.incrementAndGet()}"
        if (onDone != null) utteranceCallbacks[id] = onDone
        utterancePriorities[id] = priority
        val queueMode = if (active == null || priority.level >= active.level) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }
        val result = tts.speak(text, queueMode, null, id)
        if (result == TextToSpeech.ERROR) {
            utterancePriorities.remove(id)
            utteranceCallbacks.remove(id)?.let { transitionHandler.post(it) }
        }
    }

    private fun speak(text: String, priority: SpeechPriority) = announce(text, null, priority)

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
        // 전환 earcon 2음 — 낮은음/높은음 조합으로 상승(진입)·하강(복귀)을 표현
        const val NAV_TONE_LOW = ToneGenerator.TONE_DTMF_1
        const val NAV_TONE_HIGH = ToneGenerator.TONE_DTMF_9
        const val NAV_TONE_MS = 90
        const val NAV_TONE_GAP_MS = 110L
        /** 발화 완료 콜백을 이만큼 늦게 호출 — 스피커 잔향이 마이크에 잡히는 것을 줄인다. */
        const val TTS_ECHO_GUARD_MS = 250L
        // 등록 스캔 시작/종료 — 전환 earcon(DTMF)·LOST 경고(BEEP2)와 겹치지 않는 음색
        const val SCAN_START_TONE = ToneGenerator.TONE_PROP_BEEP
        const val SCAN_END_TONE = ToneGenerator.TONE_PROP_ACK
        const val SCAN_TONE_MS = 150
        const val SCAN_TICK_MS = 50
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
     * 유효 범위는 설정 화면의 [SpeechSpeed] 3단계가 이미 보장한다.
     */
    fun speechRate(settings: SettingsUiState): Float = settings.speechRate
}
