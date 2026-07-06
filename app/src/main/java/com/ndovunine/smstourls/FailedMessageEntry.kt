package com.ndovunine.smstourls

/**
 * Structured representation of a failed SMS forward attempt.
 *
 * @param body       The full original SMS body.
 * @param sender     The originating phone number.
 * @param reason     Human-readable failure reason extracted from server response or exception.
 * @param shortDisplay Pre-computed short display text: first segment of [body] split at '.'.
 */
data class FailedMessageEntry(
    val body: String,
    val sender: String,
    val reason: String,
    val shortDisplay: String,
    val isPermanent: Boolean = false
) {
    companion object {
        /** Create an entry from a raw message body, extracting the short display. */
        fun fromMessage(
            message: String,
            sender: String = "Unknown",
            reason: String = "Unknown error",
            isPermanent: Boolean = false
        ): FailedMessageEntry {
            val short = message.substringBefore('.').trim().ifEmpty { message.take(50) }
            return FailedMessageEntry(
                body = message,
                sender = sender,
                reason = reason,
                shortDisplay = short,
                isPermanent = isPermanent
            )
        }

        /** Format for display in the failed-message list. */
        fun displayText(entry: FailedMessageEntry): String {
            return "${entry.shortDisplay} - ${entry.reason}"
        }
    }
}