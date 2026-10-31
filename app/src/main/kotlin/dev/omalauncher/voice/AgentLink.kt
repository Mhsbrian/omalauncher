package dev.omalauncher.voice

import org.json.JSONArray
import org.json.JSONObject

/** What an agent did, read from its output by an [AgentLink]. */
sealed interface LinkEvent {
    /** Its session, to continue the conversation later. */
    data class Session(
        val id: String,
    ) : LinkEvent

    /** Ready for prompts. */
    data object Ready : LinkEvent

    /** The session asked to continue is gone; a new one was started instead. */
    data object ResumeFailed : LinkEvent

    /** More of its reply, as it's written. */
    data class Text(
        val text: String,
    ) : LinkEvent

    /** It started using a tool; what for comes with the [Step]. */
    data object Working : LinkEvent

    /** A tool call, in a few words for the screen ("reading 60-owl.owl"), with the pictures it reads. */
    data class Step(
        val label: String,
        val images: List<String> = emptyList(),
    ) : LinkEvent

    /** It asks before using a tool: [id] goes back in [AgentLink.answer]. [key] is what "always" allows. */
    data class Ask(
        val id: String,
        val label: String,
        val detail: String,
        val key: String,
    ) : LinkEvent

    /** A prompt is answered: [error] when it failed, [cancelled] when it was stopped. */
    data class Done(
        val error: String? = null,
        val cancelled: Boolean = false,
    ) : LinkEvent

    /** Something to show, like a retry after a network error. */
    data class Notice(
        val text: String,
    ) : LinkEvent

    /** It can't go on: not signed in, no session. */
    data class Failed(
        val message: String,
    ) : LinkEvent
}

/**
 * One agent CLI kept running for a spoken conversation: the command that starts it, what to write for a prompt,
 * a stop or an answer, and the [LinkEvent]s its output means. No process or threads here: lines come in
 * through [read] and replies go out through [write], so a link is tested with recorded output.
 */
abstract class AgentLink(
    protected val write: (String) -> Unit,
) {
    /** The guest shell command that starts it, continuing [resume] when it's given. */
    abstract fun command(resume: String?): String

    /** Writes what it needs before the first prompt. */
    open fun open(resume: String?) {}

    abstract fun prompt(text: String)

    /** Stops the turn in progress, and any prompts waiting behind it. */
    abstract fun cancel()

    /**
     * Answers [LinkEvent.Ask] [id]. [instead] is what the user said to do instead of a refused tool; true when
     * the agent got it with the refusal, false when it has to be sent as the next prompt.
     */
    abstract fun answer(
        id: String,
        allow: Boolean,
        always: Boolean,
        instead: String?,
    ): Boolean

    abstract fun read(line: String): List<LinkEvent>

    /** A prompt sent mid-turn may be folded into the turn, with no [LinkEvent.Done] of its own. */
    open val foldsPrompts = false
}

/** How the agent is told it's in a spoken conversation. */
const val VOICE_PROMPT =
    "You are talking with the user through a voice interface on their phone: what they say reaches you as " +
        "speech-to-text, which can mishear words, and your replies are read aloud by text-to-speech. Talk like a " +
        "person in a conversation: usually one to three short sentences, longer only when asked. Answer in the " +
        "language the user speaks. Never use markdown, lists, headings, code blocks, tables, emoji or URLs; say " +
        "numbers, paths and symbols the way you'd say them out loud. When you need a tool, first say in a few " +
        "words what you're about to do."

private fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

/**
 * Claude Code over stream-json both ways: `claude -p --input-format stream-json`, one user message per prompt,
 * its partial messages as the reply streams, and approvals through `--permission-prompt-tool stdio`.
 */
class ClaudeLink(
    write: (String) -> Unit,
) : AgentLink(write) {
    override val foldsPrompts = true

    private var session: String? = null
    private val asked = HashMap<String, JSONObject>()

    override fun command(resume: String?) =
        "exec claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages " +
            "--append-system-prompt ${sh(VOICE_PROMPT)} --allowedTools Read,Glob,Grep,WebSearch,WebFetch " +
            "--permission-prompt-tool stdio" + (resume?.let { " --resume ${sh(it)}" } ?: "")

    override fun open(resume: String?) {
        session = resume
        // As the Agent SDK opens a session.
        write(JSONObject().put("type", "control_request").put("request_id", "init").put("request", JSONObject().put("subtype", "initialize")).toString())
    }

    override fun prompt(text: String) {
        write(JSONObject().put("type", "user").put("message", JSONObject().put("role", "user").put("content", text)).toString())
    }

    override fun cancel() {
        write(JSONObject().put("type", "control_request").put("request_id", "stop${System.nanoTime()}").put("request", JSONObject().put("subtype", "interrupt")).toString())
    }

    override fun answer(
        id: String,
        allow: Boolean,
        always: Boolean,
        instead: String?,
    ): Boolean {
        val input = asked.remove(id) ?: JSONObject()
        val response =
            if (allow) {
                JSONObject().put("behavior", "allow").put("updatedInput", input)
            } else {
                JSONObject().put("behavior", "deny").put("message", instead?.let { "The user said no, and said: $it" } ?: "The user said no to this.")
            }
        write(
            JSONObject().put("type", "control_response").put(
                "response",
                JSONObject().put("subtype", "success").put("request_id", id).put("response", response),
            ).toString(),
        )
        return true
    }

    override fun read(line: String): List<LinkEvent> {
        val ev = runCatching { JSONObject(line) }.getOrNull() ?: return emptyList()
        val out = ArrayList<LinkEvent>()
        ev.optString("session_id").takeIf { it.isNotEmpty() && it != session }?.let {
            session = it
            out += LinkEvent.Session(it)
        }
        when (ev.optString("type")) {
            "system" ->
                when (ev.optString("subtype")) {
                    "init" -> out += LinkEvent.Ready
                    "api_retry" -> out += LinkEvent.Notice("retrying: " + ev.optString("error", "network"))
                }
            "stream_event" -> {
                if (!ev.isNull("parent_tool_use_id")) return out
                val e = ev.optJSONObject("event") ?: return out
                when (e.optString("type")) {
                    "content_block_delta" ->
                        e.optJSONObject("delta")?.takeIf { it.optString("type") == "text_delta" }?.let { out += LinkEvent.Text(it.optString("text")) }
                    "content_block_start" ->
                        if (e.optJSONObject("content_block")?.optString("type") == "tool_use") out += LinkEvent.Working
                }
            }
            // The whole message, tool inputs included: each tool call is a step.
            "assistant" ->
                ev.optJSONObject("message")?.optJSONArray("content")?.objects()?.filter { it.optString("type") == "tool_use" }?.forEach {
                    val name = it.optString("name")
                    val input = it.optJSONObject("input") ?: JSONObject()
                    val sub = !ev.isNull("parent_tool_use_id")
                    val images = if (name == "Read") listOfNotNull(input.optString("file_path").takeIf(::isImage)) else emptyList()
                    out += LinkEvent.Step((if (sub) "  " else "") + Steps.label(name, input), images)
                }
            "result" ->
                out +=
                    if (ev.optBoolean("is_error")) {
                        LinkEvent.Done(error = ev.optString("result").ifEmpty { ev.optString("subtype", "failed") }.take(200))
                    } else {
                        LinkEvent.Done()
                    }
            "control_request" -> {
                val id = ev.optString("request_id")
                val req = ev.optJSONObject("request") ?: JSONObject()
                if (req.optString("subtype") != "can_use_tool") {
                    write(JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "error").put("request_id", id).put("error", "not supported")).toString())
                } else {
                    val name = req.optString("tool_name")
                    val input = req.optJSONObject("input") ?: JSONObject()
                    asked[id] = input
                    val detail = listOf("command", "file_path", "url", "pattern").firstNotNullOfOrNull { k -> input.optString(k).takeIf { it.isNotEmpty() } } ?: ""
                    out += LinkEvent.Ask(id, Steps.label(name, input), detail.take(400), Steps.alwaysKey(name, input.optString("command")))
                }
            }
        }
        return out
    }
}

/**
 * An agent over ACP, the Agent Client Protocol: JSON-RPC on stdio (`grok agent stdio`, `opencode acp`). One session; prompts go
 * one at a time, a later one waiting for the one in flight. session/update notifications carry the reply and
 * tool calls, session/request_permission asks before a tool, session/load continues a conversation (its
 * replay of the old one isn't shown). ACP has no system prompt, so the first prompt of a conversation says
 * how to talk.
 */
class AcpLink(
    write: (String) -> Unit,
    /** The guest command that serves ACP on stdio: `grok agent stdio`, `opencode acp`. */
    private val serve: String,
    /** Where its sessions work: OpenCode snapshots this folder with git before each turn, so not all of home. */
    private val cwd: String = HOME,
) : AgentLink(write) {
    private var nextId = 0
    private val calls = HashMap<Int, (JSONObject?, JSONObject?) -> List<LinkEvent>>()
    private var session: String? = null
    private var ready = false
    private var loading = false
    private var primed = false
    private var inFlight = false
    private val queue = ArrayDeque<String>()
    private val asked = HashMap<String, Pair<Any, JSONArray>>()

    override fun command(resume: String?) = "exec $serve"

    override fun open(resume: String?) {
        session = resume
        primed = resume != null
        call(
            "initialize",
            JSONObject().put("protocolVersion", 1).put(
                "clientCapabilities",
                JSONObject().put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false)).put("terminal", false),
            ),
        ) { _, error ->
            if (error != null) return@call listOf(LinkEvent.Failed(error.optString("message", "it didn't start")))
            val id = session
            if (id != null) {
                loading = true
                call("session/load", JSONObject().put("sessionId", id).put("cwd", cwd).put("mcpServers", JSONArray())) { _, e -> loaded(e) }
            } else {
                newSession()
            }
            emptyList()
        }
    }

    private fun newSession() {
        call("session/new", JSONObject().put("cwd", cwd).put("mcpServers", JSONArray())) { result, error ->
            val id = result?.optString("sessionId")?.takeIf { it.isNotEmpty() }
            if (id == null) return@call listOf(LinkEvent.Failed(error?.optString("message") ?: "no conversation"))
            session = id
            listOf(LinkEvent.Session(id)) + becomeReady()
        }
    }

    private fun loaded(error: JSONObject?): List<LinkEvent> {
        loading = false
        if (error == null) return becomeReady()
        session = null
        primed = false
        newSession()
        return listOf(LinkEvent.ResumeFailed)
    }

    private fun becomeReady(): List<LinkEvent> {
        ready = true
        next()
        return listOf(LinkEvent.Ready)
    }

    override fun prompt(text: String) {
        queue.addLast(text)
        next()
    }

    private fun next() {
        if (!ready || inFlight || queue.isEmpty()) return
        var text = queue.removeFirst()
        if (!primed) {
            text = "($VOICE_PROMPT)\n\n$text"
            primed = true
        }
        inFlight = true
        val params = JSONObject().put("sessionId", session).put("prompt", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        call("session/prompt", params) { result, error ->
            inFlight = false
            val stop = result?.optString("stopReason")
            val done = LinkEvent.Done(error = error?.optString("message", "failed")?.take(200), cancelled = stop == "cancelled")
            next()
            listOf(done)
        }
    }

    override fun cancel() {
        queue.clear()
        session?.let { write(JSONObject().put("jsonrpc", "2.0").put("method", "session/cancel").put("params", JSONObject().put("sessionId", it)).toString()) }
    }

    override fun answer(
        id: String,
        allow: Boolean,
        always: Boolean,
        instead: String?,
    ): Boolean {
        val (rpcId, options) = asked.remove(id) ?: return false
        val want = if (allow) (if (always) listOf("allow_always", "allow_once") else listOf("allow_once", "allow_always")) else listOf("reject_once", "reject_always")
        val pick = want.firstNotNullOfOrNull { k -> options.objects().firstOrNull { it.optString("kind") == k } }
        val outcome = if (pick != null) JSONObject().put("outcome", "selected").put("optionId", pick.optString("optionId")) else JSONObject().put("outcome", "cancelled")
        write(JSONObject().put("jsonrpc", "2.0").put("id", rpcId).put("result", JSONObject().put("outcome", outcome)).toString())
        return false
    }

    override fun read(line: String): List<LinkEvent> {
        val ev = runCatching { JSONObject(line) }.getOrNull() ?: return emptyList()
        val method = ev.optString("method")
        // An answer to one of ours.
        if (method.isEmpty() && ev.has("id")) {
            val then = calls.remove(ev.optInt("id")) ?: return emptyList()
            return then(ev.optJSONObject("result"), ev.optJSONObject("error"))
        }
        when (method) {
            "session/update" -> if (!loading) return update(ev.optJSONObject("params")?.optJSONObject("update") ?: return emptyList())
            "session/request_permission" -> return listOf(permission(ev.opt("id") ?: return emptyList(), ev.optJSONObject("params") ?: JSONObject()))
            else ->
                // fs/… or terminal/…, which we said we don't offer; notifications (_x.ai/…) need nothing.
                if (ev.has("id")) {
                    write(JSONObject().put("jsonrpc", "2.0").put("id", ev.opt("id")).put("error", JSONObject().put("code", -32601).put("message", "$method isn't supported")).toString())
                }
        }
        return emptyList()
    }

    private fun update(u: JSONObject): List<LinkEvent> =
        when (u.optString("sessionUpdate")) {
            "agent_message_chunk" ->
                u.optJSONObject("content")?.takeIf { it.optString("type") == "text" }?.let { listOf(LinkEvent.Text(it.optString("text"))) } ?: emptyList()
            "tool_call" -> listOf(LinkEvent.Working, LinkEvent.Step(Steps.acpLabel(u), Steps.acpPaths(u).filter(::isImage)))
            else -> emptyList()
        }

    private fun permission(
        rpcId: Any,
        params: JSONObject,
    ): LinkEvent {
        val call = params.optJSONObject("toolCall") ?: JSONObject()
        val id = "p$rpcId"
        asked[id] = rpcId to (params.optJSONArray("options") ?: JSONArray())
        val raw = call.optJSONObject("rawInput") ?: JSONObject()
        val detail = listOf("command", "path", "file_path", "url").firstNotNullOfOrNull { k -> raw.optString(k).takeIf { it.isNotEmpty() } } ?: ""
        val key = if (call.optString("kind") == "execute" && raw.has("command")) Steps.alwaysKey("Bash", raw.optString("command")) else "acp:" + call.optString("kind")
        return LinkEvent.Ask(id, Steps.acpLabel(call), detail.take(400), key)
    }

    private fun call(
        method: String,
        params: JSONObject,
        then: (JSONObject?, JSONObject?) -> List<LinkEvent>,
    ) {
        val id = ++nextId
        calls[id] = then
        write(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString())
    }

    companion object {
        const val HOME = "/root"
    }
}

/** Tool calls in a few words, and what "always" covers. */
object Steps {
    fun label(
        name: String,
        input: JSONObject,
    ): String {
        fun base(p: String) = p.trimEnd('/').substringAfterLast('/').ifEmpty { p }
        return when (name) {
            "Bash" -> "running: " + input.optString("description").ifEmpty { input.optString("command") }.take(60)
            "Read" -> "reading " + base(input.optString("file_path"))
            "Write" -> "writing " + base(input.optString("file_path"))
            "Edit", "NotebookEdit" -> "editing " + base(input.optString("file_path"))
            "Glob", "Grep" -> ("searching " + input.optString("pattern")).take(60)
            "WebSearch" -> ("searching the web: " + input.optString("query")).take(60)
            "WebFetch" -> "reading " + input.optString("url").substringAfter("://").substringBefore('/')
            "Task" -> "subtask: " + input.optString("description").take(50)
            "TodoWrite" -> "planning"
            else -> name.lowercase()
        }
    }

    fun acpPaths(u: JSONObject): List<String> {
        val raw = u.optJSONObject("rawInput") ?: JSONObject()
        val locations = u.optJSONArray("locations")?.objects()?.mapNotNull { it.optString("path").takeIf(String::isNotEmpty) } ?: emptyList()
        return locations + listOf("path", "file_path").mapNotNull { k -> raw.optString(k).takeIf(String::isNotEmpty) }
    }

    fun acpLabel(u: JSONObject): String {
        val kind = u.optString("kind")
        val title = u.optString("title").trim()
        val raw = u.optJSONObject("rawInput") ?: JSONObject()
        val path = acpPaths(u).firstOrNull()
        return when {
            kind == "execute" -> "running: " + raw.optString("description").ifEmpty { title.ifEmpty { raw.optString("command") } }.take(60)
            kind in setOf("read", "edit", "delete") && path != null ->
                mapOf("read" to "reading", "edit" to "editing", "delete" to "deleting").getValue(kind) + " " + path.trimEnd('/').substringAfterLast('/')
            else -> title.ifEmpty { kind.ifEmpty { "a tool" } }.take(60)
        }
    }

    /** Every program a command line runs: `cd x && curl y | head` is cd, curl and head. */
    fun programs(command: String): Set<String> =
        command
            .split(Regex("&&|\\|\\||[;|&\\n]|\\$\\(|`"))
            .mapNotNull { part -> part.trim().split(Regex("\\s+")).firstOrNull { it.isNotEmpty() && !Regex("^\\w+=").containsMatchIn(it) } }
            .map { it.trimStart('(', '{').substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .toSet()

    /** What an "always" answer allows: a tool, or for a command every program in it. */
    fun alwaysKey(
        name: String,
        command: String,
    ) = if (name == "Bash") "Bash:" + programs(command).sorted().joinToString(" ") else name

    /** Whether "always" answers so far cover [key]: for commands, each of their programs was allowed. */
    fun covered(
        key: String,
        always: Set<String>,
    ): Boolean {
        if (!key.startsWith("Bash:")) return key in always
        val allowed = always.filter { it.startsWith("Bash:") }.flatMap { it.removePrefix("Bash:").split(' ') }.toSet()
        val progs = key.removePrefix("Bash:").split(' ').filter { it.isNotEmpty() }.toSet()
        return progs.isNotEmpty() && allowed.containsAll(progs)
    }

    /** A step as it's said: "running: List widgets" is "run a command: List widgets". */
    fun spoken(label: String): String {
        val verbs =
            listOf(
                "running: " to "run a command: ", "reading " to "read ", "writing " to "write ", "editing " to "edit ",
                "deleting " to "delete ", "searching the web: " to "search the web for ", "searching " to "search for ",
                "subtask: " to "start a subtask: ",
            )
        verbs.firstOrNull { label.startsWith(it.first) }?.let { (a, b) -> return b + label.removePrefix(a) }
        return "use $label"
    }
}

fun isImage(path: String?) = path != null && Regex("\\.(png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE).containsMatchIn(path)

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
