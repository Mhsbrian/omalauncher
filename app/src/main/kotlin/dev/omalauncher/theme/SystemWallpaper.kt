package dev.omalauncher.theme

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "wallpaper"

/**
 * Android's own wallpapers, beyond the background the launcher draws in its window: the theme's
 * background written as a picture to the home and lock screens (WallpaperManager.setStream, which
 * needs only SET_WALLPAPER), and whether our live wallpaper shows on the screen the launcher is on.
 *
 * Samsung's foldables keep a wallpaper per screen (cover and main, each with its own lock screen)
 * and set only the screen in use, whether an app sets it or the live wallpaper picker does. So the
 * pictures are written again the first time the launcher shows on each screen, and the live
 * wallpaper is checked on each: where it doesn't show, the launcher draws the background itself
 * and offers the picker again.
 */
class SystemWallpaper(
    private val context: Context,
    private val themes: ThemeStore,
) {
    private val manager = WallpaperManager.getInstance(context)
    private val prefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "wallpaper job failed", e) })
    private val writing = Mutex()

    /** What's checked and written while the launcher shows; cancelled when it goes to the background. */
    private var session: Job? = null

    /** Whether the launcher is in front, so a write lands on the screen it's on. */
    private var shown = false

    /** Whether Android lets apps set the wallpaper: not on some Android Go phones, nor under a device policy against it. */
    var canSet by mutableStateOf(true)
        private set

    private var chosenHome by mutableStateOf(HomeWallpaper.of(prefs.getString(HOME, null)))

    /** The home screen's wallpaper: as picked, or before that, live if ours is set. */
    val home get() = chosenHome ?: if (themes.liveWallpaperSet) HomeWallpaper.LIVE else HomeWallpaper.NONE

    /** Whether the lock screen gets the background as a picture. */
    var lock by mutableStateOf(prefs.getBoolean(LOCK, false))
        private set

    /** The screen the launcher last showed on (see [screenId]); null until it has. */
    var screen by mutableStateOf<String?>(null)
        private set

    /** Every screen the launcher has shown on, to name them; one unless it's a foldable. */
    var screens by mutableStateOf(prefs.getStringSet(SCREENS, null).orEmpty().toSet())
        private set

    /** Where the pictures were written; see [PictureRecord]. */
    private var record by mutableStateOf(PictureRecord(prefs.getString(WRITTEN_KEY, null).orEmpty(), prefs.getStringSet(WRITTEN_SCREENS, null).orEmpty().toSet()))

    /** What the pictures to write would be now; see [pictureKey]. Null with nothing to write. */
    private var wantedKey by mutableStateOf<String?>(null)

    /** A picture being written on [screen]. */
    var busy by mutableStateOf(false)
        private set

    /** Why the last write on [screen] failed; cleared by the next that works. */
    var failure by mutableStateOf<String?>(null)
        private set

    /** Screens our live wallpaper was seen behind the launcher on (true) or found missing on (false), since the app started. */
    private val liveSeen = mutableStateMapOf<String, Boolean>()

    /** Android's id for its home wallpaper when [liveSeen] was last filled in; a new one makes what was seen elsewhere stale. */
    private var seenWithId: Int? = null

    /** Writes the pictures again once the background changes, if the launcher is showing to say on which screen. */
    private val onThemeChanged: () -> Unit = { rewrite() }

    init {
        ThemeStore.changeListeners.add(onThemeChanged)
    }

    /** The launcher came to the front, or the screen under it changed (a foldable folded or unfolded). */
    fun onShown(activity: Activity) {
        val id = screenOf(activity)
        screen = id
        if (id !in screens) {
            screens = screens + id
            prefs.edit().putStringSet(SCREENS, screens).apply()
        }
        shown = true
        session?.cancel()
        session = scope.launch { check(id) }
    }

    /** The launcher went to the background: what shows behind it can't be checked, and a write would land on whichever screen is open. */
    fun onHidden() {
        shown = false
        session?.cancel()
        session = null
    }

    fun chooseHome(mode: HomeWallpaper) {
        chosenHome = mode
        prefs.edit().putString(HOME, mode.id).apply()
        rewrite()
    }

    fun chooseLock(on: Boolean) {
        lock = on
        prefs.edit().putBoolean(LOCK, on).apply()
        rewrite()
    }

    /** Writes the pictures on the current screen again after a failure. */
    fun retry() = rewrite()

    /** Opens Android's live wallpaper picker; on a Samsung foldable it sets the screen in use. */
    fun liveWallpaperIntent(): Intent = themes.liveWallpaperIntent()

    /** How the home screen's wallpaper stands on [screen]. */
    fun homeStatus(screen: String): WallpaperStatus {
        val here = screen == this.screen
        return homeStatus(home, liveSeen[screen], pictured(screen), here, here && busy, here && failure != null)
    }

    /** How the lock screen's wallpaper stands on [screen]. */
    fun lockStatus(screen: String): WallpaperStatus {
        val here = screen == this.screen
        return lockStatus(lock, pictured(screen), here, here && busy, here && failure != null)
    }

    private fun pictured(screen: String) = wantedKey?.let { record.covers(it, screen) } ?: false

    private fun rewrite() {
        val s = screen ?: return
        if (shown) scope.launch { writePictures(s) }
    }

    /** On coming to the front: the live wallpaper's state, the pictures, then whether ours shows behind us. */
    private suspend fun check(screen: String) {
        canSet = manager.isWallpaperSupported && manager.isSetWallpaperAllowed
        // After a start, the saved theme and background are still being read.
        snapshotFlow { themes.loaded }.first { it }
        themes.refreshLiveWallpaper()
        val homeId = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        if (homeId != seenWithId) {
            liveSeen.clear()
            seenWithId = homeId
        }
        writePictures(screen)
        if (!themes.liveWallpaperSet) {
            liveSeen[screen] = false
            themes.setLiveWallpaperShows(true)
            return
        }
        // Ours shows within a moment of the launcher coming to the front, or isn't on this screen. Until it
        // shows, the launcher draws the background itself rather than show another wallpaper through its window.
        val seen = withTimeoutOrNull(LIVE_GRACE_MS) { OmarchyWallpaperService.showingOnHome.first { it > 0 } } != null
        if (!seen) {
            Log.i(TAG, "live wallpaper set but not showing on $screen")
            liveSeen[screen] = false
            themes.setLiveWallpaperShows(false)
            OmarchyWallpaperService.showingOnHome.first { it > 0 }
        }
        liveSeen[screen] = true
        themes.setLiveWallpaperShows(true)
        // Set in Android's settings over our picture: stop writing over it.
        if (chosenHome == HomeWallpaper.PICTURE) chooseHome(HomeWallpaper.LIVE)
    }

    /**
     * Writes the background as a picture to the home and/or lock screen, unless it's already on [screen].
     * Android picks the screen by the hinge on Samsung's foldables, so a write the screen changed during
     * isn't recorded: the next check writes it again.
     */
    private suspend fun writePictures(screen: String) =
        writing.withLock {
            val which = pictureFlags(home, lock)
            val file = themes.background
            failure =
                when {
                    which == 0 -> null
                    file == null -> "the theme has no background to set"
                    !canSet -> "android doesn't let apps set the wallpaper here"
                    else -> failure
                }
            if (which == 0 || file == null || !canSet) {
                wantedKey = null
                return@withLock
            }
            val key = withContext(Dispatchers.IO) { pictureKey(file.path, file.length(), file.lastModified(), which) }
            wantedKey = key
            if (record.covers(key, screen)) {
                failure = null
                return@withLock
            }
            busy = true
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching { file.inputStream().buffered().use { manager.setStream(it, null, true, which) } }
                    }
                val id = result.getOrNull() ?: 0
                if (id == 0) {
                    Log.w(TAG, "couldn't set ${file.name} (which=$which) on $screen", result.exceptionOrNull())
                    failure = result.exceptionOrNull()?.message ?: "android didn't take the picture"
                    return@withLock
                }
                failure = null
                if (screen != this.screen) return@withLock
                Log.i(TAG, "set ${file.name} (which=$which) on $screen as $id")
                record = record.plus(key, screen)
                prefs.edit().putString(WRITTEN_KEY, record.key).putStringSet(WRITTEN_SCREENS, record.screens).apply()
                // A home picture replaces our live wallpaper.
                if (which and WallpaperManager.FLAG_SYSTEM != 0) themes.refreshLiveWallpaper()
            } finally {
                busy = false
            }
        }

    private fun screenOf(activity: Activity): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = activity.windowManager.maximumWindowMetrics.bounds
            return screenId(bounds.width(), bounds.height())
        }
        val size = Point()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getRealSize(size)
        return screenId(size.x, size.y)
    }

    private companion object {
        const val HOME = "home"
        const val LOCK = "lock"
        const val SCREENS = "screens"
        const val WRITTEN_KEY = "written_key"
        const val WRITTEN_SCREENS = "written_screens"

        /** How long after the launcher comes to the front our live wallpaper has to show behind it. */
        const val LIVE_GRACE_MS = 2_000L
    }
}
