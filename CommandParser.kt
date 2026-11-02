package com.example.voicecontrol

enum class ActionType {
    OPEN, TAP, TAP_FIELD, TYPE, SEND, SCROLL, SWIPE, BACK, HOME, RECENTS, NOTIFICATIONS, WAIT, GUESS
}

data class Step(val type: ActionType, val arg: String = "") {
    fun describe(): String = when (type) {
        ActionType.OPEN -> "Open app: $arg"
        ActionType.TAP -> "Tap on: $arg"
        ActionType.TAP_FIELD -> "Tap the text box"
        ActionType.TYPE -> "Type: $arg"
        ActionType.SEND -> "Press Send"
        ActionType.SCROLL -> "Scroll $arg"
        ActionType.SWIPE -> "Swipe $arg"
        ActionType.BACK -> "Go back"
        ActionType.HOME -> "Go to the home screen"
        ActionType.RECENTS -> "Show recent apps"
        ActionType.NOTIFICATIONS -> "Open notifications"
        ActionType.WAIT -> "Wait $arg second(s)"
        ActionType.GUESS -> "Open or tap: $arg"
    }
}

/**
 * Turns a sentence like
 *   "open whatsapp and select tanvir and tap type box then type hello and send"
 * into a list of steps. There is NO fixed phrase list: it looks for action words
 * (open / tap / type / send ...), throws away filler words (the, please, app ...) and keeps the rest
 * as the target. So "open youtube", "open the youtube app" and "please open youtube" all give the same result.
 */
object CommandParser {

    private fun v(type: ActionType, vararg w: String): List<Pair<String, ActionType>> = w.map { it to type }

    // Action words. English first, then Banglish and Bangla script.
    private val verbs: Map<String, ActionType> = (
        v(ActionType.OPEN, "open", "launch", "start", "run", "kholo", "khulo", "khol", "খোলো", "খুলো", "খোল", "ওপেন", "চালু") +
        v(ActionType.TAP, "tap", "click", "press", "select", "choose", "touch", "hit", "chapo", "চাপো", "চাপ", "ক্লিক", "সিলেক্ট") +
        v(ActionType.TYPE, "type", "write", "input", "dictate", "likho", "লিখো", "লেখো", "টাইপ") +
        v(ActionType.SEND, "send", "submit", "pathao", "পাঠাও", "সেন্ড") +
        v(ActionType.SCROLL, "scroll") +
        v(ActionType.SWIPE, "swipe", "slide") +
        v(ActionType.BACK, "back", "return", "ফিরে", "পিছনে") +
        v(ActionType.HOME, "home", "হোম") +
        v(ActionType.RECENTS, "recents", "recent", "overview") +
        v(ActionType.NOTIFICATIONS, "notifications", "notification") +
        v(ActionType.WAIT, "wait", "pause", "opekkha", "অপেক্ষা")
        ).toMap()

    // These are only treated as a new command when you clearly separate them ("and", "then")
    private val weak = setOf(ActionType.BACK, ActionType.HOME, ActionType.RECENTS, ActionType.NOTIFICATIONS)

    // Bangla grammar puts the verb last ("youtube kholo"). These verbs end a command.
    private val verbFinal = setOf("kholo", "khulo", "khol", "খোলো", "খুলো", "খোল", "ওপেন", "ক্লিক", "চাপো", "চাপ", "সিলেক্ট")

    private val separators = setOf("and", "then", "next", "tarpor", "ebong", "ar", "aar", "এবং", "তারপর", "এরপর", "আর")

    // Words that carry no meaning for the target (ignored, except inside text you ask to type)
    private val fillers = setOf(
        "the", "a", "an", "to", "on", "in", "at", "of", "my", "please", "now", "just", "kindly", "it", "me",
        "can", "could", "would", "will", "hey", "ok", "okay",
        "ta", "ti", "ke", "টা", "টি", "কে", "একটা", "koro", "korun", "koren", "করো", "করুন", "dao", "den", "দাও", "দেন"
    )

    // Ignored only at the END of a target: "youtube app", "send button"
    private val suffixFillers = setOf("app", "apps", "application", "button", "icon", "option", "tab", "key")

    // After "type hello and send": these words after "send" still mean the Send button, not more text
    private val sendTail = setOf("it", "now", "please", "message", "button", "that", "this", "the", "ok", "okay", "then", "too", "kore", "koro", "dao", "করো", "দাও")

    private val sendWords = setOf("send", "enter", "submit")

    private val fieldQualifiers = setOf("type", "text", "message", "input", "search", "write", "typing", "chat", "edit", "compose", "reply", "messaging", "writing")

    private val directions = mapOf(
        "up" to "up", "down" to "down", "left" to "left", "right" to "right",
        "upore" to "up", "niche" to "down", "daane" to "right", "baame" to "left",
        "উপরে" to "up", "নিচে" to "down", "ডানে" to "right", "বামে" to "left"
    )

    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    // Bangla-script app names -> the English name used on the phone
    private val aliases = mapOf(
        "ইউটিউব" to "youtube", "ফেসবুক" to "facebook", "মেসেঞ্জার" to "messenger", "ক্রোম" to "chrome",
        "গুগল" to "google", "জিমেইল" to "gmail", "ক্যামেরা" to "camera", "গ্যালারি" to "gallery",
        "সেটিংস" to "settings", "ফোন" to "phone", "ব্রাউজার" to "browser", "ইনস্টাগ্রাম" to "instagram",
        "টেলিগ্রাম" to "telegram", "ম্যাপ" to "maps", "ক্লড" to "claude"
    )

    private class Seg {
        var verb: ActionType? = null
        val words = mutableListOf<String>()
    }

    private fun tokenize(input: String): List<String> =
        Fuzzy.normalize(input).split(" ").filter { it.isNotEmpty() }.map { aliases[it] ?: it }

    /** The action word at position i, plus how many words it uses ("go back" = 2 words). */
    private fun verbAt(t: List<String>, i: Int): Pair<ActionType, Int>? {
        val w = t[i]
        if (w == "go") {
            return when (t.getOrNull(i + 1)) {
                "to" -> ActionType.OPEN to 2
                "back" -> ActionType.BACK to 2
                "home" -> ActionType.HOME to 2
                else -> null
            }
        }
        val type = verbs[w] ?: return null
        return type to 1
    }

    private fun sepLen(t: List<String>, i: Int): Int {
        val w = t[i]
        if (w in separators) return 1
        if (w == "after" && t.getOrNull(i + 1) == "that") return 2
        if (w == "followed" && t.getOrNull(i + 1) == "by") return 2
        return 0
    }

    private fun startsCommand(t: List<String>, i: Int, inDictation: Boolean): Boolean {
        val va = verbAt(t, i) ?: return false
        if (inDictation && va.first == ActionType.SEND) {
            // "type call me and send me the file" -> the second "send" is part of the text
            return t.drop(i + va.second).all { it in sendTail }
        }
        return true
    }

    fun parse(input: String): List<Step> {
        val tokens = tokenize(input)
        val steps = mutableListOf<Step>()
        var seg = Seg()

        fun flush() {
            build(seg)?.let { steps.add(it) }
            seg = Seg()
        }

        var i = 0
        while (i < tokens.size) {
            // 1) a separator word like "and" / "then"
            if (sepLen(tokens, i) > 0) {
                var j = i
                while (j < tokens.size) {
                    val l = sepLen(tokens, j)
                    if (l == 0) break
                    j += l
                }
                if (j < tokens.size && startsCommand(tokens, j, seg.verb == ActionType.TYPE)) {
                    flush()
                } else if (seg.verb == ActionType.TYPE) {
                    seg.words.addAll(tokens.subList(i, j))   // "and" inside the typed sentence
                }
                i = j
                continue
            }

            val t = tokens[i]
            val va = verbAt(tokens, i)

            // 2) we have not seen an action word yet in this command
            if (seg.verb == null) {
                if (va != null) {
                    val endsCommand = seg.words.isNotEmpty() && t in verbFinal
                    seg.verb = va.first
                    i += va.second
                    if (endsCommand) flush()
                    continue
                }
                // "you" is polite filler only in "can you / could you / would you ..."
                val politeYou = t == "you" && tokens.getOrNull(i - 1) in setOf("can", "could", "would", "will")
                if (t !in fillers && !politeYou) seg.words.add(t)
                i++
                continue
            }

            // 3) inside a "type ..." command everything is text
            if (seg.verb == ActionType.TYPE) {
                seg.words.add(t)
                i++
                continue
            }

            // 4) a new strong action word right after a target starts the next command ("open x tap y")
            if (va != null && seg.words.isNotEmpty() && va.first !in weak) {
                flush()
                seg.verb = va.first
                i += va.second
                continue
            }
            if (t !in fillers) seg.words.add(t)
            i++
        }
        flush()
        return steps
    }

    private fun build(seg: Seg): Step? {
        val verb = seg.verb
        val words = seg.words.toMutableList()
        if (verb != ActionType.TYPE) {
            while (words.isNotEmpty() && words.last() in suffixFillers) words.removeAt(words.size - 1)
        }
        val target = words.joinToString(" ").trim()

        if (verb == null) {
            if (target.isEmpty()) return null
            return special(target) ?: Step(ActionType.GUESS, target)
        }

        return when (verb) {
            ActionType.TYPE ->
                if (target.isEmpty()) null else Step(ActionType.TYPE, target.replaceFirstChar { it.uppercase() })
            ActionType.OPEN ->
                if (target.isEmpty()) null else (special(target) ?: Step(ActionType.OPEN, target))
            ActionType.TAP ->
                if (target.isEmpty()) null
                else special(target) ?: when {
                    target in sendWords -> Step(ActionType.SEND)
                    isFieldTarget(target) -> Step(ActionType.TAP_FIELD)
                    else -> Step(ActionType.TAP, target)
                }
            ActionType.SEND -> Step(ActionType.SEND)
            ActionType.SCROLL -> Step(ActionType.SCROLL, direction(words) ?: "down")
            ActionType.SWIPE -> Step(ActionType.SWIPE, direction(words) ?: "up")
            ActionType.BACK, ActionType.HOME, ActionType.RECENTS, ActionType.NOTIFICATIONS -> Step(verb)
            ActionType.WAIT -> Step(ActionType.WAIT, seconds(words).toString())
            else -> null
        }
    }

    private fun special(t: String): Step? = when (t) {
        "home", "home screen", "homescreen" -> Step(ActionType.HOME)
        "back" -> Step(ActionType.BACK)
        "recents", "recent", "recent apps", "overview" -> Step(ActionType.RECENTS)
        "notifications", "notification", "notification bar", "notification panel", "notification shade" ->
            Step(ActionType.NOTIFICATIONS)
        else -> null
    }

    private fun isFieldTarget(t: String): Boolean {
        val w = t.split(" ")
        if (w.last() in setOf("box", "field", "bar", "area") && w.dropLast(1).all { it in fieldQualifiers }) return true
        return t in setOf("message", "type message", "text", "input", "keyboard", "typing")
    }

    private fun direction(words: List<String>): String? = words.firstNotNullOfOrNull { directions[it] }

    private fun seconds(words: List<String>): Int {
        for (w in words) {
            w.toIntOrNull()?.let { return it.coerceIn(1, 10) }
            numberWords[w]?.let { return it }
        }
        return 1
    }
}
