package com.example.snap_sight.cv

/** Applies a unique local registered-name match after the remote taxonomy selector. */
internal object RegisteredIdentitySelection {
    fun apply(
        frameResult: FrameResult,
        base: TargetSelection,
        spec: TargetSpec?,
        identityName: String?,
        identities: Map<Int, String>,
    ): TargetSelection {
        if (identityName == null || spec?.subjectType == TargetSpec.SubjectType.LANDSCAPE) {
            return base
        }
        // A unique local identity is stronger evidence than the redacted/remote taxonomy.
        // In particular, "등록 사물" can resolve to an actionable-but-empty remote selector,
        // so intersecting with base.candidates would make that local object impossible to find.
        val named = frameResult.objects.filter { identities[it.trackId] == identityName }
        return base.copy(
            candidates = named,
            state = if (named.isEmpty()) TargetSelectionState.SEARCHING
            else TargetSelectionState.SELECTED,
        )
    }
}
