package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AimingGuidanceModeTest {
    @Test
    fun `failed and clarification specs wait without composition or zoom`() {
        listOf(TargetSpec.Status.FAILED, TargetSpec.Status.NEEDS_CLARIFICATION).forEach { status ->
            val mode = AimingGuidanceModeResolver.resolve(
                spec = spec(status = status),
                targetSpecPending = false,
                localIdentityName = null,
            )
            assertEquals(AimingGuidanceMode.GENERAL_WAITING, mode)
            assertFalse(mode.allowsCompositionGuidance)
            assertFalse(mode.allowsAutoZoom)
        }
    }

    @Test
    fun `document intent resolves to the document mode without composition guidance or zoom`() {
        val document = AimingGuidanceModeResolver.resolve(
            spec = spec(subjectType = TargetSpec.SubjectType.DOCUMENT),
            targetSpecPending = false,
            localIdentityName = null,
        )
        assertEquals(AimingGuidanceMode.DOCUMENT, document)
        assertFalse(document.allowsCompositionGuidance)
        assertFalse(document.allowsAutoZoom)
        assertTrue(TargetSpec.SubjectType.DOCUMENT.sceneOnly)
        assertTrue(TargetSpec.SubjectType.LANDSCAPE.sceneOnly)
        assertFalse(TargetSpec.SubjectType.OBJECT.sceneOnly)
    }

    @Test
    fun `landscape and unresolved intent never update auto zoom`() {
        val landscape = AimingGuidanceModeResolver.resolve(
            spec = spec(subjectType = TargetSpec.SubjectType.LANDSCAPE),
            targetSpecPending = false,
            localIdentityName = null,
        )
        val resolving = AimingGuidanceModeResolver.resolve(
            spec = null,
            targetSpecPending = true,
            localIdentityName = null,
        )

        assertEquals(AimingGuidanceMode.LANDSCAPE, landscape)
        assertEquals(AimingGuidanceMode.RESOLVING, resolving)
        assertFalse(landscape.allowsAutoZoom)
        assertFalse(resolving.allowsAutoZoom)
    }

    @Test
    fun `registered-name fast path may guide while remote spec resolves`() {
        val mode = AimingGuidanceModeResolver.resolve(
            spec = null,
            targetSpecPending = true,
            localIdentityName = "민수",
        )

        assertEquals(AimingGuidanceMode.COMPOSITION, mode)
        assertTrue(mode.allowsCompositionGuidance)
        assertTrue(mode.allowsAutoZoom)
    }

    @Test
    fun `unique registered name survives failed remote interpretation except landscape`() {
        val localFallback = AimingGuidanceModeResolver.resolve(
            spec = spec(status = TargetSpec.Status.FAILED),
            targetSpecPending = false,
            localIdentityName = "민수",
        )
        val landscape = AimingGuidanceModeResolver.resolve(
            spec = spec(subjectType = TargetSpec.SubjectType.LANDSCAPE),
            targetSpecPending = false,
            localIdentityName = "민수",
        )

        assertEquals(AimingGuidanceMode.COMPOSITION, localFallback)
        assertEquals(AimingGuidanceMode.LANDSCAPE, landscape)
    }

    @Test
    fun `explicit null intent such as no microphone is general shooting not permanent resolving`() {
        val mode = AimingGuidanceModeResolver.resolve(
            spec = null,
            targetSpecPending = false,
            localIdentityName = null,
        )

        assertEquals(AimingGuidanceMode.GENERAL_WAITING, mode)
        assertFalse(mode.allowsCompositionGuidance)
        assertFalse(mode.allowsAutoZoom)
    }

    private fun spec(
        status: TargetSpec.Status = TargetSpec.Status.OK,
        subjectType: TargetSpec.SubjectType = TargetSpec.SubjectType.PERSON,
    ) = TargetSpec(
        sessionId = "session",
        rawText = if (status == TargetSpec.Status.FAILED) "" else "촬영해줘",
        source = "test",
        status = status,
        subjectType = subjectType,
    )
}
