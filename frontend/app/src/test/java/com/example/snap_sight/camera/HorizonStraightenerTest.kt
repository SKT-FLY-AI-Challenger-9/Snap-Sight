package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [HorizonStraightener] 순수 기하 검증 — 보정 범위(2°~12°)와 부호, 회전 뒤 검은 모서리 없이
 * 남는 최대 내접 크롭 배율 (2026-08-30).
 */
class HorizonStraightenerTest {

    @Test
    fun `tiny and huge rolls are left alone`() {
        assertNull(HorizonStraightener.correctionDegrees(0f))
        assertNull(HorizonStraightener.correctionDegrees(1.9f))
        assertNull(HorizonStraightener.correctionDegrees(-1.9f))
        assertNull(HorizonStraightener.correctionDegrees(12.1f))
        assertNull(HorizonStraightener.correctionDegrees(-40f))
        assertNull(HorizonStraightener.correctionDegrees(Float.NaN))
    }

    @Test
    fun `correction rotates against the roll`() {
        // roll 음수(폰을 오른쪽/시계 방향으로 돌림) → 사진을 시계 방향(양수)으로 되돌린다
        assertEquals(8f, HorizonStraightener.correctionDegrees(-8f)!!, 1e-6f)
        assertEquals(-8f, HorizonStraightener.correctionDegrees(8f)!!, 1e-6f)
        assertEquals(HorizonStraightener.MAX_ROLL_DEG * HorizonStraightener.CORRECTION_SIGN,
            HorizonStraightener.correctionDegrees(HorizonStraightener.MAX_ROLL_DEG)!!, 1e-6f)
    }

    @Test
    fun `landscape grip corrects only the deviation from the nearest 90 degree snap`() {
        // 가로 파지(90° 근처)는 정상 자세 — 90° 자체는 보정 없음, 그로부터의 편차만 되돌린다
        assertNull(HorizonStraightener.correctionDegrees(90f))
        assertEquals(-5f, HorizonStraightener.correctionDegrees(95f)!!, 1e-4f)
        assertEquals(2f, HorizonStraightener.correctionDegrees(88f)!!, 1e-4f)
        assertEquals(-5f, HorizonStraightener.correctionDegrees(-85f)!!, 1e-4f)
        assertNull(HorizonStraightener.correctionDegrees(180f))
    }

    @Test
    fun `text rotation correction uses the snap deviation with a wider limit`() {
        // 서류 모드: 글자 줄이 시계 방향 8° → 반시계(−8°)로 되돌린다 (TEXT_CORRECTION_SIGN = −1)
        assertEquals(-8f * -HorizonStraightener.TEXT_CORRECTION_SIGN, HorizonStraightener.textCorrectionDegrees(8f)!!, 1e-4f)
        assertEquals(-5f * -HorizonStraightener.TEXT_CORRECTION_SIGN, HorizonStraightener.textCorrectionDegrees(95f)!!, 1e-4f)
        // roll 보정 상한(12°)보다 넓다
        assertEquals(18f * HorizonStraightener.TEXT_CORRECTION_SIGN, HorizonStraightener.textCorrectionDegrees(18f)!!, 1e-4f)
        assertNull(HorizonStraightener.textCorrectionDegrees(25f))
        assertNull(HorizonStraightener.textCorrectionDegrees(1f))
        assertNull(HorizonStraightener.textCorrectionDegrees(Float.NaN))
    }

    @Test
    fun `zero angle keeps the full image`() {
        assertEquals(1f, HorizonStraightener.inscribedScale(4000, 3000, 0f), 1e-6f)
    }

    @Test
    fun `inscribed scale matches the closed form for a 4 to 3 image at 10 degrees`() {
        // s = min(w/(w·cos+h·sin), h/(w·sin+h·cos)) = min(0.897, 0.822)
        assertEquals(0.822f, HorizonStraightener.inscribedScale(4000, 3000, 10f), 1e-3f)
        // 부호와 무관
        assertEquals(
            HorizonStraightener.inscribedScale(4000, 3000, 10f),
            HorizonStraightener.inscribedScale(4000, 3000, -10f),
            1e-6f,
        )
    }

    @Test
    fun `portrait image uses the shorter side as the binding constraint`() {
        // 세로 사진(3000×4000)은 가로 사진과 같은 배율 — 종횡비가 뒤집혀도 제한식은 대칭이다
        assertEquals(
            HorizonStraightener.inscribedScale(4000, 3000, 12f),
            HorizonStraightener.inscribedScale(3000, 4000, 12f),
            1e-6f,
        )
        // 12° 에서 약 79% — MAX_ROLL_DEG 의 근거(이 이상은 잘리는 면적이 과함)
        assertEquals(0.79f, HorizonStraightener.inscribedScale(4000, 3000, 12f), 0.01f)
    }

    @Test
    fun `degenerate sizes fall back to full scale`() {
        assertEquals(1f, HorizonStraightener.inscribedScale(0, 3000, 10f), 1e-6f)
        assertEquals(1f, HorizonStraightener.inscribedScale(4000, -1, 10f), 1e-6f)
    }
}
