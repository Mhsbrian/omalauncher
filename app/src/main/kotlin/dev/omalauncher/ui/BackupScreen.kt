package dev.omalauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import dev.omalauncher.R
import androidx.compose.ui.res.stringResource
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.backup.Backups
import dev.omalauncher.backup.Staged
import dev.omalauncher.backup.Summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the screen is doing: a backup read and waiting for the go-ahead shows what it holds first. */
private sealed interface BackupStep {
    data object Idle : BackupStep

    data class Working(
        val what: String,
    ) : BackupStep

    data class Done(
        val what: String,
    ) : BackupStep

    data class Failed(
        val why: String,
    ) : BackupStep

    /** [label] says where it came from: a file's name, or the home screen from before a restore. */
    data class Picked(
        val staged: Staged,
        val label: String,
    ) : BackupStep
}

/**
 * Backs the home screen up to a file and restores it from one: pinned apps, folders, widgets
 * and their data, where everything sits, the theme, style, gestures and language. A backup
 * outlives the app, so the home screen survives an uninstall, a new signing key or a new phone.
 */
@Composable
fun BackupScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val backups = remember { Backups(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<BackupStep>(BackupStep.Idle) }
    var now by remember { mutableStateOf<Summary?>(null) }
    var last by remember { mutableStateOf(backups.lastBackup) }
    LaunchedEffect(step) { if (step !is BackupStep.Working) now = withContext(Dispatchers.IO) { backups.current() } }
    // A backup read but not restored leaves nothing behind.
    DisposableEffect(Unit) { onDispose { if (step is BackupStep.Picked) backups.discard() } }

    fun run(
        what: String,
        block: suspend () -> BackupStep,
    ) {
        step = BackupStep.Working(what)
        scope.launch { step = runCatching { block() }.getOrElse { BackupStep.Failed(it.message ?: it.toString()) } }
    }

    val create =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP)) { uri ->
            if (uri != null) {
                run("backing up…") {
                    backups.backUp(uri)
                    last = backups.lastBackup
                    BackupStep.Done("saved to ${last.second ?: "the file"}")
                }
            }
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) run("reading the backup…") { BackupStep.Picked(backups.read(uri), "the backup") }
        }

    OverlayPanel(stringResource(R.string.fork_settings_backup), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            SectionLabel("back up")
            Note(
                "saves your home screen to a file you pick: pinned apps and folders, widgets with their data, " +
                    "where everything sits, the theme, style, gestures and language. Linux itself isn't in it.",
            )
            now?.let { Line("now: " + describe(it)) }
            Line(last.first.takeIf { it > 0 }?.let { "last backup: ${date(it)}" + (last.second?.let { n -> " · $n" } ?: "") } ?: "no backup yet")
            BracketAction(stringResource(R.string.fork_backup_back_up), Palette.accent) { create.launch("omalauncher-backup-${stamp()}.zip") }

            SectionLabel("restore")
            Note("replaces the home screen with a backup's and restarts the launcher. you see what's in the backup first.")
            BracketAction(stringResource(R.string.fork_backup_restore_from_file), Palette.accent) { open.launch(ZIP_TYPES) }
            backups.undoAt?.let { at ->
                Line("the home screen from before the restore on ${date(at)} is kept")
                BracketAction(stringResource(R.string.fork_backup_undo), Palette.accent) {
                    run("reading…") { BackupStep.Picked(backups.readUndo(), "the home screen from before the last restore") }
                }
            }

            Spacer(Modifier.height(12.dp))
            when (val s = step) {
                BackupStep.Idle -> {}
                is BackupStep.Working -> BasicText(s.what, style = Mono.copy(color = Palette.accent))
                is BackupStep.Done -> BasicText(s.what, style = Mono.copy(color = Palette.green))
                is BackupStep.Failed -> BasicText(s.why, style = Mono.copy(color = Palette.red))
                is BackupStep.Picked -> Confirm(s, onRestore = { run("restoring…") { backups.restore(s.staged).let { BackupStep.Idle } } }) {
                    backups.discard()
                    step = BackupStep.Idle
                }
            }
        }
    }
}

/** What a picked backup holds and what restoring it does, with the go-ahead. */
@Composable
private fun Confirm(
    picked: BackupStep.Picked,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
) {
    val info = picked.staged.info
    Column(Modifier.fillMaxWidth().frame(1.dp, Palette.accent, Palette.surface, radius = 12.dp).padding(12.dp)) {
        BasicText(picked.label, style = Mono.copy(color = Palette.accent))
        Line("made ${date(info.createdAt)} by ${info.appVersion}")
        Line(describe(picked.staged.summary()))
        if (picked.staged.skipped > 0) Line("${picked.staged.skipped} files this launcher doesn't know are left out")
        Spacer(Modifier.height(6.dp))
        Note(
            "restoring replaces what's on the home screen now with this, and the launcher restarts: " +
                "open Linux windows close. the home screen as it is now is kept, so you can undo it here.",
        )
        Row {
            BracketAction(stringResource(R.string.fork_backup_restore), Palette.accent, onClick = onRestore)
            BracketAction(stringResource(R.string.fork_cancel), Palette.muted, onClick = onCancel)
        }
    }
}

@Composable
private fun Note(text: String) = BasicText(text, Modifier.padding(vertical = 4.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))

@Composable
private fun Line(text: String) = BasicText(text, Modifier.padding(vertical = 2.dp), style = Mono)

private fun describe(s: Summary) =
    listOfNotNull(
        plural(s.apps, "app"),
        plural(s.folders, "folder"),
        plural(s.widgets, "widget"),
        s.theme?.replace('-', ' '),
    ).joinToString(" · ")

private fun plural(
    n: Int,
    what: String,
) = "$n $what" + if (n == 1) "" else "s"

private fun date(millis: Long) = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis)).lowercase()

private fun stamp() = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())

private const val ZIP = "application/zip"

/** What file managers and cloud drives call a zip. */
private val ZIP_TYPES = arrayOf(ZIP, "application/x-zip-compressed", "application/octet-stream")
