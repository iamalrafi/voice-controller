package com.example.voicecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Does the actual work on the phone: opens apps, finds things on the screen, taps, types, scrolls.
 * Everything here runs on a background thread (never on the main thread).
 */
class Executor(private val svc: AccessibilityService) {

    class Result(val ok: Boolean, val message: String)

    private class AppInfo(val label: String, val pkg: String)

    /** Runs the steps one after another. Stops at the first step that fails. */
    fun runAll(steps: List<Step>, log: (String) -> Unit): Boolean {
        for ((index, step) in steps.withIndex()) {
            log("${index + 1}/${steps.size}  ${step.describe()}")
            val r = try {
                execute(step)
            } catch (e: Exception) {
                Result(false, "Error: ${e.message}")
            }
            if (!r.ok) {
                log("   ✗ ${r.message}")
                return false
            }
            if (r.message.isNotEmpty()) log("   ✓ ${r.message}")
            sleep(450)
        }
        return true
    }

    private fun execute(step: Step): Result = when (step.type) {
        ActionType.OPEN -> openApp(step.arg)
        ActionType.GUESS -> guess(step.arg)
        ActionType.TAP -> tapText(step.arg)
        ActionType.TAP_FIELD -> tapField()
        ActionType.TYPE -> typeText(step.arg)
        ActionType.SEND -> send()
        ActionType.SCROLL -> scroll(step.arg)
        ActionType.SWIPE -> swipeFinger(step.arg)
        ActionType.BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Went back")
        ActionType.HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME, "Went home")
        ActionType.RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Opened recent apps")
        ActionType.NOTIFICATIONS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opened notifications")
        ActionType.WAIT -> {
            sleep((step.arg.toLongOrNull() ?: 1L) * 1000L)
            Result(true, "")
        }
    }

    // ------------------------------------------------------------------ opening apps

    private fun installedApps(): List<AppInfo> {
        val pm = svc.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(intent, 0)
        return list.map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
    }

    /** Best installed app for what you said. Also tries the first word(s) only: "youtube please play" -> youtube */
    private fun bestApp(target: String): Pair<AppInfo, Double>? {
        val words = Fuzzy.normalize(target).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val apps = installedApps()
        var best: AppInfo? = null
        var bestScore = 0.0
        for (n in words.size downTo 1) {
            val span = words.take(n).joinToString(" ")
            val penalty = if (n == words.size) 1.0 else 0.97
            for (a in apps) {
                val s = Fuzzy.similarity(a.label, span) * penalty
                if (s > bestScore) {
                    bestScore = s
                    best = a
                }
            }
        }
        return best?.let { it to bestScore }
    }

    /** "browser", "camera", "settings" ... work even if the app has a different name on your phone. */
    private fun aliasIntent(key: String): Intent? {
        val category = when (key) {
            "browser", "web browser", "internet" -> Intent.CATEGORY_APP_BROWSER
            "messages", "message", "sms", "messaging" -> Intent.CATEGORY_APP_MESSAGING
            "gallery", "photos" -> Intent.CATEGORY_APP_GALLERY
            "contacts" -> Intent.CATEGORY_APP_CONTACTS
            "calculator" -> Intent.CATEGORY_APP_CALCULATOR
            "calendar" -> Intent.CATEGORY_APP_CALENDAR
            "maps", "map" -> Intent.CATEGORY_APP_MAPS
            "email", "mail" -> Intent.CATEGORY_APP_EMAIL
            "music" -> Intent.CATEGORY_APP_MUSIC
            else -> null
        }
        if (category != null) {
            return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        }
        return when (key) {
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "settings", "setting" -> Intent(Settings.ACTION_SETTINGS)
            "phone", "dialer", "call" -> Intent(Intent.ACTION_DIAL)
            else -> null
        }
    }

    private fun tryStart(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        svc.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun waitForPackage(pkg: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (svc.rootInActiveWindow?.packageName?.toString() == pkg) return
            sleep(250)
        }
    }

    private fun launchPackage(app: AppInfo): Result {
        val intent = svc.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return Result(false, "Can't launch ${app.label}")
        return if (tryStart(intent)) {
            waitForPackage(app.pkg, 5000)
            sleep(800)
            Result(true, "Opened ${app.label}")
        } else {
            Result(false, "Android would not let me open ${app.label}")
        }
    }

    private fun openApp(target: String): Result {
        val key = Fuzzy.normalize(target)
        val best = bestApp(target)
        if (best != null && best.second >= 0.95) return launchPackage(best.first)

        val alias = aliasIntent(key) ?: aliasIntent(key.substringAfterLast(' '))
        if (alias != null && tryStart(alias)) {
            sleep(1800)
            return Result(true, "Opened $key")
        }
        if (best != null && best.second >= 0.72) return launchPackage(best.first)
        return Result(false, "I couldn't find an app called \"$target\"")
    }

    /** A bare word like "youtube": open the app if there is one, otherwise tap that text. */
    private fun guess(target: String): Result {
        val best = bestApp(target)
        if (best != null && best.second >= 0.9) return launchPackage(best.first)
        val node = findNode(0.75, 2500) { textScore(it, target) }
        if (node != null && clickNode(node)) return Result(true, "Tapped \"$target\"")
        if (best != null && best.second >= 0.72) return launchPackage(best.first)
        return Result(false, "I didn't understand \"$target\"")
    }

    // ------------------------------------------------------------------ finding things on screen

    /** Looks through everything visible on screen and returns the best match. Keeps retrying until timeout. */
    private fun findNode(minScore: Double, timeoutMs: Long, scorer: (AccessibilityNodeInfo) -> Double): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            val root = svc.rootInActiveWindow
            if (root != null) {
                var best: AccessibilityNodeInfo? = null
                var bestScore = 0.0
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    val n = queue.removeFirst()
                    if (n.isVisibleToUser) {
                        val s = scorer(n)
                        if (s > bestScore) {
                            bestScore = s
                            best = n
                        }
                    }
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { queue.add(it) }
                    }
                }
                if (best != null && bestScore >= minScore) return best
            }
            sleep(400)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    private fun textScore(n: AccessibilityNodeInfo, target: String): Double {
        var best = 0.0
        n.text?.let { best = maxOf(best, Fuzzy.similarity(it.toString(), target)) }
        n.contentDescription?.let { best = maxOf(best, Fuzzy.similarity(it.toString(), target)) }
        n.viewIdResourceName?.let { id ->
            best = maxOf(best, 0.9 * Fuzzy.similarity(id.substringAfterLast('/').replace('_', ' '), target))
        }
        return best
    }

    private fun findEditable(timeoutMs: Long): AccessibilityNodeInfo? =
        findNode(0.5, timeoutMs) { n -> if (n.isEditable) (if (n.isFocused) 1.0 else 0.9) else 0.0 }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        // Not clickable the normal way: tap the middle of it like a finger would
        val r = Rect()
        n.getBoundsInScreen(r)
        return tapAt(r.centerX().toFloat(), r.centerY().toFloat())
    }

    // ------------------------------------------------------------------ steps

    private fun tapText(target: String): Result {
        val node = findNode(0.75, 6000) { textScore(it, target) }
            ?: return Result(false, "I couldn't see \"$target\" on the screen")
        return if (clickNode(node)) Result(true, "Tapped \"$target\"") else Result(false, "I couldn't tap \"$target\"")
    }

    private fun tapField(): Result {
        val node = findEditable(5000) ?: return Result(false, "I couldn't find a text box on the screen")
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickNode(node)
        return if (focused || clicked) Result(true, "Tapped the text box") else Result(false, "I couldn't tap the text box")
    }

    private fun typeText(text: String): Result {
        val node = findEditable(5000) ?: return Result(false, "I couldn't find a text box to type in")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val current = if (showingHint) "" else (node.text?.toString() ?: "")
        val newText = when {
            current.isEmpty() -> text
            current.endsWith(" ") -> current + text
            else -> "$current $text"
        }
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return Result(false, "This text box would not accept typing")

        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return Result(true, "Typed \"$text\"")
    }

    private fun send(): Result {
        val button = findNode(0.9, 3000) { n -> if (n.isEditable) 0.0 else textScore(n, "send") }
        if (button != null && clickNode(button)) return Result(true, "Pressed Send")

        if (Build.VERSION.SDK_INT >= 30) {
            val field = findEditable(800)
            if (field != null && field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return Result(true, "Pressed Enter on the keyboard")
            }
        }
        return Result(false, "I couldn't find a Send button")
    }

    // ------------------------------------------------------------------ gestures and system buttons

    private fun global(action: Int, doneMessage: String): Result =
        if (svc.performGlobalAction(action)) Result(true, doneMessage) else Result(false, "Android refused that button")

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val started = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        if (!started) return false
        latch.await(3, TimeUnit.SECONDS)
        return done.get()
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        return gesture(p, 60)
    }

    /** direction = which way the FINGER moves */
    private fun swipeFinger(direction: String): Result {
        val w = svc.resources.displayMetrics.widthPixels
        val h = svc.resources.displayMetrics.heightPixels
        val p = Path()
        when (direction) {
            "up" -> { p.moveTo(w * 0.5f, h * 0.72f); p.lineTo(w * 0.5f, h * 0.28f) }
            "down" -> { p.moveTo(w * 0.5f, h * 0.28f); p.lineTo(w * 0.5f, h * 0.72f) }
            "left" -> { p.moveTo(w * 0.8f, h * 0.5f); p.lineTo(w * 0.2f, h * 0.5f) }
            else -> { p.moveTo(w * 0.2f, h * 0.5f); p.lineTo(w * 0.8f, h * 0.5f) }
        }
        return if (gesture(p, 350)) Result(true, "Swiped $direction") else Result(false, "Android refused the swipe")
    }

    /** "scroll down" = show what is further down = finger moves UP */
    private fun scroll(direction: String): Result {
        val finger = when (direction) {
            "down" -> "up"
            "up" -> "down"
            "left" -> "right"
            else -> "left"
        }
        val r = swipeFinger(finger)
        return if (r.ok) Result(true, "Scrolled $direction") else r
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
