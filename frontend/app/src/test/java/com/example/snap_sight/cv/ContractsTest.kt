package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공개 JSON 계약이 Python `FrameResult.to_dict()` 와 같은 형태인지 고정한다.
 * 이 테스트가 깨지면 ③/④/⑤ 의 파서도 함께 깨진다.
 */
class ContractsTest {

    @Test
    fun `frame result serializes the documented schema`() {
        val result = FrameResult(
            listOf(
                TrackedObject(
                    trackId = 17,
                    label = "person",
                    confidence = 0.94f,
                    bbox = BoundingBox(0.31f, 0.12f, 0.68f, 0.91f),
                    classId = 0,
                ),
                TrackedObject(
                    trackId = 23,
                    label = "dog",
                    confidence = 0.89f,
                    bbox = BoundingBox(0.10f, 0.42f, 0.35f, 0.83f),
                    classId = 41,
                ),
            )
        )

        assertEquals(
            """{"objects":[""" +
                    """{"track_id":17,"label":"person","confidence":0.9400,""" +
                    """"bbox":{"x_min":0.310000,"y_min":0.120000,"x_max":0.680000,"y_max":0.910000}},""" +
                    """{"track_id":23,"label":"dog","confidence":0.8900,""" +
                    """"bbox":{"x_min":0.100000,"y_min":0.420000,"x_max":0.350000,"y_max":0.830000}}""" +
                    """]}""",
            result.toJson(),
        )
    }

    @Test
    fun `empty frame result still emits the objects key`() {
        assertEquals("""{"objects":[]}""", FrameResult.EMPTY.toJson())
    }

    @Test
    fun `labels with a slash survive serialization unescaped`() {
        val json = TrackedObject(1, "cabinet/shelf", 0.5f, BoundingBox(0f, 0f, 1f, 1f)).toJson()
        assertTrue(json.contains(""""label":"cabinet/shelf""""))
    }

    @Test
    fun `clipped drops boxes without area and clamps the rest`() {
        assertNull(BoundingBox.clipped(0.5f, 0.5f, 0.5f, 0.9f))
        assertNull(BoundingBox.clipped(Float.NaN, 0f, 1f, 1f))
        assertNull(BoundingBox.clipped(-0.4f, 0.1f, -0.2f, 0.5f))

        assertEquals(
            BoundingBox(0f, 0.1f, 1f, 0.9f),
            BoundingBox.clipped(-0.3f, 0.1f, 1.7f, 0.9f),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bounding box rejects coordinates outside the unit square`() {
        BoundingBox(0f, 0f, 1.2f, 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a frame cannot contain duplicate track ids`() {
        val box = BoundingBox(0f, 0f, 0.5f, 0.5f)
        FrameResult(
            listOf(
                TrackedObject(1, "person", 0.9f, box),
                TrackedObject(1, "dog", 0.8f, box),
            )
        )
    }

    @Test
    fun `pixel conversion rounds to the nearest pixel`() {
        val pixels = BoundingBox(0.25f, 0.5f, 0.75f, 1f).toPixels(640, 480)
        assertEquals(PixelRect(160, 240, 480, 480), pixels)
    }
}
