package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoLibraryTest {

    @Test
    fun sessionIdParsedFromSessionNamedPhoto() {
        assertEquals(
            "s_20260819_145301",
            PhotoLibrary.sessionIdFromDisplayName("SnapSight_s_20260819_145301.jpg"),
        )
    }

    @Test
    fun legacyTimestampNameHasNoSessionId() {
        assertNull(PhotoLibrary.sessionIdFromDisplayName("SnapSight_20260814_153012_123.jpg"))
    }

    @Test
    fun nullNameHasNoSessionId() {
        assertNull(PhotoLibrary.sessionIdFromDisplayName(null))
    }
}
