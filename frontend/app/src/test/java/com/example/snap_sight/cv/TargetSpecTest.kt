package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 의도(TargetSpec) 입력의 **null 안전성**을 고정한다.
 *
 * ① STT/NLU 가 아직 붙지 않았고, 붙은 뒤에도 마이크 권한이 없거나 발화를 건너뛴 세션에서는
 * 의도가 존재하지 않는다. CV 루프는 그 어떤 경우에도 멈추면 안 된다.
 */
class TargetSpecTest {

    @Test
    fun `absent intent parses to null without throwing`() {
        assertNull(TargetSpec.fromJsonOrNull(null))
        assertNull(TargetSpec.fromJsonOrNull(""))
        assertNull(TargetSpec.fromJsonOrNull("   "))
    }

    @Test
    fun `malformed intent is reported and swallowed`() {
        val errors = mutableListOf<Throwable>()

        assertNull(TargetSpec.fromJsonOrNull("{not json", errors::add))
        assertNull(TargetSpec.fromJsonOrNull("""{"sessionId":"s1"}""", errors::add))
        assertNull(
            TargetSpec.fromJsonOrNull(
                """{"sessionId":"s1","rawText":"x","source":"clova","subjectType":"cat"}""",
                errors::add,
            )
        )

        assertEquals(3, errors.size)
    }

    @Test
    fun `v0_2 object payload parses`() {
        val spec = TargetSpec.fromJson(
            """
            {
              "schemaVersion": "0.2",
              "sessionId": "sess_20260813_002",
              "status": "ok",
              "subjectType": "object",
              "objectLabel": "cup",
              "subjectCount": 1,
              "framing": "closeup",
              "rawText": "저 컵 예쁘게 찍어줘",
              "confidence": 0.85,
              "source": "clova"
            }
            """.trimIndent()
        )

        assertEquals("0.2", spec.schemaVersion)
        assertEquals(TargetSpec.SubjectType.OBJECT, spec.subjectType)
        assertEquals("cup", spec.objectLabel)
        assertEquals(1, spec.subjectCount)
        assertEquals(TargetSpec.Framing.CLOSEUP, spec.framing)
        assertTrue(spec.isActionable)
    }

    @Test
    fun `v0_1 payload defaults and rejects objectLabel`() {
        val spec = TargetSpec.fromJson(
            """{"sessionId":"s1","rawText":"인물 사진 찍어줘","source":"clova"}"""
        )
        assertEquals("0.1", spec.schemaVersion)
        assertEquals(TargetSpec.SubjectType.PERSON, spec.subjectType)
        assertEquals(TargetSpec.Framing.FULL_BODY, spec.framing)
        assertNull(spec.subjectCount)
        assertNull(spec.objectLabel)

        assertNull(
            TargetSpec.fromJsonOrNull(
                """{"sessionId":"s1","rawText":"x","source":"clova","objectLabel":"cup"}"""
            )
        )
    }

    @Test
    fun `explicit null objectLabel and subjectCount stay null`() {
        val spec = TargetSpec.fromJson(
            """
            {"schemaVersion":"0.2","sessionId":"s1","status":"ok","subjectType":"person",
             "objectLabel":null,"subjectCount":null,"framing":"wide",
             "rawText":"풍경 찍어줘","confidence":0.4,"source":"ondevice"}
            """.trimIndent()
        )
        assertNull(spec.objectLabel)
        assertNull(spec.subjectCount)
    }

    @Test
    fun `non-ok status is not actionable`() {
        val spec = TargetSpec.fromJson(
            """{"sessionId":"s1","status":"needs_clarification","rawText":"어…","source":"clova"}"""
        )
        assertFalse(spec.isActionable)
    }

    @Test
    fun `failed status may carry empty raw text`() {
        assertNotNull(
            TargetSpec.fromJsonOrNull(
                """{"sessionId":"s1","status":"failed","rawText":"","source":"clova"}"""
            )
        )
    }

    @Test
    fun `pass-through selector ignores the intent entirely`() {
        val objects = listOf(
            TrackedObject(1, "person", 0.9f, BoundingBox(0.1f, 0.1f, 0.3f, 0.6f), classId = 0),
            TrackedObject(2, "cup", 0.7f, BoundingBox(0.5f, 0.5f, 0.6f, 0.7f), classId = 7),
        )
        val frameResult = FrameResult(objects)
        val selector = PassThroughTargetSelector()

        val withoutIntent = selector.select(frameResult, null)
        assertEquals(TargetSelectionState.DISABLED, withoutIntent.state)
        assertEquals(objects, withoutIntent.candidates)
        assertNull(withoutIntent.requestedCount)

        val spec = TargetSpec.fromJson(
            """{"sessionId":"s1","subjectType":"person","subjectCount":2,
                "rawText":"두 명 찍어줘","source":"clova"}""".trimIndent()
        )
        val withIntent = selector.select(frameResult, spec)
        assertEquals(TargetSelectionState.DISABLED, withIntent.state)
        assertEquals(objects, withIntent.candidates)
        assertEquals(2, withIntent.requestedCount)
    }

    @Test
    fun `default deviation calculator stays silent`() {
        val selection = TargetSelection(TargetSelectionState.DISABLED, emptyList())
        assertNull(NoDeviationCalculator().compute(selection, null))
    }
}
