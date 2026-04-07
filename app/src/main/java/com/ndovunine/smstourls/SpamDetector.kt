package com.ndovunine.smstourls

import android.util.Log

/**
 * SpamDetector — keyword-based SMS spam filter.
 *
 * Focused on betting, sure-odds, and gambling scam patterns.
 * Add or remove keywords in SPAM_KEYWORDS to tune detection.
 */
object SpamDetector {

    private const val TAG = "SpamDetector"

    /**
     * Minimum number of keyword hits before a message is considered spam.
     * Set to 1 for strict filtering (any match = spam).
     * Set to 2+ for looser filtering (reduces false positives).
     */
    private const val HIT_THRESHOLD = 2

    /**
     * Master keyword list. All comparisons are case-insensitive.
     * Covers: sure odds, tipsters, betting platforms, scam phrases.
     */
    private val SPAM_KEYWORDS = listOf(
        // Payment demands (core scam pattern)
        "pay", "after win", "after payment", "deposit", "settle", "send",

        // Betting terminology
        "stake", "odds", "fixed", "gg", "draw", "over", "under", "correct score",
        "single bet", "multibet", "jackpot", "vip", "source", "banker",

        // Success claims (fake wins)
        "congratulations", "won", "bravo", "boom", "perfect win", "what a win",

        // Urgency & pressure
        "must win", "sure", "guaranteed", "trust me", "don't miss", "only after payment",

        // Payment channels (Kenya-specific, but generic enough)
        "till number", "till no", "mpesa", "paybill", "send money",

        // Platforms
        "betika", "sportpesa", "odds",

        // Demanding next action
        "receive next", "get next", "next sure", "next game"
    )

    private val PATTERN_WEIGHTS = mapOf(
        Regex("pay\\s+\\d+\\s+(after|via)", RegexOption.IGNORE_CASE) to 5,
        Regex("till\\s+no\\.?\\s*\\d+", RegexOption.IGNORE_CASE) to 4,
        Regex("mpesa\\s+no\\.?\\s*\\d+", RegexOption.IGNORE_CASE) to 4,
        Regex("congratulations.*won", RegexOption.IGNORE_CASE) to 3,
        Regex("\\b(sure|fixed|guaranteed)\\b", RegexOption.IGNORE_CASE) to 2,
        Regex("https?://betika\\.com", RegexOption.IGNORE_CASE) to 4,
        Regex("(stake|odds|gg|draw|over\\s?\\d)", RegexOption.IGNORE_CASE) to 1,
        // ... more
    )

    fun isPayAfterWinScam(message: String): Boolean {
        val hasWinClaim = Regex("(congratulations|won|bravo|boom).*won", RegexOption.IGNORE_CASE).containsMatchIn(message)
        val hasPayRequest = Regex("pay\\s+\\d+", RegexOption.IGNORE_CASE).containsMatchIn(message)
        val hasNextInstruction = Regex("(receive|get)\\s+next", RegexOption.IGNORE_CASE).containsMatchIn(message)
        return hasWinClaim && hasPayRequest && hasNextInstruction
    }

    fun normalize(message: String): String {
        return message
            .lowercase()
            .replace(Regex("\\d+"), "0")            // replace all numbers
            .replace(Regex("https?://\\S+"), "URL") // replace links
            .replace(Regex("\\b\\d{9,12}\\b"), "PHONE") // replace phone numbers
    }

    /**
     * Returns true if the message is likely spam/betting-related.
     *
     * @param message Raw SMS body text
     */
    fun isSpam(message: String): Boolean {
        val lower = message.lowercase()
        var score = 0

        // Keyword matching (fixed case)
        for (keyword in SPAM_KEYWORDS) {
            if (lower.contains(keyword)) {
                score += 1
                Log.d(TAG, "Keyword hit: $keyword")
            }
        }

        // Regex pattern scoring
        for ((pattern, weight) in PATTERN_WEIGHTS) {
            if (pattern.containsMatchIn(message)) {
                score += weight
                Log.d(TAG, "Pattern hit: ${pattern.pattern}")
            }
        }

        // Special composite check
        if (isPayAfterWinScam(message)) {
            score += 5
        }

        val threshold = if (score >= 3) 3 else HIT_THRESHOLD  // dynamic
        val isSpam = score >= threshold

        if (isSpam) {
            Log.i(TAG, "SPAM detected (score=$score). Body: ${message.take(80)}")
        }
        return isSpam
    }

    /**
     * Returns a list of all matched keywords for logging/debugging.
     */
    fun getMatchedKeywords(message: String): List<String> {
        val lower = message.lowercase()
        return SPAM_KEYWORDS.filter { lower.contains(it) }
    }
}