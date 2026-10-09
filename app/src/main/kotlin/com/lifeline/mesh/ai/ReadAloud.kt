package com.lifeline.mesh.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Read-aloud (DESIGN.md 10): gate questions, replies and situation reports through Android TextToSpeech, in the app
 * language when the phone has that voice, otherwise its default voice. Callers check the Settings toggle.
 */
class ReadAloud(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayList<Pair<String, String>>()

    @Synchronized
    fun speak(text: String, languageTag: String) {
        if (text.isBlank()) return
        if (tts == null) tts = TextToSpeech(context.applicationContext, this)
        if (!ready) { pending += text to languageTag; return }
        say(text, languageTag)
    }

    fun stop() { tts?.stop() }

    @Synchronized
    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            // No TTS engine (or it failed to start): drop the queue and try again on the next speak().
            Log.w(TAG, "TextToSpeech not available ($status)")
            tts?.shutdown(); tts = null
        } else pending.forEach { (t, l) -> say(t, l) }
        pending.clear()
    }

    private fun say(text: String, languageTag: String) {
        val t = tts ?: return
        val locale = Locale.forLanguageTag(when (languageTag) { "hi" -> "hi-IN"; "kn" -> "kn-IN"; else -> "en-IN" })
        val r = t.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "no $locale voice; using the default voice")
            t.setLanguage(Locale.getDefault())
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lifeline-${System.nanoTime()}")
    }

    companion object { private const val TAG = "ReadAloud" }
}
