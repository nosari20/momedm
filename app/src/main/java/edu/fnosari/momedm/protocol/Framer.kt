package edu.fnosari.momedm.protocol

/**
 * Splits an ASCII payload into BLE-sized frames `"<msgId>:<idx>/<count>:<chunk>"` and reassembles them.
 * msgId = 4 lower-case hex chars; idx/count up to 4 digits; header worst case = 4+1+4+1+4+1 = 15 chars.
 */
object Framer {
    const val HEADER_MAX = 15
    const val MAX_COUNT = 9999
    private val FRAME_RE = Regex("^([0-9a-f]{4}):(\\d{1,4})/(\\d{1,4}):(.*)$", RegexOption.DOT_MATCHES_ALL)

    data class Frame(val msgId: Int, val index: Int, val count: Int, val chunk: String)

    /** Largest chunk that fits an ATT payload of `mtu - 3` bytes after the header. Never < 1. */
    fun maxChunk(mtu: Int): Int = (mtu - 3 - HEADER_MAX).coerceAtLeast(1)

    fun split(msgId: Int, payload: String, chunkSize: Int): List<String> {
        require(chunkSize >= 1) { "chunkSize must be >= 1" }
        val id = String.format("%04x", msgId and 0xffff)
        if (payload.isEmpty()) return listOf("$id:0/1:")
        val chunks = payload.chunked(chunkSize)
        require(chunks.size <= MAX_COUNT) { "payload too large: ${chunks.size} frames" }
        return chunks.mapIndexed { i, c -> "$id:$i/${chunks.size}:$c" }
    }

    fun parse(frame: String): Frame? {
        val m = FRAME_RE.matchEntire(frame) ?: return null
        val (id, idx, cnt, chunk) = m.destructured
        val index = idx.toInt(); val count = cnt.toInt()
        if (count < 1 || index >= count) return null
        return Frame(id.toInt(16), index, count, chunk)
    }
}

/**
 * Collects frames per msgId; returns the payload when the last chunk lands.
 *
 * Partial messages expire after [timeoutMs] of *inactivity* — `startedAt` is refreshed on every accepted
 * frame, so a slow low-MTU transfer with short gaps between chunks survives even if its total duration
 * exceeds [timeoutMs]. To bound pre-auth memory, at most [MAX_PARTIALS] messages are tracked concurrently;
 * once full, the globally least-recently-active partial is evicted to make room for a new one.
 */
class Reassembler(private val timeoutMs: Long = 10_000) {
    private class Partial(val count: Int, var startedAt: Long) {
        val chunks = HashMap<Int, String>()
        var bytes = 0
    }
    private val partials = LinkedHashMap<Int, Partial>()

    fun feed(frame: String, nowMs: Long): String? {
        val f = Framer.parse(frame) ?: return null
        partials.entries.removeIf { nowMs - it.value.startedAt > timeoutMs }
        var p = partials[f.msgId]
        if (p == null || p.count != f.count) {
            p = Partial(f.count, nowMs)
            partials[f.msgId] = p
            if (partials.size > MAX_PARTIALS) partials.remove(partials.entries.minBy { it.value.startedAt }.key)
        } else {
            p.startedAt = nowMs
        }
        if (partials[f.msgId] !== p) return null // evicted just now to respect MAX_PARTIALS

        // MAX_PARTIALS bounds how many messages are tracked, not how large they are: sixteen partials
        // of arbitrary length is still unbounded memory from a peer that never completes any of them.
        // Count the bytes too, and drop a partial that outgrows what a real message could ever need.
        val added = f.chunk.length - (p.chunks[f.index]?.length ?: 0)
        if (p.bytes + added > MAX_PARTIAL_BYTES) { partials.remove(f.msgId); return null }
        p.bytes += added

        p.chunks[f.index] = f.chunk
        if (p.chunks.size < p.count) return null
        partials.remove(f.msgId)
        return (0 until p.count).joinToString("") { p.chunks[it] ?: "" }
    }

    companion object {
        /** Upper bound on concurrently-tracked incomplete messages, to cap memory before a peer authenticates. */
        const val MAX_PARTIALS = 16

        /**
         * Upper bound on one incomplete message. The largest thing this protocol really sends is an
         * app list or a managed-configuration schema — tens of KB at the very most — so 256 KB is
         * generous for anything legitimate and still bounded for anything that is not.
         */
        const val MAX_PARTIAL_BYTES = 256 * 1024
    }
}
