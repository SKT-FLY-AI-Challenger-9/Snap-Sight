package com.example.snap_sight.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisteredIdentityMatcherTest {
    @Test
    fun exactAndPartialAliasesForSameCanonicalTargetRemainUnique() {
        val resolution = RegisteredIdentityMatcher.resolve(
            rawText = "유재석과 재석을 찍어줘",
            registeredPeople = listOf("유재석"),
            registeredObjects = emptyList(),
        )

        assertTrue(resolution is RegisteredIdentityMatcher.Resolution.Unique)
        resolution as RegisteredIdentityMatcher.Resolution.Unique
        assertEquals("유재석", resolution.identity.canonicalName)
        assertEquals(
            RegisteredIdentityMatcher.IdentityKind.PERSON,
            resolution.identity.kind,
        )
        assertTrue(resolution.matches.any { it.basis == RegisteredIdentityMatcher.MatchBasis.EXACT })
        assertTrue(resolution.matches.any { it.basis == RegisteredIdentityMatcher.MatchBasis.PARTIAL })
    }

    @Test
    fun partialAliasAndRepeatedJosaResolveToCanonicalTarget() {
        listOf("재석 찍어줘", "재석이를 찍어줘", "재석이를찍어줘").forEach { rawText ->
            val target = RegisteredIdentityMatcher.uniqueTarget(
                rawText = rawText,
                registeredPeople = listOf("유재석"),
                registeredObjects = emptyList(),
            )

            assertEquals("유재석", target?.canonicalName)
            assertEquals(RegisteredIdentityMatcher.IdentityKind.PERSON, target?.kind)
        }
    }

    @Test
    fun compactCommandRuleDoesNotScanArbitraryTwoCharacterSubstrings() {
        val resolution = RegisteredIdentityMatcher.resolve(
            rawText = "서울역풍경을찍어줘",
            registeredPeople = emptyList(),
            registeredObjects = listOf("서울가방"),
        )

        // "서울"이 등록 이름 안에 있다는 이유만으로 긴 subject 문장 내부를 훑지 않는다.
        assertTrue(resolution === RegisteredIdentityMatcher.Resolution.None)
    }

    @Test
    fun normalizedSpaceDifferenceResolvesObject() {
        val target = RegisteredIdentityMatcher.uniqueTarget(
            rawText = "우리곰인형을 찍어줘",
            registeredPeople = emptyList(),
            registeredObjects = listOf("우리 곰인형"),
        )

        assertEquals("우리 곰인형", target?.canonicalName)
        assertEquals(RegisteredIdentityMatcher.IdentityKind.OBJECT, target?.kind)
    }

    @Test
    fun differentCanonicalNamesAreAmbiguousInsteadOfChoosingLongest() {
        val resolution = RegisteredIdentityMatcher.resolve(
            rawText = "유재석 찍어줘",
            registeredPeople = listOf("유재석", "재석"),
            registeredObjects = emptyList(),
        )

        assertTrue(resolution is RegisteredIdentityMatcher.Resolution.Ambiguous)
        resolution as RegisteredIdentityMatcher.Resolution.Ambiguous
        assertEquals(setOf("유재석", "재석"), resolution.identities.map { it.canonicalName }.toSet())
        assertNull(
            RegisteredIdentityMatcher.uniqueTarget(
                "유재석 찍어줘",
                listOf("유재석", "재석"),
                emptyList(),
            )
        )
    }

    @Test
    fun sameCanonicalNameAcrossPersonAndObjectIsAmbiguous() {
        val resolution = RegisteredIdentityMatcher.resolve(
            rawText = "별이 찍어줘",
            registeredPeople = listOf("별이"),
            registeredObjects = listOf("별이"),
        )

        assertTrue(resolution is RegisteredIdentityMatcher.Resolution.Ambiguous)
    }

    @Test
    fun unrelatedUtteranceHasNoTarget() {
        assertTrue(
            RegisteredIdentityMatcher.resolve(
                rawText = "강아지 두 마리 찍어줘",
                registeredPeople = listOf("유재석"),
                registeredObjects = listOf("우리 곰인형"),
            ) === RegisteredIdentityMatcher.Resolution.None
        )
        assertNull(
            RegisteredIdentityMatcher.uniqueTarget(
                "강아지 두 마리 찍어줘",
                listOf("유재석"),
                listOf("우리 곰인형"),
            )
        )
    }
}
