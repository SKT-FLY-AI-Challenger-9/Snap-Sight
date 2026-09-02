package com.example.snap_sight.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서류 결과 1단계 (2026-08-30) — 읽기 순서 정렬, 요약, 금액·날짜·단어 찾기, 민감 번호 마스킹,
 * 음성 명령 파싱을 검증한다.
 */
class DocumentTextAnalysisTest {

    private val receipt = DocumentText(
        listOf(
            "스냅마트 영수증",
            "2026.08.30 14:02",
            "아메리카노 4,500원",
            "샌드위치 6,800원",
            "합계 11,300원",
            "카드 1234-5678-9012-3456",
            "유효기간 9월 15일까지",
        ),
    )

    @Test
    fun `lines are ordered top to bottom then left to right within a row`() {
        val text = DocumentText.fromRecognized(
            listOf(
                RecognizedLine(top = 100f, left = 400f, height = 30f, text = "오른쪽 칸"),
                RecognizedLine(top = 10f, left = 10f, height = 30f, text = "제목"),
                RecognizedLine(top = 108f, left = 10f, height = 30f, text = "왼쪽 칸"), // 같은 행(8px 차이)
                RecognizedLine(top = 200f, left = 10f, height = 30f, text = "  "), // 빈 줄은 버린다
                RecognizedLine(top = 300f, left = 10f, height = 30f, text = "마지막"),
            ),
        )
        assertEquals(listOf("제목", "왼쪽 칸", "오른쪽 칸", "마지막"), text.lines)
    }

    @Test
    fun `summary takes leading lines up to the character budget`() {
        val summary = receipt.summary(maxChars = 30)
        assertEquals("스냅마트 영수증 2026.08.30 14:02", summary)
        assertTrue(receipt.fullText.startsWith("스냅마트 영수증 2026.08.30"))
    }

    @Test
    fun `amount and date lines are found`() {
        assertEquals(
            listOf("아메리카노 4,500원", "샌드위치 6,800원", "합계 11,300원"),
            receipt.amountLines(),
        )
        assertEquals(listOf("2026.08.30 14:02", "유효기간 9월 15일까지"), receipt.dateLines())
        assertEquals(listOf("2026년 8월 30일"), DocumentText(listOf("2026년 8월 30일")).dateLines())
    }

    @Test
    fun `keyword search ignores spaces and case`() {
        assertEquals(listOf("합계 11,300원"), receipt.linesContaining("합 계"))
        assertEquals(listOf("아메리카노 4,500원"), receipt.linesContaining("아메리카노"))
        assertTrue(receipt.linesContaining("없는단어").isEmpty())
    }

    @Test
    fun `sensitive numbers are masked before speaking`() {
        assertEquals("주민번호 900101-1******", DocumentText.maskSensitive("주민번호 900101-1234567"))
        assertEquals("900101-2******", DocumentText.maskSensitive("900101 2345678"))
        assertEquals("카드 ****-****-****-3456", DocumentText.maskSensitive("카드 1234-5678-9012-3456"))
        // 전화번호·일반 숫자는 건드리지 않는다
        assertEquals("010-1234-5678 합계 11,300원", DocumentText.maskSensitive("010-1234-5678 합계 11,300원"))
    }

    @Test
    fun `answers are built per command and masked`() {
        assertEquals("금액은 아메리카노 4,500원. 샌드위치 6,800원. 합계 11,300원 이에요.", receipt.answer(DocumentVoiceCommand.Amounts))
        assertEquals("날짜는 2026.08.30 14:02. 유효기간 9월 15일까지 이에요.", receipt.answer(DocumentVoiceCommand.Dates))
        assertEquals("합계: 합계 11,300원", receipt.answer(DocumentVoiceCommand.Find("합계")))
        assertEquals("\"주소\" 관련 내용을 찾지 못했어요.", receipt.answer(DocumentVoiceCommand.Find("주소")))
        assertEquals("카드: 카드 ****-****-****-3456", receipt.answer(DocumentVoiceCommand.Find("카드")))
        assertTrue(receipt.answer(DocumentVoiceCommand.ReadAll).startsWith("스냅마트 영수증"))
        assertEquals("금액을 찾지 못했어요.", DocumentText(listOf("안녕")).answer(DocumentVoiceCommand.Amounts))
    }

    @Test
    fun `read all is capped`() {
        val long = DocumentText(List(200) { "아주 긴 줄 번호 $it 입니다" })
        val answer = long.answer(DocumentVoiceCommand.ReadAll)
        assertTrue(answer.endsWith(" 이하는 생략할게요."))
        assertTrue(answer.length <= DocumentText.READ_ALL_MAX_CHARS + 20)
    }

    @Test
    fun `voice commands are parsed by priority`() {
        assertEquals(DocumentVoiceCommand.Summary, DocumentVoiceCommand.parse("요약해서 읽어줘"))
        assertEquals(DocumentVoiceCommand.ReadAll, DocumentVoiceCommand.parse("전부 읽어줘"))
        assertEquals(DocumentVoiceCommand.ReadAll, DocumentVoiceCommand.parse("다 읽어 줘"))
        assertEquals(DocumentVoiceCommand.Amounts, DocumentVoiceCommand.parse("얼마야"))
        assertEquals(DocumentVoiceCommand.Amounts, DocumentVoiceCommand.parse("합계 알려줘"))
        assertEquals(DocumentVoiceCommand.Dates, DocumentVoiceCommand.parse("언제까지야"))
        assertEquals(DocumentVoiceCommand.Find("주소"), DocumentVoiceCommand.parse("주소 찾아줘"))
        assertEquals(DocumentVoiceCommand.Find("발급기관"), DocumentVoiceCommand.parse("발급기관이 뭐야"))
        assertEquals(DocumentVoiceCommand.Find("성명"), DocumentVoiceCommand.parse("성명은 알려줘"))
        assertEquals(DocumentVoiceCommand.None, DocumentVoiceCommand.parse("아니 괜찮아"))
        assertEquals(DocumentVoiceCommand.None, DocumentVoiceCommand.parse("  "))
    }
}
