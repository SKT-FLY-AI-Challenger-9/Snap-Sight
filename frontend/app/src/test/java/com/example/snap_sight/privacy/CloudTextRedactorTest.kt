package com.example.snap_sight.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTextRedactorTest {
    @Test
    fun removesExactPersonNameWhileKeepingIntent() {
        val result = CloudTextRedactor.redact(
            rawText = "민수님을 가까이 찍어줘",
            registeredPeople = listOf("민수님"),
            registeredObjects = emptyList(),
        )

        assertEquals("등록 인물을 가까이 찍어줘", result)
        assertFalse(result.contains("민수"))
    }

    @Test
    fun removesNormalizedObjectNameAcrossSpaceDifference() {
        val result = CloudTextRedactor.redact(
            rawText = "우리곰인형을 가까이 찍어줘",
            registeredPeople = emptyList(),
            registeredObjects = listOf("우리 곰인형"),
        )

        assertEquals("등록 사물을 가까이 찍어줘", result)
        assertFalse(result.contains("곰인형"))
    }

    @Test
    fun removesPartialNameAndCompoundJosaWithoutLeavingAlias() {
        listOf("재석 찍어줘", "재석이를 찍어줘", "재석이를찍어줘").forEach { rawText ->
            val result = CloudTextRedactor.redact(
                rawText = rawText,
                registeredPeople = listOf("유재석"),
                registeredObjects = emptyList(),
            )

            assertEquals(
                if (rawText.contains(' ')) "등록 인물 찍어줘" else "등록 인물찍어줘",
                result,
            )
            assertFalse(result.contains("재석"))
        }
    }

    @Test
    fun everyAliasFoundByUniqueMatcherIsAbsentFromCloudText() {
        val cases: List<Triple<String, List<String>, List<String>>> = listOf(
            Triple("유재석을 찍어줘", listOf("유재석"), emptyList()),
            Triple("재석이를 찍어줘", listOf("유재석"), emptyList()),
            Triple("재석이를찍어줘", listOf("유재석"), emptyList()),
            Triple("우리곰인형을 찍어줘", emptyList(), listOf("우리 곰인형")),
        )

        cases.forEach { (rawText, people, objects) ->
            val resolution = RegisteredIdentityMatcher.resolve(rawText, people, objects)
            assertTrue(resolution is RegisteredIdentityMatcher.Resolution.Unique)
            resolution as RegisteredIdentityMatcher.Resolution.Unique
            val cloudText = CloudTextRedactor.redact(rawText, people, objects)
            val normalizedCloudText = RegisteredIdentityMatcher.normalize(cloudText)

            resolution.matches.forEach { match ->
                assertFalse(normalizedCloudText.contains(match.normalizedAlias))
            }
        }
    }

    @Test
    fun ambiguousNamesOrKindsFailClosedToGenericUtterance() {
        assertEquals(
            CloudTextRedactor.GENERIC_CAPTURE_UTTERANCE,
            CloudTextRedactor.redact(
                rawText = "유재석 찍어줘",
                registeredPeople = listOf("유재석", "재석"),
                registeredObjects = emptyList(),
            ),
        )
        assertEquals(
            CloudTextRedactor.GENERIC_CAPTURE_UTTERANCE,
            CloudTextRedactor.redact(
                rawText = "별이 찍어줘",
                registeredPeople = listOf("별이"),
                registeredObjects = listOf("별이"),
            ),
        )
    }

    @Test
    fun leavesUnregisteredWordsUntouched() {
        assertEquals(
            "강아지 두 마리 찍어줘",
            CloudTextRedactor.redact("강아지 두 마리 찍어줘", emptyList(), emptyList()),
        )
    }
}
