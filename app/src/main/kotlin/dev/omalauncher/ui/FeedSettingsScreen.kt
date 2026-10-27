package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.omalauncher.feed.FeedCatalog
import dev.omalauncher.feed.FeedSettings
import dev.omalauncher.feed.FeedSource
import dev.omalauncher.feed.FeedStore
import dev.omalauncher.feed.HnMode
import dev.omalauncher.feed.ReadStyle
import dev.omalauncher.feed.Section
import dev.omalauncher.feed.XSort

/** The feed's options: whether it's on, what it reads, and how it treats what was read. Changes apply right away. */
@Composable
fun FeedSettingsScreen(
    store: FeedStore,
    onDismiss: () -> Unit,
) {
    val s = store.settings
    val update = store::update
    OverlayPanel("feed", onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Choice("feed", listOf("on", "off"), if (s.enabled) 0 else 1) { i -> update { it.copy(enabled = i == 0) } }
                Hint("swipe right on the first home page, or Super+F; also in the long-press menu")
                Choice("auto refresh", FeedSettings.REFRESH_MINUTES.map(::minutes), FeedSettings.REFRESH_MINUTES.indexOf(s.refreshMinutes).coerceAtLeast(1)) { i ->
                    update { it.copy(refreshMinutes = FeedSettings.REFRESH_MINUTES[i]) }
                }
                Choice("opened stories", ReadStyle.entries.map { it.label }, s.read.ordinal) { i -> update { it.copy(read = ReadStyle.entries[i]) } }
            }

            item { Group("x") }
            item {
                Choice("section", listOf("on", "off"), if (Section.X in s.hidden) 1 else 0) { i -> update { it.copy(hidden = it.hidden.with(Section.X, i == 1)) } }
                Choice("order", XSort.entries.map { it.label }, s.xSort.ordinal) { i -> update { it.copy(xSort = XSort.entries[i]) } }
                Choice("replies", listOf("hidden", "shown"), if (s.xReplies) 1 else 0) { i -> update { it.copy(xReplies = i == 1) } }
                Choice("reposts", listOf("shown", "hidden"), if (s.xReposts) 0 else 1) { i -> update { it.copy(xReposts = i == 0) } }
                FieldLabel("accounts")
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    for (handle in s.xHandles) {
                        Chip("@$handle ✕") { update { it.copy(xHandles = it.xHandles - handle) } }
                    }
                }
                AddField("add an account: @name") { text ->
                    val handle = FeedCatalog.handle(text)
                    if (handle != null) update { it.copy(xHandles = (it.xHandles + handle).distinctBy(String::lowercase)) }
                    handle != null
                }
                if (s.xHandles != FeedCatalog.defaultX) {
                    BracketAction("reset to the default accounts", Palette.muted) { update { it.copy(xHandles = FeedCatalog.defaultX) } }
                }
                Hint("x has no free api for reading; posts come through api.fxtwitter.com, an open-source bridge, which can be slow or miss an account now and then")
            }

            item { Group("hacker news") }
            item {
                Choice("list", listOf(HnMode.Top.label, HnMode.Front.label, "off"), if (Section.Hn in s.hidden) 2 else s.hn.ordinal) { i ->
                    update { it.copy(hidden = it.hidden.with(Section.Hn, i == 2), hn = if (i < 2) HnMode.entries[i] else it.hn) }
                }
                Choice("stories", FeedSettings.HN_COUNTS.map { "$it" }, FeedSettings.HN_COUNTS.indexOf(s.hnCount).coerceAtLeast(0)) { i ->
                    update { it.copy(hnCount = FeedSettings.HN_COUNTS[i]) }
                }
                Hint("top 24h: the day's highest scored stories (hn.algolia.com) · front page: news.ycombinator.com's ranking right now")
            }

            item { Group("tech sites") }
            item { Choice("section", listOf("on", "off"), if (Section.Tech in s.hidden) 1 else 0) { i -> update { it.copy(hidden = it.hidden.with(Section.Tech, i == 1)) } } }
            items(FeedCatalog.tech, key = { "tech:${it.id}" }) { site ->
                SiteRow(site, site.id in s.tech) { on -> update { it.copy(tech = it.tech.with(site.id, on)) } }
            }

            item { Group("news sites") }
            item { Choice("section", listOf("on", "off"), if (Section.News in s.hidden) 1 else 0) { i -> update { it.copy(hidden = it.hidden.with(Section.News, i == 1)) } } }
            items(FeedCatalog.news, key = { "news:${it.id}" }) { site ->
                SiteRow(site, site.id in s.news) { on -> update { it.copy(news = it.news.with(site.id, on)) } }
            }

            item { Group("mute") }
            item {
                Hint("stories whose title has one of these words are left out")
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    for (word in s.mute) Chip("$word ✕") { update { it.copy(mute = it.mute - word) } }
                }
                AddField("add a word or phrase") { text ->
                    val word = text.trim()
                    if (word.isNotEmpty()) update { it.copy(mute = (it.mute + word).distinctBy(String::lowercase)) }
                    word.isNotEmpty()
                }
                Spacer(Modifier.padding(4.dp))
                Hint("each source is fetched straight from the app; the sites, hn.algolia.com and api.fxtwitter.com see your ip address, nothing else")
            }
        }
    }
}

/** The settings hub's line for the feed: on and what it shows, or off. */
internal fun feedStatus(s: FeedSettings) =
    if (s.enabled) "on · " + s.sections.joinToString(", ") { it.label }.ifEmpty { "every section hidden" } else "off · hacker news, x, tech and news left of home"

private fun minutes(m: Int) =
    when {
        m == 0 -> "manual"
        m < 60 -> "${m}m"
        else -> "${m / 60}h"
    }

private fun <T> Set<T>.with(
    value: T,
    on: Boolean,
) = if (on) this + value else this - value

/** A group's title: `── x`. */
@Composable
private fun Group(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("── ", style = Mono.copy(color = Palette.border))
        BasicText(title, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
    }
}

@Composable
private fun FieldLabel(text: String) {
    BasicText(text, Modifier.padding(top = 6.dp), style = Mono.copy(color = Palette.muted))
}

@Composable
private fun Hint(text: String) {
    BasicText(text, Modifier.padding(vertical = 4.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
}

/**
 * A labeled segmented choice, like the style screen's: the picked option fills with the accent color. The
 * label goes above when the options wouldn't fit beside it on a phone.
 */
@Composable
private fun Choice(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
        BasicText(label, Modifier.width(132.dp).align(Alignment.CenterVertically).padding(vertical = 8.dp), style = Mono.copy(color = Palette.muted))
        Row(Modifier.frame(1.dp, Palette.border, radius = 12.dp)) {
            options.forEachIndexed { i, option ->
                val active = i == selected
                BasicText(
                    option,
                    Modifier
                        .background(if (active) Palette.accent else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    style = Mono.copy(color = if (active) Palette.background else Palette.foreground),
                )
            }
        }
    }
}

/** A site to read or not: `[x] ars technica          ars`. */
@Composable
private fun SiteRow(
    site: FeedSource,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable { onToggle(!on) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(if (on) "[x]" else "[ ]", style = Mono.copy(color = if (on) Palette.accent else Palette.muted))
        BasicText("  ${site.label}", Modifier.weight(1f), style = Mono.copy(color = if (on) Palette.foreground else Palette.muted))
        BasicText(site.tag, style = Mono.copy(color = Palette.muted))
    }
}

@Composable
private fun Chip(
    text: String,
    onClick: () -> Unit,
) {
    Box(Modifier.padding(end = 6.dp, bottom = 6.dp).frame(1.dp, Palette.border, Palette.surface, radius = 10.dp).clickable(onClick = onClick)) {
        BasicText(text, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = Mono)
    }
}

/** A one-line field with `[ add ]`; [onAdd] says whether the text was taken, which clears the field. */
@Composable
private fun AddField(
    placeholder: String,
    onAdd: (String) -> Boolean,
) {
    var text by remember { mutableStateOf("") }

    fun add() {
        if (onAdd(text)) text = ""
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .frame(1.dp, Palette.border, Palette.background, radius = 10.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (text.isEmpty()) BasicText(placeholder, style = Mono.copy(color = Palette.muted))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = Mono,
                cursorBrush = SolidColor(Palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BracketAction("add", Palette.accent, onClick = ::add)
    }
}
