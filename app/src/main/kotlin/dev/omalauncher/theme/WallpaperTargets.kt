package dev.omalauncher.theme

import android.app.WallpaperManager

/** What the launcher makes of Android's home screen wallpaper; see [SystemWallpaper]. */
enum class HomeWallpaper(
    val id: String,
) {
    /** Our live wallpaper, set through Android's picker: system colors follow the theme. */
    LIVE("live"),

    /** The theme's background written as a plain picture, without asking each time. */
    PICTURE("picture"),

    /** Left alone: the launcher draws the background in its own window. */
    NONE("none"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** WallpaperManager's `which` for the pictures to write: 0 when there are none. */
fun pictureFlags(
    home: HomeWallpaper,
    lock: Boolean,
): Int = (if (home == HomeWallpaper.PICTURE) WallpaperManager.FLAG_SYSTEM else 0) or (if (lock) WallpaperManager.FLAG_LOCK else 0)

/**
 * What a picture write was: the background as it was on disk, and where it went. A new background,
 * an edit of the file, or another home/lock choice makes another key, so the picture is written again.
 */
fun pictureKey(
    path: String,
    length: Long,
    modified: Long,
    which: Int,
) = "$which:$length:$modified:$path"

/**
 * The screens a picture was written on. Samsung's foldables keep a wallpaper for each screen and only
 * set the one in use, so the launcher writes the picture again on each screen it shows on; elsewhere the
 * second write is the same picture again.
 */
data class PictureRecord(
    val key: String,
    val screens: Set<String>,
) {
    fun covers(
        key: String,
        screen: String,
    ) = key == this.key && screen in screens

    /** This record with [screen] written for [key]; a new key starts over. */
    fun plus(
        key: String,
        screen: String,
    ) = if (key == this.key) copy(screens = screens + screen) else PictureRecord(key, setOf(screen))
}

/** A screen by its full size in pixels, the same in either orientation: "1080x2520". */
fun screenId(
    width: Int,
    height: Int,
) = "${minOf(width, height)}x${maxOf(width, height)}"

private fun area(id: String) =
    id.split('x').mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 }?.let { (w, h) -> w * h } ?: 0L

/**
 * Names for [screens], smallest first: just "screen" for one, a foldable's "cover" and "main" for two,
 * numbers past that (a resolution change makes a screen look new).
 */
fun screenNames(screens: Collection<String>): Map<String, String> {
    val sorted = screens.distinct().sortedWith(compareBy({ area(it) }, { it }))
    return when (sorted.size) {
        0 -> emptyMap()
        1 -> mapOf(sorted[0] to "screen")
        2 -> mapOf(sorted[0] to "cover", sorted[1] to "main")
        else -> sorted.mapIndexed { i, id -> id to "screen ${i + 1}" }.toMap()
    }
}

/** How one of Android's wallpapers stands on a screen, for the status table. */
data class WallpaperStatus(
    val mark: Mark,
    val text: String,
) {
    enum class Mark { SET, MISSING, WORKING, WAITING, OFF }
}

/**
 * The home screen's wallpaper on a screen: whether our live wallpaper was [seen] there (null when not
 * checked), or the picture was written there ([pictured]). [here] is the screen the launcher is on, which
 * is the only one a write or a check can happen on.
 */
fun homeStatus(
    home: HomeWallpaper,
    seen: Boolean?,
    pictured: Boolean,
    here: Boolean,
    busy: Boolean,
    failed: Boolean,
): WallpaperStatus =
    when (home) {
        HomeWallpaper.LIVE ->
            when (seen) {
                true -> WallpaperStatus(WallpaperStatus.Mark.SET, "✓ live")
                false -> WallpaperStatus(WallpaperStatus.Mark.MISSING, "✗ not set")
                null -> if (here) WallpaperStatus(WallpaperStatus.Mark.WORKING, "… checking") else WallpaperStatus(WallpaperStatus.Mark.WAITING, "· not seen yet")
            }
        HomeWallpaper.PICTURE -> pictureStatus(pictured, here, busy, failed)
        HomeWallpaper.NONE -> WallpaperStatus(WallpaperStatus.Mark.OFF, "– launcher only")
    }

/** The lock screen's wallpaper on a screen; see [homeStatus]. */
fun lockStatus(
    lock: Boolean,
    pictured: Boolean,
    here: Boolean,
    busy: Boolean,
    failed: Boolean,
): WallpaperStatus = if (lock) pictureStatus(pictured, here, busy, failed) else WallpaperStatus(WallpaperStatus.Mark.OFF, "– left alone")

private fun pictureStatus(
    pictured: Boolean,
    here: Boolean,
    busy: Boolean,
    failed: Boolean,
) = when {
    pictured -> WallpaperStatus(WallpaperStatus.Mark.SET, "✓ picture")
    busy -> WallpaperStatus(WallpaperStatus.Mark.WORKING, "… setting")
    failed -> WallpaperStatus(WallpaperStatus.Mark.MISSING, "✗ failed")
    here -> WallpaperStatus(WallpaperStatus.Mark.WORKING, "… setting")
    else -> WallpaperStatus(WallpaperStatus.Mark.WAITING, "· next time it's open")
}
