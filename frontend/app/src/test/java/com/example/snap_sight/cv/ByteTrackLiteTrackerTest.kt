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
    fun `a briefly missed track coasts as predicted then expires`() {
        val tracker = ByteTrackLiteTracker(ByteTrackLiteConfig(coastSeconds = 0.7, lostTrackBufferSeconds = 2.0))
        val box = detection(xMin = 0.1f, yMin = 0.1f, xMax = 0.3f, yMax = 0.5f)

        val seen = tracker.update(listOf(box), timestampS = 0.0, motionHint = null).single()
        assertEquals(false, seen.predicted)

        // 0.2초 뒤 검출이 비어도 같은 ID 가 예측 상자로 이어진다 (confidence 는 마지막 관측값)
        val coasted = tracker.update(emptyList(), timestampS = 0.2, motionHint = null).single()
        assertEquals(seen.trackId, coasted.trackId)
        assertEquals(true, coasted.predicted)
        assertEquals(seen.confidence, coasted.confidence)

        // coastSeconds 를 넘기면 출력에서 빠진다 (track 자체는 버퍼 동안 살아 있어 재획득 가능)
        assertTrue(tracker.update(emptyList(), timestampS = 1.0, motionHint = null).isEmpty())
        val reacquired = tracker.update(listOf(box), timestampS = 1.2, motionHint = null).single()
        assertEquals(seen.trackId, reacquired.trackId)
        assertEquals(false, reacquired.predicted)
    }

    @Test
    fun `coasting is off by default so only observed objects are emitted`() {
        val tracker = ByteTrackLiteTracker()
        tracker.update(listOf(detection(xMin = 0.1f, yMin = 0.1f, xMax = 0.3f, yMax = 0.5f)))
        assertTrue(tracker.update(emptyList()).isEmpty())
    }

    @Test
    fun `predict only advances a track without counting a detector miss`() {
        val tracker = ByteTrackLiteTracker(
            ByteTrackLiteConfig(coastSeconds = 1.0, lostTrackBufferSeconds = 2.0)
        )
        val box = detection(xMin = 0.1f, yMin = 0.1f, xMax = 0.3f, yMax = 0.5f)
        tracker.update(listOf(box), timestampS = 0.0)

        val firstPrediction = tracker.predictOnly(timestampS = 0.1).single()
        val secondPrediction = tracker.predictOnly(timestampS = 0.2).single()
        assertTrue(firstPrediction.predicted)
        assertEquals(100L, firstPrediction.observationAgeMs)
        assertEquals(200L, secondPrediction.observationAgeMs)

        // predict-only 호출은 missedFrames를 늘리지 않으므로 기본 저신뢰 rescue 자격이 남는다.
        val rescued = tracker.update(
            listOf(box.copy(confidence = 0.15f)),
            timestampS = 0.3,
        ).single()
        assertEquals(1, rescued.trackId)
        assertEquals(false, rescued.predicted)
    }

    @Test
    fun `predict only applies camera motion to the propagated box`() {
        val tracker = ByteTrackLiteTracker(ByteTrackLiteConfig(coastSeconds = 1.0))
        tracker.update(
            listOf(detection(xMin = 0.1f, yMin = 0.2f, xMax = 0.3f, yMax = 0.6f)),
            timestampS = 0.0,
        )
        val predicted = tracker.predictOnly(
            timestampS = 0.1,
            motionHint = MotionHint(dx = 0.2f, dy = -0.1f),
        ).single()
        assertEquals(0.3f, predicted.bbox.xMin, 1e-5f)
        assertEquals(0.1f, predicted.bbox.yMin, 1e-5f)
    }

    @Test
    fun `predict only expires a track by elapsed lost buffer time`() {
        val tracker = ByteTrackLiteTracker(
            ByteTrackLiteConfig(coastSeconds = 1.0, lostTrackBufferSeconds = 0.5)
        )
        val box = detection(xMin = 0.1f, yMin = 0.1f, xMax = 0.3f, yMax = 0.5f)
        tracker.update(listOf(box), timestampS = 0.0)
        assertTrue(tracker.predictOnly(timestampS = 0.6).isEmpty())
        assertEquals(2, tracker.update(listOf(box), timestampS = 0.7).single().trackId)
    }

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

    // --- 기능 1-C/1-D (docs/feature-expansion-plan.md) — Python 테스트와 같은 시나리오 ---

    @Test
    fun `motion hint recovers match after fast camera pan`() {
        val tracker = ByteTrackLiteTracker()
        val first = tracker.update(
            listOf(detection(xMin = 0.10f, yMin = 0.40f, xMax = 0.30f, yMax = 0.60f)),
            timestampS = 0.0,
        )
        // 화면 내용이 오른쪽으로 0.4 이동 (IoU 0) — 힌트가 예측 위치를 따라가게 한다
        val observed = tracker.update(
            listOf(detection(xMin = 0.50f, yMin = 0.40f, xMax = 0.70f, yMax = 0.60f)),
            timestampS = 0.1,
            motionHint = MotionHint(dx = 0.4f, dy = 0f),
        )
        assertEquals(1, first.single().trackId)
        assertEquals(1, observed.single().trackId)
    }

    @Test
    fun `without motion hint fast pan creates a new id`() {
        val tracker = ByteTrackLiteTracker()
        tracker.update(
            listOf(detection(xMin = 0.10f, yMin = 0.40f, xMax = 0.30f, yMax = 0.60f)),
            timestampS = 0.0,
        )
        val observed = tracker.update(
            listOf(detection(xMin = 0.50f, yMin = 0.40f, xMax = 0.70f, yMax = 0.60f)),
            timestampS = 0.1,
        )
        assertEquals(2, observed.single().trackId)
    }

    @Test
    fun `time based lost buffer expires by seconds not frames`() {
        val tracker = ByteTrackLiteTracker(ByteTrackLiteConfig(lostTrackBufferSeconds = 1.0))
        val box = detection(xMin = 0.10f, yMin = 0.40f, xMax = 0.30f, yMax = 0.60f)
        tracker.update(listOf(box), timestampS = 0.0)
        // 0.9초 동안 관측 없음 — 프레임이 많이 지나도 시간이 안 지났으면 유지
        for (index in 1..9) tracker.update(emptyList(), timestampS = 0.09 * index)
        val recovered = tracker.update(listOf(box), timestampS = 0.95)
        assertEquals(1, recovered.single().trackId)

        tracker.reset()
        tracker.update(listOf(box), timestampS = 0.0)
        tracker.update(emptyList(), timestampS = 0.5)
        tracker.update(emptyList(), timestampS = 1.2) // 1.0초 초과 → 만료
        val reappeared = tracker.update(listOf(box), timestampS = 1.3)
        assertEquals(2, reappeared.single().trackId)
    }

    @Test
    fun `match expansion recovers track after a gap`() {
        val tracker = ByteTrackLiteTracker(
            ByteTrackLiteConfig(
                lostTrackBufferSeconds = 3.0,
                matchExpansionRatePerSecond = 0.8,
                maxMatchExpansion = 1.0,
            )
        )
        tracker.update(
            listOf(detection(xMin = 0.10f, yMin = 0.40f, xMax = 0.30f, yMax = 0.60f)),
            timestampS = 0.0,
        )
        tracker.update(emptyList(), timestampS = 0.5)
        tracker.update(emptyList(), timestampS = 1.0)
        // 1초 놓친 뒤 살짝 떨어져 재등장 — 확장 없이는 IoU 미달로 새 ID 가 됐을 상황
        val reappeared = tracker.update(
            listOf(detection(xMin = 0.28f, yMin = 0.40f, xMax = 0.48f, yMax = 0.60f)),
            timestampS = 1.1,
        )
        assertEquals(1, reappeared.single().trackId)
    }

    @Test
    fun `match expansion disabled by default keeps existing behavior`() {
        val tracker = ByteTrackLiteTracker(ByteTrackLiteConfig(lostTrackBufferSeconds = 3.0))
        tracker.update(
            listOf(detection(xMin = 0.10f, yMin = 0.40f, xMax = 0.30f, yMax = 0.60f)),
            timestampS = 0.0,
        )
        tracker.update(emptyList(), timestampS = 0.5)
        tracker.update(emptyList(), timestampS = 1.0)
        val reappeared = tracker.update(
            listOf(detection(xMin = 0.28f, yMin = 0.40f, xMax = 0.48f, yMax = 0.60f)),
            timestampS = 1.1,
        )
        assertEquals(2, reappeared.single().trackId)
    }
}
