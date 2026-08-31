package com.example.snap_sight.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 풍경 전용 안내 (2026-08-28) — 수평(roll) 히스테리시스·반복 게이팅, 역광 스트릭·쿨다운,
 * 장면 낭독 1회·문구 형식, 밝기 분포 샘플링을 검증한다.
 */
class LandscapeGuideTest {

    // ---- 수평 ----
    // 부호 규약 (실기기 2026-08-28): 폰을 왼쪽(반시계)으로 돌리면 roll 이 +로 커진다.
    // 따라서 +편차는 "오른쪽으로 되돌리기", -편차는 "왼쪽으로 되돌리기"가 맞는 교정이다.

    @Test
    fun `tilted roll speaks the corrective turn direction and repeats after the interval`() {
        val guide = LandscapeGuide()
        // 왼쪽으로 10° 기울어짐(+) → 오른쪽으로 되돌리라고 안내
        assertEquals(LandscapeGuide.ROLL_TURN_RIGHT_UTTERANCE, guide.onRoll(10f, nowMs = 0))
        assertNull(guide.onRoll(10f, nowMs = 1_000))
        assertEquals(
            LandscapeGuide.ROLL_TURN_RIGHT_UTTERANCE,
            guide.onRoll(10f, nowMs = LandscapeGuide.ROLL_REPEAT_MS),
        )
        // 반대 방향(-) → 왼쪽으로
        assertEquals(
            LandscapeGuide.ROLL_TURN_LEFT_UTTERANCE,
            guide.onRoll(-9f, nowMs = LandscapeGuide.ROLL_REPEAT_MS * 2),
        )
    }

    @Test
    fun `landscape grip near 90 degrees counts as level`() {
        // 가로 파지도 정상 자세 — 90° 근처에서는 기울어짐 안내가 없어야 한다 (실기기 2026-08-28)
        val guide = LandscapeGuide()
        assertNull(guide.onRoll(90f, nowMs = 0))
        assertNull(guide.onRoll(-88f, nowMs = 1_000))
        // 90° 스냅에서 10° 지나침(100° = 왼쪽으로 과회전) → 오른쪽으로 되돌리기
        assertEquals(LandscapeGuide.ROLL_TURN_RIGHT_UTTERANCE, guide.onRoll(100f, nowMs = 2_000))
        // 90° 스냅으로 복귀 → 수평 확인
        assertEquals(LandscapeGuide.LEVEL_UTTERANCE, guide.onRoll(91f, nowMs = 3_000))
    }

    @Test
    fun `returning level speaks a one-time confirmation`() {
        val guide = LandscapeGuide()
        guide.onRoll(10f, nowMs = 0)
        assertEquals(LandscapeGuide.LEVEL_UTTERANCE, guide.onRoll(1f, nowMs = 1_000))
        // 수평 유지 중에는 침묵
        assertNull(guide.onRoll(0.5f, nowMs = 2_000))
    }

    @Test
    fun `hysteresis keeps guidance active between exit and enter thresholds`() {
        val guide = LandscapeGuide()
        guide.onRoll(10f, nowMs = 0)
        // 4° 는 진입(6°) 미만이지만 해제(2.5°) 초과 — 아직 기울어진 상태로 유지(반복 대기)
        assertNull(guide.onRoll(4f, nowMs = 1_000))
        assertEquals(
            LandscapeGuide.ROLL_TURN_RIGHT_UTTERANCE,
            guide.onRoll(4f, nowMs = LandscapeGuide.ROLL_REPEAT_MS),
        )
    }

    @Test
    fun `level phone never triggers roll guidance`() {
        val guide = LandscapeGuide()
        assertNull(guide.onRoll(3f, nowMs = 0))
        assertNull(guide.onRoll(-4f, nowMs = 1_000))
    }

    // ---- 역광 ----

    @Test
    fun `backlight speaks after a sustained streak and respects cooldown`() {
        val guide = LandscapeGuide()
        repeat(LandscapeGuide.BACKLIGHT_STREAK - 1) { index ->
            assertNull(guide.onLuminance(0.10f, 0.40f, nowMs = index * 300L))
        }
        assertEquals(
            LandscapeGuide.BACKLIGHT_UTTERANCE,
            guide.onLuminance(0.10f, 0.40f, nowMs = 1_500),
        )
        // 쿨다운 안에서는 계속 역광이어도 반복하지 않는다
        assertNull(guide.onLuminance(0.10f, 0.40f, nowMs = 2_000))
        assertEquals(
            LandscapeGuide.BACKLIGHT_UTTERANCE,
            guide.onLuminance(0.10f, 0.40f, nowMs = 2_000 + LandscapeGuide.BACKLIGHT_COOLDOWN_MS),
        )
    }

    @Test
    fun `a non-backlit frame resets the streak`() {
        val guide = LandscapeGuide()
        repeat(LandscapeGuide.BACKLIGHT_STREAK - 1) { index ->
            guide.onLuminance(0.10f, 0.40f, nowMs = index * 300L)
        }
        // 밝기만 높고 어두운 영역이 없으면(그냥 밝은 장면) 역광이 아니다 — 스트릭 리셋
        assertNull(guide.onLuminance(0.10f, 0.05f, nowMs = 1_500))
        assertNull(guide.onLuminance(0.10f, 0.40f, nowMs = 1_800))
    }

    // ---- 장면 낭독 ----

    @Test
    fun `scene summary waits out the entry announcement then announces only once`() {
        val guide = LandscapeGuide()
        val delay = LandscapeGuide.SCENE_SUMMARY_DELAY_MS
        // 진입 직후에는 "풍경 모드예요" 안내와 겹치지 않게 대기 (버려짐 방지, 2026-08-28)
        assertNull(guide.sceneSummaryOnce(listOf("나무"), nowMs = 0))
        // 대기 후 빈 프레임은 1회 기회를 소진하지 않는다
        assertNull(guide.sceneSummaryOnce(emptyList(), nowMs = delay))
        assertEquals(
            "나무 2개, 자동차 1개가 보여요.",
            guide.sceneSummaryOnce(listOf("나무", "자동차", "나무"), nowMs = delay),
        )
        assertNull(guide.sceneSummaryOnce(listOf("나무"), nowMs = delay + 1_000))
    }

    @Test
    fun `scene summary caps the number of label kinds`() {
        val labels = listOf("가", "가", "나", "나", "다", "라", "마")
        val summary = LandscapeGuide.sceneSummary(labels)!!
        assertTrue(summary.startsWith("가 2개, 나 2개"))
        // 5번째 종류("마" 또는 정렬상 마지막)는 잘린다
        assertEquals(LandscapeGuide.SCENE_MAX_KINDS - 1, summary.count { it == ',' })
    }

    // ---- 밝기 분포 ----

    @Test
    fun `luminance fractions detect bright and dark regions`() {
        val width = 40
        val height = 30
        val rgb = ByteArray(width * height * 3)
        // 위 절반 = 순백(밝음), 아래 절반 = 거의 검정(어두움)
        for (pixel in 0 until width * height) {
            val value = if (pixel < width * height / 2) 255 else 10
            rgb[pixel * 3] = value.toByte()
            rgb[pixel * 3 + 1] = value.toByte()
            rgb[pixel * 3 + 2] = value.toByte()
        }
        val (bright, dark) = LandscapeGuide.luminanceFractions(rgb, width, height)
        assertTrue("bright=$bright", bright in 0.4f..0.6f)
        assertTrue("dark=$dark", dark in 0.4f..0.6f)
    }

    @Test
    fun `invalid buffer returns zero fractions`() {
        assertEquals(0f to 0f, LandscapeGuide.luminanceFractions(ByteArray(10), 100, 100))
    }
}
