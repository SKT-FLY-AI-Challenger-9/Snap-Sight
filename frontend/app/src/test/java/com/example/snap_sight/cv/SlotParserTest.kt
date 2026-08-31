package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `tests/test_slot_parser.py` 의 Kotlin 미러 — 규칙 기반 슬롯 파서의 의미가 Python 참조
 * 구현(`ai/slot_parser.py`)과 달라지면 여기서 잡는다. 한쪽을 고치면 반드시 다른 쪽도 고친다.
 */
class SlotParserTest {

    // detector 가 쓰는 것과 같은 assets 라벨 파일 — JVM 단위 테스트의 working dir 은
    // 모듈 디렉터리(frontend/app)라 상대 경로로 읽는다. 다른 실행 환경 대비 후보를 몇 개 둔다.
    private val taxonomy: ObjectTaxonomy by lazy {
        val candidates = listOf(
            File("src/main/assets/objects365_yolo26_v1_labels.txt"),
            File("app/src/main/assets/objects365_yolo26_v1_labels.txt"),
            File("frontend/app/src/main/assets/objects365_yolo26_v1_labels.txt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("objects365_yolo26_v1_labels.txt 를 찾지 못함 — working dir: ${File(".").absolutePath}")
        ObjectTaxonomy.fromLabelsText(file.readText())
    }

    @Test
    fun `all object label values are valid objects365 labels`() {
        for ((keyword, label) in SlotParser.OBJECT_LABEL_KEYWORDS) {
            assertTrue(
                "'$keyword' -> '$label'은 유효한 Objects365 라벨이 아님",
                taxonomy.classIdForLabel(label) != null && label != "person",
            )
        }
    }

    @Test
    fun `no single character object keywords`() {
        // 1글자 키워드는 완성형 한글 음절이 다른 단어 속에 우연히 포함되어 오매칭을 일으킨다
        // (예: "게"가 "크게"에 포함). 이슈 #30에서 실제로 겪은 버그라 회귀 방지용으로 막아둔다.
        val shortKeywords = SlotParser.OBJECT_LABEL_KEYWORDS.keys.filter { it.length < 2 }
        assertEquals("1글자 키워드 발견(오매칭 위험): $shortKeywords", emptyList<String>(), shortKeywords)
    }

    @Test
    fun `object label keyword coverage matches the python reference`() {
        // Python 표(약 250+ 라벨)와 규모가 어긋나면 포팅이 뒤처졌다는 신호다.
        assertTrue(
            "objectLabel 커버리지가 Python 참조 구현보다 후퇴함",
            SlotParser.OBJECT_LABEL_KEYWORDS.values.toSet().size >= 250,
        )
    }

    @Test
    fun `longer keyword wins when one keyword contains another`() {
        val cases = mapOf(
            "쌍안경 찍어줘" to "binoculars",
            "커피테이블 찍어줘" to "coffee table",
            "나비넥타이 매고 찍어줘" to "bow tie",
            "세발자전거 찍어줘" to "tricycle",
            "감자튀김 찍어줘" to "french fries",
            "찻주전자 찍어줘" to "tea pot",
            // 짧은 키워드 쪽도 단독으로는 여전히 잘 잡혀야 한다
            "안경 찍어줘" to "glasses",
            "테이블 찍어줘" to "dining table",
            "넥타이 찍어줘" to "tie",
            "자전거 찍어줘" to "bicycle",
            "감자 찍어줘" to "potato",
            "주전자 찍어줘" to "kettle",
        )
        for ((text, expectedLabel) in cases) {
            val spec = SlotParser.parse(text, sessionId = "sess_collision")
            assertEquals("$text -> ${spec.objectLabel}", expectedLabel, spec.objectLabel)
        }
    }

    @Test
    fun `no keyword is shadowed by a longer keyword of a different label`() {
        // 모든 (짧은 키워드, 긴 키워드) 쌍 전수 검사 — 새 키워드를 추가해도 longest-match 로
        // 항상 올바르게 풀리는지 확인한다.
        val items = SlotParser.OBJECT_LABEL_KEYWORDS.entries.toList()
        for ((shortKw, shortLabel) in items) {
            for ((longKw, longLabel) in items) {
                if (shortKw == longKw || shortLabel == longLabel) continue
                if (shortKw in longKw) {
                    val spec = SlotParser.parse("저 $longKw 찍어줘", sessionId = "sess_shadow")
                    assertEquals(
                        "'$shortKw'(-> $shortLabel)가 '$longKw'(-> $longLabel)를 가림",
                        longLabel,
                        spec.objectLabel,
                    )
                }
            }
        }
    }

    @Test
    fun `person count and closeup framing parsed`() {
        val spec = SlotParser.parse("친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘", sessionId = "sess_1")

        assertEquals(TargetSpec.SubjectType.PERSON, spec.subjectType)
        assertNull(spec.objectLabel)
        assertEquals(2, spec.subjectCount)
        assertEquals(TargetSpec.Framing.CLOSEUP, spec.framing)
        assertEquals(0.8f, spec.confidence)
    }

    @Test
    fun `common words containing removed short keywords do not false match`() {
        assertNull(SlotParser.parse("이렇게 크게 나오게 찍어줘", sessionId = "sess_11").objectLabel)
        assertEquals("shrimp", SlotParser.parse("새우 요리 옆에서 찍어줘", sessionId = "sess_12").objectLabel)
        assertNull(SlotParser.parse("풍경 배경으로 찍어줘", sessionId = "sess_13").objectLabel)
    }

    @Test
    fun `no keywords falls back to defaults`() {
        val spec = SlotParser.parse("그냥 사진 찍어줘", sessionId = "sess_2")

        assertEquals(TargetSpec.SubjectType.PERSON, spec.subjectType)
        assertNull(spec.objectLabel)
        assertNull(spec.subjectCount)
        assertEquals(TargetSpec.Framing.FULL_BODY, spec.framing)
        assertEquals(0.4f, spec.confidence)
    }

    @Test
    fun `landscape subject type and wide framing parsed`() {
        val spec = SlotParser.parse("풍경 위주로 찍어줘", sessionId = "sess_3")

        assertEquals(TargetSpec.SubjectType.LANDSCAPE, spec.subjectType)
        assertEquals(TargetSpec.Framing.WIDE, spec.framing)
        assertNull(spec.subjectCount)
        assertEquals(0.8f, spec.confidence)
    }

    @Test
    fun `document keywords set subject type to document`() {
        // 서류·종이·신분증류 (2026-08-30) — bbox 조준 대신 서류 모드(텍스트 영역 프레이밍)로 간다
        for (utterance in listOf("신분증 찍어줘", "이 서류 찍어줄래", "종이에 있는 글자 찍어줘", "영수증 찍어")) {
            val spec = SlotParser.parse(utterance, sessionId = "sess_doc")
            assertEquals(utterance, TargetSpec.SubjectType.DOCUMENT, spec.subjectType)
            assertNull(spec.objectLabel)
            assertEquals(TargetSpec.Framing.FULL_BODY, spec.framing)
            assertEquals(TargetSpec.Status.OK, spec.status)
            assertEquals(0.6f, spec.confidence)
        }
    }

    @Test
    fun `object label sets subject type to object`() {
        val spec = SlotParser.parse("저 머그컵 예쁘게 찍어줘", sessionId = "sess_4")

        assertEquals(TargetSpec.SubjectType.OBJECT, spec.subjectType)
        assertEquals("cup", spec.objectLabel)
        // "예쁘게"가 구도 신호로도 세어져 주체+구도 2개 매칭 (2026-08-31)
        assertEquals(0.8f, spec.confidence)
    }

    @Test
    fun `count keyword matching default framing value`() {
        val spec = SlotParser.parse("혼자 전신 나오게 찍어줘", sessionId = "sess_5")

        assertEquals(TargetSpec.SubjectType.PERSON, spec.subjectType)
        assertEquals(1, spec.subjectCount)
        assertEquals(TargetSpec.Framing.FULL_BODY, spec.framing)
        assertEquals(0.6f, spec.confidence)
    }

    @Test
    fun `digit count pattern parsed`() {
        // STT가 숫자를 아라비아 숫자로 인식하는 경우("2명")도 subjectCount로 잡아야 한다.
        val spec = SlotParser.parse("친구 2명이랑 같이 나오게 얼굴 크게 찍어 줘", sessionId = "sess_8")

        assertEquals(2, spec.subjectCount)
        assertEquals(TargetSpec.Framing.CLOSEUP, spec.framing)
    }

    @Test
    fun `unrecognized object keeps safe defaults`() {
        val spec = SlotParser.parse("저 정수기 좀 찍어줘", sessionId = "sess_6")

        assertEquals(TargetSpec.SubjectType.PERSON, spec.subjectType)
        assertNull(spec.objectLabel)
        assertEquals(0.4f, spec.confidence)
        assertEquals("저 정수기 좀 찍어줘", spec.rawText)
    }

    @Test
    fun `schema metadata fields are populated`() {
        val spec = SlotParser.parse("혼자 찍어줘", sessionId = "sess_7")

        assertEquals("0.2", spec.schemaVersion)
        assertEquals("sess_7", spec.sessionId)
        assertEquals(TargetSpec.Status.OK, spec.status)
        assertEquals("ondevice", spec.source)
    }

    @Test
    fun `no signal matched sets needs clarification`() {
        // 신호가 하나도 안 잡히면 서버 LLM 폴백으로 넘어가는 경계 — isActionable=false 여야
        // MainActivity 하이브리드 분기가 서버 왕복을 태운다.
        val spec = SlotParser.parse("사진 찍어줘", sessionId = "sess_14")

        assertEquals(0.4f, spec.confidence)
        assertEquals(TargetSpec.Status.NEEDS_CLARIFICATION, spec.status)
        assertEquals(false, spec.isActionable)
    }

    @Test
    fun `at least one signal matched sets status ok`() {
        val spec = SlotParser.parse("혼자 찍어줘", sessionId = "sess_15")

        assertEquals(0.6f, spec.confidence)
        assertEquals(TargetSpec.Status.OK, spec.status)
        assertEquals(true, spec.isActionable)
    }

    @Test
    fun `composition keyword counts as a signal`() {
        // "구도 좋게 찍어줘"는 피사체 단어가 없어도 촬영 의도가 명확하다 — 구도 키워드가 매칭
        // 신호로 세어져 OK(0.6)가 되어야 한다 (2026-08-31 실기기에서 0.4로 떨어져 조준이
        // 아예 시작되지 않던 문제의 회귀 방지). tests/test_slot_parser.py 미러.
        val spec = SlotParser.parse("구도 좋게 찍어줘", sessionId = "sess_31")

        assertEquals(TargetSpec.SubjectType.PERSON, spec.subjectType)
        assertEquals(0.6f, spec.confidence)
        assertEquals(TargetSpec.Status.OK, spec.status)
        assertEquals(true, spec.isActionable)
    }

    @Test
    fun `upper body utterance parses ok as a person composition request`() {
        // "상반신 찍어줘" — 프레이밍 사전엔 없지만 구도 신호로 세어져 OK 가 돼야 한다
        // (실기기 2026-08-31: 0.4 로 떨어져 조준이 시작되지 않았다). MainActivity 는 이
        // 발화를 질문 없이 곧장 상반신 구도로 보낸다.
        val spec = SlotParser.parse("상반신 찍어줘", sessionId = "sess_33")

        assertEquals(TargetSpec.SubjectType.PERSON, spec.subjectType)
        assertEquals(TargetSpec.Status.OK, spec.status)
        assertEquals(true, spec.isActionable)
    }

    @Test
    fun `confidence is capped at one with all four signals`() {
        val spec = SlotParser.parse("머그컵 든 사람 2명 얼굴 멋지게 찍어줘", sessionId = "sess_32")

        assertEquals(1.0f, spec.confidence)
        assertEquals(TargetSpec.Status.OK, spec.status)
    }

    @Test
    fun `blank utterance returns a failed spec like the backend`() {
        // Python 은 backend/api/session.py 가 빈 발화를 걸러 status=failed 를 만든다.
        // Kotlin TargetSpec 은 FAILED 외엔 빈 rawText 를 거부하므로 파서가 직접 같은 규칙을 적용한다.
        val spec = SlotParser.parse("   ", sessionId = "sess_16")

        assertEquals(TargetSpec.Status.FAILED, spec.status)
        assertEquals(false, spec.isActionable)
    }
}
