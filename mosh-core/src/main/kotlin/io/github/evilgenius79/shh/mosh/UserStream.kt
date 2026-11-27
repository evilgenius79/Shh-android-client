package io.github.evilgenius79.shh.mosh

/**
 * Everything the user has done that the server has not acknowledged yet, in order.
 *
 * The client's side of the State Synchronization Protocol is an append-only stream of events, so
 * one of its states is fully described by a position in that stream: "everything up to here".
 * Positions count one per typed byte and one per resize. The stream forgets what lies before the
 * acknowledged position, because no diff will ever have to start earlier than that.
 */
internal class UserStream {
    private class Entry(val start: Long, val event: UserEvent) {
        val length: Int get() = if (event is UserEvent.Keys) event.bytes.size else 1
        val end: Long get() = start + length
    }

    private val entries = ArrayDeque<Entry>()

    /** Position after the last event. */
    var end: Long = 0
        private set

    fun addKeys(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        entries.addLast(Entry(end, UserEvent.Keys(bytes)))
        end += bytes.size
    }

    fun addResize(columns: Int, rows: Int) {
        entries.addLast(Entry(end, UserEvent.Resize(columns, rows)))
        end += 1
    }

    /** The events between two positions, with neighbouring keystrokes joined into one event. */
    fun slice(from: Long, to: Long): List<UserEvent> {
        require(from <= to && to <= end) { "Bad range $from..$to of a stream ending at $end" }
        require(from == to || (entries.isNotEmpty() && entries.first().start <= from)) { "Position $from was already forgotten" }
        val result = ArrayList<UserEvent>()
        var pendingKeys: java.io.ByteArrayOutputStream? = null
        for (entry in entries) {
            if (entry.end <= from) continue
            if (entry.start >= to) break
            when (val event = entry.event) {
                is UserEvent.Keys -> {
                    val first = maxOf(from, entry.start) - entry.start
                    val last = minOf(to, entry.end) - entry.start
                    val keys = pendingKeys ?: java.io.ByteArrayOutputStream().also { pendingKeys = it }
                    keys.write(event.bytes, first.toInt(), (last - first).toInt())
                }
                is UserEvent.Resize -> {
                    pendingKeys?.let { result.add(UserEvent.Keys(it.toByteArray())) }
                    pendingKeys = null
                    result.add(event)
                }
            }
        }
        pendingKeys?.let { result.add(UserEvent.Keys(it.toByteArray())) }
        return result
    }

    /** Forgets whole events that end at or before [position]; they have been acknowledged. */
    fun forgetBefore(position: Long) {
        while (entries.isNotEmpty() && entries.first().end <= position) entries.removeFirst()
    }

    /** Drops everything after [position]: input that will never be sent. */
    fun truncate(position: Long) {
        require(position <= end)
        while (entries.isNotEmpty() && entries.last().start >= position) entries.removeLast()
        val last = entries.lastOrNull()
        if (last != null && last.end > position) {
            // Only a run of keystrokes can straddle a position.
            val keys = (last.event as UserEvent.Keys).bytes
            entries.removeLast()
            entries.addLast(Entry(last.start, UserEvent.Keys(keys.copyOf((position - last.start).toInt()))))
        }
        end = position
    }
}
