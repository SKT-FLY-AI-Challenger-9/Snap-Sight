// 이 파일: 풍경(LANDSCAPE) 모드 전용 안내 로직 (2026-08-28, dev 반영 2026-08-30).
// 풍경은 조준할 bbox 가 없어 구도 안내가 꺼져 있다 — 대신 시각장애 사용자의 풍경 사진이
// 실제로 실패하는 요인을 안내한다: 기울어진 수평(roll), 역광, 그리고 "지금 뭐가 보이는지"
// (장면 내용 낭독). 자동촬영은 하지 않는다 — 촬영 확정은 두 번 탭 수동 그대로다
// (사용자 결정 2026-08-28).
// android.* 의존이 없고 시각(nowMs)을 주입받아 JVM 단위 테스트한다.
package com.example.snap_sight.ux

import com.example.snap_sight.camera.PhoneRoll
import kotlin.math.abs

internal class LandscapeGuide {

    private var rollGuidanceActive = false
    private var lastRollSpokenAtMs = Long.MIN_VALUE / 2
    private var backlightStreak = 0
    private var lastBacklightSpokenAtMs = Long.MIN_VALUE / 2
    private var sceneAnnounced = false
    private var sceneFirstFrameMs: Long? = null

    /** 새 세션 시작 — "이미 말했음" 상태와 히스테리시스를 지운다. */
    fun reset() {
        rollGuidanceActive = false
        lastRollSpokenAtMs = Long.MIN_VALUE / 2
        backlightStreak = 0
        lastBacklightSpokenAtMs = Long.MIN_VALUE / 2
        sceneAnnounced = false
        sceneFirstFrameMs = null
    }

    /**
     * 수평 안내 — [com.example.snap_sight.camera.TiltSensorMonitor] roll 기준. 스냅 편차·임계값·
     * 부호 규약은 [PhoneRoll] 공용 (인물·사물 세션의 [GuidancePolicy] 수평 안내와 동일).
     * [ROLL_ENTER_DEG] 를 넘으면 교정 문구를 [ROLL_REPEAT_MS] 간격으로 말하고,
     * [ROLL_EXIT_DEG] 안으로 돌아오면 "수평이 맞았어요" 1회 (히스테리시스).
     */
    fun onRoll(rollDegrees: Float, nowMs: Long): String? {
        val deviation = deviationFromNearestSnap(rollDegrees)
        val magnitude = abs(deviation)
        if (rollGuidanceActive && magnitude <= ROLL_EXIT_DEG) {
            rollGuidanceActive = false
            lastRollSpokenAtMs = nowMs
            return LEVEL_UTTERANCE
        }
        if (magnitude >= ROLL_ENTER_DEG) rollGuidanceActive = true
        if (!rollGuidanceActive) return null
        if (nowMs - lastRollSpokenAtMs < ROLL_REPEAT_MS) return null
        lastRollSpokenAtMs = nowMs
        // 부호 규약은 실기기로 확정 (2026-08-28): 폰을 왼쪽(반시계)으로 돌리면 roll 이 +로
        // 커진다. 따라서 양수 편차 = 왼쪽으로 지나침 → 오른쪽으로 되돌리라고 안내한다.
        return if (deviation > 0f) ROLL_TURN_RIGHT_UTTERANCE else ROLL_TURN_LEFT_UTTERANCE
    }

    /**
     * 역광 안내 — 분석 프레임의 밝기 분포([luminanceFractions])로 판정한다.
     * "아주 밝은 픽셀(하늘·광원)과 아주 어두운 픽셀(그늘진 전경)이 동시에 많다"가 역광의
     * 전형이다. 오탐이 순간 번쩍임으로 나가지 않게 [BACKLIGHT_STREAK]회 연속 유지될 때만,
     * [BACKLIGHT_COOLDOWN_MS] 간격으로 1회 말한다.
     */
    fun onLuminance(brightFraction: Float, darkFraction: Float, nowMs: Long): String? {
        val backlit = brightFraction >= BACKLIGHT_BRIGHT_MIN && darkFraction >= BACKLIGHT_DARK_MIN
        if (!backlit) {
            backlightStreak = 0
            return null
        }
        backlightStreak++
        if (backlightStreak < BACKLIGHT_STREAK) return null
        if (nowMs - lastBacklightSpokenAtMs < BACKLIGHT_COOLDOWN_MS) return null
        lastBacklightSpokenAtMs = nowMs
        return BACKLIGHT_UTTERANCE
    }

    /**
     * 장면 내용 낭독 — 세션당 1회, "나무 2개, 자동차 1개가 보여요" 형태 (정보 제공 안내).
     * 풍경 진입 후 [SCENE_SUMMARY_DELAY_MS] 는 기다린다 — "풍경 모드예요" 진입 안내가
     * 재생 중일 때 말하면 우선순위에 밀려 조용히 버려지고 1회 기회가 소진되는 버그가
     * 있었다 (실기기 2026-08-28).
     */
    fun sceneSummaryOnce(koreanLabels: List<String>, nowMs: Long): String? {
        if (sceneAnnounced) return null
        val since = sceneFirstFrameMs ?: nowMs.also { sceneFirstFrameMs = it }
        if (nowMs - since < SCENE_SUMMARY_DELAY_MS) return null
        val summary = sceneSummary(koreanLabels) ?: return null
        sceneAnnounced = true
        return summary
    }

    companion object {
        // ---- 수평 — 규약·임계값·문구는 [PhoneRoll]/[GuidancePolicy] 와 공용 (2026-08-30) ----
        const val ROLL_ENTER_DEG = PhoneRoll.ENTER_DEG
        const val ROLL_EXIT_DEG = PhoneRoll.EXIT_DEG
        const val ROLL_REPEAT_MS = 5_000L
        val ROLL_TURN_LEFT_UTTERANCE = GuidanceDirection.ROLL_TURN_LEFT.utterance
        val ROLL_TURN_RIGHT_UTTERANCE = GuidanceDirection.ROLL_TURN_RIGHT.utterance
        const val LEVEL_UTTERANCE = GuidancePolicy.LEVEL_UTTERANCE

        // ---- 역광 ----
        /** 프레임에서 거의 포화된(밝은) 픽셀 비율 하한. */
        const val BACKLIGHT_BRIGHT_MIN = 0.06f
        /** 프레임에서 매우 어두운 픽셀 비율 하한 — 밝음과 동시에 커야 역광이다. */
        const val BACKLIGHT_DARK_MIN = 0.35f
        const val BACKLIGHT_STREAK = 5
        const val BACKLIGHT_COOLDOWN_MS = 20_000L
        const val BACKLIGHT_UTTERANCE = "역광이에요. 반대 방향에서 찍으면 더 잘 나와요."

        /** 장면 낭독에 넣는 최대 라벨 종류 수 — 많이 나열할수록 안내가 늘어진다. */
        const val SCENE_MAX_KINDS = 4

        /** 풍경 진입 안내("풍경 모드예요")가 끝난 뒤에 장면 낭독을 시작하는 대기 시간. */
        const val SCENE_SUMMARY_DELAY_MS = 4_000L

        /** roll 을 가장 가까운 파지 스냅(0/±90/180°)으로부터의 편차로 정규화 (-45..45). */
        fun deviationFromNearestSnap(rollDegrees: Float): Float =
            PhoneRoll.deviationFromNearestSnap(rollDegrees)

        /** ["나무","나무","자동차"] → "나무 2개, 자동차 1개가 보여요." 비어 있으면 null. */
        fun sceneSummary(koreanLabels: List<String>): String? {
            if (koreanLabels.isEmpty()) return null
            val counts = koreanLabels.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .take(SCENE_MAX_KINDS)
            return counts.joinToString(", ") { "${it.key} ${it.value}개" } + "가 보여요."
        }

        /** 밝기 샘플링 보폭 — 640×480 기준 약 2만 픽셀만 본다 (프레임당 수 ms). */
        private const val LUMA_SAMPLE_STRIDE = 13
        private const val LUMA_BRIGHT_THRESHOLD = 235
        private const val LUMA_DARK_THRESHOLD = 50

        /**
         * RGB 바이트 배열(픽셀당 3바이트)에서 (아주 밝은 비율, 아주 어두운 비율)을 샘플링한다.
         * 분석 스레드에서 매 분석 프레임 호출해도 부담 없는 비용으로 설계됐다.
         */
        fun luminanceFractions(rgb: ByteArray, width: Int, height: Int): Pair<Float, Float> {
            val pixels = width * height
            if (pixels <= 0 || rgb.size < pixels * 3) return 0f to 0f
            var bright = 0
            var dark = 0
            var total = 0
            var i = 0
            while (i < pixels) {
                val idx = i * 3
                val r = rgb[idx].toInt() and 0xFF
                val g = rgb[idx + 1].toInt() and 0xFF
                val b = rgb[idx + 2].toInt() and 0xFF
                val luma = (r + g + g + b) shr 2
                if (luma >= LUMA_BRIGHT_THRESHOLD) bright++ else if (luma <= LUMA_DARK_THRESHOLD) dark++
                total++
                i += LUMA_SAMPLE_STRIDE
            }
            if (total == 0) return 0f to 0f
            return bright.toFloat() / total to dark.toFloat() / total
        }
    }
}
