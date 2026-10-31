#!/usr/bin/env python3
"""Voice talk prototype: one persistent Claude Code as the brain; the voice app speaks its sentences.

Run by the voice app as stream("python3 ~/voice/brain.py"). Prints one JSON line per change:
  {state, heard, reply, tool, err, turn, say, t}
state is starting | idle | listening | thinking | working | speaking; say is this turn's finished
sentences, growing as they stream in (the app speaks the new ones); t is a clock tick (ms).
Requests come through the FIFO /tmp/voice.in (~/voice/send): "listen", "stop", "say <text>".
Everything that happens goes to /tmp/voice.log.
"""
import json, os, re, shutil, signal, subprocess, sys, threading, time, traceback

FIFO = os.environ.get("VOICE_FIFO") or "/tmp/voice.in"
HOME = os.environ.get("VOICE_DIR") or os.path.expanduser("~/voice")
HIST = f"{HOME}/history.json"     # the conversation, for the app's transcript (it reads the file)
AGENTS = ("claude", "grok")      # the CLIs the voice app can talk to; see make_brain
SESSION_OF = lambda a: f"{HOME}/session-{a}.json"  # each agent's session, resumed when the app opens again
HIST_OF = lambda a: f"{HOME}/history-{a}.json"     # each agent's transcript while another one is talking
IMAGES = f"{HOME}/images"          # copies of the pictures Claude looked at, as they were then
SETTINGS = f"{HOME}/settings.json"  # the user's choices: {"approve": "ask" | "all"}
RESUME_H = 12                     # a conversation older than this starts fresh
PIDS = os.environ.get("VOICE_PIDS") or "/tmp/voice.pids"
LOG = open(os.environ.get("VOICE_LOG") or "/tmp/voice.log", "a", buffering=1)
QUIET_S = 120  # a turn with no event from Claude for this long gets a warning (it isn't cancelled)
PROGRESS_S = 12  # while tools run, a short spoken update at most this often
PROMPT = (
    "You are talking with the user through a voice interface on their phone: what they say reaches you "
    "as speech-to-text, which can mishear words, and your replies are read aloud by text-to-speech. "
    "Talk like a person in a conversation: usually one to three short sentences, longer only when asked. "
    "Never use markdown, lists, headings, code blocks, tables, emoji or URLs; say numbers, paths and "
    "symbols the way you'd say them out loud. When you need a tool, first say in a few words what "
    "you're about to do."
)
AGENT = [
    "claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
    "--include-partial-messages", "--append-system-prompt", PROMPT,
    "--allowedTools", "Read,Glob,Grep,WebSearch,WebFetch",
    # anything else Claude would ask about comes to us as a can_use_tool request, for the app to approve
    "--permission-prompt-tool", "stdio",
]
if os.environ.get("VOICE_AGENT"):  # a stand-in, for tests
    AGENT = os.environ["VOICE_AGENT"].split()
YES = re.compile(r"^\W*(yes|yeah|yep|sure|ok|okay|allow|allowed|approve|approved|go ahead|do it|go|fine|alright|please do)\b", re.I)
NO = re.compile(r"^\W*(no|nope|deny|denied|don't|do not|stop|cancel|never mind|nevermind)\W*$", re.I)

PROGRESS = {
    "Read": "Reading.", "Glob": "Looking through files.", "Grep": "Searching.", "Bash": "Running a command.",
    "Write": "Writing a file.", "Edit": "Editing.", "WebSearch": "Searching the web.", "WebFetch": "Reading a page.",
    "Task": "Handing part of it off.", "TodoWrite": "Planning.",
}

lock = threading.RLock()
st = {"agent": "claude", "agentName": "Claude", "agents": [], "approveAll": False, "live": False, "resumed": False, "state": "starting", "heard": "", "reply": "", "tool": None, "err": None, "turn": 0, "say": [], "steps": [], "since": 0,
      "approve": None}
brain = None


def nm():
    return st.get("agentName") or "the agent"


def installed(agent):
    """The agent's program, if it's installed."""
    found = shutil.which(agent)
    if not found and agent == "grok" and os.path.exists(os.path.expanduser("~/.grok/bin/grok")):
        found = os.path.expanduser("~/.grok/bin/grok")
    return found


def log(*a):
    LOG.write(time.strftime("%H:%M:%S ") + " ".join(str(x) for x in a) + "\n")


def emit(**kw):
    with lock:
        st.update(kw)
        st["t"] = round(time.time() * 1000)
        st["busy"] = bool(brain and brain.turns)  # Claude has a task open, whatever it's saying
        try:
            sys.stdout.write(json.dumps(st) + "\n")
            sys.stdout.flush()
        except (BrokenPipeError, ValueError):
            shutdown()  # the app closed: nobody's listening any more


def fail(msg):
    log("error:", msg)
    emit(err=msg)


def write_json(path, value):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(value, f)
    os.replace(tmp, path)  # whole files only: the app reads it as it changes


def read_json(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


history = []


def add_hist(who, text):
    text = text.strip()
    if text:
        history.append({"who": who, "text": text})
        del history[:-100]
        write_json(HIST, history)


def is_image(path):
    return bool(re.search(r"\.(png|jpe?g|webp|gif)$", path or "", re.I)) and os.path.isfile(path)


def keep_image(path):
    """A picture Claude reads (a widget preview, a screenshot): copied, since the next owl-check overwrites the
    preview, and put in the transcript."""
    if not is_image(path):
        return
    os.makedirs(IMAGES, exist_ok=True)
    kept = sorted(os.listdir(IMAGES))
    for old in kept[:-29]:  # the last 30 are plenty
        try:
            os.unlink(f"{IMAGES}/{old}")
        except OSError:
            pass
    copy = f"{IMAGES}/{time.time_ns()}{os.path.splitext(path)[1].lower()}"
    try:
        shutil.copyfile(path, copy)
    except OSError as e:
        return log("couldn't keep image", path, e)
    if history and history[-1].get("who") == "image" and history[-1].get("from") == path:
        try:  # looked at the same file again without anything in between: just the newer picture
            os.unlink(history[-1]["text"])
        except OSError:
            pass
        history.pop()
    history.append({"who": "image", "text": copy, "from": path, "name": os.path.basename(path)})
    del history[:-100]
    write_json(HIST, history)


def step(name, inp):
    """A tool call as a few words for the screen: what it reads, runs or writes."""
    base = lambda p: os.path.basename(str(p).rstrip("/")) or str(p)
    inp = inp or {}
    if name == "Bash":
        return "running: " + (inp.get("description") or inp.get("command", ""))[:60]
    if name in ("Read", "Write", "Edit", "NotebookEdit"):
        verb = {"Read": "reading", "Write": "writing", "Edit": "editing"}.get(name, "editing")
        return f"{verb} {base(inp.get('file_path', ''))}"
    if name in ("Glob", "Grep"):
        return f"searching {inp.get('pattern', '')}"[:60]
    if name == "WebSearch":
        return f"searching the web: {inp.get('query', '')}"[:60]
    if name == "WebFetch":
        return "reading " + re.sub(r"^https?://([^/]+).*", r"\1", inp.get("url", ""))
    if name == "Task":
        return "subtask: " + (inp.get("description") or "")[:50]
    if name == "TodoWrite":
        return "planning"
    return name.lower()


def programs(cmd):
    """Every program a command line runs: `cd x && curl y | head` is cd, curl and head."""
    progs = set()
    for part in re.split(r"&&|\|\||[;|&\n]|\$\(|`", cmd):
        words = [w for w in part.strip().split() if not re.match(r"^\w+=", w)]  # skip VAR=value
        if words:
            progs.add(os.path.basename(words[0].lstrip("({")))
    return progs


def always_key(name, inp):
    """What "always" covers: a tool, or for commands every program in them."""
    if name == "Bash":
        return "Bash:" + " ".join(sorted(programs(inp.get("command") or "")))
    return name


def covered(key, always):
    """Whether "always" answers already cover this: for commands, each of their programs was allowed."""
    if key.startswith("Bash:"):
        allowed = {p for k in always if k.startswith("Bash:") for p in k[5:].split()}
        progs = set(key[5:].split())
        return bool(progs) and progs <= allowed
    return key in always


def spoken(what):
    """A step as it's said: "running: List widget folder" -> "run a command: List widget folder"."""
    for a, b in (("running: ", "run a command: "), ("reading ", "read "), ("writing ", "write "), ("editing ", "edit "),
                 ("searching the web: ", "search the web for "), ("searching ", "search for "), ("subtask: ", "start a subtask: ")):
        if what.startswith(a):
            return b + what[len(a):]
    return "use " + what


def clean(s):
    s = re.sub(r"```.*?```", " ", s, flags=re.S)
    s = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", s)
    s = re.sub(r"https?://\S+", "a link", s)
    s = re.sub(r"[*_#`>|~]", "", s)
    return " ".join(s.split())


# ---------- one brain at a time ----------

def kill_leftovers():
    """A brain from an earlier open of the app, and its Claude, if they're still around."""
    try:
        pids = [int(p) for p in open(PIDS).read().split()]
    except (OSError, ValueError):
        return
    for pid in pids:
        if pid == os.getpid():
            continue
        try:
            cmd = open(f"/proc/{pid}/cmdline", "rb").read()
        except OSError:
            continue
        if b"brain.py" not in cmd and b"claude" not in cmd and b"grok" not in cmd:
            continue  # the number belongs to someone else now
        try:
            os.kill(pid, signal.SIGKILL)
            log("killed leftover", pid)
        except (ProcessLookupError, PermissionError):
            pass
        try:
            os.killpg(pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass


def shutdown(*_):
    log("shutting down")
    try:
        os.killpg(brain.proc.pid, signal.SIGKILL)
    except Exception:
        pass
    os._exit(0)


# ---------- the brain: one Claude process, turns written to its stdin ----------

class Brain:
    """Claude Code: one `claude -p` in stream-json both ways, approvals through --permission-prompt-tool stdio."""
    agent = "claude"
    folds = True  # a message sent mid-task can be folded into it, with no result of its own

    def __init__(self, resume=None):
        self.proc = None
        self.turns = 0      # sent and not answered yet
        self.buf = ""       # streamed text not yet handed over (an unfinished sentence)
        self.said_tool = False
        self.last_event = 0.0
        self.last_said = 0.0
        self.warned = False
        self.result_at = 0.0
        self.restarts = 0
        self.pending = {}       # approval requests waiting for the user, by request id
        self.always = set()     # what the user allowed for the rest of this session
        self.start(resume)

    def start(self, resume=None):
        log("starting claude", f"(resuming {resume})" if resume else "")
        self.session = resume
        self.complaints = []
        self.started = time.time()
        self.resuming = bool(resume)
        self.proc = subprocess.Popen(
            AGENT + (["--resume", resume] if resume else []), cwd=os.path.expanduser("~"), start_new_session=True,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1,
        )
        with open(PIDS, "w") as f:
            f.write(f"{os.getpid()} {self.proc.pid}\n")
        self.turns = 0
        self.pending = {}
        # as the Agent SDK opens a session; harmless where the CLI doesn't need it
        try:
            self.proc.stdin.write(json.dumps({"type": "control_request", "request_id": "init",
                                              "request": {"subtype": "initialize", "hooks": None}}) + "\n")
            self.proc.stdin.flush()
        except Exception:
            pass
        threading.Thread(target=self._read, args=(self.proc,), daemon=True).start()
        threading.Thread(target=self._errs, args=(self.proc,), daemon=True).start()

    def save(self):
        if self.session:
            write_json(SESSION_OF(self.agent), {"id": self.session, "at": time.time(), "busy": self.turns > 0})

    def new(self):
        """A fresh conversation: Claude restarted without its session, the transcript cleared."""
        with lock:
            self.deny_all()
            for path in (SESSION_OF(self.agent), HIST):
                try:
                    os.unlink(path)
                except FileNotFoundError:
                    pass
            history.clear()
            write_json(HIST, history)
            shutil.rmtree(IMAGES, ignore_errors=True)
            try:
                os.killpg(self.proc.pid, signal.SIGKILL)
            except Exception:
                pass
            self.restarts = 0
            self.start()
            emit(state="idle", heard="", reply="", err=None, say=[], steps=[], approve=None, live=False, resumed=False)

    def ask(self, text):
        with lock:
            if self.proc.poll() is not None:
                self.restart(f"{nm()} had stopped")
            self.turns += 1  # sent while it's still working, Claude takes it up after the current turn
            self.buf = ""
            self.said_tool = False
            self.last_event = time.time()
            self.warned = False
            msg = {"type": "user", "message": {"role": "user", "content": text}}
            log("ask:", text)
            add_hist("you", text)
            try:
                self.proc.stdin.write(json.dumps(msg) + "\n")
                self.proc.stdin.flush()
            except (BrokenPipeError, OSError) as e:
                self.turns = 0
                emit(state="idle")
                return fail(f"couldn't reach {nm()} ({e}); tap to try again")
            fresh = st["state"] in ("idle", "listening") and self.turns == 1
            self.save()
            emit(live=True, state="thinking", heard=text, reply="", tool=None, err=None, turn=st["turn"] + 1, say=[],
                 steps=[] if fresh else st["steps"], since=round(time.time() * 1000) if fresh else st["since"])

    def interrupt(self):
        req = {"type": "control_request", "request_id": f"i{time.time()}", "request": {"subtype": "interrupt"}}
        try:
            self.proc.stdin.write(json.dumps(req) + "\n")
            self.proc.stdin.flush()
        except Exception:
            pass

    def restart(self, why):
        self.restarts += 1
        log("restarting claude:", why)
        try:
            os.killpg(self.proc.pid, signal.SIGKILL)
        except Exception:
            pass
        self.start(self.session)  # the same conversation

    def hand_over(self, final=False):
        """Finished sentences to the app as soon as they're complete; the rest when the reply ends."""
        while True:
            m = re.search(r"[.!?:;](\s|$)|\n", self.buf)
            if not m or (m.end() == len(self.buf) and not final and not self.buf.endswith("\n")):
                break
            sent, self.buf = self.buf[: m.end()], self.buf[m.end():]
            self.say(sent)
        if final and self.buf.strip():
            self.say(self.buf)
            self.buf = ""

    def say(self, text):
        text = clean(text)
        if text:
            self.last_said = time.time()
            emit(say=st["say"] + [text])

    def _read(self, proc):
        for line in proc.stdout:
            try:
                ev = json.loads(line)
            except ValueError:
                log("claude said:", line.rstrip()[:300])
                continue
            try:
                self._event(ev)
            except Exception:
                log(traceback.format_exc())
        code = proc.wait()
        time.sleep(0.3)  # what it said on stderr as it went is read on another thread
        with lock:
            if proc is not self.proc or getattr(self, "retired", False):
                return  # one we replaced, or an agent we switched away from
            was_busy = self.turns > 0
            log("claude exited", code)
            said = " ".join(self.complaints).lower()
            if self.resuming and re.search(r"no conversation|not found|invalid session|--resume", said):
                log("resume failed; starting a fresh conversation")
                try:
                    os.unlink(SESSION_OF(self.agent))
                except FileNotFoundError:
                    pass
                add_hist("note", "couldn't continue the last conversation; this is a new one")
                self.start()
                emit(resumed=False, live=False)
                return
            if self.restarts < 3:
                self.restart(f"exited {code}")
                if was_busy:
                    emit(state="idle")
                    fail(f"{nm()} stopped and was restarted; ask again")
            else:
                emit(state="idle")
                fail(f"{nm()} keeps stopping (exit {code}); see /tmp/voice.log")

    def _event(self, ev):
        with lock:
            self.last_event = time.time()
            sid = ev.get("session_id")
            if sid and sid != self.session:
                self.session = sid
                self.save()
            live = True
            self.warned = False
            t = ev.get("type")
            if t == "stream_event":
                if ev.get("parent_tool_use_id"):
                    return
                e = ev.get("event", {})
                et = e.get("type")
                if et == "content_block_start" and e["content_block"].get("type") == "text" and st["reply"] and not st["reply"].endswith((" ", "\n")):
                    emit(reply=st["reply"] + " ")  # a new block after a tool: keep the sentences apart
                if et == "content_block_delta" and e["delta"].get("type") == "text_delta" and live:
                    d = e["delta"]["text"]
                    self.buf += d
                    self.hand_over()
                    emit(state="speaking", reply=st["reply"] + d, tool=None)
                elif et == "content_block_start" and e["content_block"].get("type") == "tool_use" and live:
                    self.hand_over(final=True)
                    name = e["content_block"].get("name") or ""
                    if time.time() - self.last_said > PROGRESS_S:
                        self.say(PROGRESS.get(name, "Still working."))
                    self.said_tool = True
                    emit(state="working", tool=name)
                elif et == "message_stop" and live:
                    self.hand_over(final=True)
            elif t == "result":
                self.turns = max(0, self.turns - 1)
                self.result_at = time.time()
                log("result:", ev.get("subtype"), "error" if ev.get("is_error") else "ok", ev.get("duration_ms"), "ms")
                if live:
                    self.hand_over(final=True)
                    add_hist("agent", st["reply"])
                    self.save()
                    emit(state="idle" if self.turns == 0 else "thinking", live=self.turns > 0, reply="" if self.turns == 0 else st["reply"])
                    if ev.get("is_error"):
                        self.say("Sorry, that didn't work.")
                        fail(str(ev.get("result") or ev.get("subtype") or "Claude failed")[:200])
                    elif not st["say"]:
                        fail(f"{nm()} answered with nothing to say")
            elif t == "control_request":
                self.control(ev)
            elif t == "assistant":
                # the whole message, tool inputs included: each tool call becomes a step on screen
                for b in (ev.get("message") or {}).get("content") or []:
                    if b.get("type") == "tool_use":
                        path = (b.get("input") or {}).get("file_path") if b.get("name") == "Read" else None
                        if is_image(path):
                            # in the transcript where it happened: what Claude said before it, then the picture,
                            # then what it says next
                            if st["reply"].strip():
                                add_hist("agent", st["reply"])
                                emit(reply="")
                            keep_image(path)
                        sub = "  " if ev.get("parent_tool_use_id") else ""
                        emit(steps=(st["steps"] + [sub + step(b.get("name", ""), b.get("input"))])[-4:])
            elif t == "system" and ev.get("subtype") == "api_retry":
                emit(err=f"Claude is retrying ({ev.get('error', 'network')})")

    # ---------- approvals ----------

    def control(self, ev):
        rid = ev.get("request_id")
        req = ev.get("request") or {}
        if req.get("subtype") != "can_use_tool":
            log("unsupported control request:", json.dumps(req)[:200])
            return self.reply({"subtype": "error", "request_id": rid, "error": "not supported by the voice app"})
        name, inp = req.get("tool_name", ""), req.get("input") or {}
        if st.get("approveAll"):
            log("allowed (allow all):", name, json.dumps(inp)[:120])
            return self.decide(rid, True, inp)
        key = always_key(name, inp)
        if covered(key, self.always):
            log("allowed (always):", key)
            return self.decide(rid, True, inp)
        what = step(name, inp)
        detail = inp.get("command") or inp.get("file_path") or inp.get("url") or inp.get("pattern") or ""
        card = {"id": rid, "what": what, "says": spoken(what), "detail": str(detail)[:400], "tool": name, "key": key}
        self.pending[rid] = (inp, card)
        log("approval asked:", rid, name, json.dumps(inp)[:200])
        if not st.get("approve"):
            self.show(card)

    def show(self, card):
        self.say(f"{nm()} wants to {spoken(card['what'])}. Allow it?")
        emit(state="approve", approve=card)

    def decide(self, rid, allow, inp=None, message=None, always=False):
        with lock:
            waiting = self.pending.pop(rid, None)
            if inp is None:
                if waiting is None:
                    return  # answered already
                inp = waiting[0]
            if always and st.get("approve"):
                self.always.add(st["approve"]["key"])
            log("approval:", rid, "allow" if allow else "deny", "always" if always else "", message or "")
            if waiting:
                what = re.sub(r"^running: ", "", waiting[1]["what"])
                add_hist("note", ("✓ " if allow else "✗ ") + what + (" · always" if always else ""))
            self.answer(rid, inp, allow, message, always)
            if st.get("approve") and st["approve"]["id"] == rid:
                emit(approve=None, state="working" if self.turns else "idle")
                for other in list(self.pending):  # several at once: the next, unless "always" now covers it
                    oinp, card = self.pending[other]
                    if st.get("approveAll") or covered(card["key"], self.always):
                        self.decide(other, True)
                    else:
                        self.show(card)
                        break

    def answer(self, rid, inp, allow, message, always):
        resp = {"behavior": "allow", "updatedInput": inp} if allow else \
            {"behavior": "deny", "message": message or "The user said no to this."}
        self.reply({"subtype": "success", "request_id": rid, "response": resp})

    def stop_agent(self, retire=True):
        self.retired = retire  # switched away from: its exit isn't a crash to recover from
        try:
            os.killpg(self.proc.pid, signal.SIGKILL)
        except Exception:
            pass

    def deny_all(self):
        for rid in list(self.pending):
            self.decide(rid, False, message="The user stopped this.")

    def reply(self, response):
        try:
            self.proc.stdin.write(json.dumps({"type": "control_response", "response": response}) + "\n")
            self.proc.stdin.flush()
        except Exception as e:
            fail(f"couldn't answer {nm()} ({e})")

    def _errs(self, proc):
        for line in proc.stderr:
            log("claude stderr:", line.rstrip()[:300])
            if proc is self.proc:
                self.complaints = (self.complaints + [line])[-20:]


class AcpBrain(Brain):
    """An agent over ACP, the Agent Client Protocol: JSON-RPC on stdio (`grok agent stdio`). One session, prompts
    sent one at a time (a later one waits its turn), session/update notifications for text and tool calls,
    session/request_permission for approvals, session/load to continue a conversation."""
    agent = "grok"
    folds = False  # every prompt gets its own answer

    def start(self, resume=None):
        log("starting", self.agent, f"(resuming {resume})" if resume else "")
        self.session = resume
        self.complaints = []
        self.started = time.time()
        self.resuming = bool(resume)
        self.primed = bool(resume)  # a resumed conversation already knows it's spoken
        self.ready = False
        self.loading = False
        self.queue = []          # prompts waiting for the one in flight
        self.in_flight = None    # the prompt request's id
        self.calls = {}          # our requests' ids -> what to do with the answer
        self.next_id = 0
        self.after_tool = False
        exe = installed(self.agent) or self.agent
        self.proc = subprocess.Popen(
            [exe, "agent", "stdio"], cwd=os.path.expanduser("~"), start_new_session=True,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1,
        )
        with open(PIDS, "w") as f:
            f.write(f"{os.getpid()} {self.proc.pid}\n")
        self.turns = 0
        self.pending = {}
        threading.Thread(target=self._read, args=(self.proc,), daemon=True).start()
        threading.Thread(target=self._errs, args=(self.proc,), daemon=True).start()
        self.call("initialize", {"protocolVersion": 1, "clientCapabilities": {
            "fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False}}, self._initialized)

    # -- JSON-RPC

    def send(self, msg):
        try:
            self.proc.stdin.write(json.dumps(msg) + "\n")
            self.proc.stdin.flush()
            return True
        except (BrokenPipeError, OSError, ValueError) as e:
            log("couldn't write to", self.agent, e)
            return False

    def call(self, method, params, then):
        self.next_id += 1
        self.calls[self.next_id] = then
        self.send({"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params})
        return self.next_id

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})

    # -- session

    def _initialized(self, result, error):
        if error:
            return fail(f"{nm()} didn't start: {error.get('message', error)}")
        home = os.path.expanduser("~")
        if self.session:
            self.loading = True  # it replays the conversation as updates: not for the screen or the voice
            self.call("session/load", {"sessionId": self.session, "cwd": home, "mcpServers": []}, self._loaded)
        else:
            self.call("session/new", {"cwd": home, "mcpServers": []}, self._created)

    def _loaded(self, result, error):
        self.loading = False
        if error:
            log("session/load failed:", error)
            try:
                os.unlink(SESSION_OF(self.agent))
            except FileNotFoundError:
                pass
            add_hist("note", "couldn't continue the last conversation; this is a new one")
            self.session = None
            self.primed = False
            emit(resumed=False)
            return self.call("session/new", {"cwd": os.path.expanduser("~"), "mcpServers": []}, self._created)
        self._ready()

    def _created(self, result, error):
        if error or not (result or {}).get("sessionId"):
            msg = (error or {}).get("message") or "no session"
            return fail(f"{nm()} couldn't start a conversation: {msg}")
        self.session = result["sessionId"]
        self.save()
        self._ready()

    def _ready(self):
        self.ready = True
        log(self.agent, "ready, session", self.session)
        self._next()

    # -- turns

    def ask(self, text):
        with lock:
            if self.proc.poll() is not None:
                self.restart(f"{nm()} had stopped")
            fresh = st["state"] in ("idle", "listening") and self.turns == 0
            self.turns += 1
            self.last_event = time.time()
            self.warned = False
            log("ask:", text)
            add_hist("you", text)
            self.queue.append(text)
            self.save()
            emit(live=True, state="thinking", heard=text, reply="" if fresh else st["reply"], tool=None, err=None,
                 turn=st["turn"] + (1 if fresh else 0), say=[] if fresh else st["say"],
                 steps=[] if fresh else st["steps"], since=round(time.time() * 1000) if fresh else st["since"])
            self._next()

    def _next(self):
        if not self.ready or self.in_flight or not self.queue:
            return
        text = self.queue.pop(0)
        if not self.primed:  # no system prompt over ACP: the first message says how to talk
            text = f"({PROMPT})\n\n{text}"
            self.primed = True
        self.buf = ""
        self.after_tool = False
        self.in_flight = self.call("session/prompt", {"sessionId": self.session, "prompt": [{"type": "text", "text": text}]},
                                   self._answered)

    def _answered(self, result, error):
        self.in_flight = None
        self.turns = max(0, self.turns - 1)
        self.result_at = time.time()
        stop = (result or {}).get("stopReason")
        log("result:", stop or "", "error " + json.dumps(error)[:200] if error else "ok")
        self.hand_over(final=True)
        add_hist("agent", st["reply"])
        self.save()
        emit(state="idle" if self.turns == 0 else "thinking", live=self.turns > 0, reply="" if self.turns == 0 else st["reply"])
        if error:
            self.say("Sorry, that didn't work.")
            fail(str(error.get("message") or error)[:200])
        elif stop not in ("cancelled",) and not st["say"]:
            fail(f"{nm()} answered with nothing to say")
        self._next()

    def interrupt(self):
        self.queue.clear()
        if self.session:
            self.notify("session/cancel", {"sessionId": self.session})

    def restart(self, why):
        self.restarts += 1
        log("restarting", self.agent + ":", why)
        self.stop_agent(retire=False)
        self.start(self.session)

    # -- what it sends

    def _event(self, ev):
        with lock:
            if "id" in ev and ("result" in ev or "error" in ev) and "method" not in ev:
                then = self.calls.pop(ev["id"], None)
                if then:
                    then(ev.get("result"), ev.get("error"))
                return
            method = ev.get("method")
            if method == "session/update":
                if self.loading:
                    return
                self.last_event = time.time()
                self.warned = False
                self.update((ev.get("params") or {}).get("update") or {})
            elif method == "session/request_permission" and "id" in ev:
                self.last_event = time.time()
                self.permission(ev["id"], ev.get("params") or {})
            elif "id" in ev and method:
                # fs/… or terminal/…, which we said we don't offer
                self.send({"jsonrpc": "2.0", "id": ev["id"], "error": {"code": -32601, "message": f"{method} isn't supported"}})
            # _x.ai/… and other notifications: nothing to show

    def update(self, u):
        kind = u.get("sessionUpdate")
        if kind == "agent_message_chunk":
            c = u.get("content") or {}
            if c.get("type") != "text":
                return
            d = c.get("text", "")
            if self.after_tool and st["reply"] and not st["reply"].endswith((" ", "\n")):
                d = " " + d.lstrip()
            self.after_tool = False
            self.buf += d
            self.hand_over()
            emit(state="speaking", reply=st["reply"] + d, tool=None)
        elif kind == "tool_call":
            self.hand_over(final=True)
            self.after_tool = True
            what = acp_step(u)
            for path in acp_paths(u):
                if is_image(path):
                    if st["reply"].strip():
                        add_hist("agent", st["reply"])
                        emit(reply="")
                    keep_image(path)
            if time.time() - self.last_said > PROGRESS_S:
                self.say(ACP_PROGRESS.get(u.get("kind"), "Still working."))
            emit(state="working", tool=u.get("kind") or "tool", steps=(st["steps"] + [what])[-4:])
        elif kind == "tool_call_update":
            for path in acp_paths(u):
                if is_image(path) and not any(h.get("from") == path for h in history[-1:]):
                    keep_image(path)

    # -- approvals

    def permission(self, rpc_id, params):
        call = params.get("toolCall") or {}
        rid = f"p{rpc_id}"
        payload = {"rpc": rpc_id, "options": params.get("options") or []}
        if st.get("approveAll"):
            log("allowed (allow all):", call.get("title"))
            return self.decide(rid, True, payload)
        key = acp_key(call)
        if covered(key, self.always):
            log("allowed (always):", key)
            return self.decide(rid, True, payload)
        what = acp_step(call)
        raw = call.get("rawInput") or {}
        detail = raw.get("command") or raw.get("path") or raw.get("file_path") or raw.get("url") or ""
        card = {"id": rid, "what": what, "says": spoken(what), "detail": str(detail)[:400], "tool": call.get("kind"), "key": key}
        self.pending[rid] = (payload, card)
        log("approval asked:", rid, json.dumps(call)[:200])
        if not st.get("approve"):
            self.show(card)

    def answer(self, rid, payload, allow, message, always):
        opts = payload.get("options") or []
        want = (["allow_always", "allow_once"] if always else ["allow_once", "allow_always"]) if allow else ["reject_once", "reject_always"]
        pick = next((o for k in want for o in opts if o.get("kind") == k), None)
        outcome = {"outcome": "selected", "optionId": pick["optionId"]} if pick else {"outcome": "cancelled"}
        self.send({"jsonrpc": "2.0", "id": payload["rpc"], "result": {"outcome": outcome}})
        if not allow and message and message.startswith("The user said no, and said:"):
            self.ask(message.split(":", 1)[1].strip())  # what to do instead: its next prompt


ACP_PROGRESS = {"read": "Reading.", "edit": "Editing.", "delete": "Deleting.", "move": "Moving files.",
                "search": "Searching.", "execute": "Running a command.", "fetch": "Reading a page.", "think": "Thinking."}


def acp_paths(u):
    raw = u.get("rawInput") or {}
    paths = [l.get("path") for l in u.get("locations") or [] if l.get("path")]
    return paths + [raw[k] for k in ("path", "file_path") if isinstance(raw.get(k), str)]


def acp_step(u):
    """A tool call as a few words, like step(): its title, which ACP agents write for people."""
    kind = u.get("kind") or ""
    title = (u.get("title") or "").strip()
    raw = u.get("rawInput") or {}
    if kind == "execute":
        return "running: " + (raw.get("description") or title or raw.get("command", ""))[:60]
    paths = acp_paths(u)
    if kind in ("read", "edit", "delete") and paths:
        return {"read": "reading", "edit": "editing", "delete": "deleting"}[kind] + " " + os.path.basename(paths[0])
    return (title or kind or "a tool")[:60]


def acp_key(u):
    raw = u.get("rawInput") or {}
    if u.get("kind") == "execute" and raw.get("command"):
        return "Bash:" + " ".join(sorted(programs(raw["command"])))
    return "acp:" + (u.get("kind") or u.get("title") or "")


def make_brain(agent, resume):
    st["agent"] = agent
    st["agentName"] = {"claude": "Claude", "grok": "Grok"}.get(agent, agent)
    return (AcpBrain if agent == "grok" else Brain)(resume)


def session_to_resume(agent):
    last = read_json(SESSION_OF(agent), {})
    if last.get("id") and time.time() - last.get("at", 0) < RESUME_H * 3600:
        return last
    return None


def switch(agent):
    """Another agent: the current one stops, its transcript is put aside, the other's comes back with its session."""
    global brain
    with lock:
        if agent == st["agent"] or agent not in st["agents"]:
            return
        log("switching to", agent)
        brain.deny_all()
        brain.stop_agent()
        write_json(HIST_OF(st["agent"]), history)
        history.clear()
        last = session_to_resume(agent)
        if last:
            history.extend(read_json(HIST_OF(agent), []))
        write_json(HIST, history)
        write_json(SETTINGS, {**read_json(SETTINGS, {}), "agent": agent})
        brain = make_brain(agent, last and last["id"])
        emit(state="idle", heard="", reply="", err=None, say=[], steps=[], approve=None, live=False,
             resumed=bool(last), turn=st["turn"] + 1)


def requests():
    if not os.path.exists(FIFO):
        os.mkfifo(FIFO)
    f = os.fdopen(os.open(FIFO, os.O_RDWR), "r")  # RDWR: never sees EOF between writers
    for line in f:
        line = line.rstrip("\n")
        log("request:", line[:200])
        try:
            if line == "stop":
                with lock:
                    brain.deny_all()
                    if brain.turns:
                        brain.interrupt()
                        brain.turns = 0
                    emit(state="idle", err=None, approve=None)
            elif line in ("approve all", "approve ask"):
                every = line == "approve all"
                write_json(SETTINGS, {**read_json(SETTINGS, {}), "approve": "all" if every else "ask"})
                with lock:
                    emit(approveAll=every)
                    add_hist("note", f"allowing everything {nm()} asks for" if every else "asking before each action again")
                    if every:  # what's waiting now goes ahead too
                        for rid in list(brain.pending):
                            brain.decide(rid, True)
            elif line.startswith("agent "):
                switch(line[6:].strip())
            elif line == "new":
                brain.new()
            elif line == "listen":
                emit(state="listening", err=None)
            elif line == "back":
                with lock:
                    if st["state"] == "listening":
                        emit(state="approve" if st.get("approve") else "working" if brain.turns else "idle")
            elif line.split(" ")[0] in ("allow", "always", "deny") and len(line.split(" ")) == 2:
                word, rid = line.split(" ")
                brain.decide(rid, word != "deny", always=word == "always")
            elif line.startswith("say "):
                text = line[4:].strip()
                waiting = st.get("approve")
                if text and waiting:
                    # an answer to the question on screen: yes, no, or what to do instead
                    if YES.search(text):
                        brain.decide(waiting["id"], True)
                    elif NO.search(text):
                        brain.decide(waiting["id"], False)
                    else:
                        brain.decide(waiting["id"], False, message=f"The user said no, and said: {text}")
                    emit(heard=text)
                elif text:
                    brain.ask(text)
        except Exception:
            log(traceback.format_exc())
            fail("the voice brain hit an error; see /tmp/voice.log")


def main():
    global brain
    log("---- brain starting, pid", os.getpid())
    kill_leftovers()
    try:
        os.unlink(FIFO)
    except FileNotFoundError:
        pass
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGHUP, shutdown)
    if os.path.exists(f"{HOME}/session.json"):  # from before there was a choice of agents
        os.replace(f"{HOME}/session.json", SESSION_OF("claude"))
    st["agents"] = [a for a in AGENTS if installed(a)]
    agent = read_json(SETTINGS, {}).get("agent") or "claude"
    if agent not in st["agents"] and st["agents"]:
        agent = st["agents"][0]
    history.extend(read_json(HIST, []))
    last = session_to_resume(agent)
    resume = last and last["id"]
    if not last and read_json(SESSION_OF(agent), {}).get("at") and history:
        history.clear()  # a conversation too old to continue: start the transcript over too
    st["agentName"] = {"claude": "Claude", "grok": "Grok"}.get(agent, agent)
    if last and last.get("busy"):
        add_hist("note", f"you left while {nm()} was working, which stopped it; say continue to pick it up")
    write_json(HIST, history)
    st["approveAll"] = read_json(SETTINGS, {}).get("approve") == "all"
    brain = make_brain(agent, resume)
    threading.Thread(target=requests, daemon=True).start()
    emit(state="idle", resumed=bool(resume))
    while True:
        time.sleep(0.2)
        with lock:
            if brain.turns and not brain.warned and time.time() - brain.last_event > QUIET_S:
                brain.warned = True
                fail(f"{nm()} has been quiet for two minutes; stop it if it's stuck")
            # A message sent mid-task that Claude folded into that task gets no result of its own.
            if brain.folds and brain.turns and brain.result_at and brain.last_event <= brain.result_at and time.time() - brain.result_at > 4:
                log("no further turn after the result; taking the queued message as answered")
                brain.turns = 0
                brain.result_at = 0
                if st["state"] != "listening":
                    emit(state="idle")
            if st["state"] != "idle":
                emit()  # tick: the orb's pulse


if __name__ == "__main__":
    try:
        main()
    except Exception:
        log(traceback.format_exc())
        raise
