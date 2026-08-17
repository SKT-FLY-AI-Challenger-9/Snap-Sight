package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 순수 계산부([FrameScorer.blurScoreOfLuma])만 검증 — 비트맵 디코딩은 계측 테스트 영역. */
class FrameScorerTest {

    private fun flat(width: Int, height: Int, value: Float) =
        FloatArray(width * height) { value }

    private fun checkerboard(width: Int, height: Int) = FloatArray(width * height) { i ->
        val x = i % width
        val y = i / width
        if ((x + y) % 2 == 0) 255f else 0f
    }

    @Test
    fun flatImageIsMaximallyBlurry() {
        // 경계가 전혀 없는 프레임 → 라플라시안 응답 0 → 블러 의심도 최대
        assertEquals(1f, FrameScorer.blurScoreOfLuma(flat(32, 32, 128f), 32, 32), 1e-6f)
    }

    @Test
    fun checkerboardIsSharp() {
        // 픽셀 단위 경계 → 분산이 기준치를 훨씬 넘음 → 블러 의심도 0
        assertEquals(0f, FrameScorer.blurScoreOfLuma(checkerboard(32, 32), 32, 32), 1e-6f)
    }

    @Test
    fun mildTextureScoresBetweenExtremes() {
        // 대비 4 의 미세 체커보드: 라플라시안 ±16, 분산 256 → 기준(300) 미만이라 0 과 1 사이
        // (참고: 선형 그라데이션은 2차 미분이 0 이라 이 지표에서는 "흐림"이 정답)
        val width = 32
        val height = 32
        val mild = FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if ((x + y) % 2 == 0) 130f else 126f
        }
        val score = FrameScorer.blurScoreOfLuma(mild, width, height)
        assertTrue("score=$score", score > 0f && score < 1f)
    }

    @Test
    fun degenerateSizesAreWorstCase() {
        assertEquals(1f, FrameScorer.blurScoreOfLuma(flat(2, 2, 128f), 2, 2), 1e-6f)
        assertEquals(1f, FrameScorer.blurScoreOfLuma(FloatArray(4), 32, 32), 1e-6f)
    }

    @Test
    fun sharperImageScoresLowerThanBlurrier() {
        // 같은 패턴이라도 대비가 클수록(=더 선명) 점수가 낮아야 한다.
        // 흐린 쪽은 대비 2 (라플라시안 ±8, 분산 64 < 기준 300) 로 잡아 포화를 피한다.
        val width = 32
        val height = 32
        val strong = checkerboard(width, height)
        val weak = FloatArray(width * height) { i -> if (strong[i] > 0f) 129f else 127f }
        val strongScore = FrameScorer.blurScoreOfLuma(strong, width, height)
        val weakScore = FrameScorer.blurScoreOfLuma(weak, width, height)
        assertTrue("sharp=$strongScore blurry=$weakScore", strongScore < weakScore)
    }
}
