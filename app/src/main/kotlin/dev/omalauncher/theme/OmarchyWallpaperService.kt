package dev.omalauncher.theme

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.omalauncher.ui.decodeForScreen
import dev.omalauncher.ui.wallpaperCrop
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Android live wallpaper showing the current Omarchy background. Its main
 * purpose is [Engine.onComputeColors]: Android 12+ derives the system color
 * scheme (quick settings, notifications, Settings, keyboard, dynamic-color
 * apps) from the colors the wallpaper reports, so we report the theme's
 * palette instead of colors sampled from the image.
 */
class OmarchyWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = OmarchyEngine()

    companion object {
        /**
         * How many of our engines show as a home screen wallpaper now, previews aside: one behind the
         * launcher means ours is the wallpaper of the screen it's on (see [SystemWallpaper]).
         */
        val showingOnHome = MutableStateFlow(0)
    }

    private inner class OmarchyEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private val decoder = Executors.newSingleThreadExecutor()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        private var colors: Map<String, String> = emptyMap()
        private var bitmap: Bitmap? = null
        private var loadedPath: String? = null

        /** Where the launcher's pages are scrolled, 0 to 1: the background slides along. */
        private var xOffset = 0.5f

        /** Whether this engine counts in [showingOnHome]. */
        private var counted = false

        private val onThemeChanged: () -> Unit = { main.post { reload() } }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(true)
            ThemeStore.changeListeners.add(onThemeChanged)
            reload()
        }

        override fun onDestroy() {
            countShowing(false)
            ThemeStore.changeListeners.remove(onThemeChanged)
            decoder.shutdownNow()
            super.onDestroy()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            if (xOffset == this.xOffset) return
            this.xOffset = xOffset
            if (isVisible) draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            countShowing(visible)
            if (visible) draw()
        }

        /** Android 14+ can move one engine between the home and lock screens. */
        override fun onWallpaperFlagsChanged(which: Int) {
            countShowing(isVisible)
        }

        private fun countShowing(visible: Boolean) {
            val home = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || wallpaperFlags and WallpaperManager.FLAG_SYSTEM != 0
            val counts = visible && !isPreview && home
            if (counts == counted) return
            counted = counts
            showingOnHome.update { it + if (counts) 1 else -1 }
        }

        /** Reads the saved theme off the main thread, then redraws with it. */
        private fun reload() {
            // A change posted just before the engine was destroyed.
            if (decoder.isShutdown) return
            // Sized for the display this engine is on: with more than one, each has an engine.
            val display = displayContext ?: this@OmarchyWallpaperService
            decoder.execute {
                val (themeColors, file) = ThemeStore.savedSelection(this@OmarchyWallpaperService) ?: (emptyMap<String, String>() to null)
                val decoded = if (file?.path != loadedPath) file?.let { decodeForScreen(it, display) } else null
                main.post {
                    val colorsChanged = themeColors != colors
                    colors = themeColors
                    if (file?.path != loadedPath) {
                        loadedPath = file?.path
                        bitmap?.recycle()
                        bitmap = decoded
                    }
                    draw()
                    if (colorsChanged) notifyColorsChanged()
                }
            }
        }

        private fun draw() {
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
            try {
                canvas.drawColor(color("background", Color.BLACK))
                bitmap?.let { bmp ->
                    val src = wallpaperCrop(bmp.width, bmp.height, canvas.width, canvas.height, xOffset)
                    canvas.drawBitmap(bmp, src, Rect(0, 0, canvas.width, canvas.height), paint)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun color(
            key: String,
            fallback: Int,
        ): Int = colors[key]?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

        /** The theme palette, accent first: Android seeds its system colors from the most prominent one. */
        override fun onComputeColors(): WallpaperColors? {
            if (colors.isEmpty()) return null
            val primary = Color.valueOf(color("accent", Color.BLUE))
            val secondary = Color.valueOf(color("magenta", color("accent", Color.BLUE)))
            val tertiary = Color.valueOf(color("background", Color.BLACK))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return WallpaperColors(primary, secondary, tertiary)
            val hints =
                if (colors["mode"] == "light") WallpaperColors.HINT_SUPPORTS_DARK_TEXT else WallpaperColors.HINT_SUPPORTS_DARK_THEME
            return WallpaperColors(primary, secondary, tertiary, hints)
        }
    }
}
