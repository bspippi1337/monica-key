package no.blckswan.monicakey

internal class ReplayWindow(
    highest: Long = 0L,
    seen: Collection<Long> = emptyList(),
    private val width: Long = 2048L
) {
    private var highestSeen = highest.coerceAtLeast(0L)
    private val seenIds = seen.filterTo(linkedSetOf()) { it > 0L }

    fun accepts(messageId: Long): Boolean {
        if (messageId <= 0L) return false
        if (messageId <= highestSeen - width) return false
        return messageId !in seenIds
    }

    fun mark(messageId: Long) {
        require(accepts(messageId)) { "Replay or stale message" }
        seenIds += messageId
        if (messageId > highestSeen) highestSeen = messageId
        val floor = (highestSeen - width).coerceAtLeast(0L)
        seenIds.removeAll { it <= floor }
    }

    fun highest(): Long = highestSeen

    fun snapshot(): List<Long> = seenIds.sorted()
}
