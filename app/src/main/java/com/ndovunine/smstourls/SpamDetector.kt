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

        // Sure odds / guaranteed wins
        "PAY",
        "AFTER WIN",
        "WINNING TICKET",
        "SURE",
        "STRAIGHT WIN"
    )

    /**
     * Returns true if the message is likely spam/betting-related.
     *
     * @param message Raw SMS body text
     */
    fun isSpam(message: String): Boolean {
        val lower = message.lowercase()
        var hits = 0

        for (keyword in SPAM_KEYWORDS) {
            if (lower.contains(keyword)) {
                hits++
                Log.d(TAG, "Spam keyword matched: \"$keyword\" (hits=$hits)")
                if (hits >= HIT_THRESHOLD) {
                    Log.i(TAG, "Message flagged as SPAM. Body preview: ${message.take(60)}")
                    return true
                }
            }
        }

        return false
    }

    /**
     * Returns a list of all matched keywords for logging/debugging.
     */
    fun getMatchedKeywords(message: String): List<String> {
        val lower = message.lowercase()
        return SPAM_KEYWORDS.filter { lower.contains(it) }
    }
}