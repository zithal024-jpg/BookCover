package com.bookcover.diagnostic

/** Listing a display is not proof it is the physical outer screen or can be activated. */
internal data class DisplayCandidate(
    val id: Int,
    val name: String,
    val isPresentation: Boolean,
    val isPrivate: Boolean,
    val isOn: Boolean,
)

internal fun eligiblePresentationTargets(
    displays: List<DisplayCandidate>,
    hostDisplayId: Int,
): List<DisplayCandidate> = displays.filter {
    it.id != 0 && it.id != hostDisplayId && it.isPresentation && !it.isPrivate && it.isOn
}
