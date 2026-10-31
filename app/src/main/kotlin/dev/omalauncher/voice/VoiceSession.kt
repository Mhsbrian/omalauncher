package dev.omalauncher.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.linux.Agent
import dev.omalauncher.linux.AgentStore
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.killTree
import dev.omalauncher.ui.Speaker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * A spoken conversation with a coding agent in Linux: one agent CLI kept running through an [AgentLink], its reply
 * said sentence by sentence by [Speaker] as it streams in, its tool calls shown as steps, its questions as
 * approvals, the conversation kept per agent and continued when it's opened again. It belongs to the launcher, not
 * to a screen: leaving the voice screen doesn't stop a task. The agent is stopped when the screen has been closed
 * and nothing's happening for [IDLE_STOP_MS]. Main thread.
 */
class VoiceSession(
    private val context: Context,
    private val proot: Proot,
    private val agents: AgentStore,
    private val speaker: Speaker,
) {
    enum class Phase { Off, Starting, Idle, Thinking, Working, Approval }

    /** A line of the conversation: [who] is "you", "agent", "note" or "image" ([text] then a file in [dir]). */
    data class Entry(
        val who: String,
        val text: String,
    )

    data class Approval(
        val id: String,
        val label: String,
        val detail: String,
        val key: String,
    )

    /** The agents that can talk: installed, and with a link. */
    val voiceAgents: List<Agent> get() = agents.installed.filter { it.bin in LINKS }

    var agent by mutableStateOf<Agent?>(null)
        private set
    var phase by mutableStateOf(Phase.Off)
        private set
    val transcript = mutableStateListOf<Entry>()

    /** The reply being written now, before it joins [transcript]. */
    var reply by mutableStateOf("")
        private set

    /** This task's tool calls, the latest last. */
    val steps = mutableStateListOf<String>()

    /** The agent's questions waiting for an answer; the first is shown. */
    val approvals = mutableStateListOf<Approval>()

    /** Something to tell the user, like an error; null when there's nothing. */
    var notice by mutableStateOf<String?>(null)

    /** When the task in progress started (ms), for the time shown. */
    var since by mutableLongStateOf(0L)
        private set

    /** Prompts sent and not answered yet. */
    var turns by mutableIntStateOf(0)
        private set
    val busy get() = turns > 0

    /** Every question answered yes without asking. */
    var approveAll by mutableStateOf(false)
        private set

    private var link: AgentLink? = null
    private var process: Process? = null
    private var session: String? = null
    private val always = HashSet<String>()
    private var buffer = ""
    private var afterTool = false
    private var lastSaid = 0L
    private var lastEvent = 0L
    private var doneAt = 0L
    private var restarts = 0
    private var screens = 0
    private val complaints = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Writes to the agent off the main thread, in order: a full pipe mustn't freeze the screen. */
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "voice-write").apply { isDaemon = true } }

    val dir get() = File(context.filesDir, "voice")

    /**
     * What went to and came from the agent, for finding out why it misbehaves: /tmp/voice-agent.log in Linux,
     * readable from a terminal, cut in half when it passes 1 MB; /tmp is emptied as the launcher starts.
     */
    private fun trace(
        way: Char,
        line: String,
    ) = writer.execute {
        runCatching {
            val log = proot.hostFile("/tmp/voice-agent.log")
            if (log.length() > 1_000_000) log.writeText(log.readText().takeLast(500_000))
            log.appendText("${System.currentTimeMillis() % 100_000_000} $way ${line.take(4000)}\n")
        }
    }
    private val settingsFile get() = File(dir, "settings.json")

    private fun transcriptFile(bin: String) = File(dir, "transcript-$bin.json")

    private fun sessionFile(bin: String) = File(dir, "session-$bin.json")

    // ---------- the screen

    /** The voice screen opened: the agent starts (or keeps running). */
    fun opened() {
        screens++
        main.removeCallbacks(idleStop)
        if (agent == null) {
            val settings = readJson(settingsFile)
            approveAll = settings?.optBoolean("approveAll") == true
            val pick = voiceAgents.firstOrNull { it.bin == settings?.optString("agent") } ?: voiceAgents.firstOrNull { it.bin == agents.default?.bin } ?: voiceAgents.firstOrNull()
            pick?.let(::load)
        }
        if (process?.isAlive != true) start()
    }

    fun closed() {
        screens = (screens - 1).coerceAtLeast(0)
        scheduleIdleStop()
    }

    // ---------- what the user does

    fun ask(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        notice = null
        // An answer to the question on screen: yes, no, or what to do instead.
        approvals.firstOrNull()?.let { a ->
            transcript += Entry("you", said)
            saveTranscript()
            when {
                YES.containsMatchIn(said) -> answer(a.id, true)
                NO.matches(said) -> answer(a.id, false)
                else -> answer(a.id, false, instead = said)
            }
            return
        }
        if (process?.isAlive != true) start()
        val fresh = turns == 0
        if (fresh) {
            steps.clear()
            since = System.currentTimeMillis()
        }
        turns++
        transcript += Entry("you", said)
        saveTranscript()
        saveSession()
        phase = Phase.Thinking
        lastEvent = System.currentTimeMillis()
        link?.prompt(said)
    }

    /** Stops the task: the voice, the turn and anything waiting. */
    fun stop() {
        speaker.stop()
        for (a in approvals.toList()) answer(a.id, false)
        if (turns > 0) link?.cancel()
        turns = 0
        endReply()
        phase = if (process?.isAlive == true) Phase.Idle else Phase.Off
        scheduleIdleStop()
    }

    fun answer(
        id: String,
        allow: Boolean,
        always: Boolean = false,
        instead: String? = null,
    ) {
        val a = approvals.firstOrNull { it.id == id } ?: return
        approvals.remove(a)
        if (always) this.always += a.key
        transcript += Entry("note", (if (allow) "✓ " else "✗ ") + a.label.removePrefix("running: ") + if (always) " · always" else "")
        saveTranscript()
        val took = link?.answer(id, allow, always, instead) ?: true
        if (!took && instead != null) link?.prompt(instead)
        // Questions that came while this one was up and that "always" now covers.
        for (other in approvals.toList()) if (approveAll || Steps.covered(other.key, this.always)) answer(other.id, true)
        phase = if (approvals.isNotEmpty()) Phase.Approval else if (busy) Phase.Working else Phase.Idle
        approvals.firstOrNull()?.let(::announce)
    }

    fun approveEverything(on: Boolean) {
        approveAll = on
        saveSettings()
        transcript += Entry("note", if (on) "allowing everything ${agent?.name ?: "the agent"} asks for" else "asking before each action again")
        saveTranscript()
        if (on) for (a in approvals.toList()) answer(a.id, true)
    }

    /** Talks to [to] instead: the conversation so far is put aside and [to]'s comes back. */
    fun switchTo(to: Agent) {
        if (to.bin == agent?.bin || busy) return
        stopAgent()
        load(to)
        saveSettings()
        start()
    }

    /** A new conversation with the same agent. */
    fun startOver() {
        val a = agent ?: return
        stopAgent()
        sessionFile(a.bin).delete()
        transcript.clear()
        saveTranscript()
        File(dir, "images").deleteRecursively()
        session = null
        start()
    }

    // ---------- the agent

    private fun load(to: Agent) {
        agent = to
        always.clear()
        transcript.clear()
        val last = readJson(sessionFile(to.bin))
        val fresh = last != null && System.currentTimeMillis() - last.optLong("at") < RESUME_MS
        session = if (fresh) last!!.optString("id").ifEmpty { null } else null
        if (fresh) {
            readArray(transcriptFile(to.bin))?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject).forEach { transcript += Entry(it.optString("who"), it.optString("text")) } }
            if (last!!.optBoolean("busy")) transcript += Entry("note", "the last task was stopped; say continue to pick it up")
        }
    }

    private fun start() {
        val a = agent ?: return run { notice = "no agent installed: add Claude Code or Grok in agents settings" }
        val l = linkFor(a) { line -> writer.execute { write(line) } }
        link = l
        complaints.clear()
        phase = Phase.Starting
        val p =
            runCatching { proot.process(l.command(session)).redirectErrorStream(false).start() }.getOrElse {
                phase = Phase.Off
                notice = "couldn't start ${a.name}: ${it.message}"
                return
            }
        process = p
        l.open(session)
        trace('$', "start ${a.bin}" + (session?.let { " resuming $it" } ?: ""))
        // Stopping the agent closes its pipes under these readers: that ends them, it isn't an error.
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    trace('<', line)
                    main.post { if (process === p) events(l.read(line)) }
                }
            }
            runCatching { p.waitFor() }
            main.post { exited(p) }
        }, "voice-read").apply { isDaemon = true }.start()
        Thread({
            runCatching {
                p.errorStream.bufferedReader().forEachLine { line ->
                    trace('!', line)
                    main.post { if (process === p) complain(line) }
                }
            }
        }, "voice-err").apply { isDaemon = true }.start()
        // Claude says nothing until the first prompt; it's ready once it runs.
        if (l is ClaudeLink) phase = Phase.Idle
        startWatch()
    }

    private fun write(line: String) {
        val p = process ?: return
        trace('>', line)
        runCatching {
            p.outputStream.write((line + "\n").toByteArray())
            p.outputStream.flush()
        }.onFailure { Log.w(TAG, "couldn't write to the agent", it) }
    }

    private fun stopAgent() {
        val p = process ?: return
        process = null
        if (turns > 0) saveSession(busy = true)
        speaker.stop()
        approvals.clear()
        turns = 0
        endReply()
        phase = Phase.Off
        writer.execute { killTree(p) }
    }

    private val idleStop = Runnable { if (screens == 0 && !busy && approvals.isEmpty()) stopAgent() }

    private fun scheduleIdleStop() {
        main.removeCallbacks(idleStop)
        if (screens == 0) main.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun exited(p: Process) {
        if (process !== p) return
        process = null
        val code = runCatching { p.exitValue() }.getOrDefault(-1)
        val said = complaints.joinToString(" ").lowercase()
        Log.i(TAG, "${agent?.bin} exited $code: ${said.takeLast(300)}")
        val wasBusy = busy
        turns = 0
        approvals.clear()
        endReply()
        if (session != null && Regex("no conversation|not found|invalid session").containsMatchIn(said)) {
            agent?.let { sessionFile(it.bin).delete() }
            session = null
            transcript += Entry("note", "couldn't continue the last conversation; this is a new one")
            saveTranscript()
            return start()
        }
        if (restarts < 3 && screens > 0) {
            restarts++
            start()
            if (wasBusy) notice = "${agent?.name} stopped and was restarted; ask again"
        } else {
            phase = Phase.Off
            if (wasBusy || screens > 0) notice = "${agent?.name} stopped: " + complaints.lastOrNull().orEmpty().take(200)
        }
    }

    private fun complain(line: String) {
        complaints.addLast(line)
        while (complaints.size > 20) complaints.removeFirst()
    }

    // ---------- what it says

    private fun events(list: List<LinkEvent>) {
        if (list.isEmpty()) return
        lastEvent = System.currentTimeMillis()
        for (e in list) {
            when (e) {
                is LinkEvent.Session -> {
                    session = e.id
                    saveSession()
                }
                LinkEvent.Ready -> if (phase == Phase.Starting) phase = Phase.Idle
                LinkEvent.ResumeFailed -> {
                    transcript += Entry("note", "couldn't continue the last conversation; this is a new one")
                    saveTranscript()
                }
                is LinkEvent.Text -> {
                    var d = e.text
                    if (afterTool && reply.isNotEmpty() && !reply.last().isWhitespace()) d = " " + d.trimStart()
                    afterTool = false
                    buffer += d
                    reply += d
                    handOver(final = false)
                    if (phase != Phase.Approval) phase = Phase.Working
                }
                LinkEvent.Working -> {
                    handOver(final = true)
                    afterTool = true
                    if (System.currentTimeMillis() - lastSaid > PROGRESS_MS) say("One moment.")
                    if (phase != Phase.Approval) phase = Phase.Working
                }
                is LinkEvent.Step -> {
                    steps += e.label
                    while (steps.size > 50) steps.removeAt(0)
                    for (path in e.images) keepImage(path)
                }
                is LinkEvent.Ask -> {
                    val a = Approval(e.id, e.label, e.detail, e.key)
                    if (approveAll || Steps.covered(e.key, always)) {
                        link?.answer(e.id, true, false, null)
                    } else {
                        approvals += a
                        if (approvals.size == 1) {
                            phase = Phase.Approval
                            announce(a)
                        }
                    }
                }
                is LinkEvent.Done -> {
                    turns = (turns - 1).coerceAtLeast(0)
                    doneAt = System.currentTimeMillis()
                    restarts = 0
                    val had = reply.isNotBlank() || transcript.lastOrNull()?.who == "image"
                    endReply()
                    saveSession()
                    if (e.error != null) {
                        say("Sorry, that didn't work.")
                        notice = e.error
                    } else if (!had && !e.cancelled) {
                        notice = "${agent?.name} answered with nothing to say"
                    }
                    if (turns == 0) {
                        phase = if (approvals.isEmpty()) Phase.Idle else Phase.Approval
                        scheduleIdleStop()
                    }
                }
                is LinkEvent.Notice -> notice = e.text
                is LinkEvent.Failed -> {
                    notice = "${agent?.name}: ${e.message}"
                    phase = Phase.Off
                }
            }
        }
    }

    private fun announce(a: Approval) = say("${agent?.name ?: "It"} wants to ${Steps.spoken(a.label)}. Allow it?")

    /** Finished sentences to the voice as soon as they're complete; the rest when the reply ends. */
    private fun handOver(final: Boolean) {
        while (true) {
            val m = SENTENCE.find(buffer) ?: break
            if (m.range.last + 1 == buffer.length && !final && !buffer.endsWith("\n")) break
            say(buffer.substring(0, m.range.last + 1))
            buffer = buffer.substring(m.range.last + 1)
        }
        if (final && buffer.isNotBlank()) say(buffer)
        if (final) buffer = ""
    }

    private fun say(text: String) {
        val clean = spokenText(text)
        if (clean.isEmpty()) return
        lastSaid = System.currentTimeMillis()
        speaker.speak(clean)
    }

    /** The reply so far into the transcript, as its own paragraph. */
    private fun endReply() {
        handOver(final = true)
        if (reply.isNotBlank()) {
            transcript += Entry("agent", reply.trim())
            saveTranscript()
        }
        reply = ""
        afterTool = false
    }

    /** A picture it read, like a widget's preview: copied now, as the next owl-check overwrites it, after what it said so far. */
    private fun keepImage(guestPath: String) {
        val source = proot.guestFile(guestPath)?.takeIf { it.isFile } ?: return
        endReply()
        val images = File(dir, "images").apply { mkdirs() }
        images.listFiles()?.sortedBy { it.name }?.dropLast(29)?.forEach { it.delete() }
        val copy = File(images, "${System.nanoTime()}.${source.extension.lowercase()}")
        runCatching { source.copyTo(copy) }.onFailure { return }
        // The same picture again with nothing in between: just the newer one.
        transcript.lastOrNull()?.takeIf { it.who == "image" && it.text.substringAfterLast('|') == guestPath }?.let {
            File(it.text.substringBefore('|')).delete()
            transcript.removeAt(transcript.lastIndex)
        }
        transcript += Entry("image", copy.path + "|" + guestPath)
        saveTranscript()
    }

    // ---------- a watch for stuck turns and folded prompts

    private val watch =
        object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                // Claude can fold a prompt sent mid-task into that task, with no answer of its own.
                if (link?.foldsPrompts == true && turns > 0 && doneAt > 0 && lastEvent <= doneAt && now - doneAt > 4000) {
                    turns = 0
                    doneAt = 0
                    phase = if (approvals.isEmpty()) Phase.Idle else Phase.Approval
                }
                if (turns > 0 && now - lastEvent > QUIET_MS && notice == null) notice = "${agent?.name} has been quiet for two minutes; stop it if it's stuck"
                if (process != null) main.postDelayed(this, 1000)
            }
        }

    private fun startWatch() {
        main.removeCallbacks(watch)
        main.postDelayed(watch, 1000)
    }

    // ---------- files

    private fun saveTranscript() {
        val a = agent ?: return
        val out = JSONArray()
        for (e in transcript.takeLast(200)) out.put(JSONObject().put("who", e.who).put("text", e.text))
        writeFile(transcriptFile(a.bin), out.toString())
    }

    private fun saveSession(busy: Boolean = this.busy) {
        val a = agent ?: return
        val id = session ?: return
        writeFile(sessionFile(a.bin), JSONObject().put("id", id).put("at", System.currentTimeMillis()).put("busy", busy).toString())
    }

    private fun saveSettings() = writeFile(settingsFile, JSONObject().put("agent", agent?.bin).put("approveAll", approveAll).toString())

    private fun writeFile(
        file: File,
        text: String,
    ) = writer.execute {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    private fun readJson(file: File) = runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun readArray(file: File) = runCatching { JSONArray(file.readText()) }.getOrNull()

    companion object {
        private const val TAG = "voice"

        /** The agents that speak ACP, by bin, with what starts them as an ACP server on stdio. */
        private val ACP = mapOf("grok" to "grok agent stdio", "opencode" to "opencode acp")

        /** The agents with a link, by bin. */
        val LINKS = ACP.keys + "claude"

        fun linkFor(
            agent: Agent,
            write: (String) -> Unit,
        ): AgentLink = ACP[agent.bin]?.let { AcpLink(write, it) } ?: ClaudeLink(write)

        const val IDLE_STOP_MS = 10 * 60_000L
        const val RESUME_MS = 12 * 3_600_000L
        const val QUIET_MS = 120_000L
        const val PROGRESS_MS = 12_000L
        private val SENTENCE = Regex("[.!?:;](\\s|$)|\\n")
        private val YES = Regex("^\\W*(yes|yeah|yep|sure|ok|okay|allow|allowed|approve|go ahead|do it|go|fine|alright|please do|ja|si|sí|oui)\\b", RegexOption.IGNORE_CASE)
        private val NO = Regex("^\\W*(no|nope|deny|don't|do not|stop|cancel|never mind|nevermind|nein|non)\\W*$", RegexOption.IGNORE_CASE)

        /** Text as it's read aloud: no code, links or markdown marks. */
        fun spokenText(s: String): String =
            s
                .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                .replace(Regex("https?://\\S+"), "a link")
                .replace(Regex("[*_#`>|~]"), "")
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
    }
}
