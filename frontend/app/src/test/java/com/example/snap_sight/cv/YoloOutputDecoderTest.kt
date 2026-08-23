package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 모델 출력 해석은 **틀려도 예외가 안 나고 조용히 틀린다** — bbox 가 엉뚱한 곳을 가리켜도
 * 앱은 멀쩡히 돌아간다. 그래서 실기기·모델 없이 여기서 고정한다.
 *
 * 실제 배포 체크포인트(`yolo26n-objv1-150.pt`)의 PyTorch 출력 형태는 `(1, 300, 6)` 이다.
 * 즉 NMS-free end-to-end head → [YoloOutputLayout.END_TO_END].
 */
class YoloOutputDecoderTest {

    private val labels = listOf("person", "sneakers", "chair")

    private fun endToEndDecoder(
        detectionSlots: Int = 4,
        maxDetections: Int = 300,
        applyNms: Boolean = true,
    ) = YoloOutputDecoder(
        labels = labels,
        layout = YoloOutputLayout.END_TO_END,
        anchorCount = detectionSlots,
        channelCount = 6,
        minimumConfidence = 0.10f,
        nmsIouThreshold = 0.45f,
        maxDetections = maxDetections,
        applyNms = applyNms,
    )

    /** `[x1, y1, x2, y2, conf, classId]` 행들을 고정 슬롯 텐서로 편다. */
    private fun endToEndTensor(slots: Int, vararg rows: FloatArray): FloatArray {
        val values = FloatArray(slots * 6)
        rows.forEachIndexed { index, row -> row.copyInto(values, index * 6) }
        return values
    }

    @Test
    fun `layout is detected from the tensor shape`() {
        assertEquals(
            YoloOutputLayout.END_TO_END,
            YoloOutputDecoder.layoutFor(intArrayOf(1, 300, 6), classCount = 365),
        )
        assertEquals(
            YoloOutputLayout.CHANNELS_FIRST,
            YoloOutputDecoder.layoutFor(intArrayOf(1, 369, 8400), classCount = 365),
        )
        assertEquals(
            YoloOutputLayout.ANCHORS_FIRST,
            YoloOutputDecoder.layoutFor(intArrayOf(1, 8400, 369), classCount = 365),
        )
        assertNull(YoloOutputDecoder.layoutFor(intArrayOf(1, 25200, 85), classCount = 365))
        assertNull(YoloOutputDecoder.layoutFor(intArrayOf(1, 300), classCount = 365))
    }

    @Test
    fun `letterbox geometry centres a landscape frame`() {
        val geometry = LetterboxGeometry.of(640, 480, 640, 640)
        assertEquals(640, geometry.scaledWidth)
        assertEquals(480, geometry.scaledHeight)
        assertEquals(0, geometry.padX)
        assertEquals(80, geometry.padY)
    }

    @Test
    fun `normalized end-to-end boxes map back through the letterbox padding`() {
        val decoder = endToEndDecoder()
        // 640x480 프레임 → 640x640 입력. 위아래 80px 패딩.
        val geometry = LetterboxGeometry.of(640, 480, 640, 640)

        // 입력 텐서 안에서 가로 중앙 절반, 세로는 패딩 바로 아래부터 240px.
        // 정규화 좌표: x 0.25~0.75, y 80/640=0.125 ~ 320/640=0.5
        val values = endToEndTensor(
            4,
            floatArrayOf(0.25f, 0.125f, 0.75f, 0.5f, 0.9f, 0f),
        )

        val detection = decoder.decode(values, geometry).single()
        assertEquals("person", detection.label)
        assertEquals(0, detection.classId)
        assertEquals(0.9f, detection.confidence, 1e-6f)
        // x 는 패딩이 없으므로 그대로, y 는 패딩을 걷어내고 480 기준으로 다시 정규화된다.
        assertEquals(0.25f, detection.bbox.xMin, 1e-4f)
        assertEquals(0.75f, detection.bbox.xMax, 1e-4f)
        assertEquals(0.0f, detection.bbox.yMin, 1e-4f)
        assertEquals(0.5f, detection.bbox.yMax, 1e-4f)
    }

    @Test
    fun `pixel-unit end-to-end boxes map back identically`() {
        val decoder = endToEndDecoder()
        val geometry = LetterboxGeometry.of(640, 480, 640, 640)

        // 위와 같은 박스를 입력 픽셀 단위로 표현한 것.
        val values = endToEndTensor(
            4,
            floatArrayOf(160f, 80f, 480f, 320f, 0.9f, 0f),
        )

        val detection = decoder.decode(values, geometry).single()
        assertTrue("픽셀 단위로 판별돼야 한다", decoder.coordinatesArePixels == true)
        assertEquals(0.25f, detection.bbox.xMin, 1e-4f)
        assertEquals(0.75f, detection.bbox.xMax, 1e-4f)
        assertEquals(0.0f, detection.bbox.yMin, 1e-4f)
        assertEquals(0.5f, detection.bbox.yMax, 1e-4f)
    }

    @Test
    fun `an empty frame does not lock in the wrong coordinate unit`() {
        val decoder = endToEndDecoder()
        val geometry = LetterboxGeometry.of(640, 480, 640, 640)

        // 검출 0개 — 모든 슬롯이 0 이다. 여기서 "정규화" 로 확정해버리면
        // 이후 픽셀 단위 출력이 전부 좌상단 구석으로 뭉친다.
        assertTrue(decoder.decode(FloatArray(4 * 6), geometry).isEmpty())
        assertNull("판별을 미뤄야 한다", decoder.coordinatesArePixels)

        // 실제 검출이 들어온 다음에야 확정한다.
        val values = endToEndTensor(4, floatArrayOf(160f, 80f, 480f, 320f, 0.9f, 0f))
        assertEquals(1, decoder.decode(values, geometry).size)
        assertEquals(true, decoder.coordinatesArePixels)
    }

    @Test
    fun `low confidence slots are dropped`() {
        val decoder = endToEndDecoder()
        val geometry = LetterboxGeometry.of(640, 640, 640, 640)
        val values = endToEndTensor(
            4,
            floatArrayOf(0.1f, 0.1f, 0.3f, 0.3f, 0.90f, 0f),
            floatArrayOf(0.5f, 0.5f, 0.7f, 0.7f, 0.05f, 1f),
        )

        val detections = decoder.decode(values, geometry)
        assertEquals(1, detections.size)
        assertEquals("person", detections.single().label)
    }

    @Test
    fun `end-to-end output applies class-wise nms when requested`() {
        val decoder = endToEndDecoder()
        val geometry = LetterboxGeometry.of(640, 640, 640, 640)
        // `[1,N,6]`은 tensor layout일 뿐 NMS 완료 증거가 아니다. 배포 export는 nms=false다.
        val values = endToEndTensor(
            4,
            floatArrayOf(0.30f, 0.10f, 0.70f, 0.90f, 0.95f, 0f),
            floatArrayOf(0.35f, 0.10f, 0.75f, 0.90f, 0.90f, 0f),
        )

        val detections = decoder.decode(values, geometry)
        assertEquals(1, detections.size)
        assertEquals(0.95f, detections.single().confidence, 1e-6f)
    }

    @Test
    fun `end-to-end nms keeps overlapping boxes from different classes`() {
        val decoder = endToEndDecoder()
        val geometry = LetterboxGeometry.of(640, 640, 640, 640)
        val values = endToEndTensor(
            4,
            floatArrayOf(0.30f, 0.10f, 0.70f, 0.90f, 0.95f, 0f),
            floatArrayOf(0.35f, 0.10f, 0.75f, 0.90f, 0.90f, 1f),
        )

        assertEquals(listOf("person", "sneakers"), decoder.decode(values, geometry).map { it.label })
    }

    @Test
    fun `end-to-end nms can be disabled for a model that already suppresses duplicates`() {
        val decoder = endToEndDecoder(applyNms = false)
        val geometry = LetterboxGeometry.of(640, 640, 640, 640)
        val values = endToEndTensor(
            4,
            floatArrayOf(0.30f, 0.10f, 0.70f, 0.90f, 0.95f, 0f),
            floatArrayOf(0.35f, 0.10f, 0.75f, 0.90f, 0.90f, 0f),
        )

        assertEquals(2, decoder.decode(values, geometry).size)
    }

    @Test
    fun `out of range class ids are ignored instead of crashing`() {
        val decoder = endToEndDecoder()
        val geometry = LetterboxGeometry.of(640, 640, 640, 640)
        val values = endToEndTensor(
            4,
            floatArrayOf(0.1f, 0.1f, 0.3f, 0.3f, 0.9f, 99f),
            floatArrayOf(0.5f, 0.5f, 0.7f, 0.7f, 0.8f, 2f),
        )

        val detections = decoder.decode(values, geometry)
        assertEquals(1, detections.size)
        assertEquals("chair", detections.single().label)
    }

    @Test
    fun `degenerate boxes are dropped rather than clamped into fake detections`() {
        val decoder = endToEndDecoder()
        val geometry = LetterboxGeometry.of(640, 640, 640, 640)
        val values = endToEndTensor(
            4,
            // 폭 0
            floatArrayOf(0.4f, 0.1f, 0.4f, 0.9f, 0.9f, 0f),
            // 프레임 완전 바깥
            floatArrayOf(1.4f, 1.1f, 1.8f, 1.9f, 0.9f, 0f),
            // 정상
            floatArrayOf(0.1f, 0.1f, 0.3f, 0.3f, 0.8f, 1f),
        )

        val detections = decoder.decode(values, geometry)
        assertEquals(1, detections.size)
        assertEquals("sneakers", detections.single().label)
    }

    // --- v8 스타일 layout (모델을 교체할 경우 대비) -------------------------------

    @Test
    fun `channels-first layout decodes cxcywh with per-class scores`() {
        val anchorCount = 2
        val channelCount = 4 + labels.size
        val decoder = YoloOutputDecoder(
            labels = labels,
            layout = YoloOutputLayout.CHANNELS_FIRST,
            anchorCount = anchorCount,
            channelCount = channelCount,
            minimumConfidence = 0.10f,
            nmsIouThreshold = 0.45f,
            maxDetections = 300,
            applyNms = true,
        )
        val geometry = LetterboxGeometry.of(640, 640, 640, 640)

        // [channel][anchor] 배치. anchor 0 만 유효한 검출.
        val values = FloatArray(channelCount * anchorCount)
        fun set(channel: Int, anchor: Int, value: Float) {
            values[channel * anchorCount + anchor] = value
        }
        set(0, 0, 0.5f)   // centerX
        set(1, 0, 0.5f)   // centerY
        set(2, 0, 0.2f)   // width
        set(3, 0, 0.4f)   // height
        set(4, 0, 0.10f)  // person
        set(5, 0, 0.85f)  // sneakers  ← 최고점
        set(6, 0, 0.05f)  // chair

        val detection = decoder.decode(values, geometry).single()
        assertEquals("sneakers", detection.label)
        assertEquals(1, detection.classId)
        assertEquals(0.85f, detection.confidence, 1e-6f)
        assertEquals(0.4f, detection.bbox.xMin, 1e-4f)
        assertEquals(0.6f, detection.bbox.xMax, 1e-4f)
        assertEquals(0.3f, detection.bbox.yMin, 1e-4f)
        assertEquals(0.7f, detection.bbox.yMax, 1e-4f)
        assertFalse(decoder.coordinatesArePixels!!)
    }

    @Test
    fun `nms removes duplicates for anchor-based layouts`() {
        val anchorCount = 3
        val channelCount = 4 + labels.size
        val decoder = YoloOutputDecoder(
            labels = labels,
            layout = YoloOutputLayout.ANCHORS_FIRST,
            anchorCount = anchorCount,
            channelCount = channelCount,
            minimumConfidence = 0.10f,
            nmsIouThreshold = 0.45f,
            maxDetections = 300,
            applyNms = true,
        )
        val geometry = LetterboxGeometry.of(640, 640, 640, 640)

        val values = FloatArray(anchorCount * channelCount)
        fun writeAnchor(anchor: Int, box: FloatArray, classIndex: Int, score: Float) {
            box.copyInto(values, anchor * channelCount)
            values[anchor * channelCount + 4 + classIndex] = score
        }
        // 같은 객체를 가리키는 앵커 둘 + 멀리 떨어진 객체 하나
        writeAnchor(0, floatArrayOf(0.5f, 0.5f, 0.2f, 0.4f), 0, 0.90f)
        writeAnchor(1, floatArrayOf(0.51f, 0.5f, 0.2f, 0.4f), 0, 0.80f)
        writeAnchor(2, floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f), 0, 0.70f)

        val detections = decoder.decode(values, geometry)
        assertEquals(2, detections.size)
        assertEquals(0.90f, detections[0].confidence, 1e-6f)
        assertEquals(0.70f, detections[1].confidence, 1e-6f)
    }
}
