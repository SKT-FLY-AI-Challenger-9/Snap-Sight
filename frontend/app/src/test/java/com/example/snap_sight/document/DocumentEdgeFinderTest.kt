package com.example.snap_sight.document

import com.example.snap_sight.ux.DocumentQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DocumentEdgeFinder] — 서류 외곽 v2 스트립 직선 피팅 (2026-08-31). 합성 루마 이미지로 검증한다:
 * 대비가 있으면 네 변 직선을 찾고(기울어진 변이면 기울기까지), 대비가 없으면 변별로 null(글자
 * 폴백), 서류 너머의 더 먼 경계(책상 모서리)는 글자에 가까운 변을 우선해 배제하며, 부분 가림은
 * 이상치 스트립 제거로 견딘다.
 */
class DocumentEdgeFinderTest {

    private val width = 320
    private val height = 240

    /** [background] 위에 밝기 [paper] 인 사각형(픽셀 좌표)을 그린 루마. */
    private fun canvas(
        background: Int,
        paper: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): IntArray {
        val luma = IntArray(width * height) { background }
        for (y in top until bottom) {
            for (x in left until right) {
                luma[y * width + x] = paper
            }
        }
        return luma
    }

    // 서류 픽셀 상자 (60,40)-(260,200), 글자 상자는 그 안쪽 (정규화)
    private val textLeft = 100f / width
    private val textTop = 80f / height
    private val textRight = 220f / width
    private val textBottom = 160f / height

    private fun find(luma: IntArray) = DocumentEdgeFinder.find(
        luma, width, height, textLeft, textTop, textRight, textBottom,
    )

    @Test
    fun `high contrast paper yields all four edges near the true bounds`() {
        val edges = find(canvas(background = 40, paper = 200, left = 60, top = 40, right = 260, bottom = 200))
        assertEquals(4, edges.count)
        assertEquals(60f / width, edges.left!!.mid, 3f / width)
        assertEquals(40f / height, edges.top!!.mid, 3f / height)
        assertEquals(260f / width, edges.right!!.mid, 3f / width)
        assertEquals(200f / height, edges.bottom!!.mid, 3f / height)
    }

    @Test
    fun `slanted edges yield lines whose intersections recover the trapezoid corners`() {
        // 사다리꼴 서류: TL(80,40) TR(240,40) BR(260,200) BL(60,200) — 좌우 변이 기울어짐
        val luma = IntArray(width * height) { 40 }
        for (y in 40 until 200) {
            val xl = 80 - (y - 40) / 8   // 80 → 60
            val xr = 240 + (y - 40) / 8  // 240 → 260
            for (x in xl until xr) luma[y * width + x] = 200
        }
        val edges = find(luma)
        assertEquals(4, edges.count)
        val quad = DocumentQuad.from(
            left = edges.left!!, top = edges.top!!, right = edges.right!!, bottom = edges.bottom!!,
        )
        assertNotNull(quad)
        quad!!
        assertEquals(80f / width, quad.tl.x, 5f / width)
        assertEquals(240f / width, quad.tr.x, 5f / width)
        assertEquals(260f / width, quad.br.x, 5f / width)
        assertEquals(60f / width, quad.bl.x, 5f / width)
        // 윗변(160px)이 아랫변(200px)보다 짧다 → 수렴비 ≈ 0.8
        assertEquals(0.8f, quad.verticalConvergence, 0.06f)
    }

    @Test
    fun `no contrast yields no edges`() {
        val edges = find(canvas(background = 200, paper = 205, left = 60, top = 40, right = 260, bottom = 200))
        assertEquals(0, edges.count)
    }

    @Test
    fun `a weaker boundary inside the card loses to the stronger true edge outside`() {
        // 실기기 2026-08-31: 신분증 안쪽 무늬(사진 테두리·색 띠) 경계를 오른쪽 변으로 잡아 사진이
        // 잘렸다. 안쪽 경계는 그 바깥이 여전히 종이색이므로, 더 바깥의 진짜 테두리가 이겨야 한다.
        val luma = canvas(background = 40, paper = 200, left = 60, top = 40, right = 260, bottom = 200)
        for (y in 45 until 195) {
            for (x in 230 until 233) luma[y * width + x] = 80 // 카드 안쪽 세로 띠
        }
        val edges = find(luma)
        assertNotNull(edges.right)
        assertEquals(260f / width, edges.right!!.mid, 3f / width)
    }

    @Test
    fun `an edge beyond the paper is ignored in favour of the nearer paper edge`() {
        // 서류 왼쪽 변(60) 너머에 더 어두운 책상 경계(x=20)가 있어도 글자에 가까운 변을 고른다
        val luma = canvas(background = 90, paper = 200, left = 60, top = 40, right = 260, bottom = 200)
        for (y in 0 until height) {
            for (x in 0 until 20) luma[y * width + x] = 10
        }
        val edges = find(luma)
        assertNotNull(edges.left)
        assertEquals(60f / width, edges.left!!.mid, 3f / width)
    }

    @Test
    fun `a partly covered edge is fitted from the remaining strips`() {
        // 위 변의 왼쪽 1/3 스트립을 손가락(서류와 같은 밝기)이 가려도 나머지 스트립으로 직선을 세운다
        val luma = canvas(background = 40, paper = 200, left = 60, top = 40, right = 260, bottom = 200)
        for (y in 20 until 40) {
            for (x in 100 until 140) luma[y * width + x] = 200 // 글자 구간 왼쪽 스트립 위를 서류 밝기로
        }
        val edges = find(luma)
        assertNotNull(edges.top)
        // 가려진 스트립(피크 ~20)은 이상치로 버려지고 나머지가 40 을 가리킨다
        assertEquals(40f / height, edges.top!!.mid, 3f / height)
        assertTrue(kotlin.math.abs(edges.top!!.at1 - edges.top!!.at0) < 4f / height) // 기울기 오염 없음
    }

    @Test
    fun `only sides with contrast are reported`() {
        // 서류가 프레임 오른쪽 밖으로 이어짐 — 오른쪽 변 없음(경계 제외 구간뿐)
        val edges = find(canvas(background = 40, paper = 200, left = 60, top = 40, right = width, bottom = 200))
        assertNull(edges.right)
        assertNotNull(edges.left)
        assertNotNull(edges.top)
        assertNotNull(edges.bottom)
    }

    @Test
    fun `search span too narrow returns null`() {
        // 글자 상자가 프레임을 거의 채우면 검색 구간이 없어 판단하지 않는다
        val edges = DocumentEdgeFinder.find(
            canvas(background = 40, paper = 200, left = 2, top = 2, right = width - 2, bottom = height - 2),
            width, height,
            textLeft = 4f / width, textTop = 4f / height,
            textRight = (width - 4f) / width, textBottom = (height - 4f) / height,
        )
        assertEquals(0, edges.count)
    }
}
