package dev.omalauncher.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Android's own text-to-speech, in the phone's voice and language: the voice screen's replies.
 * Sentences queue up and play one after another; [stop] stops what's playing and drops the rest. Started
 * on first use. Main thread.
 */
class Speaker(
    private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var ready = false

    /** Whether it's saying something, or has something queued. */
    var speaking by mutableStateOf(false)
        private set

    /**
     * Why replies aren't heard, for the screen to say: no text-to-speech engine on the phone, or the engine
     * failing to read (often no voice installed for the language). Null once it reads again.
     */
    var problem by mutableStateOf<String?>(null)
        private set

    /** Starts the engine ahead of the first reply: sooner speech, and [problem] known before anything's said. */
    fun warmUp() = start()

    /** What was asked before the engine was ready, said once it is. */
    private val waiting = ArrayDeque<String>()
    private var next = 0

    /** Utterances handed to the engine and not finished yet. */
    private var queued = 0
    private val main = Handler(Looper.getMainLooper())

    fun speak(text: String) {
        if (text.isBlank()) return
        speaking = true
        if (!ready) {
            waiting += text
            start()
            return
        }
        say(text)
    }

    fun stop() {
        waiting.clear()
        queued = 0
        speaking = false
        tts?.stop()
    }

    private fun start() {
        if (tts != null) return
        // With no engine at all, Android reports the failure from inside the constructor, before it returns.
        var failed = false
        val engine =
            TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "text-to-speech failed to start: $status")
                    failed = true
                    tts = null
                    problem = NO_ENGINE
                    waiting.clear()
                    speaking = false
                    return@TextToSpeech
                }
                tts?.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}

                        override fun onDone(id: String?) =
                            main.post {
                                problem = null
                                finished()
                            }.let {}

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) =
                            main.post {
                                problem = CANT_READ
                                finished()
                            }.let {}

                        override fun onStop(
                            id: String?,
                            interrupted: Boolean,
                        ) = main.post(::finished).let {}
                    },
                )
                ready = true
                problem = null
                while (waiting.isNotEmpty()) say(waiting.removeFirst())
            }
        if (failed) engine.shutdown() else tts = engine
    }

    private fun finished() {
        queued = (queued - 1).coerceAtLeast(0)
        if (queued == 0 && waiting.isEmpty()) speaking = false
    }

    private fun say(text: String) {
        queued++
        // Refused (the engine went away): no progress callback comes for it, so it's done now.
        if (tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "s${next++}") != TextToSpeech.SUCCESS) {
            problem = CANT_READ
            finished()
        }
    }

    private companion object {
        const val TAG = "speaker"
        const val NO_ENGINE = "no text-to-speech on this phone: replies aren't read aloud"
        const val CANT_READ = "text-to-speech couldn't read the reply; is a voice for its language installed?"
    }
}
