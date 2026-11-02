package com.example.voicecontrol

import kotlin.math.max
import kotlin.math.min

/**
 * Small "forgiving text matcher".
 * Used to match what you SAID ("whats app", "tan veer") against app names and text on the screen
 * ("WhatsApp", "Tanvir Ahmed"). No AI, no internet - just string maths.
 */
object Fuzzy {

    private fun isWordChar(ch: Char): Boolean {
        if (ch.isLetterOrDigit()) return true
        val c = ch.category
        // Keeps Bangla vowel signs etc. so Bangla words are not cut into pieces
        return c == CharCategory.NON_SPACING_MARK || c == CharCategory.COMBINING_SPACING_MARK
    }

    /** lowercase, punctuation removed, single spaces. "What's  App!" -> "whats app" */
    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            when {
                isWordChar(ch) -> sb.append(ch)
                ch == '\'' || ch == '’' -> {
                    // apostrophes are dropped: what's -> whats
                }
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun ratio(a: String, b: String): Double {
        val m = max(a.length, b.length)
        if (m == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / m
    }

    /** Rough "how it sounds" form, so tanvir/tanbir/tanveer look alike. */
    private fun phon(s: String): String {
        var t = s.replace("ph", "f").replace("ck", "k").replace("th", "t")
            .replace("sh", "s").replace("ee", "i").replace("oo", "u")
        t = t.map {
            when (it) {
                'c', 'q' -> 'k'
                'v', 'w' -> 'b'
                'z' -> 'j'
                'y' -> 'i'
                else -> it
            }
        }.joinToString("")
        val sb = StringBuilder()
        for (ch in t) if (sb.isEmpty() || sb.last() != ch) sb.append(ch)
        return sb.toString()
    }

    /** 0.0 (nothing alike) .. 1.0 (same). */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        val cx = x.replace(" ", "")
        val cy = y.replace(" ", "")
        if (cx.isEmpty() || cy.isEmpty()) return 0.0
        if (cx == cy) return 1.0

        var best = ratio(cx, cy)
        best = max(best, 0.95 * ratio(phon(cx), phon(cy)))

        if (cx.length >= 3 && cy.length >= 3) {
            val xw = x.split(" ")
            val yw = y.split(" ")
            if (yw.contains(x) && yw.size <= 4) {
                best = max(best, 0.9)           // "tanvir" inside "Tanvir Ahmed"
            } else if (xw.contains(y) && xw.size <= 4) {
                best = max(best, 0.9)
            } else if (yw.contains(x) || xw.contains(y)) {
                best = max(best, 0.65)          // word appears inside a long sentence
            } else if (cy.contains(cx) || cx.contains(cy)) {
                val r = min(cx.length, cy.length).toDouble() / max(cx.length, cy.length)
                best = max(best, 0.6 + 0.3 * r)
            }
        }
        return best
    }
}
