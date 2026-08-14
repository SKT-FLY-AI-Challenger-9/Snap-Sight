package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloPostprocessorTest {

    // 640x480 원본 → 320 입력: scale=0.5, 세로 여백 (320-240)/2 = 40px
    @Test
    fun letterbox_landscapeSource_padsVertically() {
        val lb = YoloPostprocessor.letterboxFor(640, 480, 320)
        assertEquals(0.5f, lb.scale, 1e-6f)
        assertEquals(0f, lb.padX, 1e-6f)
        assertEquals(40f, lb.padY, 1e-6f)
    }

    @Test
    fun decode_mapsInputPixelBoxBackToNormalizedSourceCoords() {
        // 입력 좌표계에서 letterbox 여백(padY=40) 안쪽 중앙에 있는 박스
        // x: 80..240 → 원본 160..480 → 0.25..0.75
        // y: 60..300 → 원본 (60-40)/0.5=40 .. (300-40)/0.5=520 → 480 초과분은 1.0 클램프
        val rows = arrayOf(floatArrayOf(80f, 60f, 240f, 300f, 0.9f, 41f)) // 41 = cup
        val result = YoloPostprocessor.decode(rows, 320, 640, 480, 0.5f)

        assertEquals(1, result.size)
        val d = result[0]
        assertEquals("cup", d.label)
        assertEquals(0.25f, d.left, 1e-4f)
        assertEquals(0.75f, d.right, 1e-4f)
        assertEquals(40f / 480f, d.top, 1e-4f)
        assertEquals(1.0f, d.bottom, 1e-4f)
    }

    @Test
    fun decode_acceptsNormalizedCoordsFromLitertExport() {
        // 같은 박스를 0..1 정규화(입력 320 기준)로 표현 — 픽셀 테스트와 결과가 같아야 한다
        val rows = arrayOf(floatArrayOf(80f / 320, 60f / 320, 240f / 320, 300f / 320, 0.9f, 41f))
        val result = YoloPostprocessor.decode(rows, 320, 640, 480, 0.5f)

        assertEquals(1, result.size)
        val d = result[0]
        assertEquals("cup", d.label)
        assertEquals(0.25f, d.left, 1e-4f)
        assertEquals(0.75f, d.right, 1e-4f)
        assertEquals(40f / 480f, d.top, 1e-4f)
        assertEquals(1.0f, d.bottom, 1e-4f)
    }

    @Test
    fun decode_dropsLowScoreAndUnknownClassAndDegenerateBox() {
        val rows = arrayOf(
            floatArrayOf(10f, 10f, 100f, 100f, 0.2f, 0f),   // 점수 미달
            floatArrayOf(10f, 10f, 100f, 100f, 0.9f, 999f), // 없는 클래스
            floatArrayOf(100f, 100f, 100f, 100f, 0.9f, 0f), // 넓이 0
        )
        assertTrue(YoloPostprocessor.decode(rows, 320, 640, 480, 0.35f).isEmpty())
    }

    @Test
    fun decode_sortsByScoreDescending() {
        val rows = arrayOf(
            floatArrayOf(10f, 50f, 100f, 150f, 0.6f, 0f),
            floatArrayOf(10f, 50f, 100f, 150f, 0.9f, 15f),
        )
        val result = YoloPostprocessor.decode(rows, 320, 640, 480, 0.35f)
        assertEquals(listOf("cat", "person"), result.map { it.label })
    }

    @Test
    fun cocoLabels_matchTargetSpecObjectLabels() {
        // ai/target_spec_schema.md 의 objectLabel 허용값이 전부 클래스 목록에 있어야 한다
        val schemaLabels = listOf(
            "cup", "bottle", "wine_glass", "bowl", "chair", "couch", "potted_plant",
            "bed", "dining_table", "book", "clock", "vase", "backpack", "handbag",
            "suitcase", "umbrella", "laptop", "cell_phone", "teddy_bear", "cake",
            "bicycle", "car", "dog", "cat", "bird",
        )
        assertEquals(80, CocoLabels.SNAKE_CASE.size)
        schemaLabels.forEach { assertTrue("누락: $it", it in CocoLabels.SNAKE_CASE) }
    }

    @Test
    fun detection_centerAndAreaRatio() {
        val d = Detection("person", 0.9f, left = 0.25f, top = 0.5f, right = 0.75f, bottom = 1.0f)
        assertEquals(0.5f, d.centerX, 1e-6f)
        assertEquals(0.75f, d.centerY, 1e-6f)
        assertEquals(0.25f, d.areaRatio, 1e-6f)
    }
}
