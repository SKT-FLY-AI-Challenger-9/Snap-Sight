package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [EyesClosedScorer.combine] — 얼굴별 눈 열림 확률 → 사진 전체 눈감음 의심도 (순수 계산부). */
class EyesClosedScorerTest {

    @Test
    fun `no faces or no classification yields null so the score is omitted`() {
        assertNull(EyesClosedScorer.combine(emptyList()))
        assertNull(EyesClosedScorer.combine(listOf(null to null)))
    }

    @Test
    fun `closed eyes raise the suspicion score`() {
        // 두 눈 다 감김 (열림 0.05) → 의심도 0.95
        assertEquals(0.95f, EyesClosedScorer.combine(listOf(0.05f to 0.05f))!!, 1e-4f)
        // 두 눈 다 뜸 → 의심도 낮음
        assertEquals(0.05f, EyesClosedScorer.combine(listOf(0.95f to 0.95f))!!, 1e-4f)
    }

    @Test
    fun `one visible open eye keeps the face from being flagged`() {
        // 옆얼굴·윙크 — 한쪽 눈만 크게 열림 → GazeJudge 규약(최댓값)대로 오탐하지 않는다
        assertEquals(0.1f, EyesClosedScorer.combine(listOf(0.9f to 0.1f))!!, 1e-4f)
        assertEquals(0.1f, EyesClosedScorer.combine(listOf(null to 0.9f))!!, 1e-4f)
    }

    @Test
    fun `photo score is the worst face`() {
        // 한 명이라도 감았으면 나쁜 후보 — 최악값 채택
        val score = EyesClosedScorer.combine(
            listOf(
                0.95f to 0.95f, // 뜬 사람
                0.1f to 0.05f,  // 감은 사람
                null to null,   // 분류 불가 — 제외
            )
        )
        assertEquals(0.9f, score!!, 1e-4f)
    }
}
