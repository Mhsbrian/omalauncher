# Voice talk prototype

A spoken conversation with an agent CLI in the guest, tested on the Pixel 9a on 2026-10-31. Being replaced by a
native launcher screen; kept for reference.

- `voice.owl`: the app (owl 18). Opens listening, a tap on the orb talks, the reply is said with `speak()`,
  the transcript scrolls under the orb, steps and approval cards while the agent works, an agent menu,
  ask / allow all.
- `brain.py`: run by the app as `stream("python3 ~/voice/brain.py")`. Keeps one agent running and prints one
  JSON line per change. Claude Code over stream-json (`--input-format/--output-format stream-json`,
  approvals through `--permission-prompt-tool stdio`, `--resume`); Grok over ACP (`grok agent stdio`:
  initialize, session/new|load, session/prompt, session/update, session/request_permission, session/cancel).
  Keeps the transcript, sessions and settings in `~/voice/`, the pictures the agent reads in `~/voice/images/`,
  logs to `/tmp/voice.log`.
- `send`: the app's requests to the brain over the FIFO `/tmp/voice.in` (`listen`, `say <text>`, `stop`,
  `allow|always|deny <id>`, `approve all|ask`, `agent <name>`, `new`, `back`).
- `up`: what put it on the phone: served from the Mac (`adb reverse tcp:8765 tcp:8765`), run as
  `curl -so /tmp/u localhost:8765/up; bash /tmp/u` in a guest terminal.

Needs python in the guest. Measured on the 9a: a fresh `claude -p` takes 3.2-4 s to start before its request,
one kept running answers in about 1.1 s; `grok agent stdio` is ready in about 3 s and answered in 3.
