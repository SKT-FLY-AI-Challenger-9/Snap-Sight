package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelMatchTargetSelectorTest {

    private val selector = LabelMatchTargetSelector()

    private fun obj(trackId: Int, label: String, confidence: Float = 0.8f) = TrackedObject(
        trackId = trackId, label = label, confidence = confidence,
        bbox = BoundingBox(xMin = 0.4f, yMin = 0.4f, xMax = 0.6f, yMax = 0.6f),
    )

    private fun spec(objectLabel: String?) = TargetSpec(
        sessionId = "s_test", rawText = "테스트", source = "ondevice", schemaVersion = "0.2",
        subjectType = TargetSpec.SubjectType.OBJECT, objectLabel = objectLabel,
    )

    @Test
    fun matchingLabelIsSelected() {
        val frame = FrameResult(listOf(obj(1, "laptop"), obj(2, "cup"), obj(3, "laptop")))
        val selection = selector.select(frame, spec("laptop"))
        assertEquals(TargetSelectionState.SELECTED, selection.state)
        assertEquals(listOf(1, 3), selection.candidates.map { it.trackId })
    }

    @Test
    fun noMatchFallsBackToPassThrough() {
        val frame = FrameResult(listOf(obj(1, "cup")))
        val selection = selector.select(frame, spec("laptop"))
        assertEquals(TargetSelectionState.DISABLED, selection.state)
        assertEquals(1, selection.candidates.size)
    }

    @Test
    fun nullSpecPassesThrough() {
        val frame = FrameResult(listOf(obj(1, "cup"), obj(2, "chair")))
        val selection = selector.select(frame, null)
        assertEquals(TargetSelectionState.DISABLED, selection.state)
        assertEquals(2, selection.candidates.size)
    }
}
