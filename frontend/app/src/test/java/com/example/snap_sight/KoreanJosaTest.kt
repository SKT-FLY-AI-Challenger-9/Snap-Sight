package com.example.snap_sight

import org.junit.Assert.assertEquals
import org.junit.Test

/** [KoreanJosa] — 토큰 치환 시 조사 교정 (실기기 2026-08-23: "내 인형가 있어요"). */
class KoreanJosaTest {

    @Test
    fun `batchim final consonant picks the right subject particle`() {
        // 실기기에서 나온 바로 그 문장
        assertEquals(
            "가운데에 내 인형이 있어요.",
            KoreanJosa.replaceTokenWithName("가운데에 local_track_3가 있어요.", "local_track_3", "내 인형"),
        )
        // 받침 없는 이름은 반대 방향 교정
        assertEquals(
            "내 카메라가 보여요",
            KoreanJosa.replaceTokenWithName("local_track_2이 보여요", "local_track_2", "내 카메라"),
        )
    }

    @Test
    fun `person display with honorific suffix works`() {
        assertEquals(
            "유재석님이 웃고 있어요",
            KoreanJosa.replaceTokenWithName("local_track_1가 웃고 있어요", "local_track_1", "유재석님"),
        )
        assertEquals(
            "유재석님은 왼쪽에 있어요",
            KoreanJosa.replaceTokenWithName("local_track_1는 왼쪽에 있어요", "local_track_1", "유재석님"),
        )
    }

    @Test
    fun `various particle pairs are corrected`() {
        assertEquals("내 인형을", KoreanJosa.replaceTokenWithName("T를", "T", "내 인형"))
        assertEquals("내 인형과", KoreanJosa.replaceTokenWithName("T와", "T", "내 인형"))
        assertEquals("내 인형이에요", KoreanJosa.replaceTokenWithName("T예요", "T", "내 인형"))
        assertEquals("내 인형이라는", KoreanJosa.replaceTokenWithName("T라는", "T", "내 인형"))
        // ㄹ받침 + (으)로 예외: "지하철로"
        assertEquals("지하철로", KoreanJosa.replaceTokenWithName("T으로", "T", "지하철"))
        assertEquals("내 인형으로", KoreanJosa.replaceTokenWithName("T로", "T", "내 인형"))
    }

    @Test
    fun `no particle or non-particle text is left untouched`() {
        assertEquals(
            "내 인형 옆에 다른 인형이 있어요",
            KoreanJosa.replaceTokenWithName("T 옆에 다른 인형이 있어요", "T", "내 인형"),
        )
        // 조사 목록 밖("의")은 그대로 둔다 — 받침과 무관한 조사
        assertEquals("내 인형의 팔", KoreanJosa.replaceTokenWithName("T의 팔", "T", "내 인형"))
    }

    @Test
    fun `non-hangul names keep the model's original particle`() {
        // 받침 판단 불가 — 교정하지 않고 원문 유지
        assertEquals("iPhone가 있어요", KoreanJosa.replaceTokenWithName("T가 있어요", "T", "iPhone"))
    }

    @Test
    fun `multiple occurrences are all replaced`() {
        assertEquals(
            "내 인형이 보이고, 내 인형은 크다",
            KoreanJosa.replaceTokenWithName("T가 보이고, T는 크다", "T", "내 인형"),
        )
    }
}
