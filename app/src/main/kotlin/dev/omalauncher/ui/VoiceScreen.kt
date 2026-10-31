package dev.omalauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.voice.VoiceSession
import dev.omalauncher.voice.VoiceSession.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The voice screen: a spoken conversation with a coding agent ([VoiceSession]). It listens as it opens; a tap on
 * the orb talks again or talks over the reply. The orb shows what's happening (listening to your voice's level,
 * speaking, working, waiting for an OK), the conversation scrolls under it, and an agent's question slides up
 * from the bottom.
 */
@Composable
fun VoiceScreen(
    runtime: Runtime,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val session = runtime.voice
    val speaker = runtime.speaker
    val speech = remember { Speech(context.applicationContext) }
    val haptics = LocalHapticFeedback.current
    var viewing by mutableRememberedString()
    var picking by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    var showSteps by remember { mutableStateOf(false) }

    DisposableEffect(session) {
        session.opened()
        onDispose {
            speech.cancel()
            session.closed()
        }
    }

    fun listen() {
        speaker.stop()
        speech.start(hold = false) { heard -> if (heard.isNotBlank()) session.ask(heard) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) listen() }

    fun talk() {
        if (speech.listening) return speech.stop()
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Opening it is asking to talk: listen once the agent is up.
    LaunchedEffect(Unit) {
        while (session.phase == Phase.Starting) delay(100)
        talk()
    }
    LaunchedEffect(session.approvals.firstOrNull()) { if (session.approvals.isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    val agentName = session.agent?.name ?: "agent"
    val now by produceState(System.currentTimeMillis(), session.busy) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = if (session.busy && session.since > 0) ((now - session.since) / 1000).let { "%d:%02d".format(it / 60, it % 60) } else ""
    val status =
        when {
            speech.error != null -> speech.error!!
            speech.listening -> if (session.busy) "listening · $agentName keeps working" else "listening"
            session.phase == Phase.Starting -> "starting $agentName"
            session.phase == Phase.Off && session.agent == null -> "no agent"
            session.approvals.isNotEmpty() -> "$agentName needs your OK · $elapsed"
            session.busy -> "working · $elapsed"
            speaker.speaking -> "tap to talk over it"
            else -> "tap to talk"
        }

    // Newest first: the list runs from the bottom.
    val items =
        buildList {
            if (speech.listening && speech.text.isNotBlank()) add(VoiceSession.Entry("you", speech.text))
            if (session.reply.isNotBlank()) add(VoiceSession.Entry("agent", session.reply))
            addAll(session.transcript.asReversed())
        }
    val talking = items.isNotEmpty()

    Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            // The status, the agent and how its questions are answered.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(status, Modifier.weight(1f), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (session.voiceAgents.size > 1) {
                    Box {
                        Chip("${session.agent?.bin ?: "agent"} ▾", Palette.accent, enabled = !session.busy && !speech.listening) { picking = true }
                        if (picking) {
                            ChoicePopup(
                                options = session.voiceAgents,
                                selected = session.agent,
                                position = BelowAnchorEnd,
                                onDismiss = { picking = false },
                                onPick = {
                                    speaker.stop()
                                    session.switchTo(it)
                                },
                                label = { it.name },
                                minWidth = 160.dp,
                            )
                        }
                    }
                }
                val yellow = Palette.named("yellow") ?: Palette.accent
                Chip(if (session.approveAll) "allow all" else "ask", if (session.approveAll) yellow else Palette.muted) { session.approveEverything(!session.approveAll) }
                if (session.transcript.isNotEmpty() && !session.busy) {
                    Chip(if (confirmNew) "clear?" else "new", if (confirmNew) Palette.red else Palette.muted) {
                        if (confirmNew) {
                            speaker.stop()
                            session.startOver()
                            confirmNew = false
                        } else {
                            confirmNew = true
                        }
                    }
                    LaunchedEffect(confirmNew) {
                        if (confirmNew) {
                            delay(4000)
                            confirmNew = false
                        }
                    }
                }
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val orb = min(maxWidth.value * 0.62f, 250f).dp
                // In the middle until there's a conversation, then at the top above it.
                val orbTop by animateDpAsState(if (talking) 0.dp else (maxHeight - orb) / 2, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "orb")
                if (talking) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(top = orb + 8.dp),
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        items(items.size) { i -> Message(items[i], session) { viewing = it } }
                    }
                }
                Orb(
                    session = session,
                    speech = speech,
                    speaking = speaker.speaking,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = orbTop)
                            .size(orb)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { talk() },
                )
            }

            // What it's doing now; a tap shows the steps before.
            if (session.busy && session.approvals.isEmpty()) {
                val steps = session.steps
                Column(Modifier.fillMaxWidth().clickable { showSteps = !showSteps }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    if (showSteps) {
                        for (s in steps.dropLast(1).takeLast(6)) BasicText("  " + s.trim(), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("● ", style = Mono.copy(color = Palette.named("yellow") ?: Palette.accent))
                        BasicText(steps.lastOrNull()?.trim() ?: "thinking", Modifier.weight(1f), style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (steps.size > 1) BasicText("${steps.size} steps", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                    }
                }
            }
            (session.notice ?: speech.error)?.let { BasicText(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
            if (!speech.listening && (session.busy || speaker.speaking)) {
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                    BracketAction("stop", Palette.red) {
                        speech.cancel()
                        session.stop()
                    }
                }
            }
        }

        // An agent's question, from the bottom.
        val ask = session.approvals.firstOrNull()
        AnimatedVisibility(ask != null, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            val a = ask ?: return@AnimatedVisibility
            Column(Modifier.padding(12.dp).card(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText("$agentName wants to ${dev.omalauncher.voice.Steps.spoken(a.label)}", style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item))
                if (a.detail.isNotEmpty() && a.detail != a.label) CodeBlock(a.detail)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).clip(LauncherStyle.shape(8.dp)).background(Palette.green).clickable { session.answer(a.id, true) }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { BasicText("allow", style = Mono.copy(color = Palette.background)) }
                    BracketAction("always", Palette.accent) { session.answer(a.id, true, always = true) }
                    BracketAction("deny", Palette.red) { session.answer(a.id, false) }
                }
                BasicText("or tap the orb and say yes, no, or what to do instead", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }

        // A picture, whole: pinch to zoom, tap to close.
        viewing?.let { path -> ImageViewer(path) { viewing = null } }
    }
}

/** One line of the conversation. */
@Composable
private fun Message(
    e: VoiceSession.Entry,
    session: VoiceSession,
    onImage: (String) -> Unit,
) {
    when (e.who) {
        "you" ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                BasicText(
                    e.text,
                    Modifier.widthIn(max = 300.dp).clip(LauncherStyle.shape(14.dp)).background(Palette.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    style = Mono.copy(color = Palette.foreground),
                )
            }
        "note" -> BasicText("· " + e.text, Modifier.fillMaxWidth(), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        "image" -> {
            val file = e.text.substringBefore('|')
            val name = e.text.substringAfterLast('|').substringAfterLast('/')
            val bitmap = rememberBitmap(file, 600)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(LauncherStyle.shape(10.dp)).background(Palette.surface).clickable { onImage(file) }) {
                    bitmap?.let { Image(it, name, Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = ContentScale.Fit) }
                        ?: Spacer(Modifier.height(120.dp))
                }
                BasicText(name, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        else -> BasicText(e.text.ifBlank { "…" }, Modifier.fillMaxWidth(), style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item))
    }
}

/** What's happening, as a shape: it follows your voice while listening, breathes while speaking, and is ringed while working. */
@Composable
private fun Orb(
    session: VoiceSession,
    speech: Speech,
    speaking: Boolean,
    modifier: Modifier,
) {
    val yellow = Palette.named("yellow") ?: Palette.accent
    val waiting = session.approvals.isNotEmpty()
    val color by animateColorAsState(
        when {
            speech.listening -> Palette.green
            waiting -> Palette.red
            // Not the accent: on themes where it's red, speaking would look like waiting for an OK.
            speaking -> Palette.named("blue") ?: Palette.accent
            session.busy -> yellow
            else -> Palette.muted
        },
        tween(300),
        label = "color",
    )
    val size by animateFloatAsState(
        when {
            speech.listening -> 0.84f + 0.16f * speech.level
            speaking -> 0.86f
            waiting -> 0.8f
            session.busy -> 0.74f
            else -> 0.7f
        },
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "size",
    )
    val loop = rememberInfiniteTransition(label = "orb")
    val turn by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "turn")
    val breath by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breath")
    val working = session.busy && !waiting
    Canvas(modifier.graphicsLayer { scaleX = size; scaleY = size }) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = min(this.size.width, this.size.height) / 2 - 10.dp.toPx()
        val pulse = if (speaking) 1f + 0.04f * sin(breath * PI.toFloat()) else 1f
        drawCircle(color.copy(alpha = 0.10f), r * pulse, c)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.65f), color.copy(alpha = 0.18f)), c, r * 0.82f * pulse), r * 0.82f * pulse, c)
        drawCircle(color.copy(alpha = 0.9f), r * 0.82f * pulse, c, style = Stroke(2.dp.toPx()))
        if (working) {
            val box = Size(r * 2, r * 2)
            val at = Offset(c.x - r, c.y - r)
            drawArc(yellow, turn - 90f, 70f, false, at, box, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            drawArc(yellow.copy(alpha = 0.45f), turn + 90f, 70f, false, at, box, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun ImageViewer(
    path: String,
    onClose: () -> Unit,
) {
    val bitmap = rememberBitmap(path, 2000)
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .pointerInput(Unit) {
                detectTransformGestures { _, move, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    pan = if (scale == 1f) Offset.Zero else pan + move
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, null, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }, contentScale = ContentScale.Fit)
        }
        BasicText("tap to close", Modifier.align(Alignment.BottomCenter).padding(16.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), )
    }
}

/** A picture file, decoded off the main thread at about [maxSide] pixels. */
@Composable
private fun rememberBitmap(
    path: String,
    maxSide: Int,
): ImageBitmap? =
    produceState<ImageBitmap?>(null, path) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
    }.value

/** A small word in the top row that does something. */
@Composable
private fun Chip(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = BasicText(
    text,
    Modifier.clip(LauncherStyle.shape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    style = Mono.copy(color = color, fontSize = TextSize.Small, textAlign = TextAlign.Center),
)

@Composable
private fun mutableRememberedString() = remember { mutableStateOf<String?>(null) }
