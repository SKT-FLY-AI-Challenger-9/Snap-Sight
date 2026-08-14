package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 입력 채널 배치를 틀리면 예외가 아니라 **색이 뒤섞인 입력**이 모델에 들어간다.
 * 검출이 이상해질 뿐 앱은 멀쩡히 돌아가므로 여기서 고정한다.
 *
 * 실제 배포 export(ultralytics 8.4.120, LiteRT-Torch 경로)의 입력은 `[1, 3, 640, 640]` = NCHW 다.
 */
class InputTensorSpecTest {

    @Test
    fun `nchw is detected from the deployed export shape`() {
        val spec = InputTensorSpec.of(intArrayOf(1, 3, 640, 640))!!
        assertEquals(InputTensorLayout.NCHW, spec.layout)
        assertEquals(640, spec.width)
        assertEquals(640, spec.height)
    }

    @Test
    fun `nhwc is detected and keeps width and height distinct`() {
        val spec = InputTensorSpec.of(intArrayOf(1, 480, 640, 3))!!
        assertEquals(InputTensorLayout.NHWC, spec.layout)
        assertEquals(640, spec.width)
        assertEquals(480, spec.height)
    }

    @Test
    fun `nchw keeps width and height distinct`() {
        val spec = InputTensorSpec.of(intArrayOf(1, 3, 480, 640))!!
        assertEquals(640, spec.width)
        assertEquals(480, spec.height)
    }

    @Test
    fun `unreadable shapes are rejected instead of guessed`() {
        assertNull(InputTensorSpec.of(intArrayOf(1, 640, 640)))
        assertNull(InputTensorSpec.of(intArrayOf(2, 3, 640, 640)))
        assertNull(InputTensorSpec.of(intArrayOf(1, 4, 640, 640)))
    }

    @Test
    fun `nhwc interleaves channels per pixel`() {
        val spec = InputTensorSpec(InputTensorLayout.NHWC, width = 4, height = 2)
        // 픽셀 0 → 0,1,2 / 픽셀 1 → 3,4,5
        assertEquals(0, spec.indexOf(pixelIndex = 0, channel = 0))
        assertEquals(1, spec.indexOf(pixelIndex = 0, channel = 1))
        assertEquals(2, spec.indexOf(pixelIndex = 0, channel = 2))
        assertEquals(3, spec.indexOf(pixelIndex = 1, channel = 0))
        assertEquals(23, spec.indexOf(pixelIndex = 7, channel = 2))
    }

    @Test
    fun `nchw separates channels into planes`() {
        val spec = InputTensorSpec(InputTensorLayout.NCHW, width = 4, height = 2)
        val planeSize = 8
        // R 평면 전체 → G 평면 전체 → B 평면 전체
        assertEquals(0, spec.indexOf(pixelIndex = 0, channel = 0))
        assertEquals(planeSize, spec.indexOf(pixelIndex = 0, channel = 1))
        assertEquals(2 * planeSize, spec.indexOf(pixelIndex = 0, channel = 2))
        assertEquals(1, spec.indexOf(pixelIndex = 1, channel = 0))
        assertEquals(2 * planeSize + 7, spec.indexOf(pixelIndex = 7, channel = 2))
    }

    @Test
    fun `both layouts cover every element exactly once`() {
        for (layout in InputTensorLayout.entries) {
            val spec = InputTensorSpec(layout, width = 5, height = 3)
            val total = spec.pixelCount * 3
            val seen = BooleanArray(total)
            for (pixelIndex in 0 until spec.pixelCount) {
                for (channel in 0 until 3) {
                    val index = spec.indexOf(pixelIndex, channel)
                    assertEquals("$layout: 인덱스 $index 가 범위를 벗어남", true, index in 0 until total)
                    assertEquals("$layout: 인덱스 $index 가 중복됨", false, seen[index])
                    seen[index] = true
                }
            }
            assertEquals("$layout: 빠진 인덱스 없음", total, seen.count { it })
        }
    }
}
