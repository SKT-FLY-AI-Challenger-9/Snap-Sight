package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 모델 없이 detector -> extensions -> tracker -> threshold 순서만 검증한다. */
class CvPipelineTest {

    private class FakeDetector(private val frames: List<List<Detection>>) : Detector {
        var loadCount = 0
        var closeCount = 0
        private var index = 0

        override fun load() {
            loadCount++
        }

        override fun detect(frame: CvFrame): List<Detection> =
            frames.getOrElse(index++) { emptyList() }

        override fun close() {
            closeCount++
        }
    }

    private class FailingDetector : Detector {
        override fun load() = throw ModelUnavailableException("no model")
        override fun detect(frame: CvFrame): List<Detection> = emptyList()
        override fun close() = Unit
    }

    /** person 검출마다 같은 자리에 face 를 하나 더 얹는 가짜 확장. */
    private class FaceStubExtension : DetectionExtension {
        override fun extend(frame: CvFrame, primaryDetections: List<Detection>): List<Detection> =
            primaryDetections
                .filter { it.label == "person" }
                .map { it.copy(label = "face", classId = null) }
    }

    private fun blankFrame(width: Int = 8, height: Int = 8) =
        CvFrame(ByteArray(width * height * 3), width, height)

    private fun detection(label: String, confidence: Float, classId: Int?) = Detection(
        label = label,
        confidence = confidence,
        bbox = BoundingBox(0.2f, 0.2f, 0.4f, 0.6f),
        classId = classId,
    )

    @Test
    fun `output threshold hides low confidence tracks but keeps their ids alive`() {
        val detector = FakeDetector(
            listOf(
                listOf(detection("person", 0.9f, 0)),
                listOf(detection("person", 0.15f, 0)),
                listOf(detection("person", 0.9f, 0)),
            )
        )
        val pipeline = CvPipeline(detector, ByteTrackLiteTracker())
        pipeline.load()

        assertEquals(1, pipeline.process(blankFrame()).objects.single().trackId)
        // 0.15 는 공개 threshold(0.25) 미만이라 결과에서 빠지지만 track 은 유지된다.
        assertTrue(pipeline.process(blankFrame()).isEmpty)
        assertEquals(1, pipeline.process(blankFrame()).objects.single().trackId)
    }

    @Test
    fun `extensions add detections without changing the public schema`() {
        val detector = FakeDetector(listOf(listOf(detection("person", 0.9f, 0))))
        val pipeline = CvPipeline(detector, ByteTrackLiteTracker(), listOf(FaceStubExtension()))
        pipeline.load()

        val labels = pipeline.process(blankFrame()).objects.map { it.label }.toSet()
        assertEquals(setOf("person", "face"), labels)
    }

    @Test
    fun `reset restarts track ids for a new session`() {
        val detector = FakeDetector(List(3) { listOf(detection("person", 0.9f, 0)) })
        val pipeline = CvPipeline(detector, ByteTrackLiteTracker())
        pipeline.load()

        pipeline.process(blankFrame())
        pipeline.process(blankFrame())
        pipeline.reset()
        assertEquals(1, pipeline.process(blankFrame()).objects.single().trackId)
    }

    @Test
    fun `load is idempotent and close releases the detector once`() {
        val detector = FakeDetector(emptyList())
        val pipeline = CvPipeline(detector, ByteTrackLiteTracker())

        pipeline.load()
        pipeline.load()
        assertEquals(1, detector.loadCount)
        assertTrue(pipeline.isLoaded)

        pipeline.close()
        pipeline.close()
        assertEquals(1, detector.closeCount)
        assertFalse(pipeline.isLoaded)
    }

    @Test(expected = IllegalStateException::class)
    fun `process before load fails loudly`() {
        CvPipeline(FakeDetector(emptyList()), ByteTrackLiteTracker()).process(blankFrame())
    }

    @Test(expected = ModelUnavailableException::class)
    fun `a missing model surfaces as ModelUnavailableException`() {
        CvPipeline(FailingDetector(), ByteTrackLiteTracker()).load()
    }
}
