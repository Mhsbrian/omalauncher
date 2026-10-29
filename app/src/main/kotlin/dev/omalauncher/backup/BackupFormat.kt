package dev.omalauncher.backup

import dev.omalauncher.theme.StyleFile
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.theme.Toml
import dev.omalauncher.upgrade.Versioned
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A SharedPreferences file in a backup. [keys] are the ones it holds, null for all of them but
 * [exclude]; [reset] are removed on restore, since they record what was done on this install.
 */
internal class PrefsPart(
    val name: String,
    val keys: Set<String>?,
    val reset: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
)

/**
 * What a backup of the home screen holds and how its zip is laid out. It's the launcher's own
 * data as stored, so a restore puts the same values back and each store's Versioned migrations
 * still apply to what an older app saved. Linux and anything tied to this install (setup, GPU,
 * caches, the Omarchy download) stay out.
 */
internal object BackupFormat {
    /** The zip's layout; a backup from a newer layout than this app reads is refused. */
    const val VERSION = 1

    /** What, when and from which app; written first. */
    const val INFO = "backup.json"

    val PREFS =
        listOf(
            // Pinned apps and folders, and the order of each folder's apps.
            PrefsPart("shortcuts", null),
            // Where every tile sits and on which page; the rows per screen size are this phone's.
            PrefsPart("widget_grid", setOf("layout")),
            // The recent apps strip.
            PrefsPart("app_usage", null),
            // Before style.toml (0.7), the theme, background and every option on the style screen; kept for
            // restoring such a backup, and for an older launcher. rendered_by only says Linux's theme files
            // were written here, so they're written again.
            PrefsPart("themes", null, reset = setOf("rendered_by"), exclude = setOf("rendered_by")),
            PrefsPart("feed", setOf("settings")),
            // The terminal's text size and font before style.toml, what swiping up and down on home do
            // (settings > gestures) and the language picked in settings; the rest of these preferences are this install's.
            PrefsPart(
                "launcher",
                setOf("terminal_text", "terminal_font", "gesture_swipe_up", "gesture_swipe_down", "gestures_in_style", "fork_swipe_down_in_style", "language"),
            ),
        )

    /**
     * Directories under the app's files, kept whole except dot-entries: the widget engine's
     * errors, checks, previews, inbox and trash, icon-check's checks and previews, and temporary files.
     */
    val DIRS = listOf("widgets", "widget-state", "themes", "backgrounds", "icons", "icon-packs")

    /** The style, from 0.7: theme, background and the style screen's options. defaults.toml and errors.txt beside it are this app's. */
    const val STYLE = "style/style.toml"

    /**
     * Home's layout as a file agents edit (from upstream 0.10): the preferences above stay what home draws from, so a
     * backup without it restores them, and the launcher writes this again from them at its next start.
     */
    const val HOME = "home/layout.toml"

    /**
     * Files under the app's files. The launcher's record of the default widgets it wrote keeps a
     * restored default updating, and one deleted before the backup deleted.
     */
    val FILES = listOf("launcher-managed.json", STYLE, HOME)

    fun prefsEntry(name: String) = "prefs/$name.json"

    fun fileEntry(path: String) = "files/$path"
}

/** Where a zip entry goes; [entryFor] refuses everything else, so a backup can only write where the launcher keeps these. */
internal sealed interface Entry {
    data object Info : Entry

    data class Prefs(
        val part: PrefsPart,
    ) : Entry

    /** [path] is relative to the app's files: `widgets/clock.owl`. */
    data class Data(
        val path: String,
    ) : Entry
}

internal fun entryFor(name: String): Entry? {
    if (name == BackupFormat.INFO) return Entry.Info
    BackupFormat.PREFS.firstOrNull { BackupFormat.prefsEntry(it.name) == name }?.let { return Entry.Prefs(it) }
    if (!name.startsWith("files/")) return null
    val path = name.removePrefix("files/")
    if (path in BackupFormat.FILES) return Entry.Data(path)
    val parts = path.split('/')
    if (parts.size < 2 || parts[0] !in BackupFormat.DIRS) return null
    if (parts.any { it.isEmpty() || it.startsWith(".") || '\\' in it || '\u0000' in it }) return null
    return Entry.Data(path)
}

/** A preference's value as JSON, with its type, since JSON can't tell an Int from a Long or a Float. */
internal fun prefsToJson(
    values: Map<String, *>,
    part: PrefsPart,
): JSONObject {
    val o = JSONObject()
    for ((key, value) in values) {
        if (part.keys != null && key !in part.keys || key in part.exclude) continue
        val typed =
            when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
                else -> continue
            }
        o.put(key, typed)
    }
    return o
}

/** The values [prefsToJson] wrote, as the types SharedPreferences hands out; entries it can't read are left out. */
internal fun prefsFromJson(o: JSONObject): Map<String, Any> =
    o.keys().asSequence().mapNotNull { key ->
        val e = o.optJSONObject(key) ?: return@mapNotNull null
        val value: Any =
            runCatching {
                when (e.getString("t")) {
                    "s" -> e.getString("v")
                    "b" -> e.getBoolean("v")
                    "i" -> e.getInt("v")
                    "l" -> e.getLong("v")
                    "f" -> e.getDouble("v").toFloat()
                    "ss" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> null
                }
            }.getOrNull() ?: return@mapNotNull null
        key to value
    }.toMap()

/** How restoring [values] changes a preferences file: everything cleared first, or only [part]'s keys set or removed. */
internal data class PrefsEdit(
    val clearAll: Boolean,
    val put: Map<String, Any>,
    val remove: Set<String>,
)

internal fun prefsEdit(
    part: PrefsPart,
    values: Map<String, Any>,
): PrefsEdit {
    val keys = part.keys
    if (keys == null) return PrefsEdit(clearAll = true, put = values, remove = part.reset)
    val put = values.filterKeys { it in keys }
    return PrefsEdit(clearAll = false, put = put, remove = (keys - put.keys) + part.reset)
}

/** About a backup, from its [BackupFormat.INFO]. */
internal data class BackupInfo(
    val version: Int,
    val appVersion: String,
    val versionCode: Long,
    val createdAt: Long,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("format", version)
            .put("app", appVersion)
            .put("versionCode", versionCode)
            .put("created", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = BackupInfo(o.getInt("format"), o.optString("app"), o.optLong("versionCode"), o.optLong("created"))
    }
}

/** What a home screen holds, to say what a backup has before it's restored. */
internal data class Summary(
    /** Pinned apps, in folders or not. */
    val apps: Int,
    val folders: Int,
    /** Widget files on the home screen; the examples beside them aren't. */
    val widgets: Int,
    val theme: String?,
)

/**
 * [shortcuts] and [themes] as their preferences hold them, [style] the style.toml, [widgetsDir]
 * the widget files. The theme is style.toml's; a backup from before it has it in [themes].
 */
internal fun summarize(
    shortcuts: Map<String, Any>,
    themes: Map<String, Any>,
    style: File,
    widgetsDir: File,
): Summary {
    fun count(key: String) = (Versioned.read(shortcuts[key] as? String, 1) as? JSONArray)?.length() ?: 0
    val widgets = widgetsDir.listFiles { f -> f.isFile && f.name.endsWith(".owl") && !f.name.startsWith(".") }?.size ?: 0
    val theme =
        if (style.isFile) {
            runCatching { ThemeStore.themeOf(StyleFile.decode(Toml.parse(style.readText()).also(StyleFile::migrate)).style) }.getOrNull()
        } else {
            themes["theme"] as? String
        }
    return Summary(count("items"), count("folders"), widgets, theme)
}

/** Caps on what [readBackup] unpacks: a backup is a file from anywhere. */
internal data class Limits(
    val entries: Int = 20_000,
    val entryBytes: Long = 256L shl 20,
    val totalBytes: Long = 1L shl 30,
)

/** A backup unpacked into [dir] (its files under `files/`), ready for [applyFiles] and the preferences. */
internal class Staged(
    val info: BackupInfo,
    val prefs: Map<String, Map<String, Any>>,
    val dir: File,
    /** Entries this app doesn't know, left out. */
    val skipped: Int,
) {
    val files get() = File(dir, "files")

    fun summary() = summarize(prefs["shortcuts"].orEmpty(), prefs["themes"].orEmpty(), File(files, BackupFormat.STYLE), File(files, "widgets"))
}

/** Refused backups: the message is for the user. */
internal class BackupException(
    message: String,
) : IOException(message)

/**
 * Writes a backup of [filesDir]'s parts and [prefs] (by file name, as SharedPreferences.getAll
 * returns them). Symlinks are left out, never followed: Linux can put them in the widgets.
 */
internal fun writeBackup(
    out: OutputStream,
    info: BackupInfo,
    prefs: Map<String, Map<String, *>>,
    filesDir: File,
) {
    ZipOutputStream(out).use { zip ->
        fun put(
            name: String,
            bytes: ByteArray,
        ) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }

        fun put(
            name: String,
            file: File,
        ) {
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        put(BackupFormat.INFO, info.toJson().toString(2).toByteArray())
        for (part in BackupFormat.PREFS) {
            val values = prefs[part.name] ?: continue
            put(BackupFormat.prefsEntry(part.name), prefsToJson(values, part).toString(2).toByteArray())
        }
        for (path in BackupFormat.FILES) {
            val file = File(filesDir, path)
            if (file.isFile && !Files.isSymbolicLink(file.toPath())) put(BackupFormat.fileEntry(path), file)
        }
        for (dir in BackupFormat.DIRS) {
            val root = File(filesDir, dir)
            for (file in walk(root)) {
                val path = dir + "/" + file.relativeTo(root).invariantSeparatorsPath
                if (entryFor(BackupFormat.fileEntry(path)) != null) put(BackupFormat.fileEntry(path), file)
            }
        }
    }
}

/** Regular files under [root], not entering dot-directories or following symlinks. */
private fun walk(root: File): List<File> {
    if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
    val out = ArrayList<File>()
    for (f in root.listFiles().orEmpty().sortedBy { it.name }) {
        if (f.name.startsWith(".") || Files.isSymbolicLink(f.toPath())) continue
        if (f.isDirectory) out += walk(f) else if (f.isFile) out += f
    }
    return out
}

/**
 * Unpacks a backup into [staging], emptied first, checking every entry: only the places
 * [entryFor] allows, within [limits]. Throws [BackupException] for anything that isn't a
 * backup this app can restore.
 */
internal fun readBackup(
    input: InputStream,
    staging: File,
    limits: Limits = Limits(),
): Staged {
    staging.deleteRecursively()
    staging.mkdirs()
    var info: BackupInfo? = null
    val prefs = HashMap<String, Map<String, Any>>()
    var entries = 0
    var total = 0L
    var skipped = 0
    try {
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (++entries > limits.entries) throw BackupException("too many files for a backup")
                if (e.isDirectory) continue
                val target = entryFor(e.name)
                if (target == null) {
                    skipped++
                    continue
                }
                val cap = minOf(limits.entryBytes, limits.totalBytes - total)
                val out = java.io.ByteArrayOutputStream()
                val sink = if (target is Entry.Data) File(staging, "files/" + target.path).apply { parentFile?.mkdirs() }.outputStream() else out
                val n = sink.use { copyCapped(zip, it, cap) }
                if (n < 0) throw BackupException(if (cap < limits.entryBytes) "the backup is too large" else "${e.name} is too large")
                total += n
                when (target) {
                    Entry.Info -> info = runCatching { BackupInfo.fromJson(JSONObject(out.toByteArray().decodeToString())) }.getOrNull()
                    is Entry.Prefs -> prefs[target.part.name] = prefsFromJson(JSONObject(out.toByteArray().decodeToString()))
                    is Entry.Data -> {}
                }
            }
        }
    } catch (e: BackupException) {
        throw e
    } catch (e: Exception) {
        throw BackupException("not a backup this launcher can read (${e.message ?: e::class.simpleName})")
    }
    val found = info ?: throw BackupException("not an omalauncher backup")
    if (found.version > BackupFormat.VERSION) throw BackupException("made by a newer omalauncher (format ${found.version}); update it first")
    if (found.version < 1) throw BackupException("not an omalauncher backup")
    return Staged(found, prefs, staging, skipped)
}

/** Copies the rest of [input]'s current entry to [out]; its size, or -1 past [cap] bytes. */
private fun copyCapped(
    input: InputStream,
    out: OutputStream,
    cap: Long,
): Long {
    val buf = ByteArray(64 * 1024)
    var n = 0L
    while (true) {
        val r = input.read(buf)
        if (r < 0) return n
        n += r
        if (n > cap) return -1
        out.write(buf, 0, r)
    }
}

/**
 * Puts [staged]'s files in place of [filesDir]'s: each directory and file of [BackupFormat]
 * replaced whole, so what isn't in the backup goes too. A file is moved over the one it replaces,
 * never deleted first: the style's watcher would take a missing style.toml for the defaults.
 * [deleteTree] removes a directory without following symlinks.
 */
internal fun applyFiles(
    staged: Staged,
    filesDir: File,
    deleteTree: (File) -> Unit,
) {
    for (dir in BackupFormat.DIRS) {
        val target = File(filesDir, dir)
        if (target.exists()) deleteTree(target)
        val from = File(staged.files, dir)
        if (from.isDirectory && !from.renameTo(target)) throw IOException("can't move $dir into place")
    }
    for (path in BackupFormat.FILES) {
        val target = File(filesDir, path)
        val from = File(staged.files, path)
        if (!from.isFile) {
            target.delete()
            continue
        }
        target.parentFile?.mkdirs()
        if (!from.renameTo(target)) throw IOException("can't move $path into place")
    }
}
