package dev.omalauncher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.theme.HomeWallpaper
import dev.omalauncher.theme.SystemWallpaper
import dev.omalauncher.theme.WallpaperStatus
import dev.omalauncher.theme.screenNames

/** The two of Android's wallpapers a picture can be written to; asked about before the first write. */
private enum class PictureFor { HOME, LOCK }

/**
 * Android's own wallpapers, made from the background above: the home screen's (our live wallpaper,
 * the background as a picture, or left to the launcher) and the lock screen's, with how each stands
 * on every screen the launcher has shown on; see [SystemWallpaper].
 */
@Composable
internal fun AndroidWallpaperSection(wallpapers: SystemWallpaper) {
    val context = LocalContext.current
    var confirming by remember { mutableStateOf<PictureFor?>(null) }

    // Some phones (Android Go) have no live wallpaper picker.
    fun openPicker() {
        runCatching { context.startActivity(wallpapers.liveWallpaperIntent()) }
    }
    val here = wallpapers.screen
    val liveMissingHere = here != null && wallpapers.home == HomeWallpaper.LIVE && wallpapers.homeStatus(here).mark == WallpaperStatus.Mark.MISSING

    Heading("android wallpaper")
    val homes = if (wallpapers.canSet) HomeWallpaper.entries else listOf(HomeWallpaper.LIVE, HomeWallpaper.NONE)
    StyleOption("home", homes.map { it.label }, homes.indexOf(wallpapers.home)) { i ->
        val mode = homes[i]
        when {
            mode == wallpapers.home -> if (liveMissingHere) openPicker()
            mode == HomeWallpaper.PICTURE -> confirming = PictureFor.HOME
            else -> {
                wallpapers.chooseHome(mode)
                if (mode == HomeWallpaper.LIVE && here != null && wallpapers.homeStatus(here).mark != WallpaperStatus.Mark.SET) openPicker()
            }
        }
    }
    OptionHint(
        when (wallpapers.home) {
            // SystemUI defers live-wallpaper color changes until the screen turns off.
            HomeWallpaper.LIVE -> "omalauncher's live wallpaper; system colors follow the theme after the screen turns off"
            HomeWallpaper.PICTURE -> "the background as a plain picture; android takes system colors from it"
            HomeWallpaper.NONE -> "only omalauncher shows the background; android's wallpaper stays as it is"
        },
        Modifier.padding(bottom = 4.dp),
    )
    if (wallpapers.canSet || wallpapers.lock) {
        StyleOption("lock", listOf("picture", "leave"), if (wallpapers.lock) 0 else 1) { i ->
            if (i == 1) {
                wallpapers.chooseLock(false)
            } else if (!wallpapers.lock) {
                confirming = PictureFor.LOCK
            }
        }
        OptionHint(
            when {
                !wallpapers.lock -> "android's lock screen wallpaper stays as it is"
                // SystemUI takes system colors from the wallpaper set last, unless its newer picker is on.
                wallpapers.home == HomeWallpaper.LIVE -> "the background as a plain picture, which every lock screen shows; android may take system colors from it rather than the theme"
                else -> "the background as a plain picture, which every lock screen shows"
            },
            Modifier.padding(bottom = 4.dp),
        )
    }
    ScreenTable(wallpapers)
    if (liveMissingHere) BracketAction("set the live wallpaper on this screen", Palette.accent, ::openPicker)
    wallpapers.failure?.let { reason ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(reason, Modifier.weight(1f), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            BracketAction("retry", Palette.accent, wallpapers::retry)
        }
    }

    confirming?.let { target ->
        val colors = if (target == PictureFor.HOME) " Android takes system colors from it too." else ""
        ConfirmDialog(
            if (target == PictureFor.HOME) "set android's home wallpaper?" else "set android's lock screen wallpaper?",
            "The theme's background replaces it, now and whenever the theme or background changes.$colors " +
                "Apps can't keep a copy of the wallpaper there now, so it can't be put back.",
            "set",
            onDismiss = { confirming = null },
        ) {
            confirming = null
            if (target == PictureFor.HOME) wallpapers.chooseHome(HomeWallpaper.PICTURE) else wallpapers.chooseLock(true)
        }
    }
}

private val HomeWallpaper.label
    get() =
        when (this) {
            HomeWallpaper.LIVE -> "live"
            HomeWallpaper.PICTURE -> "picture"
            HomeWallpaper.NONE -> "leave"
        }

/**
 * Home and lock on each screen the launcher has shown on, the one it's on marked: a foldable's
 * screens each keep their own wallpaper, and only the one in use can be set or checked.
 */
@Composable
private fun ScreenTable(wallpapers: SystemWallpaper) {
    val names = screenNames(wallpapers.screens)
    if (names.isEmpty()) return
    val small = Mono.copy(fontSize = TextSize.Small)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OptionLabel("")
        BasicText("home", Modifier.weight(1f), style = small.copy(color = Palette.muted))
        BasicText("lock", Modifier.weight(1f), style = small.copy(color = Palette.muted))
    }
    for ((screen, name) in names) {
        val here = screen == wallpapers.screen
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            OptionLabel(if (here) "▸ $name" else "  $name")
            Cell(wallpapers.homeStatus(screen), Modifier.weight(1f))
            Cell(wallpapers.lockStatus(screen), Modifier.weight(1f))
        }
    }
    if (names.size > 1) OptionHint("each screen keeps its own wallpaper and updates the first time omalauncher shows on it", Modifier.padding(top = 4.dp))
}

@Composable
private fun Cell(
    status: WallpaperStatus,
    modifier: Modifier,
) {
    val color =
        when (status.mark) {
            WallpaperStatus.Mark.SET -> Palette.green
            WallpaperStatus.Mark.MISSING -> Palette.red
            WallpaperStatus.Mark.WORKING -> Palette.foreground
            WallpaperStatus.Mark.WAITING, WallpaperStatus.Mark.OFF -> Palette.muted
        }
    BasicText(status.text, modifier, style = Mono.copy(color = color, fontSize = TextSize.Small))
}
