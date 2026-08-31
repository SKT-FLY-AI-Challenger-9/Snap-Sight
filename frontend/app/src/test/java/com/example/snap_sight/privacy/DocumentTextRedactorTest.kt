package com.example.snap_sight.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DocumentTextRedactor] — 클라우드 LLM 질의 전 식별번호 마스킹 (2026-08-31).
 * 핵심: 주민등록번호는 동의로도 처리 불가(개인정보보호법 §24-2)라 반드시 잡혀야 하고,
 * 날짜·금액처럼 질의 응답에 필요한 숫자는 남아야 한다.
 */
class DocumentTextRedactorTest {

    @Test
    fun `resident registration numbers are masked in every common shape`() {
        for (raw in listOf(
            "주민번호 900101-1234567 입니다",
            "주민번호 900101 - 1234567 입니다",
            "주민번호 9001011234567 입니다",
        )) {
            val result = DocumentTextRedactor.redact(raw)
            assertEquals(raw, "주민번호 [주민등록번호] 입니다", result.text)
            assertEquals(1, result.maskedCount)
        }
        // 외국인등록번호(7번째 자리 5~8)도 같은 규칙으로 잡힌다
        assertEquals(
            "[주민등록번호]",
            DocumentTextRedactor.redact("900101-5234567").text,
        )
    }

    @Test
    fun `phone card licence passport business and email are masked with their own labels`() {
        val result = DocumentTextRedactor.redact(
            "연락처 010-1234-5678, 카드 1234-5678-9012-3456, 면허 12-34-567890-12, " +
                "여권 M12345678, 사업자 123-45-67890, 메일 hong@example.com",
        )
        assertEquals(
            "연락처 [전화번호], 카드 [카드번호], 면허 [운전면허번호], " +
                "여권 [여권번호], 사업자 [사업자등록번호], 메일 [이메일]",
            result.text,
        )
        assertEquals(6, result.maskedCount)
    }

    @Test
    fun `long digit runs like account numbers fall back to the generic label`() {
        assertEquals("계좌 [번호]", DocumentTextRedactor.redact("계좌 110-234-567890").text)
        // 7번째 자리가 0/9면 주민번호 규칙(1~8)에 안 걸리고 일반 번호로 떨어진다
        assertEquals("계좌 [번호]", DocumentTextRedactor.redact("계좌 110234067890").text)
    }

    @Test
    fun `dates amounts and short numbers survive`() {
        val raw = "납부기한 2026-08-31, 금액 1,234,567원, 수량 3개, 문서번호 제2026호"
        val result = DocumentTextRedactor.redact(raw)
        assertEquals(raw, result.text)
        assertEquals(0, result.maskedCount)
        assertFalse(result.anyMasked)
    }

    @Test
    fun `mixed document masks everything sensitive and keeps the rest readable`() {
        val result = DocumentTextRedactor.redact(
            "홍길동 900101-1234567 010-9876-5432 2026-08-31까지 50,000원 납부",
        )
        assertEquals("홍길동 [주민등록번호] [전화번호] 2026-08-31까지 50,000원 납부", result.text)
        assertEquals(2, result.maskedCount)
        assertTrue(result.anyMasked)
    }
}
