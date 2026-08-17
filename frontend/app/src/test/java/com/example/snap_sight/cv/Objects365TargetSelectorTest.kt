package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tests/test_target_selection.py` 의 Kotlin 미러 — 선택 규칙의 의미가 PC 참조 구현과
 * 달라지면 여기서 잡는다. 한쪽을 고치면 반드시 다른 쪽도 고친다.
 */
class Objects365TargetSelectorTest {

    // Objects365 canonical 순서의 앞부분 + label fallback 검증용 wine glass.
    private val taxonomy = ObjectTaxonomy(
        listOf("person", "sneakers", "chair", "hat", "lamp", "bottle", "cabinet/shelf", "cup", "wine glass")
    )
    private val selector = Objects365TargetSelector(taxonomy)

    private val box = BoundingBox(0.1f, 0.1f, 0.4f, 0.8f)
    private val person1 = TrackedObject(1, "Person", 0.9f, box, classId = 0)
    private val person2 = TrackedObject(2, "Person", 0.8f, box, classId = 0)
    private val bottle = TrackedObject(3, "Bottle", 0.85f, box, classId = 5)
    private val chair = TrackedObject(4, "Chair", 0.75f, box, classId = 2)
    private val allObjects = FrameResult(listOf(person1, person2, bottle, chair))

    private fun targetSpec(
        subjectType: TargetSpec.SubjectType = TargetSpec.SubjectType.PERSON,
        subjectCount: Int? = null,
        status: TargetSpec.Status = TargetSpec.Status.OK,
        schemaVersion: String = "0.1",
        objectLabel: String? = null,
    ) = TargetSpec(
        sessionId = "session-1",
        rawText = "테스트 발화",
        source = "ondevice",
        schemaVersion = schemaVersion,
        status = status,
        subjectType = subjectType,
        objectLabel = objectLabel,
        subjectCount = subjectCount,
        framing = TargetSpec.Framing.FULL_BODY,
        confidence = 0.9f,
    )

    @Test
    fun `person intent selects people after all objects were tracked`() {
        val selection = selector.select(allObjects, targetSpec())

        assertEquals(TargetSelectionState.SELECTED, selection.state)
        assertEquals(listOf(1, 2), selection.candidates.map { it.trackId })
        assertTrue(selection.toFrameResult().toJson().contains("\"label\":\"Person\""))
        // 선택이 원본 전체 추적 결과를 바꾸거나 줄이지 않는다.
        assertEquals(listOf(1, 2, 3, 4), allObjects.objects.map { it.trackId })
    }

    @Test
    fun `generic object intent selects all supported non-person objects`() {
        val selection = selector.select(allObjects, targetSpec(TargetSpec.SubjectType.OBJECT))

        assertEquals(listOf(3, 4), selection.candidates.map { it.trackId })
    }

    @Test
    fun `specific object label selects only that objects365 class`() {
        val cup = TrackedObject(10, "cup", 0.9f, box, classId = 7)
        val selection = selector.select(
            FrameResult(listOf(person1, bottle, chair, cup)),
            targetSpec(TargetSpec.SubjectType.OBJECT, schemaVersion = "0.2", objectLabel = "cup"),
        )

        assertEquals(listOf(10), selection.candidates.map { it.trackId })
    }

    @Test
    fun `specific object label uses model label when class id is unavailable`() {
        val wineGlass = TrackedObject(10, "Wine Glass", 0.9f, box)
        val selection = selector.select(
            FrameResult(listOf(wineGlass, bottle)),
            targetSpec(TargetSpec.SubjectType.OBJECT, schemaVersion = "0.2", objectLabel = "wine glass"),
        )

        assertEquals(listOf(10), selection.candidates.map { it.trackId })
    }

    @Test
    fun `specific object missing from frame reports searching after filtering`() {
        val selection = selector.select(
            allObjects,
            targetSpec(
                TargetSpec.SubjectType.OBJECT,
                schemaVersion = "0.2",
                objectLabel = "cup",
                subjectCount = 1,
            ),
        )

        assertEquals(TargetSelectionState.SEARCHING, selection.state)
        assertEquals(TargetCountStatus.UNDER, selection.countStatus)
        assertTrue(selection.candidates.isEmpty())
    }

    @Test
    fun `generic object intent excludes unknown extension classes`() {
        val face = TrackedObject(10, "face", 0.9f, box)
        val selection = selector.select(
            FrameResult(listOf(bottle, face)),
            targetSpec(TargetSpec.SubjectType.OBJECT),
        )

        assertEquals(listOf(3), selection.candidates.map { it.trackId })
    }

    @Test
    fun `selector accepts an explicit alternative taxonomy mapping`() {
        val alternative = Objects365TargetSelector(ObjectTaxonomy(listOf("bottle", "person")))
        val customPerson = TrackedObject(10, "human", 0.9f, box, classId = 1)

        val selection = alternative.select(FrameResult(listOf(customPerson)), targetSpec())

        assertEquals(listOf(10), selection.candidates.map { it.trackId })
    }

    @Test
    fun `legacy person class override remains supported`() {
        val override = Objects365TargetSelector(taxonomy, personClassId = 7)
        val customPerson = TrackedObject(10, "human", 0.9f, box, classId = 7)

        val selection = override.select(FrameResult(listOf(customPerson)), targetSpec())

        assertEquals(listOf(10), selection.candidates.map { it.trackId })
    }

    @Test
    fun `reduced taxonomy reports searching for an unsupported requested label`() {
        val reduced = Objects365TargetSelector(ObjectTaxonomy(listOf("person", "cup")))
        val selection = reduced.select(
            FrameResult(listOf(TrackedObject(10, "cup", 0.9f, box, classId = 1))),
            targetSpec(
                TargetSpec.SubjectType.OBJECT,
                schemaVersion = "0.2",
                objectLabel = "bottle",
                subjectCount = 1,
            ),
        )

        assertEquals(TargetSelectionState.SEARCHING, selection.state)
        assertTrue(selection.candidates.isEmpty())
    }

    @Test
    fun `landscape intent does not fabricate an object target`() {
        val selection = selector.select(allObjects, targetSpec(TargetSpec.SubjectType.LANDSCAPE))

        assertEquals(TargetSelectionState.SCENE_ONLY, selection.state)
        assertTrue(selection.candidates.isEmpty())
        assertEquals(TargetCountStatus.NOT_APPLICABLE, selection.countStatus)
    }

    @Test
    fun `subject count reports over exact and under without arbitrary truncation`() {
        val cases = listOf(
            Triple(1, TargetSelectionState.AMBIGUOUS, TargetCountStatus.OVER),
            Triple(2, TargetSelectionState.SELECTED, TargetCountStatus.EXACT),
            Triple(3, TargetSelectionState.SEARCHING, TargetCountStatus.UNDER),
        )
        for ((requestedCount, expectedState, expectedCountStatus) in cases) {
            val selection = selector.select(allObjects, targetSpec(subjectCount = requestedCount))

            assertEquals("count=$requestedCount", expectedState, selection.state)
            assertEquals("count=$requestedCount", expectedCountStatus, selection.countStatus)
            // OVER 여도 임의의 top-N 을 고르지 않는다 — 후보 전체를 그대로 보고한다.
            assertEquals(listOf(1, 2), selection.candidates.map { it.trackId })
        }
    }

    @Test
    fun `unresolved nlu result does not choose a target`() {
        val selection = selector.select(
            allObjects,
            targetSpec(status = TargetSpec.Status.NEEDS_CLARIFICATION),
        )

        assertEquals(TargetSelectionState.UNRESOLVED, selection.state)
        assertTrue(selection.candidates.isEmpty())
    }

    @Test
    fun `changing intent reuses the existing track ids`() {
        val people = selector.select(allObjects, targetSpec(TargetSpec.SubjectType.PERSON))
        val objects = selector.select(allObjects, targetSpec(TargetSpec.SubjectType.OBJECT))

        assertEquals(listOf(1, 2), people.candidates.map { it.trackId })
        assertEquals(listOf(3, 4), objects.candidates.map { it.trackId })
    }

    @Test
    fun `null intent passes every object through as disabled`() {
        val selection = selector.select(allObjects, null)

        assertEquals(TargetSelectionState.DISABLED, selection.state)
        assertEquals(allObjects.objects, selection.candidates)
        assertNull(selection.requestedCount)
        assertEquals(TargetCountStatus.NOT_APPLICABLE, selection.countStatus)
    }

    @Test
    fun `selection keeps the public object schema unchanged`() {
        val selection = selector.select(allObjects, targetSpec(subjectCount = 2))

        assertEquals(TargetSelectionState.SELECTED, selection.state)
        assertEquals(TargetCountStatus.EXACT, selection.countStatus)
        assertEquals(2, selection.detectedCount)
        val json = selection.toFrameResult().toJson()
        assertTrue(json.startsWith("{\"objects\":["))
        assertTrue(json.contains("\"track_id\":1"))
        assertTrue(json.contains("\"bbox\":{\"x_min\":"))
        // 선택 상태(state/count)는 objects 스키마에 섞지 않는다 — 별도 필드로만 나간다.
        assertFalse(json.contains("state"))
    }

    // ------------------------------------------------------------------
    // ObjectTaxonomy — detector 라벨 파싱과의 패리티
    // ------------------------------------------------------------------

    @Test
    fun `taxonomy label text parsing matches the detector rules`() {
        // 마지막 개행(들)만 제거한다. CRLF 도 허용.
        val parsed = ObjectTaxonomy.fromLabelsText("person\r\nchair\nbottle\n\n")
        assertEquals(listOf("person", "chair", "bottle"), parsed.labels)
        assertEquals(0, parsed.personClassId)
        assertEquals(2, parsed.classIdForLabel("bottle"))
    }

    @Test
    fun `taxonomy rejects an interior blank line instead of shifting class ids`() {
        val thrown = runCatching { ObjectTaxonomy.fromLabelsText("person\n\nbottle\n") }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `taxonomy matches by class id first and falls back to casefolded label`() {
        assertTrue(taxonomy.matches(classId = 5, observedLabel = "whatever", canonicalLabel = "bottle"))
        assertFalse(taxonomy.matches(classId = 2, observedLabel = "bottle", canonicalLabel = "bottle"))
        assertTrue(taxonomy.matches(classId = null, observedLabel = " Bottle ", canonicalLabel = "bottle"))
        // canonical label 조회는 대소문자를 구분한다 — canonical 은 ①이 보증하는 값이다.
        assertFalse(taxonomy.matches(classId = 5, observedLabel = "Bottle", canonicalLabel = "BOTTLE"))
    }

    @Test
    fun `taxonomy supported-object check excludes person and unknown labels`() {
        assertTrue(taxonomy.isSupportedObject(classId = 5, observedLabel = "Bottle"))
        assertTrue(taxonomy.isSupportedObject(classId = null, observedLabel = "cabinet/shelf"))
        assertFalse(taxonomy.isSupportedObject(classId = 0, observedLabel = "Person"))
        assertFalse(taxonomy.isSupportedObject(classId = null, observedLabel = "face"))
        assertFalse(taxonomy.isSupportedObject(classId = 999, observedLabel = "Bottle"))
    }
}
