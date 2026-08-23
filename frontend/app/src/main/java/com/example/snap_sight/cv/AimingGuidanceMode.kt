package com.example.snap_sight.cv

/**
 * One canonical switch for composition speech, composition UI, and auto zoom.
 * Modes without an actionable bbox target must never feed LOST or movement
 * guidance and must not advance auto-zoom search streaks.
 */
enum class AimingGuidanceMode(
    val allowsCompositionGuidance: Boolean,
    val allowsAutoZoom: Boolean,
) {
    COMPOSITION(allowsCompositionGuidance = true, allowsAutoZoom = true),
    LANDSCAPE(allowsCompositionGuidance = false, allowsAutoZoom = false),
    RESOLVING(allowsCompositionGuidance = false, allowsAutoZoom = false),
    GENERAL_WAITING(allowsCompositionGuidance = false, allowsAutoZoom = false),
}

object AimingGuidanceModeResolver {
    fun resolve(
        spec: TargetSpec?,
        targetSpecPending: Boolean,
        localIdentityName: String?,
    ): AimingGuidanceMode = when {
        targetSpecPending && localIdentityName == null -> AimingGuidanceMode.RESOLVING
        spec?.subjectType == TargetSpec.SubjectType.LANDSCAPE -> AimingGuidanceMode.LANDSCAPE
        localIdentityName != null -> AimingGuidanceMode.COMPOSITION
        spec != null && !spec.isActionable -> AimingGuidanceMode.GENERAL_WAITING
        spec == null && localIdentityName == null -> AimingGuidanceMode.GENERAL_WAITING
        else -> AimingGuidanceMode.COMPOSITION
    }
}
