package dev.omalauncher.ui

import dev.omalauncher.widgets.lang.rankMatches
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.AndroidApp
import dev.omalauncher.Runtime
import dev.omalauncher.icons.IconFiles
import dev.omalauncher.icons.IconPack
import dev.omalauncher.icons.IconPackInfo
import dev.omalauncher.icons.IconStore
import dev.omalauncher.icons.PackKind
import dev.omalauncher.icons.searchDrawables
import dev.omalauncher.theme.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** What settings call where an app's icon comes from. */
private val IconFiles.Source.label
    get() =
        when (this) {
            IconFiles.Source.File -> "custom · ~/${IconStore.GUEST_DIR}"
            IconFiles.Source.Pack -> "from the icon pack"
            IconFiles.Source.Backdrop -> "its own, on the pack's backdrop"
            IconFiles.Source.Own -> "its own"
        }

/** Settings' summary of the icons: the pack, and how many apps have one of the user's own. */
fun iconsStatus(store: IconStore): String {
    val custom = store.files.size
    return listOfNotNull(
        store.pack?.let { "pack: ${it.label}" } ?: "apps' own · icon packs, your own, agent-drawn",
        custom.takeIf { it > 0 }?.let { "$it custom" },
    ).joinToString(" · ")
}

/** What an agent is asked to do with icons; the launcher starts the agent the bar uses on it. */
sealed interface IconTask {
    /** A new pack, in a style the user describes. */
    data object NewPack : IconTask

    /** The icons [pack] is missing, in its style. */
    data class Fill(
        val pack: IconPackInfo,
    ) : IconTask

    /** A change to [pack] the user describes. */
    data class Edit(
        val pack: IconPackInfo,
    ) : IconTask

    /** One icon, for [app]. */
    data class Draw(
        val app: AndroidApp,
    ) : IconTask

    /** Nothing to draw yet: Linux or an agent to install first. */
    data object Setup : IconTask
}

/**
 * Settings › icons as a page of the settings activity, with an app's picker over it while one is
 * picked; back closes the picker first. [open] shows another page (Linux or agents to set up).
 */
@Composable
internal fun IconsPage(
    runtime: Runtime,
    onDismiss: () -> Unit,
    open: (SettingsPage) -> Unit,
) {
    var picking by remember { mutableStateOf<AndroidApp?>(null) }
    IconsScreen(runtime, onDismiss = onDismiss, onPick = { picking = it }, onTask = { runIconTask(runtime, it, open) })
    picking?.let { app ->
        BackHandler { picking = null }
        // Opened from the list, so its chevron goes back to the list, not out of the icons page.
        CompositionLocalProvider(LocalPanelBack provides { picking = null }) {
            IconPickerScreen(runtime, app, onDismiss = { picking = null }, onTask = { runIconTask(runtime, it, open) })
        }
    }
}

/**
 * Settings › icons: the icon pack every app's icon comes from, the installed ones and the ones agents
 * drew, a new one drawn by the agent picked here, what a pack does with apps it has no icon for, and
 * every app with where its icon comes from; a tap changes it.
 */
@Composable
fun IconsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    /** Opens one app's icon picker. */
    onPick: (AndroidApp) -> Unit,
    /** Starts an agent on icons. */
    onTask: (IconTask) -> Unit,
) {
    val store = runtime.icons
    val scope = rememberCoroutineScope()
    // A big pack's list takes megabytes: the cards' packs go when the screen does.
    DisposableEffect(store) { onDispose { store.trim() } }
    var query by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<IconPackInfo?>(null) }
    OverlayPanel("icons", onDismiss, contentWidth = ReadingWidth) {
        val apps = runtime.androidApps.apps
        val shown = if (query.isBlank()) apps.toList() else rankMatches(apps.toList(), query, { it.label }, alias = { it.spoken.takeIf { s -> s != it.label } }).map { it.first }
        LazyColumn(Modifier.weight(1f)) {
            item(key = "pack-heading") { Heading("icon pack") { FindPacks() } }
            item(key = "packs") { PackRow(store, onNew = { onTask(IconTask.NewPack) }) }
            item(key = "pack-actions") {
                val pack = store.pack
                if (pack != null && pack.info.kind == PackKind.Folder) {
                    FolderPackActions(store, pack, onFill = { onTask(IconTask.Fill(pack.info)) }, onEdit = { onTask(IconTask.Edit(pack.info)) }, onRemove = { removing = pack.info })
                }
            }
            item(key = "options") {
                Column {
                    if (store.pack?.hasBackdrop == true) {
                        Flag("other apps", "on backdrop", "as is", runtime.style, Style.ICON_PACK_BACKDROP)
                        OptionHint("apps the pack has no icon for", Modifier.padding(bottom = 4.dp))
                    }
                    Flag("icons", "monochrome", "colored", runtime.style, Style.MONOCHROME_ICONS)
                }
            }
            item(key = "agents") {
                Column {
                    Heading("drawn by")
                    AgentChoice(runtime, onSetup = { onTask(IconTask.Setup) })
                    BasicText(
                        "the launcher's agent, as in the bar, draws new packs, a pack's missing icons and single apps' icons, in " +
                            "the theme's colors; it works in ~/${IconStore.PACKS_GUEST_DIR} and ~/${IconStore.GUEST_DIR}, and what it " +
                            "saves shows within a second",
                        Modifier.padding(top = 6.dp),
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    )
                }
            }
            item(key = "apps-heading") {
                Column {
                    Heading("apps")
                    SearchField(query, { query = it }, onGo = {}, placeholder = "search apps")
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(shown, key = { it.key }) { app -> AppIconRow(runtime, app, store) { onPick(app) } }
        }
        removing?.let { info ->
            ConfirmDialog(
                "remove ${info.label}?",
                "Its folder is deleted, with every icon in it; apps go back to their own icons.",
                "remove",
                onDismiss = { removing = null },
            ) {
                removing = null
                scope.launch { store.remove(info) }
            }
        }
    }
}

/**
 * The installed agents, as chips; the launcher's agent, the one the bar asks and the home menu names,
 * is filled, and picking one here picks it there too. Without Linux or an agent, what to install first.
 */
@Composable
private fun AgentChoice(
    runtime: Runtime,
    onSetup: () -> Unit,
) {
    val installed = runtime.agents.installed
    val chosen = runtime.agents.default
    when {
        !runtime.bootstrap.isInstalled -> BracketAction("install linux to draw with agents", Palette.accent, onClick = onSetup)
        installed.isEmpty() -> BracketAction("install a coding agent", Palette.accent, onClick = onSetup)
        else ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (agent in installed) {
                    val active = agent.bin == chosen?.bin
                    BasicText(
                        agent.name,
                        Modifier
                            .frame(1.dp, Palette.border, if (active) Palette.accent else Color.Transparent, radius = 12.dp)
                            .clickable { runtime.agents.choose(agent) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        style = Mono.copy(color = if (active) Palette.background else Palette.foreground),
                        softWrap = false,
                    )
                }
            }
    }
}

/** The agent-made pack in use: where it is, how many apps it's missing, and an agent to fill them in or change it. */
@Composable
private fun FolderPackActions(
    store: IconStore,
    pack: IconPack,
    onFill: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val missing = store.missing(pack)
    Column(Modifier.padding(top = 8.dp)) {
        pack.info.description?.let { BasicText(it, style = Mono.copy(fontSize = TextSize.Small)) }
        BasicText(
            "~/${IconStore.PACKS_GUEST_DIR}/${pack.id} · " + if (missing == 0) "an icon for every app" else "$missing apps without an icon",
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        FlowRow {
            if (missing > 0) BracketAction("fill in $missing", Palette.accent, onClick = onFill)
            BracketAction("change it", Palette.accent, onClick = onEdit)
            BracketAction("remove", Palette.red, onClick = onRemove)
        }
    }
}

/** Opens the Play Store's icon packs, or a browser's. */
@Composable
private fun FindPacks() {
    val context = LocalContext.current
    BracketAction("find packs", Palette.accent) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=icon%20pack&c=apps")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=icon%20pack&c=apps")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.recoverCatching { context.startActivity(web) }
    }
}

/** The apps' own icons, the packs agents drew, the installed ones, then a new one an agent draws, as cards; the one in use is outlined. */
@Composable
private fun PackRow(
    store: IconStore,
    onNew: () -> Unit,
) {
    val current = store.pack?.info?.key
    LazyRow(
        Modifier.bleed(PanelPadding),
        contentPadding = PaddingValues(horizontal = PanelPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "own") { PackCard(store, null, selected = current == null) { store.use(null) } }
        items(store.packs, key = { it.key }) { info ->
            PackCard(store, info, selected = info.key == current) { store.use(info) }
        }
        // Like the backgrounds' [ + image ]: the agent picked below draws it.
        item(key = "new") {
            Box(
                Modifier
                    .width(156.dp)
                    .height(PackCardHeight)
                    .frame(2.dp, Palette.border, Palette.surface, radius = 12.dp)
                    .clickable(onClick = onNew),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("[ + new pack ]", style = Mono.copy(color = Palette.accent))
            }
        }
    }
    if (store.packs.isEmpty()) {
        BasicText(
            "no icon packs yet: an agent draws one with + new pack, and icon packs from the Play Store and F-Droid show here once installed",
            Modifier.padding(top = 6.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
    }
}

/** A pack, null for the apps' own icons: four of the user's apps as it draws them, and how many it covers. */
@Composable
private fun PackCard(
    store: IconStore,
    info: IconPackInfo?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val px = with(LocalDensity.current) { SampleSize.roundToPx() }
    val preview by produceState<Pair<List<ImageBitmap>, String>?>(null, info, store.packs) {
        val p = store.preview(info, px)
        value = p.samples.map { it.asImageBitmap() } to
            when {
                info == null -> "${p.total} apps"
                // Short enough for the card: whose it is matters more than the word apps.
                info.kind == PackKind.Folder -> "drawn · ${p.covered} of ${p.total}"
                else -> "${p.covered} of ${p.total} apps"
            }
    }
    Column(
        Modifier
            .width(156.dp)
            .height(PackCardHeight)
            .frame(2.dp, if (selected) Palette.accent else Palette.border, Palette.surface, radius = 12.dp)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(Modifier.height(SampleSize), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (bmp in preview?.first.orEmpty()) Image(bmp, null, Modifier.size(SampleSize))
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            info?.label ?: "apps' own",
            style = Mono.copy(color = if (selected) Palette.accent else Palette.foreground),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText(preview?.second ?: "reading…", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1)
    }
}

private val SampleSize = 28.dp

/** Tall enough for its samples, name and count at a large font size. */
private val PackCardHeight = 108.dp

/** One app in the icons list: its icon as drawn now, its name, and where the icon comes from. */
@Composable
private fun AppIconRow(
    runtime: Runtime,
    app: AndroidApp,
    store: IconStore,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(runtime.androidApps.icons[app.key], runtime.androidApps.masks[app.key], app.spoken, 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            BasicText(app.spoken, style = Mono.copy(fontSize = TextSize.Item), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(store.sourceOf(app).label, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Symbol(Glyph.ChevronRight, Palette.muted, fontSize = TextSize.Title)
    }
}

/** An app's icon as the drawer draws it: a silhouette on a badge in the monochrome style, initials until it's loaded. */
@Composable
private fun AppIcon(
    icon: ImageBitmap?,
    mask: ImageBitmap?,
    label: String,
    size: Dp,
) {
    val mono = LauncherStyle.monochromeIcons
    val badge = LauncherStyle.shape(size / 2)
    when {
        mono && mask != null ->
            Box(Modifier.size(size).clip(badge).background(Palette.surface), contentAlignment = Alignment.Center) {
                Image(mask, label, Modifier.size(size * 0.7f), colorFilter = ColorFilter.tint(Palette.accent))
            }
        !mono && icon != null -> Image(icon, label, Modifier.size(size))
        else ->
            Box(Modifier.size(size).clip(badge).background(Palette.surface), contentAlignment = Alignment.Center) {
                BasicText(initials(label), style = Mono.copy(color = Palette.accent))
            }
    }
}

/**
 * One app's icon: any installed pack's icons to pick from, the pack's own pick for it first, an image from
 * the phone, an agent to draw one, or back to what it had. Picks are saved as files in ~/.config/omalauncher/icons.
 */
@Composable
fun IconPickerScreen(
    runtime: Runtime,
    app: AndroidApp,
    onDismiss: () -> Unit,
    /** Starts an agent on icons: drawing [app]'s, or installing what that takes. */
    onTask: (IconTask) -> Unit,
) {
    val store = runtime.icons
    val scope = rememberCoroutineScope()
    val agentName = runtime.agents.default?.name?.lowercase() ?: "an agent"
    val source = store.sourceOf(app)
    // The pack in use first, as that's where an icon matching the others is.
    var browsing by remember { mutableStateOf(store.pack?.info ?: store.packs.firstOrNull()) }
    var query by remember(app) { mutableStateOf(app.label) }
    var failed by remember { mutableStateOf(false) }
    val pickImage =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                scope.launch {
                    failed = !store.pickImage(app, uri)
                    if (!failed) onDismiss()
                }
            }
        }
    OverlayPanel("icon", onDismiss, contentWidth = ReadingWidth) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(runtime.androidApps.icons[app.key], runtime.androidApps.masks[app.key], app.spoken, 64.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                BasicText(app.spoken, style = Mono.copy(fontSize = TextSize.Title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(source.label, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                BasicText(
                    "file: ${store.stemFor(app)}.svg (or .png)",
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FlowRow(Modifier.padding(top = 8.dp)) {
            if (source == IconFiles.Source.File) BracketAction("reset", Palette.red) { scope.launch { store.reset(app) } }
            if (source == IconFiles.Source.Pack || source == IconFiles.Source.Backdrop) {
                BracketAction("keep its own", Palette.accent) { scope.launch { store.pickOwn(app).also { onDismiss() } } }
            }
            BracketAction("image", Palette.accent) { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            BracketAction("draw with $agentName", Palette.accent) { onTask(IconTask.Draw(app)) }
        }
        // Which agent draws it, when there's a choice.
        if (runtime.agents.installed.size > 1) AgentChoice(runtime, onSetup = { onTask(IconTask.Setup) })
        if (failed) BasicText("that image couldn't be read", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
        if (store.packs.isEmpty()) {
            Heading("icon packs") { FindPacks() }
            BasicText(
                "install an icon pack to pick from its icons here",
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
            return@OverlayPanel
        }
        Heading("pick from") { FindPacks() }
        LazyRow(
            Modifier.bleed(PanelPadding),
            contentPadding = PaddingValues(horizontal = PanelPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(store.packs, key = { it.key }) { info ->
                val active = info.key == browsing?.key
                BasicText(
                    info.label,
                    Modifier
                        .frame(1.dp, Palette.border, if (active) Palette.accent else Color.Transparent, radius = 12.dp)
                        .clickable { browsing = info }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = Mono.copy(color = if (active) Palette.background else Palette.foreground),
                    softWrap = false,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SearchField(query, { query = it }, onGo = {}, placeholder = "search icons")
        Spacer(Modifier.height(8.dp))
        val pack by produceState<IconPack?>(null, browsing) { value = browsing?.let { store.open(it) } }
        val p = pack
        if (p == null) {
            BasicText(if (browsing == null) "" else "reading…", style = Mono.copy(color = Palette.muted))
            return@OverlayPanel
        }
        val names =
            remember(p, query) {
                val suggested = p.nameFor(app.component, Calendar.getInstance().get(Calendar.DAY_OF_MONTH))
                (listOfNotNull(suggested) + searchDrawables(p.names, query)).distinct()
            }
        if (names.isEmpty()) BasicText("no icon in ${p.label} matches", style = Mono.copy(color = Palette.muted))
        LazyVerticalGrid(
            GridCells.Adaptive(64.dp),
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(names, key = { it }) { name ->
                PackIconCell(p, name, store.colors) {
                    scope.launch {
                        store.pick(app, p, name)
                        onDismiss()
                    }
                }
            }
        }
    }
}

/** One of a pack's icons, drawn when it scrolls into view. */
@Composable
private fun PackIconCell(
    pack: IconPack,
    name: String,
    colors: Map<String, String>,
    onClick: () -> Unit,
) {
    val px = with(LocalDensity.current) { 48.dp.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, pack, name, colors) {
        value = withContext(Dispatchers.IO) { pack.thumbnail(name, px, colors)?.asImageBitmap() }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(LauncherStyle.shape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it, name, Modifier.size(48.dp)) }
    }
}
