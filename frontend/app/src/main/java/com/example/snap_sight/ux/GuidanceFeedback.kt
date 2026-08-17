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

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale.KOREAN
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

    private fun vibrateShort() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(SHORT_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
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
