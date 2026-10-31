package dev.omalauncher

import android.os.Bundle
import dev.omalauncher.ui.VoiceScreen

/** The voice screen, a spoken conversation with a coding agent: its own task and Recents card, opened from the AI bar's mic. */
class VoiceActivity : InstantActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The agents run in Linux; without it there's no one to talk to.
        if (!runtime.bootstrap.wanted) {
            finishAndRemoveTask()
            return
        }
        setInstantContent {
            VoiceScreen(runtime, onExit = ::backInstantly)
        }
    }
}
