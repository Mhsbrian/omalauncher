package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperTargetsTest {
    @Test fun writesOnlyThePicturesAskedFor() {
        // WallpaperManager.FLAG_SYSTEM = 1, FLAG_LOCK = 2.
        assertEquals(0, pictureFlags(HomeWallpaper.NONE, lock = false))
        assertEquals(0, pictureFlags(HomeWallpaper.LIVE, lock = false))
        assertEquals(2, pictureFlags(HomeWallpaper.LIVE, lock = true))
        assertEquals(2, pictureFlags(HomeWallpaper.NONE, lock = true))
        assertEquals(1, pictureFlags(HomeWallpaper.PICTURE, lock = false))
        assertEquals(3, pictureFlags(HomeWallpaper.PICTURE, lock = true))
    }

    @Test fun readsHomeChoicesBack() {
        for (mode in HomeWallpaper.entries) assertEquals(mode, HomeWallpaper.of(mode.id))
        assertNull(HomeWallpaper.of(null))
        assertNull(HomeWallpaper.of("something else"))
    }

    @Test fun anotherBackgroundOrChoiceIsAnotherPicture() {
        val key = pictureKey("/themes/a/1.jpg", 100, 5, 2)
        assertEquals(key, pictureKey("/themes/a/1.jpg", 100, 5, 2))
        assertNotEquals(key, pictureKey("/themes/a/2.jpg", 100, 5, 2))
        assertNotEquals(key, pictureKey("/themes/a/1.jpg", 101, 5, 2))
        assertNotEquals(key, pictureKey("/themes/a/1.jpg", 100, 6, 2))
        assertNotEquals(key, pictureKey("/themes/a/1.jpg", 100, 5, 3))
    }

    @Test fun eachScreenOfAFoldableGetsThePictureOnce() {
        val cover = screenId(1080, 2520)
        val main = screenId(2184, 1968)
        val first = PictureRecord("", emptySet()).plus("a", cover)
        assertTrue(first.covers("a", cover))
        assertFalse(first.covers("a", main))
        val both = first.plus("a", main)
        assertTrue(both.covers("a", cover) && both.covers("a", main))
        // A new background starts over, on the screen it was set on.
        val next = both.plus("b", main)
        assertEquals(setOf(main), next.screens)
        assertFalse(next.covers("a", main))
        assertFalse(next.covers("b", cover))
    }

    @Test fun aScreenIsTheSameInEitherOrientation() {
        assertEquals("1080x2400", screenId(1080, 2400))
        assertEquals("1080x2400", screenId(2400, 1080))
    }

    @Test fun namesAFoldablesScreensBySize() {
        assertEquals(emptyMap<String, String>(), screenNames(emptyList()))
        assertEquals(mapOf("1080x2400" to "screen"), screenNames(listOf("1080x2400")))
        assertEquals(
            mapOf("1080x2520" to "cover", "1968x2184" to "main"),
            screenNames(listOf("1968x2184", "1080x2520", "1968x2184")),
        )
        assertEquals(listOf("screen 1", "screen 2", "screen 3"), screenNames(listOf("1440x3120", "1080x2340", "1080x2400")).values.toList())
    }

    @Test fun liveHomeIsWhatWasSeenOnThatScreen() {
        assertEquals(WallpaperStatus.Mark.SET, homeStatus(HomeWallpaper.LIVE, seen = true, pictured = false, here = false, busy = false, failed = false).mark)
        assertEquals(WallpaperStatus.Mark.MISSING, homeStatus(HomeWallpaper.LIVE, seen = false, pictured = false, here = true, busy = false, failed = false).mark)
        assertEquals(WallpaperStatus.Mark.WORKING, homeStatus(HomeWallpaper.LIVE, seen = null, pictured = false, here = true, busy = false, failed = false).mark)
        assertEquals(WallpaperStatus.Mark.WAITING, homeStatus(HomeWallpaper.LIVE, seen = null, pictured = false, here = false, busy = false, failed = false).mark)
        assertEquals(WallpaperStatus.Mark.OFF, homeStatus(HomeWallpaper.NONE, seen = true, pictured = true, here = true, busy = false, failed = false).mark)
    }

    @Test fun picturesWaitForTheirScreen() {
        fun lock(
            pictured: Boolean,
            here: Boolean,
            busy: Boolean = false,
            failed: Boolean = false,
        ) = lockStatus(true, pictured, here, busy, failed).mark
        assertEquals(WallpaperStatus.Mark.SET, lock(pictured = true, here = false))
        assertEquals(WallpaperStatus.Mark.WAITING, lock(pictured = false, here = false))
        assertEquals(WallpaperStatus.Mark.WORKING, lock(pictured = false, here = true, busy = true))
        assertEquals(WallpaperStatus.Mark.MISSING, lock(pictured = false, here = true, failed = true))
        assertEquals(WallpaperStatus.Mark.OFF, lockStatus(false, pictured = true, here = true, busy = false, failed = false).mark)
        assertEquals(
            homeStatus(HomeWallpaper.PICTURE, seen = null, pictured = false, here = true, busy = false, failed = true),
            lockStatus(true, pictured = false, here = true, busy = false, failed = true),
        )
    }
}
