package com.example.snap_sight.ux

import android.content.Context
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.MediaPlayer
import android.media.PlaybackParams
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
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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

    // ---- TalkBack 공존 (2026-08-24) ----
    // 스크린리더(탐색 터치)가 켜져 있으면 TalkBack 낭독과 우리 앱 음성이 겹쳐 들린다.
    // 그동안은 앱의 발화를 잠근다 — 진동·earcon·셔터음은 겹침이 없어 그대로 두고,
    // 발화 뒤 순서 연쇄(onDone: 안내 후 STT 시작 등)는 즉시 이어가 흐름이 멈추지 않게 한다.
    // 탐색 터치는 TalkBack 류 스크린리더가 켜졌을 때만 활성화되는 신호라 오탐이 적다.
    // 예외 (엔드유저 피드백 2026-08-30): 촬영 장면의 안내(방향·READY·세션 흐름)는 TalkBack 이
    // 대신 읽어줄 수 없는 실시간 정보라 잠그지 않는다 — [screenReaderSpeechAllowed] 가 정한다.
    private val accessibilityManager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
        as? android.view.accessibility.AccessibilityManager

    @Volatile
    private var screenReaderActive: Boolean =
        accessibilityManager?.isTouchExplorationEnabled == true

    private val touchExplorationListener =
        android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            screenReaderActive = enabled
            Log.i(
                TAG,
                if (enabled) "탐색 터치(스크린리더) 감지 — 앱 음성 잠금"
                else "탐색 터치 해제 — 앱 음성 재개",
            )
        }

    init {
        accessibilityManager?.addTouchExplorationStateChangeListener(touchExplorationListener)
        if (screenReaderActive) Log.i(TAG, "시작 시점에 스크린리더 감지 — 앱 음성 잠금 상태로 시작")
    }

    /**
     * 스크린리더가 켜져 있어도 앱 음성을 내야 하는 상황인가 — true 면 잠금을 풀고 평소처럼
     * 말한다 (엔드유저 피드백 2026-08-30: "톡백이 켜져 있어도 촬영 장면 가이드는 나와야").
     * MainActivity 가 "촬영 세션 진행 중"으로 연결한다. null 이면 항상 잠금(기존 동작).
     * 분석 스레드·메인 스레드 양쪽에서 호출된다.
     */
    @Volatile
    var screenReaderSpeechAllowed: (() -> Boolean)? = null

    private fun speechMutedByScreenReader(): Boolean =
        screenReaderActive && screenReaderSpeechAllowed?.invoke() != true

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

    /**
     * 음식 세션의 폰 각도 편차(목표각 - 현재각, 도)를 돌려주는 훅 — null 이면 피치 안내 없음.
     * MainActivity 가 음식 세션 판정 + TiltSensorMonitor 로 연결한다. 분석 스레드에서 호출된다.
     */
    @Volatile
    var pitchDeviation: (() -> Float?)? = null

    /**
     * 현재 폰 피치(도, [TiltSensorMonitor] 규약: 양수 = 카메라가 아래를 봄)를 돌려주는 훅 (2026-08-25).
     * 일반 세션의 수직 이동 안내("위로/아래로")가 폰 기울기 때문일 때 "폰 윗부분을 … 기울여 주세요"로
     * 바꾸는 데 쓴다 ([GuidancePolicy.refineVerticalWithPitch]). null 이면 항상 이동 문구.
     * MainActivity 가 TiltSensorMonitor 로 연결한다. 분석 스레드에서 호출된다.
     */
    @Volatile
    var phonePitch: (() -> Float?)? = null

    /**
     * 현재 폰 좌우 기울기(도, [TiltSensorMonitor] roll 규약: 음수 = 폰 윗부분이 오른쪽으로 기움)
     * 를 돌려주는 훅 (2026-08-30). 크게 기울어져 있으면 [GuidancePolicy] 가 다른 축보다 먼저
     * "폰 오른쪽/왼쪽을 조금 올려 주세요"를 말한다. null 이면 수평 안내 없음.
     * MainActivity 가 TiltSensorMonitor 로 연결한다. 분석 스레드에서 호출된다.
     */
    @Volatile
    var phoneRoll: (() -> Float?)? = null

    /**
     * 조준(AIMING) 시작 이후 누적된 카메라 회전량(라디안, yaw to pitch) — null이면 자이로 없음/
     * 미시작. MainActivity가 [com.example.snap_sight.camera.CameraMotionEstimator]로 연결한다.
     * 위치 안내를 "화면 속 위치" 대신 "카메라 켜진 순간(12시) 기준 실제로 얼마나 돌았는지"로
     * 말하는 데 쓴다 (사용자 요청 2026-08-27). null이면 기존처럼 화면 위치 기준으로 말한다.
     * 분석 스레드에서 호출된다.
     */
    @Volatile
    var cameraOrientationRad: (() -> Pair<Float, Float>?)? = null

    /**
     * 지금 세션이 인물(사람) 세션인가 — 머리·발이 잘릴 만큼 가까울 때 "뒤로 가라"를
     * 촬영자가 아니라 피사체(상대방)에게 전달하라고 안내하는 데 쓴다(사용자 요청
     * 2026-08-27). MainActivity가 portraitCropEligible로 연결한다. 분석 스레드에서 호출된다.
     */
    @Volatile
    var personSession: (() -> Boolean)? = null

    /**
     * 인물 프레이밍이 줌 중이거나 목표에 도달한 상태인가 (2026-08-31) — true 면 [GuidancePolicy]
     * 가 READY("좋아요")·상하 기울이기·"가까이"를 내지 않는다 (프레이밍 흐름과의 발화 충돌 방지).
     * MainActivity 가 FramingPhase 로 연결한다. 분석 스레드에서 호출된다.
     */
    @Volatile
    var personFramingBusy: (() -> Boolean)? = null

    /**
     * true 인 동안 방향·READY 안내 발화를 잠시 삼킨다 (진동·경고음은 유지) — 구도 세션이
     * "상반신에 집중해서 찍을까요?"를 묻고 대답을 듣는 동안 시계 안내와 겹치지 않게 한다
     * (사용자 요청 2026-08-31). MainActivity 가 질문 PENDING 상태로 연결한다.
     */
    @Volatile
    var holdGuidanceSpeech: (() -> Boolean)? = null

    @Volatile
    private var ttsReady = false

    // S5 설정에서 조절하는 값들 — applySettings() 전까지는 기본값(최대 강도·기본 속도)으로 동작한다.
    @Volatile
    private var vibrationIntensity = 1f
    @Volatile
    private var soundVolume = 1f
    @Volatile
    private var pendingSpeechRate = 1f

    /**
     * 프리캐싱 음원 보이스의 assets 디렉터리 (예: "tts/aria"). null 이면 내장 TTS 만 쓴다.
     * [SpeechCatalog]에 있는 확정 문장은 이 디렉터리의 mp3 로 재생해 내비게이션처럼 즉시 나온다.
     * 카탈로그에 없는 동적 문장(서버 사진 설명 등)은 보이스와 무관하게 내장 TTS 폴백.
     */
    @Volatile
    private var voiceAssetDir: String? = null

    /**
     * 카탈로그에 없는 동적 문장을 프리셋 보이스로 즉석 합성하는 훅 (text, voiceKey) → mp3 바이트.
     * MainActivity 가 백엔드 /api/tts/skt 프록시([SpeechSynthClient])로 연결한다. null 이면 항상 TTS.
     */
    @Volatile
    var dynamicSpeechFetcher: ((text: String, voiceKey: String) -> ByteArray?)? = null

    /** 프리셋이 기본(TTS)이 아니면 그 키(aria/oliver) — 동적 합성 보이스 선택. */
    @Volatile
    private var dynamicVoiceKey: String? = null

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

    /**
     * 발화 세대 — [stopSpeaking] 마다 올라간다. 서버 합성처럼 비동기로 준비되는 발화는 예약
     * 시점의 세대를 기억했다가, 재생 직전에 세대가 바뀌었으면(그사이 화면 전환·끼어들기가
     * 있었으면) 조용히 버린다 — 이전 화면의 설명이 뒤늦게 재생되던 문제 대책 (2026-08-30).
     */
    private val speechGeneration = AtomicLong()
    @Volatile private var activeUtteranceId: String? = null
    @Volatile private var activeSpeechPriority: SpeechPriority? = null

    /**
     * 재생이 실제로 시작되는 순간(내장 TTS·서버 합성 공통) 알림 — 지연 측정용 훅.
     * MainActivity 가 [com.example.snap_sight.metrics.SessionLatencyTracker.onSpeechStart] 를 연결한다.
     */
    @Volatile
    var speechStartListener: ((SpeechPriority) -> Unit)? = null

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            activeUtteranceId = utteranceId
            activeSpeechPriority = utteranceId?.let(utterancePriorities::get)
            activeSpeechPriority?.let { priority -> speechStartListener?.invoke(priority) }
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
            transitionHandler.post { playNextQueuedAsset() } // TTS 종료 뒤 대기 음원 이어 재생
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
        val preset = VoicePreset.fromKey(settings.voicePreset)
        voiceAssetDir = preset.assetDir
        dynamicVoiceKey = preset.takeIf { it.assetDir != null }?.key
        rebuildToneGenerator()
    }

    /** 탐색·이탈 안내 문장에 들어갈 피사체 이름 — 발화·스펙이 해석되는 대로 호출 (null이면 "피사체"). */
    fun setSessionSubject(word: String?) = policy.setSubject(word)

    /** 새 촬영 세션(AIMING 진입) — 이전 세션의 "이미 말했음" 상태를 지운다. */
    fun resetSession() {
        policy.reset()
        stopPresenceVibration() // 정책 reset 은 액션을 못 내보내므로 여기서 직접 끈다
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
        synchronized(assetPlayerLock) {
            queuedAssets.removeAll {
                it.priority == SpeechPriority.ADJUSTMENT || it.priority == SpeechPriority.READY
            }
        }
        val hasQueuedTargetGuidance = utterancePriorities.values.any {
            it == SpeechPriority.ADJUSTMENT || it == SpeechPriority.READY
        }
        if (hasQueuedTargetGuidance) {
            runCatching { tts.stop() }
            stopPrecached()
        }
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
            pitchDeviationDeg = pitchDeviation?.invoke(),
            phonePitchDeg = phonePitch?.invoke(),
            cameraOrientationRad = cameraOrientationRad?.invoke(),
            personSession = personSession?.invoke() == true,
            phoneRollDeg = phoneRoll?.invoke(),
            personFramingBusy = personFramingBusy?.invoke() == true,
        )
        val holdSpeech = holdGuidanceSpeech?.invoke() == true
        for (action in decision.actions) {
            when (action) {
                is GuidanceAction.Speak -> if (!holdSpeech) speak(
                    action.text,
                    if (action.text == GuidancePolicy.READY_UTTERANCE) SpeechPriority.READY
                    else SpeechPriority.ADJUSTMENT,
                )
                GuidanceAction.Vibrate -> vibrateShort()
                GuidanceAction.WarningTone -> playWarningTone()
                is GuidanceAction.PresenceVibrationLevel -> startPresenceVibration(action.level)
                GuidanceAction.PresenceVibrationStop -> stopPresenceVibration()
            }
        }
        return decision.verdict
    }

    // ---- 존재 확인 연속 진동 — 피사체가 잡혀 있는 동안 "징징" (사용자 요청 2026-08-24) ----

    @Volatile
    private var presenceVibrating = false

    /** 현재 재생 중인 존재 진동 단계 — 꺼져 있으면 -1. 같은 단계 재요청은 패턴을 다시 시작하지 않는다. */
    @Volatile
    private var presenceLevel = -1

    /**
     * 존재 진동 시작 또는 단계 변경. 펄스 길이는 고정([PRESENCE_PULSE_ON_MS])이고 휴지 간격만
     * 단계에 따라 줄어든다([GuidanceFeedbackSettingsMapper.presencePulseOffMs]) — 진폭 차이는
     * 기기마다 체감이 다르고 진폭 제어가 없는 기기도 있어, "가까워질수록 빠르게"는 간격으로
     * 표현한다(엔드유저 피드백 2026-08-30). 모터 과열·소음을 피하려고 가장 빠른 단계도 휴지를 둔다.
     */
    private fun startPresenceVibration(level: Int) {
        if (GuidanceFeedbackSettingsMapper.vibrationAmplitude(vibrationIntensity) == null) return
        if (presenceVibrating && presenceLevel == level) return
        presenceVibrating = true
        presenceLevel = level
        playPresenceWaveform(level)
    }

    /** 존재 패턴 파형 재생 — 확정 진동([vibrateConfirm]) 뒤 복구에도 쓴다. */
    private fun playPresenceWaveform(level: Int) {
        val amplitude = GuidanceFeedbackSettingsMapper.vibrationAmplitude(vibrationIntensity) ?: return
        val timings = longArrayOf(
            0, PRESENCE_PULSE_ON_MS, GuidanceFeedbackSettingsMapper.presencePulseOffMs(level),
        )
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, intArrayOf(0, amplitude, 0), 0)
        } else {
            VibrationEffect.createWaveform(timings, 0)
        }
        runCatching { vibrator.vibrate(effect) }
    }

    /**
     * 존재 확인 연속 진동을 즉시 끈다. 조준(AIMING)을 벗어나는 모든 경로(셔터·취소·홈 복귀)에서
     * 호출해야 한다 — 분석이 멈추면 정책이 스스로 끌 기회가 없어 진동이 계속 돌기 때문
     * (실사용 피드백 2026-08-24: "사진 저장 후에도 진동이 반복").
     */
    fun stopPresenceVibration() {
        if (!presenceVibrating) return
        presenceVibrating = false
        presenceLevel = -1
        runCatching { vibrator.cancel() }
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

    /**
     * 인물 프레이밍 확정 진동 — [PersonFramingController.Outcome.vibrate] 전용 공개 창구.
     * 존재 확인 연속 진동이 도는 중에도 삼켜지지 않는다 (실기기 2026-08-31 — [vibrateShort] 는
     * 존재 패턴을 지키려고 스스로 빠지기 때문에 목표 도달 진동이 아예 안 느껴졌다):
     * 잠깐 끊고 2연타로 구분해 울린 뒤 존재 패턴을 복구한다.
     */
    fun vibrateConfirm() {
        if (!presenceVibrating) {
            vibrateShort()
            return
        }
        val amplitude = GuidanceFeedbackSettingsMapper.vibrationAmplitude(vibrationIntensity) ?: return
        runCatching { vibrator.cancel() }
        val timings = longArrayOf(0, CONFIRM_PULSE_MS, CONFIRM_GAP_MS, CONFIRM_PULSE_MS)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, intArrayOf(0, amplitude, 0, amplitude), -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        runCatching { vibrator.vibrate(effect) }
        transitionHandler.postDelayed({
            if (presenceVibrating) playPresenceWaveform(presenceLevel.coerceAtLeast(0))
        }, CONFIRM_PULSE_MS + CONFIRM_GAP_MS + CONFIRM_PULSE_MS + CONFIRM_RESUME_SLACK_MS)
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
     *
     * [interrupt] 가 true 면 **사용자 조작에 대한 응답**으로 본다 — 진행 중인 발화·예약된
     * onDone 연쇄·대기 중인 서버 합성을 전부 끊고([stopSpeaking]) 우선순위와 무관하게 즉시
     * 말한다 (엔드유저 피드백 2026-08-30: 갤러리에서 사진을 바꿔 눌러도 이전 설명이 이어지고
     * 새 설명은 안 나옴). 자동 안내(방향·판정·세션 흐름)는 false 로 두어 "말하는 도중에 끊고
     * 다시 말하지 않는다"는 기존 규칙(2026-08-26)을 유지한다.
     */
    fun announce(
        text: String,
        onDone: (() -> Unit)? = null,
        priority: SpeechPriority = SpeechPriority.STATUS,
        interrupt: Boolean = false,
        rateOverride: Float? = null,
    ) {
        if (interrupt) stopSpeaking()
        if (speechMutedByScreenReader()) {
            // TalkBack 낭독과 겹치지 않게 침묵 — 순서 연쇄(onDone)는 반드시 이어간다.
            // 발화가 없으니 안내 음성이 마이크에 섞일 일도 없다.
            onDone?.let { done -> transitionHandler.post { done() } }
            return
        }
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
        if (active != null && active.level >= priority.level && onDone == null) {
            // 신원·상태 알림이 진행 중인 이동/READY/촬영 안내를 자르거나 뒤늦게 재생되지 않게 버린다.
            // 같은 우선순위(예: 이동 안내가 이동 안내를)도 이제 자르지 않는다 — 말하는 도중에
            // 판정이 또 와도 끊고 다시 말하지 않고, 끝날 때까지 기다렸다가 다음 판정을 따른다
            // (실사용 피드백 2026-08-26 — "조금 오른쪽으로 이동해주세요"·"좋아요"가 너무 자주
            // 끊기고 다시 시작함).
            return
        }
        // 같은 우선순위끼리는(예: 갤러리 진입 안내 도중 "말해서 찾기"를 눌러 등 방금 안내를
        // 명시적으로 요청한 onDone 호출도) 자르지 않고 끝날 때까지 기다렸다가 이어 말한다 —
        // 엄격히 더 높은 우선순위일 때만 즉시 끼어든다 (사용자 요청 2026-08-27, "갤러리
        // 들어가자마자 음성이 겹친다").
        val flush = active == null || priority.level > active.level

        // 한 앱 한 목소리: ① 확정 문장은 프리캐싱 음원, ② 그 외 문장은 즉석 합성(캐시 우선),
        // ③ 프리셋이 기본이면 내장 TTS. 프리셋(아리아/올리버) 모드에서 ①②가 모두 실패하면
        // 내장 TTS 로 목소리를 섞지 않고 침묵한다 ([speakWithTts] 상단 가드, 2026-08-31).
        val assetId = voiceAssetDir?.let { SpeechCatalog.assetIdFor(text) }
        if (assetId != null && !flush) {
            // 진행 중인 같은/높은 우선순위 발화를 끊지 않고, 끝난 뒤 프리셋 음원으로 이어
            // 말한다 — 예전엔 이 경로가 내장 TTS 큐로 새서 기본 목소리가 섞였다 (2026-08-31).
            synchronized(assetPlayerLock) {
                queuedAssets.addLast(QueuedAsset(assetId, onDone, priority, rateOverride))
            }
            transitionHandler.post { playNextQueuedAsset() } // 그새 발화가 끝났을 수 있다
            return
        }
        if (flush && assetId != null) {
            val id = "announce_${utteranceCounter.incrementAndGet()}"
            if (onDone != null) utteranceCallbacks[id] = onDone
            utterancePriorities[id] = priority
            if (playPrecached(assetId, id, priority, rateOverride)) return
            utterancePriorities.remove(id)
            utteranceCallbacks.remove(id)
        }
        if (flush && assetId == null && scheduleDynamic(text, onDone, priority)) return

        speakWithTts(text, onDone, priority, flush)
    }

    private fun speakWithTts(
        text: String,
        onDone: (() -> Unit)?,
        priority: SpeechPriority,
        flush: Boolean,
    ) {
        // 프리셋 보이스(아리아/올리버) 모드에서는 내장 TTS 를 **절대** 내지 않는다 (사용자
        // 요청 2026-08-31 — "기본 tts 는 아예 나오면 안된다"). 여기 도달했다는 건 프리캐싱
        // 음원도, 즉석 합성도 이 문장을 못 맡았다는 뜻 — 예외 없이 침묵하고 onDone 순서
        // 연쇄만 이어간다. 못 나간 문장은 [SPEECH_COVERAGE_TAG] 로 전부 남겨 추적한다
        // (`adb logcat -s SpeechCoverage`) — 자주 보이면 카탈로그+음원으로 승격 대상이다.
        if (voiceAssetDir != null) {
            Log.w(SPEECH_COVERAGE_TAG, "프리셋 미커버 — 침묵 처리(내장 TTS 차단): $text")
            onDone?.let { done -> transitionHandler.post { done() } }
            return
        }
        val id = "announce_${utteranceCounter.incrementAndGet()}"
        if (onDone != null) utteranceCallbacks[id] = onDone
        utterancePriorities[id] = priority
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (flush) stopPrecached() // 진행 중인 음원 재생도 새 발화에 밀려난다 (스테일 음성 방지)
        val result = tts.speak(text, queueMode, null, id)
        if (result == TextToSpeech.ERROR) {
            utterancePriorities.remove(id)
            utteranceCallbacks.remove(id)?.let { transitionHandler.post(it) }
        }
    }

    // ---- 프리캐싱 음원 재생 (SKT A.X TTS 합성분, assets/tts/{voice}/{id}.mp3) ----

    private val assetPlayerLock = Any()
    private var assetPlayer: MediaPlayer? = null
    @Volatile private var assetUtteranceId: String? = null

    /**
     * 프리셋 음원 대기열 (2026-08-31) — 같은/높은 우선순위 발화가 재생 중이라 끊을 수 없는
     * (flush=false) 카탈로그 문장을 보관했다가, 현재 발화가 끝나면 이어 재생한다. 예전
     * 내장 TTS 의 QUEUE_ADD 역할의 프리셋판 — 이게 없으면 그런 문장이 내장 TTS 로 새거나
     * (프라이버시 예외) 침묵 처리돼 "무엇을 찍을까요?"가 기본 목소리로 나왔다 (실기기 로그
     * 2026-08-31). [stopSpeaking] 은 대기열도 비운다 (지난 화면의 발화 방지).
     */
    private data class QueuedAsset(
        val assetId: String,
        val onDone: (() -> Unit)?,
        val priority: SpeechPriority,
        val rateOverride: Float?,
    )
    private val queuedAssets = ArrayDeque<QueuedAsset>()

    /** 현재 발화가 끝난 뒤 호출 — 대기 중인 프리셋 음원이 있으면 이어 재생한다. */
    private fun playNextQueuedAsset() {
        val next = synchronized(assetPlayerLock) {
            if (activeUtteranceId != null) return
            queuedAssets.removeFirstOrNull()
        } ?: return
        val id = "announce_${utteranceCounter.incrementAndGet()}"
        next.onDone?.let { utteranceCallbacks[id] = it }
        utterancePriorities[id] = next.priority
        if (!playPrecached(next.assetId, id, next.priority, next.rateOverride)) {
            utterancePriorities.remove(id)
            utteranceCallbacks.remove(id)
            next.onDone?.let { done -> transitionHandler.post { done() } }
            playNextQueuedAsset() // 실패한 항목은 건너뛰고 다음 대기분 시도
        }
    }

    /** 확정 문장 음원을 즉시 재생한다. 성공 시 true, 실패하면 false(호출부가 TTS 폴백). */
    private fun playPrecached(
        assetId: String,
        utteranceId: String,
        priority: SpeechPriority,
        rateOverride: Float? = null,
    ): Boolean {
        val dir = voiceAssetDir ?: return false
        return playMediaSource(utteranceId, priority, "asset:$assetId", rateOverride) { player ->
            appContext.assets.openFd("$dir/$assetId.mp3").use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
        }
    }

    /** 동적 합성 캐시 파일을 재생한다 — 성공 시 true. */
    private fun playCachedFile(file: File, utteranceId: String, priority: SpeechPriority): Boolean =
        playMediaSource(utteranceId, priority, file.name) { player ->
            player.setDataSource(file.path)
        }

    private inline fun playMediaSource(
        utteranceId: String,
        priority: SpeechPriority,
        sourceLabel: String,
        rateOverride: Float? = null,
        setSource: (MediaPlayer) -> Unit,
    ): Boolean {
        synchronized(assetPlayerLock) {
            stopPrecachedLocked()
            runCatching { tts.stop() } // TTS 가 말하는 중이었다면 함께 끊는다 (FLUSH 의미 유지)
            val player = MediaPlayer()
            return runCatching {
                setSource(player)
                player.setOnCompletionListener { completeAsset(utteranceId) }
                player.setOnErrorListener { _, _, _ -> completeAsset(utteranceId); true }
                player.prepare()
                // 재생 배속(0.8/1.0/1.5) — 합성은 1.0배 한 벌이고 재생 시점에 조절한다.
                // rateOverride 는 특정 안내(촬영 확인 질문, 1.2배속 — 사용자 요청 2026-08-31)의
                // 문장 단위 배속 지정 — 설정 배속보다 우선한다.
                player.playbackParams = PlaybackParams().setSpeed(rateOverride ?: pendingSpeechRate)
                if (!player.isPlaying) player.start()
                speechStartListener?.invoke(priority) // 서버 합성 경로의 재생 시작 (지연 측정 훅)
                assetPlayer = player
                assetUtteranceId = utteranceId
                activeUtteranceId = utteranceId
                activeSpeechPriority = priority
                true
            }.getOrElse {
                Log.w(TAG, "음원 재생 실패($sourceLabel) — TTS 폴백", it)
                runCatching { player.release() }
                false
            }
        }
    }

    // ---- 동적 문장 즉석 합성 (촬영 요약·사진 설명 — 카탈로그에 없는 문장) ----

    private val dynamicFetchExecutor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "SnapSight-TtsFetch") }
    }

    private fun dynamicCacheFile(voiceKey: String, text: String): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(text.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(File(appContext.cacheDir, "tts_cache"), voiceKey), "$digest.mp3")
    }

    /**
     * 동적 문장의 합성 음원을 미리 받아 캐시한다 — 낭독 시점의 대기를 없애는 프리페치.
     * 셔터 직후 온디바이스 요약처럼 "곧 말할 문장"이 정해지는 즉시 호출한다.
     */
    fun prefetchDynamic(text: String) {
        if (speechMutedByScreenReader()) return // 어차피 말하지 않을 문장 — 합성 비용 절약
        val voice = dynamicVoiceKey ?: return
        if (text.isBlank() || SpeechCatalog.assetIdFor(text) != null) return
        if (dynamicSpeechGate?.invoke(text) == false) return
        val fetcher = dynamicSpeechFetcher ?: return
        val target = dynamicCacheFile(voice, text)
        if (target.exists()) return
        dynamicFetchExecutor.execute {
            if (target.exists()) return@execute
            runCatching {
                val bytes = fetcher(text, voice) ?: return@execute
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) tmp.delete()
            }.onFailure { Log.w(TAG, "동적 합성 프리페치 실패", it) }
        }
    }

    /** 동적 문장 안내 — 이제 [announce] 자체가 같은 일을 한다 (호출부 호환용 별칭). */
    fun announceDynamic(
        text: String,
        onDone: (() -> Unit)? = null,
        priority: SpeechPriority = SpeechPriority.STATUS,
        interrupt: Boolean = false,
    ) = announce(text, onDone, priority, interrupt)

    /**
     * 이 문장을 서버 합성으로 보내도 되는가 — false 면 즉석 합성을 건너뛰고 내장 TTS.
     * MainActivity 가 등록 이름 포함 여부로 연결한다 (등록 이름은 기기 밖으로 내보내지 않는다).
     */
    @Volatile
    var dynamicSpeechGate: ((String) -> Boolean)? = null

    /** 마지막 합성 실패 시각 — 오프라인에서 발화마다 타임아웃을 기다리지 않게 잠시 물러선다. */
    @Volatile
    private var lastDynamicFailureMs = Long.MIN_VALUE / 2

    /** 즉석 합성 경로를 스케줄한다 — 맡았으면 true, 아니면 false(호출부가 TTS 로). */
    private fun scheduleDynamic(
        text: String,
        onDone: (() -> Unit)?,
        priority: SpeechPriority,
    ): Boolean {
        val voice = dynamicVoiceKey ?: return false
        val fetcher = dynamicSpeechFetcher ?: return false
        if (text.isBlank()) return false
        if (dynamicSpeechGate?.invoke(text) == false) return false
        // 프리셋 음원(카탈로그)에 없어서 즉석 합성으로 가는 문장 — 전부 남겨 추적한다.
        // 자주 찍히는 고정 문장이 보이면 카탈로그+음원으로 승격 대상이다 (2026-08-31).
        Log.i(SPEECH_COVERAGE_TAG, "프리셋에 없는 문장 — 즉석 합성: $text")
        val cached = dynamicCacheFile(voice, text)
        if (cached.exists()) return startDynamicPlayback(cached, text, onDone, priority)
        if (System.currentTimeMillis() - lastDynamicFailureMs < DYNAMIC_FAILURE_BACKOFF_MS) {
            return false // 최근 실패(오프라인 등) — 바로 TTS 로
        }
        val generation = speechGeneration.get()
        dynamicFetchExecutor.execute {
            val ready = runCatching {
                if (!cached.exists()) {
                    fetcher(text, voice)?.let { bytes ->
                        cached.parentFile?.mkdirs()
                        cached.writeBytes(bytes)
                    }
                }
                cached.exists()
            }.getOrDefault(false)
            if (ready) {
                lastDynamicFailureMs = Long.MIN_VALUE / 2
            } else {
                lastDynamicFailureMs = System.currentTimeMillis()
            }
            transitionHandler.post {
                // 합성을 기다리는 사이 화면 전환·끼어들기([stopSpeaking])가 있었으면 이 문장은
                // 이미 지난 화면의 것이다 — 재생도, onDone 연쇄도 이어가지 않는다.
                if (speechGeneration.get() != generation) {
                    Log.i(TAG, "동적 발화 폐기(세대 바뀜): ${text.take(20)}")
                    return@post
                }
                // announce() 의 폐기 규칙과 동일하게 "같은 우선순위도 자르지 않는다" — 합성을
                // 기다리는 사이 다른 같은 급 발화가 시작됐으면 뒤늦게 그 위로 끼어들지 않는다
                // (겹침 원인 ③: 서버 응답이 느릴 때 방향 안내를 도중에 끊던 문제, 2026-08-31).
                val activeNow = activeSpeechPriority
                if (activeNow != null && activeNow.level >= priority.level && onDone == null) return@post
                if (!ready || !startDynamicPlayback(cached, text, onDone, priority)) {
                    speakWithTts(text, onDone, priority, flush = true)
                }
            }
        }
        return true
    }

    private fun startDynamicPlayback(
        file: File,
        text: String,
        onDone: (() -> Unit)?,
        priority: SpeechPriority,
    ): Boolean {
        val id = "announce_${utteranceCounter.incrementAndGet()}"
        if (onDone != null) utteranceCallbacks[id] = onDone
        utterancePriorities[id] = priority
        if (playCachedFile(file, id, priority)) return true
        utterancePriorities.remove(id)
        utteranceCallbacks.remove(id)
        Log.w(TAG, "동적 캐시 재생 실패 — TTS 폴백: ${text.take(20)}")
        return false
    }

    /** 진행 중인 프리캐싱 재생을 끊는다 — 완료 콜백은 반드시 호출된다. */
    private fun stopPrecached() = synchronized(assetPlayerLock) { stopPrecachedLocked() }

    private fun stopPrecachedLocked() {
        val player = assetPlayer ?: return
        val id = assetUtteranceId
        assetPlayer = null
        assetUtteranceId = null
        runCatching { player.stop() }
        runCatching { player.release() }
        if (id != null) completeAsset(id, drainQueue = false) // 끊김 — 새 발화가 이어받는다
    }

    private fun completeAsset(utteranceId: String, drainQueue: Boolean = true) {
        synchronized(assetPlayerLock) {
            if (assetUtteranceId == utteranceId) {
                assetPlayer?.let { runCatching { it.release() } }
                assetPlayer = null
                assetUtteranceId = null
            }
        }
        utterancePriorities.remove(utteranceId)
        if (activeUtteranceId == utteranceId) {
            activeUtteranceId = null
            activeSpeechPriority = null
        }
        // 자연 종료 시에만 대기열을 잇는다 — 새 발화가 이전 것을 끊는 경로(stopPrecached)에서
        // 대기분을 재생하면 방금 시작한 발화를 도로 죽인다. post 로 미뤄 새 발화가 active 를
        // 먼저 잡게 한다.
        if (drainQueue) transitionHandler.post { playNextQueuedAsset() }
        val callback = utteranceCallbacks.remove(utteranceId) ?: return
        transitionHandler.postDelayed(callback, TTS_ECHO_GUARD_MS)
    }

    private fun speak(text: String, priority: SpeechPriority) = announce(text, null, priority)

    private fun vibrateShort() {
        // 존재 확인 연속 진동이 도는 중엔 한발 진동으로 패턴을 끊지 않는다 — 그 채널이 우선
        if (presenceVibrating) return
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

    /**
     * 진행 중이거나 곧 이어질 안내 음성을 전부 즉시 멈춘다 — 화면 전환 시 이전 화면의
     * 음성이 새 화면까지 새어 나가는 문제 대책 (사용자 요청 2026-08-25: "yolo 설명에서
     * 바로 홈으로 갔을 때 음성이 나오는 문제"). 화면 전환 함수(enterScreen·returnHome 등)
     * 맨 앞에서 호출한다.
     *
     * [stopPrecached]·`tts.stop()`은 "완료 콜백은 반드시 호출된다"는 계약이라(다른 발화가
     * 끼어들 때 대기 중인 호출부가 막히지 않게 하려는 것) 그대로 두면 "저장했어요→요약→
     * 버튼 안내" 같은 onDone 체인이 화면이 바뀐 뒤에도 이어져 버린다. 콜백을 먼저 비워서
     * 그 체인 자체를 끊은 뒤에 멈춘다.
     */
    fun stopSpeaking() {
        speechGeneration.incrementAndGet() // 대기 중인 서버 합성 발화도 무효화 (2026-08-30)
        synchronized(initLock) { pendingWhileInit = null } // TTS 초기화 대기분도 지난 화면의 것
        synchronized(assetPlayerLock) { queuedAssets.clear() } // 대기 음원도 지난 화면의 것
        utteranceCallbacks.clear()
        utterancePriorities.clear()
        activeUtteranceId = null
        activeSpeechPriority = null
        stopPrecached()
        runCatching { tts.stop() }
    }

    /** Activity onDestroy 등에서 호출 — TTS/톤/음원 리소스 해제. */
    fun release() {
        accessibilityManager?.removeTouchExplorationStateChangeListener(touchExplorationListener)
        tts.stop()
        tts.shutdown()
        stopPrecached()
        stopPresenceVibration()
        toneGenerator?.release()
        toneGenerator = null
        runCatching { shutterSound.release() }
    }

    private companion object {
        const val TAG = "GuidanceFeedback"

        /**
         * 프리셋 커버리지 추적 (2026-08-31) — 프리셋 음원으로 못 나간 문장(즉석 합성행·침묵
         * 처리·프라이버시 예외)을 남긴다. `adb logcat -s SpeechCoverage` 로 모아 보고, 자주
         * 보이는 고정 문장은 카탈로그+음원으로 승격한다.
         */
        const val SPEECH_COVERAGE_TAG = "SpeechCoverage"
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
        /** 즉석 합성 실패 후 이 시간 동안은 재시도 없이 바로 TTS — 오프라인 발화 지연 방지. */
        const val DYNAMIC_FAILURE_BACKOFF_MS = 30_000L
        // 존재 확인 연속 진동 파형 — 140ms 온 / 160ms 오프 반복
        /** 존재 진동 한 펄스 길이 — 휴지 간격은 단계별([GuidanceFeedbackSettingsMapper.presencePulseOffMs]). */
        const val PRESENCE_PULSE_ON_MS = 100L

        // 프레이밍 확정 2연타 (2026-08-31) — 존재 패턴(단발 반복)과 구분되는 리듬
        const val CONFIRM_PULSE_MS = 90L
        const val CONFIRM_GAP_MS = 70L
        const val CONFIRM_RESUME_SLACK_MS = 60L
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

    /**
     * 존재 진동 단계별 펄스 사이 휴지(ms) — 단계는 [GuidancePolicy.PRESENCE_LEVELS] 기준
     * 0(멂) .. 3(목표 범위 안). 멀면 약 1.3Hz 의 느린 톡톡, 범위 안이면 약 5Hz 의 빠른 드르륵.
     * 범위 밖 단계는 가장 가까운 끝으로 clamp 한다.
     */
    fun presencePulseOffMs(level: Int): Long =
        PRESENCE_PULSE_OFF_MS_BY_LEVEL[level.coerceIn(0, PRESENCE_PULSE_OFF_MS_BY_LEVEL.size - 1)]

    private val PRESENCE_PULSE_OFF_MS_BY_LEVEL = longArrayOf(700L, 420L, 220L, 90L)

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
