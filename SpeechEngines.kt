package com.example.voicecontrol

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileNotFoundException

/** What the service wants to hear back from a speech engine. All calls arrive on the main thread. */
interface SpeechListener {
    fun onPartial(text: String)      // words so far, while you are still talking
    fun onSegment(text: String)      // a finished chunk of speech
    fun onEnded()                    // the engine stopped by itself
    fun onError(message: String)
}

interface SpeechEngine {
    fun start(listener: SpeechListener)
    fun stop()
    fun release()
}

/**
 * Engine 1: Vosk. Fully offline, model lives inside the app (about 40 MB).
 * Needs the model files in app/src/main/assets/model-en-us (see PUT_VOSK_MODEL_HERE.txt).
 */
class VoskEngine(private val ctx: Context) : SpeechEngine {

    companion object {
        const val ASSET_DIR = "model-en-us"

        fun isModelBundled(ctx: Context): Boolean = try {
            ctx.assets.list(ASSET_DIR)?.contains("am") == true
        } catch (e: Exception) {
            false
        }
    }

    private var model: Model? = null
    private var service: SpeechService? = null

    val isReady: Boolean get() = model != null

    /** Copies the model out of the APK the first time, then loads it. Runs in the background. */
    fun prepare(onReady: () -> Unit, onError: (String) -> Unit) {
        if (model != null) {
            onReady()
            return
        }
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val dir = File(ctx.filesDir, "vosk-model")
                val marker = File(dir, ".ready")
                if (!marker.exists()) {
                    dir.deleteRecursively()
                    copyAssets(ASSET_DIR, dir)
                    marker.writeText("ok")
                }
                model = Model(dir.absolutePath)
                main.post { onReady() }
            } catch (e: Throwable) {
                main.post { onError("Could not load the voice model: ${e.message}") }
            }
        }.start()
    }

    private fun copyAssets(path: String, dest: File) {
        val children = ctx.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            try {
                dest.parentFile?.mkdirs()
                ctx.assets.open(path).use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
            } catch (e: FileNotFoundException) {
                dest.mkdirs()   // it was an empty folder
            }
        } else {
            dest.mkdirs()
            for (name in children) copyAssets("$path/$name", File(dest, name))
        }
    }

    private fun field(json: String?, key: String): String {
        if (json == null) return ""
        return try {
            JSONObject(json).optString(key, "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    override fun start(listener: SpeechListener) {
        val m = model
        if (m == null) {
            listener.onError("Voice model is not loaded yet")
            return
        }
        try {
            val recognizer = Recognizer(m, 16000.0f)
            val ss = SpeechService(recognizer, 16000.0f)
            ss.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val t = field(hypothesis, "partial")
                    if (t.isNotEmpty()) listener.onPartial(t)
                }

                override fun onResult(hypothesis: String?) {
                    val t = field(hypothesis, "text")
                    if (t.isNotEmpty()) listener.onSegment(t)
                }

                override fun onFinalResult(hypothesis: String?) {
                    // ignored: we already collected partial + segment text
                }

                override fun onError(exception: Exception?) {
                    listener.onError(exception?.message ?: "Microphone error")
                }

                override fun onTimeout() {
                    listener.onEnded()
                }
            })
            service = ss
        } catch (e: Throwable) {
            listener.onError(e.message ?: "Could not start listening")
        }
    }

    override fun stop() {
        try {
            service?.stop()
            service?.shutdown()
        } catch (e: Throwable) {
            // nothing to do
        }
        service = null
    }

    override fun release() {
        stop()
        try {
            model?.close()
        } catch (e: Throwable) {
            // nothing to do
        }
        model = null
    }
}

/**
 * Engine 2: the phone's own speech recogniser (Google). Works with no extra setup.
 * It is offline only if the offline language pack is installed on the phone
 * (Settings > Google > Voice > Offline speech recognition). Supports Bangla ("bn-BD") too.
 */
class AndroidSpeechEngine(private val ctx: Context, private val lang: String) : SpeechEngine {

    private var recognizer: SpeechRecognizer? = null

    override fun start(listener: SpeechListener) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            listener.onError("This phone has no speech recogniser. Use the Vosk model instead.")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) listener.onPartial(t)
            }

            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) listener.onSegment(t)
                listener.onEnded()
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "The phone wants internet for this language. Install its offline pack or use Vosk."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recogniser is busy, try again"
                    else -> "Speech recogniser error ($error)"
                }
                listener.onError(msg)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        }
        r.startListening(intent)
    }

    override fun stop() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (e: Throwable) {
            // nothing to do
        }
        recognizer = null
    }

    override fun release() = stop()
}
