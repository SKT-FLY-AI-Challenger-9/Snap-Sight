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

    @Test
    fun selectedPhotoMapsBackToOriginalSession() {
        assertEquals(
            "9a65c1c0-1111-2222-3333-123456789abc",
            PhotoLibrary.sessionIdFromDisplayName(
                "SnapSight_9a65c1c0-1111-2222-3333-123456789abc_selected.jpg"
            ),
        )
    }

    @Test
    fun selectedPhotoReplacesOriginalInGalleryPreference() {
        val names = listOf(
            "SnapSight_s_new.jpg",
            "SnapSight_s_same.jpg",
            "SnapSight_s_same_selected.jpg",
            "SnapSight_s_old.jpg",
        )

        assertEquals(listOf(0, 2, 3), PhotoLibrary.preferredPhotoIndices(names))
    }

    @Test
    fun newerSelectedRemainsPreferredOverOriginal() {
        val names = listOf(
            "SnapSight_s_same_selected.jpg",
            "SnapSight_s_same.jpg",
        )

        assertEquals(listOf(0), PhotoLibrary.preferredPhotoIndices(names))
    }

    @Test
    fun similarlyNamedForeignPhotoIsNotClaimed() {
        assertNull(PhotoLibrary.sessionIdFromDisplayName("Other_s_example.jpg"))
    }

    @Test
    fun canonicalDisplayNameUsesSelectedSuffix() {
        assertEquals(
            "SnapSight_9a65c1c0-1111-2222-3333-123456789abc_selected.jpg",
            CanonicalFrameStore.displayNameFor("9a65c1c0-1111-2222-3333-123456789abc"),
        )
    }
}
