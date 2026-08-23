package com.example.snap_sight.ux

import com.example.snap_sight.cv.BoundingBox
import com.example.snap_sight.cv.TrackedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CaptureAnnouncementBuilderTest {
    private fun item(
        id: Int,
        label: String,
        confidence: Float = 0.9f,
        box: BoundingBox = BoundingBox(0.1f, 0.1f, 0.5f, 0.6f),
        predicted: Boolean = false,
    ) = TrackedObject(id, label, confidence, box, predicted = predicted,
        observationAgeMs = if (predicted) 100L else 0L)

    @Test
    fun staleFactsFallBackButOneFreshYoloObservationIsEnough() {
        val objectList = listOf(item(1, "person"))
        assertEquals(
            CaptureAnnouncementBuilder.FALLBACK,
            CaptureAnnouncementBuilder.build(
                CaptureAnnouncementBuilder.Input(objectList, stableFrames = mapOf(1 to 4), sourceAgeMs = 1_501L)
            )
        )
        assertEquals(
            "촬영했어요. 사람 한 명을 확인했어요.",
            CaptureAnnouncementBuilder.build(
                CaptureAnnouncementBuilder.Input(objectList, stableFrames = mapOf(1 to 1), sourceAgeMs = 20L)
            )
        )
    }

    @Test
    fun freshYoloResultSurvivesAThermallyThrottledKeyframeGap() {
        val text = CaptureAnnouncementBuilder.build(
            CaptureAnnouncementBuilder.Input(
                objects = listOf(item(1, "laptop", confidence = 0.5f)),
                sourceAgeMs = 900L,
            )
        )

        assertEquals("촬영했어요. 노트북을 확인했어요.", text)
    }

    @Test
    fun rendersVerifiedPeopleAndObjectsNaturally() {
        val text = CaptureAnnouncementBuilder.build(
            CaptureAnnouncementBuilder.Input(
                objects = listOf(
                    item(1, "person", box = BoundingBox(0.05f, 0.1f, 0.42f, 0.8f)),
                    item(2, "person", box = BoundingBox(0.55f, 0.1f, 0.92f, 0.8f)),
                    item(3, "laptop"),
                ),
                identities = mapOf(1 to "민수"),
                registeredPeople = setOf("민수"),
                stableFrames = mapOf(1 to 4, 2 to 3, 3 to 3),
                sourceAgeMs = 30L,
            )
        )
        assertEquals("촬영했어요. 민수님, 다른 사람 한 명과 노트북을 확인했어요.", text)
        assertFalse(text.contains("person"))
    }

    @Test
    fun neverSpeaksUnknownRawLabels() {
        val text = CaptureAnnouncementBuilder.build(
            CaptureAnnouncementBuilder.Input(
                objects = listOf(item(1, "unknown/raw-label")),
                stableFrames = mapOf(1 to 3),
                sourceAgeMs = 0L,
            )
        )
        assertEquals(CaptureAnnouncementBuilder.FALLBACK, text)
    }

    @Test
    fun removesOverlappingDuplicatesAndCounts() {
        val text = CaptureAnnouncementBuilder.build(
            CaptureAnnouncementBuilder.Input(
                objects = listOf(
                    item(1, "cup", box = BoundingBox(0.1f, 0.1f, 0.4f, 0.5f)),
                    item(2, "cup", 0.8f, BoundingBox(0.12f, 0.12f, 0.42f, 0.52f)),
                    item(3, "cup", 0.85f, BoundingBox(0.6f, 0.1f, 0.9f, 0.5f)),
                ),
                stableFrames = mapOf(1 to 3, 2 to 3, 3 to 3),
                sourceAgeMs = 10L,
            )
        )
        assertEquals("촬영했어요. 컵 두 개를 확인했어요.", text)
    }

    @Test
    fun usesTheExactObjects365AssetLabelsForCommonObjects() {
        val text = CaptureAnnouncementBuilder.build(
            CaptureAnnouncementBuilder.Input(
                objects = listOf(item(1, "tv"), item(2, "handbag"), item(3, "bowl")),
                sourceAgeMs = 10L,
            )
        )

        assertEquals("촬영했어요. 텔레비전, 가방과 그릇을 확인했어요.", text)
    }
}
