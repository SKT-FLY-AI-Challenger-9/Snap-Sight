// 이 파일: 서류 모드 1단계(2026-08-30) — 촬영본 OCR 결과를 "읽어줘·요약·금액/날짜/단어 찾기"로
// 답하는 순수 로직. LLM 없이 규칙만으로, 전부 온디바이스. 신분증 번호류는 낭독 전에 반드시
// [DocumentText.maskSensitive] 로 가린다(모델·서버에 맡기지 않고 코드로 강제). android.* 의존 없음.
package com.example.snap_sight.ux

/** OCR 한 줄 — 프레임/사진 좌표(픽셀이든 정규화든 같은 단위면 됨)와 텍스트. */
data class RecognizedLine(val top: Float, val left: Float, val height: Float, val text: String)

/** 서류 본문 — 읽기 순서로 정렬된 줄들. */
data class DocumentText(val lines: List<String>) {

    val isEmpty: Boolean get() = lines.all { it.isBlank() }

    /** 줄을 공백으로 이어 붙인 전체 본문. */
    val fullText: String get() = lines.filter { it.isNotBlank() }.joinToString(" ")

    /** 앞에서부터 [maxChars] 안에 들어오는 줄까지 — 촬영 직후 "무슨 서류인지" 감을 주는 용도. */
    fun summary(maxChars: Int = SUMMARY_MAX_CHARS): String {
        val out = StringBuilder()
        for (line in lines) {
            if (line.isBlank()) continue
            if (out.isNotEmpty() && out.length + line.length + 1 > maxChars) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(line.trim())
            if (out.length >= maxChars) break
        }
        return out.toString()
    }

    /** 금액이 들어 있는 줄들 (예: "합계 12,300원", "₩5000"). */
    fun amountLines(): List<String> = lines.filter { AMOUNT_PATTERN.containsMatchIn(it) }

    /** 날짜가 들어 있는 줄들 (2026.08.30 / 2026-08-30 / 2026년 8월 30일 / 8월 30일). */
    fun dateLines(): List<String> = lines.filter { DATE_PATTERN.containsMatchIn(it) }

    /** [keyword] 가 들어 있는 줄들 — 공백·대소문자를 무시하고 비교한다. */
    fun linesContaining(keyword: String): List<String> {
        val needle = keyword.replace(" ", "").lowercase()
        if (needle.isBlank()) return emptyList()
        return lines.filter { it.replace(" ", "").lowercase().contains(needle) }
    }

    /** 음성 명령 → 낭독할 답. 민감 번호 마스킹은 여기서 한 번에 한다. */
    fun answer(command: DocumentVoiceCommand): String = maskSensitive(
        when (command) {
            DocumentVoiceCommand.ReadAll -> {
                val text = fullText
                if (text.length <= READ_ALL_MAX_CHARS) text
                else text.take(READ_ALL_MAX_CHARS) + " 이하는 생략할게요."
            }
            DocumentVoiceCommand.Summary -> summary()
            DocumentVoiceCommand.Amounts -> amountLines().let {
                if (it.isEmpty()) "금액을 찾지 못했어요." else "금액은 ${it.joinToString(". ")} 이에요."
            }
            DocumentVoiceCommand.Dates -> dateLines().let {
                if (it.isEmpty()) "날짜를 찾지 못했어요." else "날짜는 ${it.joinToString(". ")} 이에요."
            }
            is DocumentVoiceCommand.Find -> linesContaining(command.keyword).let {
                if (it.isEmpty()) "\"${command.keyword}\" 관련 내용을 찾지 못했어요."
                else "${command.keyword}: ${it.take(FIND_MAX_LINES).joinToString(". ")}"
            }
            DocumentVoiceCommand.None -> ""
        },
    )

    companion object {
        const val SUMMARY_MAX_CHARS = 160
        const val READ_ALL_MAX_CHARS = 1_200
        const val FIND_MAX_LINES = 5

        private val AMOUNT_PATTERN = Regex("""₩\s?[\d,]+|\d{1,3}(?:,\d{3})+\s?원?|\d+\s?원""")
        private val DATE_PATTERN = Regex(
            """\d{4}\s?[.\-/년]\s?\d{1,2}\s?[.\-/월]\s?\d{1,2}\s?일?|\d{1,2}\s?월\s?\d{1,2}\s?일""",
        )

        // 주민등록번호(앞 6 + 뒤 7, 뒤 첫 자리 1~8) — 뒷자리는 첫 자리만 남기고 가린다
        private val RRN_PATTERN = Regex("""(\d{6})\s?-?\s?([1-8])(\d{6})""")
        // 카드번호 4-4-4-4 — 마지막 네 자리만 남긴다
        private val CARD_PATTERN = Regex("""\b(\d{4})[\s-](\d{4})[\s-](\d{4})[\s-](\d{4})\b""")

        /**
         * 낭독 전 민감 번호 가리기 — 주민등록번호 뒷자리, 카드번호 앞 12자리. 신분증은 서버로도
         * 모델로도 보내지 않지만, 옆 사람이 들을 수 있는 음성에는 더더욱 내보내지 않는다.
         */
        fun maskSensitive(text: String): String {
            var out = RRN_PATTERN.replace(text) { m -> "${m.groupValues[1]}-${m.groupValues[2]}******" }
            out = CARD_PATTERN.replace(out) { m -> "****-****-****-${m.groupValues[4]}" }
            return out
        }

        /**
         * OCR 줄들을 읽기 순서로 — 위에서 아래로, 같은 행(위쪽 좌표 차이가 줄 높이의 절반 미만)
         * 안에서는 왼쪽에서 오른쪽으로. ML Kit 블록 순서는 다단 서류에서 뒤섞이는 일이 있다.
         */
        fun fromRecognized(recognized: List<RecognizedLine>): DocumentText {
            val byTop = recognized.filter { it.text.isNotBlank() }.sortedBy { it.top }
            val rows = mutableListOf<MutableList<RecognizedLine>>()
            for (line in byTop) {
                val row = rows.lastOrNull()
                if (row != null) {
                    val anchor = row.first()
                    val tolerance = maxOf(anchor.height, line.height, 1e-6f) * 0.5f
                    if (line.top - anchor.top < tolerance) {
                        row.add(line)
                        continue
                    }
                }
                rows.add(mutableListOf(line))
            }
            return DocumentText(rows.flatMap { row -> row.sortedBy { it.left }.map { it.text.trim() } })
        }
    }
}

/** 서류 결과 화면의 음성 명령. */
sealed interface DocumentVoiceCommand {
    object ReadAll : DocumentVoiceCommand
    object Summary : DocumentVoiceCommand
    object Amounts : DocumentVoiceCommand
    object Dates : DocumentVoiceCommand
    data class Find(val keyword: String) : DocumentVoiceCommand
    /** 더 물을 게 없다("없어/괜찮아") — 대화를 끝낸다. */
    object None : DocumentVoiceCommand

    companion object {
        private val NO_WORDS = listOf("없어", "없습니다", "아니", "괜찮아", "됐어", "됐습니다", "그만")
        private val SUMMARY_WORDS = listOf("요약", "간단히", "짧게")
        private val READ_ALL_WORDS = listOf("전부", "전체", "다읽", "모두", "읽어", "낭독")
        private val AMOUNT_WORDS = listOf("금액", "얼마", "가격", "비용", "총액", "합계", "요금")
        private val DATE_WORDS = listOf("날짜", "언제", "기한", "일자", "까지", "며칠")
        private val FIND_SUFFIXES = listOf("찾아줘", "찾아", "알려줘", "알려", "뭐야", "뭐지", "있어", "읽어줘")
        private val PARTICLES = listOf("은", "는", "이", "가", "을", "를", "좀", "의", "에")

        /** 발화 → 명령. 요약 > 전부 읽기 > 금액 > 날짜 > 단어 찾기 순으로 본다. */
        fun parse(utterance: String): DocumentVoiceCommand {
            val compact = utterance.replace(" ", "")
            if (compact.isBlank()) return None
            if (NO_WORDS.any(compact::contains)) return None
            if (SUMMARY_WORDS.any(compact::contains)) return Summary
            if (READ_ALL_WORDS.any(compact::contains)) return ReadAll
            if (AMOUNT_WORDS.any(compact::contains)) return Amounts
            if (DATE_WORDS.any(compact::contains)) return Dates
            var keyword = compact
            for (suffix in FIND_SUFFIXES) {
                val index = keyword.indexOf(suffix)
                if (index > 0) {
                    keyword = keyword.substring(0, index)
                    break
                }
            }
            keyword = keyword.trim()
            for (particle in PARTICLES) {
                if (keyword.length > 1 && keyword.endsWith(particle)) {
                    keyword = keyword.dropLast(particle.length)
                    break
                }
            }
            return if (keyword.isBlank()) None else Find(keyword)
        }
    }
}
