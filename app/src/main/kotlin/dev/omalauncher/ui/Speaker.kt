package dev.omalauncher.ui

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * speak(text) from a widget: Android's own text-to-speech, in the phone's voice and language. Sentences queue up
 * and play one after another; speak() stops what's playing and drops the rest. Started on first use.
 */
class Speaker(
    private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var ready = false

    /** What was asked before the engine was ready, said once it is. */
    private val waiting = ArrayDeque<String>()
    private var next = 0

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            waiting += text
            start()
            return
        }
        say(text)
    }

    fun stop() {
        waiting.clear()
        tts?.stop()
    }

    private fun start() {
        if (tts != null) return
        tts =
            TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "text-to-speech failed to start: $status")
                    tts = null
                    waiting.clear()
                    return@TextToSpeech
                }
                tts?.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                ready = true
                while (waiting.isNotEmpty()) say(waiting.removeFirst())
            }
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "s${next++}")
    }

    private companion object {
        const val TAG = "speaker"
    }
}
