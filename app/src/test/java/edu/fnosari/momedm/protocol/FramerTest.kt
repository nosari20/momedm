package edu.fnosari.momedm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramerTest {
    @Test fun maxChunkFromMtu() {
        assertEquals(5, Framer.maxChunk(23))      // 23-3-15
        assertEquals(499, Framer.maxChunk(517))
        assertEquals(1, Framer.maxChunk(10))      // never below 1
    }
    @Test fun splitAndParse() {
        val frames = Framer.split(0x1a2b, "abcdefghij", 4)
        assertEquals(listOf("1a2b:0/3:abcd", "1a2b:1/3:efgh", "1a2b:2/3:ij"), frames)
        val f = Framer.parse("1a2b:1/3:efgh")!!
        assertEquals(0x1a2b, f.msgId); assertEquals(1, f.index); assertEquals(3, f.count); assertEquals("efgh", f.chunk)
    }
    @Test fun emptyPayloadIsOneFrame() {
        assertEquals(listOf("0001:0/1:"), Framer.split(1, "", 5))
    }
    @Test fun parseRejectsGarbage() {
        assertNull(Framer.parse("nope")); assertNull(Framer.parse("zzzz:0/1:x")); assertNull(Framer.parse("0001:2/1:x"))
    }
    @Test fun reassembleInOrder() {
        val r = Reassembler()
        assertNull(r.feed("0001:0/2:hel", 0L))
        assertEquals("hello", r.feed("0001:1/2:lo", 1L))
    }
    @Test fun reassembleDropsStaleMessage() {
        val r = Reassembler(timeoutMs = 10_000)
        assertNull(r.feed("0001:0/2:hel", 0L))
        assertNull(r.feed("0002:0/1:x", 20_000L).let { assertEquals("x", it); null })
        assertNull(r.feed("0001:1/2:lo", 20_001L)) // first message expired → restarted, still incomplete
    }
    @Test fun interleavedMessagesAreIndependent() {
        val r = Reassembler()
        assertNull(r.feed("000a:0/2:A1", 0L)); assertNull(r.feed("000b:0/2:B1", 0L))
        assertEquals("B1B2", r.feed("000b:1/2:B2", 0L)); assertEquals("A1A2", r.feed("000a:1/2:A2", 0L))
    }
    @Test fun reassembleSurvivesLongTransferWithShortGaps() {
        // Timeout is idle-based (refreshed per accepted frame), not total-duration: a slow low-MTU
        // transfer whose gaps between chunks stay under timeoutMs must still complete even though the
        // whole transfer exceeds timeoutMs.
        val r = Reassembler(timeoutMs = 10_000)
        val frames = Framer.split(1, "hello", 1) // 5 frames, one char each
        assertEquals(5, frames.size)
        assertNull(r.feed(frames[0], 0L))
        assertNull(r.feed(frames[1], 4_000L))
        assertNull(r.feed(frames[2], 8_000L))
        assertNull(r.feed(frames[3], 12_000L))
        assertEquals("hello", r.feed(frames[4], 16_000L))
    }
    @Test fun reassemblerCapsConcurrentPartials() {
        // Bound pre-auth memory: at most MAX_PARTIALS incomplete messages tracked concurrently.
        val r = Reassembler()
        val firstChunks = (0..16).map { msgId -> Framer.split(msgId, "AB", 1) } // 17 distinct 2-chunk messages
        for ((t, frames) in firstChunks.withIndex()) assertNull(r.feed(frames[0], t.toLong()))
        // The 17th insertion evicted the oldest (msgId 0): completing it now yields nothing.
        assertNull(r.feed(firstChunks[0][1], 17L))
        // The most recently added (msgId 16) is still tracked and completes normally.
        assertEquals("AB", r.feed(firstChunks[16][1], 18L))
    }

    @Test fun reassemblerCapsTheSizeOfOnePartial() {
        // MAX_PARTIALS bounds how MANY messages are tracked; without a byte budget a single message
        // that never completes could still grow without limit.
        val r = Reassembler()
        val payload = "x".repeat(Reassembler.MAX_PARTIAL_BYTES + 64 * 1024)
        val frames = Framer.split(1, payload, 32 * 1024)
        var out: String? = null
        for ((t, f) in frames.withIndex()) out = r.feed(f, t.toLong())
        // The partial is dropped once it outgrows the budget, so the message never assembles — rather
        // than the reassembler holding every byte a peer cares to send.
        assertNull(out)
    }
}
