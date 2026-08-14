package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Python `tests/test_cv_tracker.py` 와 같은 시나리오를 Kotlin 포팅본에 적용한다.
 * ID 연속성이 깨지면 ⑥ 의 피드백이 매 프레임 다른 대상을 가리키게 되므로 회귀 방지가 중요하다.
 */
class ByteTrackLiteTrackerTest {

    private fun detection(
        label: String = "person",
        confidence: Float = 0.9f,
        xMin: Float,
        yMin: Float,
        xMax: Float,
        yMax: Float,
        classId: Int? = 0,
    ) = Detection(label, confidence, BoundingBox(xMin, yMin, xMax, yMax), classId)

    @Test
    fun `the same object keeps its track id across frames`() {
        val tracker = ByteTrackLiteTracker()

        val first = tracker.update(listOf(detection(xMin = 0.1f, yMin = 0.1f, xMax = 0.3f, yMax = 0.5f)))
        val second = tracker.update(listOf(detection(xMin = 0.12f, yMin = 0.1f, xMax = 0.32f, yMax = 0.5f)))
        val third = tracker.update(listOf(detection(xMin = 0.14f, yMin = 0.1f, xMax = 0.34f, yMax = 0.5f)))

        assertEquals(1, first.single().trackId)
        assertEquals(1, second.single().trackId)
        assertEquals(1, third.single().trackId)
    }

    @Test
    fun `two objects get distinct ids that survive input reordering`() {
        val tracker = ByteTrackLiteTracker()
        val left = detection(xMin = 0.05f, yMin = 0.1f, xMax = 0.25f, yMax = 0.6f)
        val right = detection(xMin = 0.6f, yMin = 0.1f, xMax = 0.8f, yMax = 0.6f)

        val first = tracker.update(listOf(left, right))
        assertEquals(setOf(1, 2), first.map { it.trackId }.toSet())
        val leftId = first.first { it.bbox.xMin < 0.4f }.trackId
        val rightId = first.first { it.bbox.xMin >= 0.4f }.trackId
        assertNotEquals(leftId, rightId)

        // detector 가 순서를 뒤집어 줘도 ID 는 위치를 따라가야 한다.
        val second = tracker.update(listOf(right, left))
        assertEquals(leftId, second.first { it.bbox.xMin < 0.4f }.trackId)
        assertEquals(rightId, second.first { it.bbox.xMin >= 0.4f }.trackId)
    }

    @Test
    fun `low confidence detections recover a track but never create one`() {
        val tracker = ByteTrackLiteTracker()

        // 저신뢰만 있는 첫 프레임은 track 을 만들지 않는다.
        assertTrue(tracker.update(listOf(detection(confidence = 0.15f, xMin = 0.1f, yMin = 0.1f, xMax = 0.3f, yMax = 0.5f))).isEmpty())

        val created = tracker.update(listOf(detection(confidence = 0.9f, xMin = 0.1f, yMin = 0.1f, xMax = 0.3f, yMax = 0.5f)))
        assertEquals(1, created.single().trackId)

        // 저신뢰 검출은 기존 track 에 연결된다 (공개 threshold 는 파이프라인이 따로 건다).
        val recovered = tracker.update(listOf(detection(confidence = 0.15f, xMin = 0.11f, yMin = 0.1f, xMax = 0.31f, yMax = 0.5f)))
        assertEquals(1, recovered.single().trackId)
        assertEquals(0.15f, recovered.single().confidence, 1e-6f)

        val again = tracker.update(listOf(detection(confidence = 0.9f, xMin = 0.12f, yMin = 0.1f, xMax = 0.32f, yMax = 0.5f)))
        assertEquals(1, again.single().trackId)
    }

    @Test
    fun `a briefly occluded object keeps its id and a long gap gets a new one`() {
        val tracker = ByteTrackLiteTracker(ByteTrackLiteConfig(lostTrackBuffer = 2))
        val box = detection(xMin = 0.4f, yMin = 0.4f, xMax = 0.6f, yMax = 0.8f)

        assertEquals(1, tracker.update(listOf(box)).single().trackId)

        // 버퍼 안에서의 짧은 가림 — 예측만 된 track 은 결과에 나오지 않는다.
        assertTrue(tracker.update(emptyList()).isEmpty())
        assertEquals(1, tracker.update(listOf(box)).single().trackId)

        // 버퍼를 넘기면 만료되고 새 ID 가 발급된다.
        repeat(4) { tracker.update(emptyList()) }
        assertEquals(2, tracker.update(listOf(box)).single().trackId)
    }

    @Test
    fun `label flicker does not switch the reported detection label`() {
        val tracker = ByteTrackLiteTracker()
        val coordinates = floatArrayOf(0.3f, 0.3f, 0.5f, 0.7f)

        repeat(5) {
            tracker.update(
                listOf(detection("person", 0.9f, coordinates[0], coordinates[1], coordinates[2], coordinates[3]))
            )
        }
        // 한 프레임짜리 오분류가 들어와도 그 프레임의 label/confidence 쌍은 그대로 보고한다.
        val flicker = tracker.update(
            listOf(detection("dog", 0.3f, coordinates[0], coordinates[1], coordinates[2], coordinates[3], classId = 41))
        )
        assertEquals("dog", flicker.single().label)

        val recovered = tracker.update(
            listOf(detection("person", 0.9f, coordinates[0], coordinates[1], coordinates[2], coordinates[3]))
        )
        assertEquals(1, recovered.single().trackId)
        assertEquals("person", recovered.single().label)
    }

    @Test
    fun `reset restarts ids from one`() {
        val tracker = ByteTrackLiteTracker()
        val box = detection(xMin = 0.1f, yMin = 0.1f, xMax = 0.3f, yMax = 0.5f)

        tracker.update(listOf(box))
        tracker.update(listOf(box))
        tracker.reset()

        assertEquals(1, tracker.update(listOf(box)).single().trackId)
    }

    @Test
    fun `external timestamps must increase strictly`() {
        val tracker = ByteTrackLiteTracker()
        val box = detection(xMin = 0.1f, yMin = 0.1f, xMax = 0.3f, yMax = 0.5f)

        tracker.update(listOf(box), timestampS = 1.0)
        tracker.update(listOf(box), timestampS = 1.05)

        var rejected = false
        try {
            tracker.update(listOf(box), timestampS = 1.05)
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("같은 timestamp 는 거부돼야 한다", rejected)
    }

    @Test
    fun `a stream cannot mix timestamped and untimestamped frames`() {
        val tracker = ByteTrackLiteTracker()
        val box = detection(xMin = 0.1f, yMin = 0.1f, xMax = 0.3f, yMax = 0.5f)

        tracker.update(listOf(box), timestampS = 1.0)

        var rejected = false
        try {
            tracker.update(listOf(box))
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("timestamp 사용 여부를 섞으면 거부돼야 한다", rejected)
    }
}
