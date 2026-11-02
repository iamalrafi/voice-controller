package com.example.voicecontrol

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The heart of the app.
 *  - It is an Android "accessibility service": that is what allows tapping, typing and scrolling in other apps.
 *  - It shows a small floating mic button. Tap it, speak, and it runs what you said.
 */
class VoiceControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: VoiceControlService? = null

        @Volatile
        var logListener: (() -> Unit)? = null

        private val logLines = ArrayList<String>()
        private const val SILENCE_MS = 1800L        // how long a pause means "I'm done talking"
        private const val NOTHING_SAID_MS = 7000L   // give up if you say nothing at all

        fun log(msg: String) {
            synchronized(logLines) {
                logLines.add(msg)
                while (logLines.size > 60) logLines.removeAt(0)
            }
            Handler(Looper.getMainLooper()).post { logListener?.invoke() }
        }

        fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }
    }

    private enum class State { IDLE, LISTENING, BUSY }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var executor: Executor? = null

    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null

    private var engine: SpeechEngine? = null
    private var vosk: VoskEngine? = null
    private var listening = false
    private var busy = false
    private val buffer = ArrayList<String>()
    private var pending = ""

    private val finishRunnable = Runnable { finishListening() }

    private val speechListener = object : SpeechListener {
        override fun onPartial(text: String) {
            if (!listening) return
            pending = text
            armTimer(SILENCE_MS)
        }

        override fun onSegment(text: String) {
            if (!listening) return
            buffer.add(text)
            pending = ""
            armTimer(SILENCE_MS)
        }

        override fun onEnded() {
            if (listening) finishListening()
        }

        override fun onError(message: String) {
            if (listening) fail(message)
        }
    }

    // ------------------------------------------------------------------ service life cycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        executor = Executor(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ensureBubble()
        log("Voice Control is ON. Tap the green mic button and speak.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not need to watch the screen all the time - we only look when you give a command.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        try {
            bubble?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            // already gone
        }
        bubble = null
        engine?.stop()
        engine = null
        vosk?.release()
        vosk = null
        listening = false
        busy = false
        if (instance === this) instance = null
    }

    // ------------------------------------------------------------------ the floating mic button

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    /** Shows the mic button (needs the "Display over other apps" permission). Safe to call many times. */
    fun ensureBubble() {
        if (bubble != null) return
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow \"Display over other apps\" in the Voice Control app to see the mic button")
            return
        }
        val wm = windowManager ?: return

        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        }

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val size = dp(60)
        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(12)
        lp.y = dp(220)

        // Drag to move, tap to talk
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        if (moved) {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            try {
                                windowManager?.updateViewLayout(v, lp)
                            } catch (ex: Exception) {
                                // ignore
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) onBubbleTapped()
                    }
                }
                return true
            }
        })

        try {
            wm.addView(view, lp)
            bubble = view
            setState(State.IDLE)
        } catch (e: Exception) {
            log("Could not show the mic button: ${e.message}")
        }
    }

    private fun setState(s: State) {
        val color = when (s) {
            State.IDLE -> 0xFF2E7D32.toInt()        // green
            State.LISTENING -> 0xFFD32F2F.toInt()   // red
            State.BUSY -> 0xFFF9A825.toInt()        // amber
        }
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(color)
        bubble?.background = bg
        bubble?.text = when (s) {
            State.IDLE -> "🎤"
            State.LISTENING -> "👂"
            State.BUSY -> "⏳"
        }
    }

    private fun onBubbleTapped() {
        when {
            busy -> toast("Still working on the last command...")
            listening -> finishListening()
            else -> startListening()
        }
    }

    // ------------------------------------------------------------------ listening

    private fun startListening() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Open the Voice Control app and allow the microphone first")
            return
        }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val choice = prefs.getString("engine", "auto") ?: "auto"
        val bundled = VoskEngine.isModelBundled(this)
        val useVosk = choice == "vosk" || (choice == "auto" && bundled)

        buffer.clear()
        pending = ""
        listening = true
        setState(State.LISTENING)

        if (useVosk) {
            if (!bundled) {
                fail("The Vosk voice model is not inside the app yet. See the guide, or choose the phone's recognizer.")
                return
            }
            val v = vosk ?: VoskEngine(this).also { vosk = it }
            if (v.isReady) {
                beginEngine(v)
            } else {
                toast("Loading the voice model (first time only, a few seconds)...")
                v.prepare(
                    onReady = { if (listening) beginEngine(v) },
                    onError = { fail(it) }
                )
            }
        } else {
            beginEngine(AndroidSpeechEngine(this, prefs.getString("lang", "en-US") ?: "en-US"))
        }
    }

    private fun beginEngine(e: SpeechEngine) {
        engine = e
        armTimer(NOTHING_SAID_MS)
        e.start(speechListener)
    }

    private fun armTimer(ms: Long) {
        handler.removeCallbacks(finishRunnable)
        handler.postDelayed(finishRunnable, ms)
    }

    private fun fail(msg: String) {
        handler.removeCallbacks(finishRunnable)
        listening = false
        val e = engine
        engine = null
        handler.post { e?.stop() }
        setState(State.IDLE)
        log(msg)
        toast(msg)
    }

    private fun finishListening() {
        if (!listening) return
        listening = false
        handler.removeCallbacks(finishRunnable)
        val e = engine
        engine = null

        val parts = ArrayList(buffer)
        if (pending.isNotBlank()) parts.add(pending)
        buffer.clear()
        pending = ""

        setState(State.IDLE)
        handler.post { e?.stop() }

        val text = parts.joinToString(" ").trim()
        if (text.isEmpty()) {
            toast("I didn't hear anything")
            return
        }
        runTextCommand(text)
    }

    // ------------------------------------------------------------------ running a command

    /** Understands the sentence and does it. Also used by the "Run it" button in the app. */
    fun runTextCommand(text: String) {
        handler.post {
            log("Heard: \"$text\"")
            val steps = CommandParser.parse(text)
            if (steps.isEmpty()) {
                log("I couldn't find a command in that.")
                toast("I didn't understand that")
                return@post
            }
            val ex = executor ?: return@post
            if (busy) {
                toast("Still working on the last command...")
                return@post
            }
            busy = true
            setState(State.BUSY)
            worker.execute {
                val ok = try {
                    ex.runAll(steps) { line -> log(line) }
                } catch (e: Exception) {
                    log("Error: ${e.message}")
                    false
                }
                handler.post {
                    busy = false
                    setState(State.IDLE)
                    log(if (ok) "Done." else "Stopped.")
                    toast(if (ok) "Done ✓" else "Stopped - see the log in the app")
                }
            }
        }
    }
}
