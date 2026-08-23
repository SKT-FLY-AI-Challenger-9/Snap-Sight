package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisteredIdentitySelectionTest {
    @Test
    fun `local identity recovers a single candidate after remote failure`() {
        val minsu = tracked(1)
        val other = tracked(2)
        val failed = TargetSpec(
            sessionId = "session",
            rawText = "",
            source = "test",
            status = TargetSpec.Status.FAILED,
        )
        val unresolved = TargetSelection(TargetSelectionState.UNRESOLVED, emptyList())

        val selected = RegisteredIdentitySelection.apply(
            frameResult = FrameResult(listOf(minsu, other)),
            base = unresolved,
            spec = failed,
            identityName = "민수",
            identities = mapOf(1 to "민수", 2 to "영희"),
        )

        assertEquals(TargetSelectionState.SELECTED, selected.state)
        assertEquals(listOf(minsu), selected.candidates)
    }

    @Test
    fun `local identity overrides actionable but empty remote taxonomy selection`() {
        val registeredObject = tracked(7, label = "bottle")
        val actionable = TargetSpec(
            sessionId = "session",
            rawText = "등록 사물을 찍어줘",
            source = "test",
            schemaVersion = "0.2",
            status = TargetSpec.Status.OK,
            subjectType = TargetSpec.SubjectType.OBJECT,
            objectLabel = "등록 사물",
        )

        val selected = RegisteredIdentitySelection.apply(
            frameResult = FrameResult(listOf(registeredObject)),
            base = TargetSelection(TargetSelectionState.SEARCHING, emptyList()),
            spec = actionable,
            identityName = "내 물병",
            identities = mapOf(registeredObject.trackId to "내 물병"),
        )

        assertEquals(TargetSelectionState.SELECTED, selected.state)
        assertEquals(listOf(registeredObject), selected.candidates)
    }

    @Test
    fun `landscape always keeps scene-only selection`() {
        val sceneOnly = TargetSelection(TargetSelectionState.SCENE_ONLY, emptyList())
        val landscape = TargetSpec(
            sessionId = "session",
            rawText = "풍경을 찍어줘",
            source = "test",
            subjectType = TargetSpec.SubjectType.LANDSCAPE,
        )

        val selected = RegisteredIdentitySelection.apply(
            frameResult = FrameResult(listOf(tracked(1))),
            base = sceneOnly,
            spec = landscape,
            identityName = "민수",
            identities = mapOf(1 to "민수"),
        )

        assertEquals(TargetSelectionState.SCENE_ONLY, selected.state)
        assertTrue(selected.candidates.isEmpty())
    }

    private fun tracked(id: Int, label: String = "person") = TrackedObject(
        trackId = id,
        label = label,
        confidence = 0.9f,
        bbox = BoundingBox(0.2f, 0.2f, 0.6f, 0.8f),
    )
}
