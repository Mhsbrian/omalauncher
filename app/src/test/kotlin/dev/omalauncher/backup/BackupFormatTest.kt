package dev.omalauncher.backup

import dev.omalauncher.upgrade.Versioned
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupFormatTest {
    @get:Rule val tmp = TemporaryFolder()

    private val info = BackupInfo(BackupFormat.VERSION, "0.5.0-mhs.2", 10002, 1_760_000_000_000)

    @Test fun entriesGoOnlyWhereTheLauncherKeepsThem() {
        for (ok in listOf(
            "backup.json",
            "prefs/shortcuts.json",
            "prefs/launcher.json",
            "files/launcher-managed.json",
            "files/style/style.toml",
            "files/home/layout.toml",
            "files/widgets/clock.owl",
            "files/widgets/examples/gauge.owl",
            "files/widget-state/clock.owl.json",
            "files/themes/mine/colors.toml",
            "files/backgrounds/tokyo-night/me.png",
            "files/icons/org.mozilla.firefox.svg",
            "files/icon-packs/neon/pack.toml",
        )) {
            assertTrue(ok, entryFor(ok) != null)
        }
        for (bad in listOf(
            "files/widgets/../shared_prefs/x.xml",
            "files/widgets/.errors/clock.owl.txt",
            "files/widgets/.hidden.owl",
            "files/widgets/a\\b.owl",
            "files/widgets//a.owl",
            "files/icons/.check/org.mozilla.firefox.svg.txt",
            "files/icons/.preview/org.mozilla.firefox.svg.png",
            "files/widgets",
            "files/style/defaults.toml",
            "files/style/errors.txt",
            "files/omarchy/themes/x",
            "files/shared_prefs/shortcuts.xml",
            "files/arch/etc/passwd",
            "prefs/evil.json",
            "prefs/../x.json",
            "/etc/passwd",
            "../backup.json",
            "widgets/clock.owl",
        )) {
            assertNull(bad, entryFor(bad))
        }
    }

    @Test fun prefsKeepTheirTypes() {
        val values = mapOf("s" to "x", "b" to true, "i" to 7, "l" to (1L shl 40), "f" to 1.5f, "ss" to setOf("a", "b"))
        val back = prefsFromJson(JSONObject(prefsToJson(values, PrefsPart("any", null)).toString()))
        assertEquals(values, back)
        assertEquals(Long::class.javaObjectType, back["l"]!!::class.javaObjectType)
        assertEquals(Int::class.javaObjectType, back["i"]!!::class.javaObjectType)
    }

    @Test fun onlyAPartsKeysAreBackedUp() {
        val themes = BackupFormat.PREFS.first { it.name == "themes" }
        // Every style option, one added after this test included, but not what says Linux was themed here.
        val o = prefsToJson(mapOf("theme" to "kanagawa", "rendered_by" to "9-123", "mono_icons" to true, "framed_system_bars" to false, "a_later_option" to 2), themes)
        assertEquals(setOf("theme", "mono_icons", "framed_system_bars", "a_later_option"), o.keys().asSequence().toSet())
        val launcher = BackupFormat.PREFS.first { it.name == "launcher" }
        val l =
            prefsToJson(
                mapOf("setup_done" to true, "gpu_wanted" to true, "terminal_font" to "JetBrains Mono", "gesture_swipe_up" to "none", "gesture_swipe_down" to "quick_settings"),
                launcher,
            )
        assertEquals(setOf("terminal_font", "gesture_swipe_up", "gesture_swipe_down"), l.keys().asSequence().toSet())
    }

    @Test fun restoringAWholeFileClearsItFirst() {
        val edit = prefsEdit(PrefsPart("shortcuts", null), mapOf("items" to "x"))
        assertTrue(edit.clearAll)
        assertEquals(mapOf("items" to "x"), edit.put)
    }

    @Test fun restoringSomeKeysLeavesTheRestAndResets() {
        val launcher = BackupFormat.PREFS.first { it.name == "launcher" }
        val edit = prefsEdit(launcher, mapOf("terminal_font" to "Hack", "setup_done" to true))
        assertFalse(edit.clearAll)
        // This install's own state (setup, GPU) stays as it is.
        assertEquals(mapOf("terminal_font" to "Hack"), edit.put)
        // A setting that wasn't set when backing up isn't left from before.
        assertTrue("gesture_swipe_up" in edit.remove)
        assertTrue("gesture_swipe_down" in edit.remove)
        assertFalse("setup_done" in edit.remove)
    }

    @Test fun restoringTheStyleReplacesItAndThemesLinuxAgain() {
        val themes = BackupFormat.PREFS.first { it.name == "themes" }
        val edit = prefsEdit(themes, mapOf("theme" to "kanagawa", "framed_system_bars" to false))
        assertTrue(edit.clearAll)
        assertEquals(mapOf("theme" to "kanagawa", "framed_system_bars" to false), edit.put)
        assertTrue("rendered_by" in edit.remove)
    }

    @Test fun backsUpAndRestoresTheHomeScreen() {
        val from = home()
        val prefs =
            mapOf(
                "shortcuts" to mapOf("items" to items(3), "folders" to Versioned.write(1, JSONArray().put(JSONObject().put("key", "folder:a")))),
                "widget_grid" to mapOf("layout" to "{\"v\":1,\"data\":{}}", "portrait_rows_1080x2400" to 10),
                "themes" to mapOf("theme" to "kanagawa", "rendered_by" to "x"),
                "launcher" to mapOf("setup_done" to true, "terminal_text" to "large"),
            )
        val zip = ByteArrayOutputStream().also { writeBackup(it, info, prefs, from) }.toByteArray()

        val staged = readBackup(ByteArrayInputStream(zip), tmp.newFolder("staging"))
        assertEquals(info, staged.info)
        assertEquals(0, staged.skipped)
        assertEquals(mapOf("layout" to "{\"v\":1,\"data\":{}}"), staged.prefs["widget_grid"])
        assertEquals(mapOf("terminal_text" to "large"), staged.prefs["launcher"])
        // The theme style.toml names, not the one the preferences kept from before it.
        assertEquals(Summary(apps = 3, folders = 1, widgets = 2, theme = "nord"), staged.summary())

        val to = tmp.newFolder("to")
        File(to, "style/style.toml").apply { parentFile.mkdirs() }.writeText("theme = \"old\"")
        File(to, "style/defaults.toml").writeText("this app's")
        File(to, "widgets/old.owl").apply { parentFile.mkdirs() }.writeText("old")
        File(to, "backgrounds/x/y.png").apply { parentFile.mkdirs() }.writeText("y")
        File(to, "omarchy/keep.txt").apply { parentFile.mkdirs() }.writeText("not ours to restore")
        applyFiles(staged, to) { it.deleteRecursively() }

        for (path in listOf("widgets/clock.owl", "widgets/notes.owl", "widgets/examples/gauge.owl", "widget-state/notes.owl.json", "themes/mine/colors.toml", "launcher-managed.json", "style/style.toml")) {
            assertArrayEquals(path, File(from, path).readBytes(), File(to, path).readBytes())
        }
        assertArrayEquals(File(from, "backgrounds/tokyo-night/me.png").readBytes(), File(to, "backgrounds/tokyo-night/me.png").readBytes())
        // What wasn't in the backup goes; caches, previews and links were never in it; the rest of the app's files stay.
        assertFalse(File(to, "widgets/old.owl").exists())
        assertFalse(File(to, "backgrounds/x").exists())
        assertFalse(File(to, "widgets/.errors").exists())
        assertFalse(File(to, "widgets/link.owl").exists())
        assertTrue(File(to, "omarchy/keep.txt").exists())
        // style.toml alone: its defaults and errors are this app's.
        assertEquals("this app's", File(to, "style/defaults.toml").readText())
        assertFalse(File(to, "style/errors.txt").exists())
    }

    @Test fun aStyleGoesWhereThereWasNone() {
        val from = tmp.newFolder("from")
        File(from, "style/style.toml").apply { parentFile.mkdirs() }.writeText("theme = \"nord\"")
        val zip = ByteArrayOutputStream().also { writeBackup(it, info, emptyMap(), from) }.toByteArray()
        val to = tmp.newFolder("to")
        applyFiles(readBackup(ByteArrayInputStream(zip), tmp.newFolder("s")), to) { it.deleteRecursively() }
        assertEquals("theme = \"nord\"", File(to, "style/style.toml").readText())
    }

    @Test fun homesLayoutFileGoesBackWithTheBackup() {
        val from = tmp.newFolder("from")
        File(from, "home/layout.toml").apply { parentFile.mkdirs() }.writeText("version = 1\n[[page]]\n")
        val zip = ByteArrayOutputStream().also { writeBackup(it, info, emptyMap(), from) }.toByteArray()
        val to = tmp.newFolder("to")
        File(to, "home/layout.toml").apply { parentFile.mkdirs() }.writeText("version = 1\n")
        applyFiles(readBackup(ByteArrayInputStream(zip), tmp.newFolder("s")), to) { it.deleteRecursively() }
        assertEquals("version = 1\n[[page]]\n", File(to, "home/layout.toml").readText())
    }

    @Test fun aBackupWithoutHomesLayoutFileLetsItBeWrittenAgainFromThePreferences() {
        val zip = ByteArrayOutputStream().also { writeBackup(it, info, emptyMap(), tmp.newFolder("from")) }.toByteArray()
        val to = tmp.newFolder("to")
        File(to, "home/layout.toml").apply { parentFile.mkdirs() }.writeText("version = 1\n[[page]]\n")
        applyFiles(readBackup(ByteArrayInputStream(zip), tmp.newFolder("s")), to) { it.deleteRecursively() }
        assertFalse(File(to, "home/layout.toml").exists())
    }

    @Test fun aStyleWithoutAThemeHasTheDefaultOne() {
        val from = tmp.newFolder("from")
        File(from, "style/style.toml").apply { parentFile.mkdirs() }.writeText("version = 1\n")
        val zip = ByteArrayOutputStream().also { writeBackup(it, info, emptyMap(), from) }.toByteArray()
        assertEquals("tokyo-night", readBackup(ByteArrayInputStream(zip), tmp.newFolder("s")).summary().theme)
    }

    @Test fun aBackupFromBeforeStyleTomlHasItsThemeInThePreferences() {
        val from = tmp.newFolder("from")
        File(from, "widgets/a.owl").apply { parentFile.mkdirs() }.writeText("a")
        val zip = ByteArrayOutputStream().also { writeBackup(it, info, mapOf("themes" to mapOf("theme" to "kanagawa")), from) }.toByteArray()
        assertEquals("kanagawa", readBackup(ByteArrayInputStream(zip), tmp.newFolder("s")).summary().theme)
    }

    @Test fun aRestoreWithoutTheRecordDropsTheOldOne() {
        val from = tmp.newFolder("from")
        File(from, "widgets/a.owl").apply { parentFile.mkdirs() }.writeText("a")
        val zip = ByteArrayOutputStream().also { writeBackup(it, info, emptyMap(), from) }.toByteArray()
        val to = tmp.newFolder("to")
        File(to, "launcher-managed.json").writeText("{}")
        applyFiles(readBackup(ByteArrayInputStream(zip), tmp.newFolder("s")), to) { it.deleteRecursively() }
        assertFalse(File(to, "launcher-managed.json").exists())
    }

    @Test fun unknownEntriesAreLeftOutAndNothingLeavesStaging() {
        val staging = tmp.newFolder("staging")
        val zip =
            zipOf(
                "backup.json" to info.toJson().toString(),
                "files/widgets/../../escaped.txt" to "x",
                "files/widgets/a.owl" to "a",
                "something/else" to "y",
            )
        val staged = readBackup(ByteArrayInputStream(zip), staging)
        assertEquals(2, staged.skipped)
        assertTrue(File(staging, "files/widgets/a.owl").isFile)
        assertFalse(File(staging.parentFile, "escaped.txt").exists())
        assertFalse(File(staging, "escaped.txt").exists())
    }

    @Test fun refusesWhatIsntABackup() {
        expectRefusal("not an omalauncher backup") { readBackup(ByteArrayInputStream(zipOf("files/widgets/a.owl" to "a")), tmp.newFolder("a")) }
        // ZipInputStream reads bytes that aren't a zip as an empty one: no backup.json, so the same answer.
        expectRefusal("not an omalauncher backup") { readBackup(ByteArrayInputStream("not a zip at all".toByteArray()), tmp.newFolder("b")) }
        val newer = info.copy(version = BackupFormat.VERSION + 1).toJson().toString()
        expectRefusal("newer") { readBackup(ByteArrayInputStream(zipOf("backup.json" to newer)), tmp.newFolder("c")) }
    }

    @Test fun refusesEntriesPastTheLimits() {
        val zip = zipOf("backup.json" to info.toJson().toString(), "files/widgets/big.owl" to "x".repeat(2000))
        expectRefusal("too large") { readBackup(ByteArrayInputStream(zip), tmp.newFolder("d"), Limits(entryBytes = 1000)) }
        expectRefusal("too large") { readBackup(ByteArrayInputStream(zip), tmp.newFolder("e"), Limits(totalBytes = 1500)) }
        expectRefusal("too many") { readBackup(ByteArrayInputStream(zip), tmp.newFolder("f"), Limits(entries = 1)) }
    }

    /** A launcher's files: two widgets and an example, a widget's state, a theme, a background, the record, the style, and what's left out. */
    private fun home(): File {
        val dir = tmp.newFolder("from")
        fun put(
            path: String,
            text: String,
        ) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        put("widgets/clock.owl", "clock")
        put("widgets/notes.owl", "notes")
        put("widgets/examples/gauge.owl", "gauge")
        put("widgets/.errors/clock.owl.txt", "error")
        put("widgets/.preview/clock.owl.png", "png")
        put("widget-state/notes.owl.json", "{\"todo\":[\"milk\"]}")
        put("themes/mine/colors.toml", "accent = \"#7aa2f7\"")
        File(dir, "backgrounds/tokyo-night/me.png").apply { parentFile.mkdirs() }.writeBytes(ByteArray(256) { it.toByte() })
        put("launcher-managed.json", "{\"v\":1,\"data\":{\"widgets/clock.owl\":\"abc\"}}")
        put("style/style.toml", "version = 1\ntheme = \"nord\"\n[widgets]\ngaps-in = 8\n")
        put("style/defaults.toml", "# every setting")
        put("style/errors.txt", "widgets.gaps-in: too large")
        Files.createSymbolicLink(File(dir, "widgets/link.owl").toPath(), File(dir, "widgets/clock.owl").toPath())
        return dir
    }

    private fun items(n: Int) = Versioned.write(1, JSONArray().apply { repeat(n) { put(JSONObject().put("key", "app:x$it")) } })

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                for ((name, text) in entries) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(text.toByteArray())
                    z.closeEntry()
                }
            }
        }.toByteArray()

    private fun expectRefusal(
        containing: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("expected a refusal containing \"$containing\"")
        } catch (e: BackupException) {
            assertTrue("\"${e.message}\" should contain \"$containing\"", e.message!!.contains(containing))
        }
    }
}
