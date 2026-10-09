package com.lifeline.mesh.packet

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

sealed interface DecodeResult {
    data class Ok(val packet: Packet) : DecodeResult
    /** [reason] is a short plain phrase for the event log, e.g. "too short". */
    data class Malformed(val reason: String) : DecodeResult
}

/**
 * Binary codec for CLAUDE.md section 6. Big-endian, 19-byte header, version 1.
 *
 * Encoding never fails for a valid [Packet]: text longer than its limit is trimmed on a whole UTF-8 character.
 * Decoding never throws: anything that doesn't match the spec comes back as [DecodeResult.Malformed].
 */
object PacketCodec {
    const val VERSION = 1
    const val HEADER_SIZE = 19
    const val MAX_PAYLOAD = 1024

    const val TTL_DEFAULT = 5
    const val TTL_HELLO = 2

    const val MAX_ALERT_NOTE = 60
    const val MAX_CHECKIN_NAME = 24
    const val MAX_ACK_NOTE = 40
    const val MAX_REPORT_NOTE = 60
    const val MAX_HELLO_NAME = 16
    const val MAX_HELLO_NEIGHBORS = 16
    const val MAX_SUMMARY_IDS = 100

    private const val KNOWN_NEEDS_MASK = 0x3F

    // ---------------------------------------------------------------- encode

    fun encode(p: Packet): ByteArray {
        val body: ByteArray = when (p) {
            is Packet.Alert -> {
                val note = utf8Trimmed(p.note, MAX_ALERT_NOTE)
                ByteBuffer.allocate(12 + note.size).apply {
                    putInt(p.latE6); putInt(p.lonE6)
                    put(p.accuracy.toByte()); put(p.needs.toByte()); put(p.severity.toByte()); put(p.people.toByte())
                    put(note)
                }.array()
            }
            is Packet.CheckIn -> {
                val name = utf8Trimmed(p.name, MAX_CHECKIN_NAME)
                ByteBuffer.allocate(9 + name.size).apply {
                    putInt(p.latE6); putInt(p.lonE6); put(p.accuracy.toByte()); put(name)
                }.array()
            }
            is Packet.Ack -> {
                val note = utf8Trimmed(p.note, MAX_ACK_NOTE)
                ByteBuffer.allocate(9 + note.size).apply {
                    putLong(p.refId); put(p.etaMinutes.toByte()); put(note)
                }.array()
            }
            is Packet.Report -> {
                val note = utf8Trimmed(p.note, MAX_REPORT_NOTE)
                ByteBuffer.allocate(10 + note.size).apply {
                    putInt(p.latE6); putInt(p.lonE6); put(p.accuracy.toByte()); put(p.reportType.code.toByte()); put(note)
                }.array()
            }
            is Packet.Ping -> ByteBuffer.allocate(9).apply {
                putLong(p.refId); put(if (p.echo) 1 else 0)
            }.array()
            is Packet.Hello -> {
                val name = utf8Trimmed(p.name, MAX_HELLO_NAME)
                ByteBuffer.allocate(3 + 4 * p.neighbors.size + name.size).apply {
                    put(p.battery.toByte()); put(p.role.code.toByte()); put(p.neighbors.size.toByte())
                    p.neighbors.forEach { putInt(it) }
                    put(name)
                }.array()
            }
            is Packet.Summary -> ByteBuffer.allocate(1 + 8 * p.ids.size).apply {
                put(p.ids.size.toByte()); p.ids.forEach { putLong(it) }
            }.array()
        }
        val h = p.header
        val out = ByteBuffer.allocate(HEADER_SIZE + body.size).apply {
            put(((VERSION shl 4) or p.type.code).toByte())
            putLong(h.messageId)
            putInt(h.origin)
            put(h.ttl.toByte())
            put(h.hops.toByte())
            putInt(h.time.toInt()) // low 32 bits = unsigned seconds
            put(body)
        }.array()
        check(out.size <= MAX_PAYLOAD) { "packet too large: ${out.size}" }
        return out
    }

    // ---------------------------------------------------------------- decode

    fun decode(bytes: ByteArray): DecodeResult = try {
        decodeOrThrow(bytes)
    } catch (e: Malformed) {
        DecodeResult.Malformed(e.message ?: "malformed")
    } catch (e: RuntimeException) {
        // Belt and braces: a bug here must never crash the relay.
        DecodeResult.Malformed("malformed (${e.javaClass.simpleName})")
    }

    private class Malformed(reason: String) : Exception(reason)

    private fun bad(reason: String): Nothing = throw Malformed(reason)

    private fun decodeOrThrow(bytes: ByteArray): DecodeResult {
        if (bytes.size < HEADER_SIZE) bad("too short")
        if (bytes.size > MAX_PAYLOAD) bad("too long")
        val buf = ByteBuffer.wrap(bytes)
        val first = buf.get().toInt() and 0xFF
        if (first ushr 4 != VERSION) bad("unknown version ${first ushr 4}")
        val type = PacketType.fromCode(first and 0x0F) ?: bad("unknown type ${first and 0x0F}")
        val header = Header(
            messageId = buf.long,
            origin = buf.int,
            ttl = buf.u8(),
            hops = buf.u8(),
            time = buf.int.toLong() and 0xFFFF_FFFFL,
        )

        val packet: Packet = when (type) {
            PacketType.ALERT -> {
                buf.need(12)
                val lat = buf.lat(); val lon = buf.lon()
                val acc = buf.u8(); val needs = buf.u8(); val sev = buf.u8(); val people = buf.u8()
                if (needs and KNOWN_NEEDS_MASK.inv() != 0) bad("unknown needs bits")
                if (sev > 3) bad("severity out of range")
                Packet.Alert(header, lat, lon, acc, needs, sev, people, buf.text(MAX_ALERT_NOTE, "note"))
            }
            PacketType.CHECK_IN -> {
                buf.need(9)
                Packet.CheckIn(header, buf.lat(), buf.lon(), buf.u8(), buf.text(MAX_CHECKIN_NAME, "name"))
            }
            PacketType.ACK -> {
                buf.need(9)
                Packet.Ack(header, buf.long, buf.u8(), buf.text(MAX_ACK_NOTE, "note"))
            }
            PacketType.REPORT -> {
                buf.need(10)
                val lat = buf.lat(); val lon = buf.lon(); val acc = buf.u8()
                val kind = ReportType.fromCode(buf.u8()) ?: bad("unknown report type")
                Packet.Report(header, lat, lon, acc, kind, buf.text(MAX_REPORT_NOTE, "note"))
            }
            PacketType.PING -> {
                buf.need(9)
                val ref = buf.long
                val echo = when (buf.u8()) { 0 -> false; 1 -> true; else -> bad("bad echo flag") }
                if (buf.hasRemaining()) bad("trailing bytes")
                Packet.Ping(header, ref, echo)
            }
            PacketType.HELLO -> {
                buf.need(3)
                val battery = buf.u8()
                val role = HelloRole.fromCode(buf.u8()) ?: bad("unknown role")
                val count = buf.u8()
                if (count > MAX_HELLO_NEIGHBORS) bad("too many neighbors")
                buf.need(4 * count)
                val neighbors = List(count) { buf.int }
                Packet.Hello(header, battery, role, neighbors, buf.text(MAX_HELLO_NAME, "name"))
            }
            PacketType.SUMMARY -> {
                buf.need(1)
                val count = buf.u8()
                if (count > MAX_SUMMARY_IDS) bad("too many ids")
                if (buf.remaining() != 8 * count) bad("summary length mismatch")
                Packet.Summary(header, List(count) { buf.long })
            }
        }
        return DecodeResult.Ok(packet)
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xFF
    private fun ByteBuffer.need(n: Int) { if (remaining() < n) bad("too short") }
    private fun ByteBuffer.lat(): Int = int.also { if (it !in -90_000_000..90_000_000) bad("latitude out of range") }
    private fun ByteBuffer.lon(): Int = int.also { if (it !in -180_000_000..180_000_000) bad("longitude out of range") }

    /** The rest of the buffer as text. Oversized → malformed; bad bytes are decoded safely (see [utf8Decode]). */
    private fun ByteBuffer.text(max: Int, field: String): String {
        val n = remaining()
        if (n > max) bad("$field too long")
        val raw = ByteArray(n).also { get(it) }
        return utf8Decode(raw)
    }

    // ---------------------------------------------------------------- UTF-8

    /**
     * UTF-8 bytes of [s], at most [maxBytes], cut only between whole characters (code points).
     * A Hindi or Kannada note is never split inside a multi-byte character.
     */
    fun utf8Trimmed(s: String, maxBytes: Int): ByteArray {
        val all = s.toByteArray(Charsets.UTF_8)
        if (all.size <= maxBytes) return all
        var cut = maxBytes
        // Step back over continuation bytes (10xxxxxx) so the cut lands on a character's first byte.
        while (cut > 0 && (all[cut].toInt() and 0xC0) == 0x80) cut--
        return all.copyOf(cut)
    }

    /** [s] trimmed the same way as [utf8Trimmed], as a String (for showing the user what will be sent). */
    fun trimToBytes(s: String, maxBytes: Int): String = String(utf8Trimmed(s, maxBytes), Charsets.UTF_8)

    /**
     * Never throws. A character cut off at the very end (a sender that trimmed badly) is dropped;
     * any other invalid byte becomes U+FFFD.
     */
    fun utf8Decode(raw: ByteArray): String {
        val end = completeLength(raw)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return try {
            decoder.decode(ByteBuffer.wrap(raw, 0, end)).toString()
        } catch (e: CharacterCodingException) {
            "" // unreachable with REPLACE, but never crash on input from another phone
        }
    }

    /** Length of [raw] without a trailing incomplete multi-byte sequence. */
    private fun completeLength(raw: ByteArray): Int {
        val n = raw.size
        // Find the start of the last character (at most 3 continuation bytes back).
        var i = n - 1
        var back = 0
        while (i >= 0 && back < 3 && (raw[i].toInt() and 0xC0) == 0x80) { i--; back++ }
        if (i < 0) return n
        val lead = raw[i].toInt() and 0xFF
        val expected = when {
            lead < 0x80 -> 1
            lead shr 5 == 0b110 -> 2
            lead shr 4 == 0b1110 -> 3
            lead shr 3 == 0b11110 -> 4
            else -> return n // not a lead byte; the decoder replaces it
        }
        return if (n - i < expected) i else n
    }
}
