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
    private const val HIT_THRESHOLD = 8

    /**
     * Master keyword list. All comparisons are case-insensitive.
     * Covers: sure odds, tipsters, betting platforms, scam phrases.
     */
    private val SPAM_KEYWORDS = listOf(
        // Payment demands (core scam pattern)
        "pay", "after win", "after payment", "deposit", "settle", "send",

        // Betting terminology
        "stake", "odds", "fixed", "gg", "draw", "over", "under", "correct score",
        "single bet", "multibet", "jackpot", "vip", "source", "banker","games","game",

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
        // ============ HIGH WEIGHT (4-5) - Strong scam indicators ============

        // Payment demands
        Regex("pay\\s+\\d+\\s+(after|via)", RegexOption.IGNORE_CASE) to 5,
        Regex("pay\\s+(only|only\\s+)?(ksh|kes|bob)?\\s*\\d+", RegexOption.IGNORE_CASE) to 5,
        Regex("after\\s+win\\s+pay", RegexOption.IGNORE_CASE) to 5,
        Regex("pay\\s+\\d+\\s+to\\s+(receive|get)", RegexOption.IGNORE_CASE) to 5,
        Regex("deposit\\s+\\d+", RegexOption.IGNORE_CASE) to 5,
        Regex("settle\\s+(only\\s+)?\\d+", RegexOption.IGNORE_CASE) to 5,
        Regex("send\\s+\\d+\\s+(to|via|through)", RegexOption.IGNORE_CASE) to 5,

        // Payment channel indicators
        Regex("till\\s+no\\.?\\s*\\d+", RegexOption.IGNORE_CASE) to 4,
        Regex("till\\s+number\\s*\\d+", RegexOption.IGNORE_CASE) to 4,
        Regex("mpesa\\s+no\\.?\\s*\\d+", RegexOption.IGNORE_CASE) to 4,
        Regex("mpesa\\s+number\\s*\\d+", RegexOption.IGNORE_CASE) to 4,
        Regex("paybill", RegexOption.IGNORE_CASE) to 4,

        // Win claims (usually fake)
        Regex("congratulations.*won", RegexOption.IGNORE_CASE) to 3,
        Regex("bravo.*won", RegexOption.IGNORE_CASE) to 3,
        Regex("boom.*won", RegexOption.IGNORE_CASE) to 3,
        Regex("perfect\\s+win", RegexOption.IGNORE_CASE) to 3,
        Regex("what\\s+a\\s+win", RegexOption.IGNORE_CASE) to 3,
        Regex("successfully\\s+won", RegexOption.IGNORE_CASE) to 3,
        Regex("game\\s+won", RegexOption.IGNORE_CASE) to 3,
        Regex("won\\s+now\\s+pay", RegexOption.IGNORE_CASE) to 4,  // Combined pattern

        // Betting platform links
        Regex("https?://betika\\.com", RegexOption.IGNORE_CASE) to 4,
        Regex("https?://betika\\.com/share/\\w+", RegexOption.IGNORE_CASE) to 4,
        Regex("sportpesa", RegexOption.IGNORE_CASE) to 3,
        Regex("betika\\.com/share/", RegexOption.IGNORE_CASE) to 4,

        // "Next" pattern (indicates ongoing scam)
        Regex("(receive|get)\\s+next", RegexOption.IGNORE_CASE) to 4,
        Regex("next\\s+sure\\s+(odd|game|match|bet)", RegexOption.IGNORE_CASE) to 4,
        Regex("after\\s+payment\\s+(receive|get)", RegexOption.IGNORE_CASE) to 4,
        Regex("only\\s+after\\s+(you\\s+)?pay", RegexOption.IGNORE_CASE) to 4,

        // ============ MEDIUM WEIGHT (2-3) - Strong betting indicators ============

        // Fixed/guaranteed claims
        Regex("\\bsure\\b", RegexOption.IGNORE_CASE) to 2,
        Regex("\\bfixed\\b", RegexOption.IGNORE_CASE) to 3,
        Regex("\\bguaranteed\\b", RegexOption.IGNORE_CASE) to 2,
        Regex("must\\s+win", RegexOption.IGNORE_CASE) to 3,
        Regex("100%\\s+(sure|guaranteed|win)", RegexOption.IGNORE_CASE) to 3,

        // Odds and returns
        Regex("\\d+(\\.\\d+)?\\s*odds?", RegexOption.IGNORE_CASE) to 2,
        Regex("\\d+odds?", RegexOption.IGNORE_CASE) to 2,
        Regex("returns?\\s+\\d+", RegexOption.IGNORE_CASE) to 2,
        Regex("win\\s+\\d+k", RegexOption.IGNORE_CASE) to 2,

        // Betting terminology
        Regex("\\bgg\\b", RegexOption.IGNORE_CASE) to 2,
        Regex("\\bover\\s?\\d+\\.?\\d*\\b", RegexOption.IGNORE_CASE) to 2,
        Regex("\\bunder\\s?\\d+\\.?\\d*\\b", RegexOption.IGNORE_CASE) to 2,
        Regex("correct\\s+score", RegexOption.IGNORE_CASE) to 3,
        Regex("draw\\s+ft", RegexOption.IGNORE_CASE) to 2,
        Regex("1st\\s+half\\s+draw", RegexOption.IGNORE_CASE) to 2,
        Regex("first\\s+half\\s+draw", RegexOption.IGNORE_CASE) to 2,
        Regex("ht\\s+draw", RegexOption.IGNORE_CASE) to 2,
        Regex("single\\s+bet", RegexOption.IGNORE_CASE) to 2,
        Regex("multi bet", RegexOption.IGNORE_CASE) to 2,
        Regex("jackpot", RegexOption.IGNORE_CASE) to 2,
        Regex("banker", RegexOption.IGNORE_CASE) to 2,
        Regex("vip slip", RegexOption.IGNORE_CASE) to 2,
        Regex("exclusive\\s+vip", RegexOption.IGNORE_CASE) to 2,
        Regex("source", RegexOption.IGNORE_CASE) to 2,

        // Betting ID patterns
        Regex("pick\\s*:\\s*\\w+\\s+(win|win ft|gg|draw)", RegexOption.IGNORE_CASE) to 2,
        Regex("select\\s*:\\s*\\w+\\s+(win|draw|gg)", RegexOption.IGNORE_CASE) to 2,
        Regex("bet\\s*-\\s*gg\\s+ft", RegexOption.IGNORE_CASE) to 2,

        // Stake instructions
        Regex("stake\\s+high", RegexOption.IGNORE_CASE) to 2,
        Regex("stake\\s+well", RegexOption.IGNORE_CASE) to 2,
        Regex("stake\\s+\\d+k", RegexOption.IGNORE_CASE) to 2,

        // ============ LOW WEIGHT (1) - Supporting indicators ============

        // Common betting verbs/nouns
        Regex("\\bstake\\b", RegexOption.IGNORE_CASE) to 1,
        Regex("\\bodds\\b", RegexOption.IGNORE_CASE) to 1,
        Regex("\\bdraw\\b", RegexOption.IGNORE_CASE) to 1,
        Regex("\\bwin(?!\\s+(ticket|after|game))\\b", RegexOption.IGNORE_CASE) to 1,
        Regex("\\blost\\b", RegexOption.IGNORE_CASE) to 1,

        // Pressure/urgency words
        Regex("don'?t\\s+miss", RegexOption.IGNORE_CASE) to 1,
        Regex("trust\\s+me", RegexOption.IGNORE_CASE) to 1,
        Regex("serious\\s+(profit|player|source)", RegexOption.IGNORE_CASE) to 1,
        Regex("risky|risk\\s+takers", RegexOption.IGNORE_CASE) to 1,
        Regex("exclusive", RegexOption.IGNORE_CASE) to 1,
        Regex("genuine\\s+source", RegexOption.IGNORE_CASE) to 2,

        // Team names with betting picks (common in scam messages)
        Regex("\\w+\\s+vs\\.?\\s+\\w+", RegexOption.IGNORE_CASE) to 1,

        // Time indicators for matches
        //Regex("\\d{1,2}:\\d{2}\\s*(am|pm)", RegexOption.IGNORE_CASE) to 1,
        Regex("kick\\s+off", RegexOption.IGNORE_CASE) to 1,
        Regex("ft", RegexOption.IGNORE_CASE) to 1,

        // Phone numbers (Kenyan format)
        Regex("\\b0?7\\d{8}\\b", RegexOption.IGNORE_CASE) to 2,
        Regex("\\b0?1\\d{8}\\b", RegexOption.IGNORE_CASE) to 2,

        // Jackpot mentions
        Regex("jackpot.*available", RegexOption.IGNORE_CASE) to 2,
        //Regex("mega jackpot", RegexOption.IGNORE_CASE) to 2,
        //Regex("midweek jackpot", RegexOption.IGNORE_CASE) to 2,

        // Transaction/amount patterns
        Regex("via\\s+(mpesa|till|paybill)", RegexOption.IGNORE_CASE) to 2,
        Regex("through\\s+(mpesa|till)", RegexOption.IGNORE_CASE) to 2,

        // Special market mentions
        Regex("(special|golden|beneficiary)\\s+(market|tip|game)", RegexOption.IGNORE_CASE) to 2,
        Regex("(breakfast|lunch|dinner)\\s+tip", RegexOption.IGNORE_CASE) to 1,

        // Link shorteners (suspicious)
        Regex("https?://\\S+\\.(ly|tinyurl|bit\\.ly|shorturl)", RegexOption.IGNORE_CASE) to 3,

        // Recovery/refund claims (common scam pattern)
        Regex("recover(y|ing)\\s+(loses?|money)", RegexOption.IGNORE_CASE) to 2,

        // All caps sections (common in scam messages for emphasis)
        //Regex("[A-Z]{5,}", RegexOption.IGNORE_CASE) to 1,

        // Consecutive exclamation/punctuation
        Regex("!{3,}", RegexOption.IGNORE_CASE) to 1,

        // Betting platform markers
        Regex("bet-id|bet id", RegexOption.IGNORE_CASE) to 1,
        Regex("\\bover\\s+\\d+\\.?\\d?\\b", RegexOption.IGNORE_CASE) to 1,

        // Time range patterns
        //Regex("\\d{1,2}:\\d{2}\\s*(am|pm|hrs)", RegexOption.IGNORE_CASE) to 1,

        // Payment amount patterns
        Regex("pay\\s+(only|only\\s+)?(ksh|kes|bob|sh)?\\s*\\d+", RegexOption.IGNORE_CASE) to 3,

        // Sequence patterns (pay this -> get that)
        Regex("pay\\s+\\d+.*?(receive|get).+?(next|another)", RegexOption.IGNORE_CASE) to 4,

        // Multiple team/match mentions
        Regex("(\\d+\\s*\\.\\s*\\w+\\s+(win|vs\\.?|pick))", RegexOption.IGNORE_CASE) to 1,

        // Disclaimer-like phrases (ironically common in scams)
        Regex("no\\s+risk", RegexOption.IGNORE_CASE) to 2,
        Regex("high\\s+confidence", RegexOption.IGNORE_CASE) to 2
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