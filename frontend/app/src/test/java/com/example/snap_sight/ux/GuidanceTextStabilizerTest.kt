package com.example.snap_sight.ux

import com.example.snap_sight.cv.ReadinessBlocker
import org.junit.Assert.assertEquals
import org.junit.Test

class GuidanceTextStabilizerTest {
    @Test
    fun `short predicted and held gaps keep the last confirmed message`() {
        val stabilizer = GuidanceTextStabilizer()

        assertEquals(
            "지금이에요! 화면을 두 번 탭하세요",
            stabilizer.stabilize(
                proposedText = "지금이에요! 화면을 두 번 탭하세요",
                subjectDetected = true,
                blockers = emptySet(),
            ),
        )
        assertEquals(
            "지금이에요! 화면을 두 번 탭하세요",
            stabilizer.stabilize(
                proposedText = "구도를 다시 확인하고 있어요",
                subjectDetected = true,
                blockers = setOf(ReadinessBlocker.PREDICTED),
            ),
        )
        assertEquals(
            "지금이에요! 화면을 두 번 탭하세요",
            stabilizer.stabilize(
                proposedText = "구도를 다시 확인하고 있어요",
                subjectDetected = true,
                blockers = setOf(ReadinessBlocker.HELD),
            ),
        )
    }

    @Test
    fun `fresh movement replaces the latch and stale observation is shown`() {
        val stabilizer = GuidanceTextStabilizer()
        stabilizer.stabilize("지금이에요! 화면을 두 번 탭하세요", true, emptySet())

        assertEquals(
            "카메라를 조금 왼쪽으로 이동해주세요",
            stabilizer.stabilize(
                proposedText = "카메라를 조금 왼쪽으로 이동해주세요",
                subjectDetected = true,
                blockers = setOf(ReadinessBlocker.HORIZONTAL),
            ),
        )
        assertEquals(
            "구도를 다시 확인하고 있어요",
            stabilizer.stabilize(
                proposedText = "구도를 다시 확인하고 있어요",
                subjectDetected = true,
                blockers = setOf(ReadinessBlocker.PREDICTED, ReadinessBlocker.STALE),
            ),
        )
    }

    @Test
    fun `lost target clears the previous confirmed message`() {
        val stabilizer = GuidanceTextStabilizer()
        stabilizer.stabilize("지금이에요! 화면을 두 번 탭하세요", true, emptySet())

        assertEquals(
            "피사체를 찾고 있어요",
            stabilizer.stabilize("피사체를 찾고 있어요", false, emptySet()),
        )
        assertEquals(
            "구도를 다시 확인하고 있어요",
            stabilizer.stabilize(
                proposedText = "구도를 다시 확인하고 있어요",
                subjectDetected = true,
                blockers = setOf(ReadinessBlocker.PREDICTED),
            ),
        )
    }
}
