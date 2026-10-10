package com.lifeline.mesh.packet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class PacketCodecTest {

    private val h = Header(messageId = 0x0123456789ABCDEFL, origin = 0x3FA1BEEF, ttl = 5, hops = 0, time = 1_791_500_000L)

    // Bengaluru, near the venue
    private val lat = Geo.toE6(12.971599)
    private val lon = Geo.toE6(77.594566)

    private val hindi = "पानी बढ़ गया है, छत पर पाँच लोग हैं, एक बुज़ुर्ग बीमार हैं"
    private val kannada = "ಮನೆಯಲ್ಲಿ ನಾಲ್ಕು ಜನ ಸಿಲುಕಿದ್ದಾರೆ, ನೀರು ಬೇಕು, ದಯವಿಟ್ಟು ಬನ್ನಿ"

    private fun roundTrip(p: Packet): Packet {
        val bytes = PacketCodec.encode(p)
        val r = PacketCodec.decode(bytes)
        if (r !is DecodeResult.Ok) fail("expected Ok, got $r")
        return (r as DecodeResult.Ok).packet
    }

    private fun malformed(bytes: ByteArray): String {
        val r = PacketCodec.decode(bytes)
        if (r !is DecodeResult.Malformed) fail("expected Malformed, got $r")
        return (r as DecodeResult.Malformed).reason
    }

    /** Raw packet with a valid header and an arbitrary body, to build malformed input. */
    private fun raw(firstByte: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(PacketCodec.HEADER_SIZE + body.size).apply {
            put(firstByte.toByte()); putLong(1L); putInt(2); put(5); put(0); putInt(3); put(body)
        }.array()

    private fun alertBody(note: ByteArray, sev: Int = 3, needs: Int = 3, lat: Int = this.lat) =
        ByteBuffer.allocate(12 + note.size).apply {
            putInt(lat); putInt(lon); put(2); put(needs.toByte()); put(sev.toByte()); put(5); put(note)
        }.array()

    private fun isValidUtf8(b: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b)); true
    } catch (e: Exception) { false }

    // ------------------------------------------------------------ round trips, every type

    @Test fun alertRoundTrip() {
        val p = Packet.Alert(h, lat, lon, accuracy = 3, needs = 3, severity = 3, people = 5, note = "5 on roof")
        assertEquals(p, roundTrip(p))
    }

    @Test fun alertExtremes() {
        val p = Packet.Alert(h, -90_000_000, 180_000_000, Geo.ACCURACY_UNKNOWN, needs = 63, severity = 0, people = 255)
        assertEquals(p, roundTrip(p))
    }

    @Test fun checkInRoundTrip() {
        val p = Packet.CheckIn(h, lat, lon, 1, "Asha")
        assertEquals(p, roundTrip(p))
    }

    @Test fun ackRoundTrip() {
        val p = Packet.Ack(h, refId = -42L, etaMinutes = 20, note = "Boat on the way")
        assertEquals(p, roundTrip(p))
    }

    @Test fun reportRoundTripAllKinds() {
        ReportType.entries.forEach { kind ->
            val p = Packet.Report(h, lat, lon, 4, kind, "Main road")
            assertEquals(p, roundTrip(p))
        }
    }

    @Test fun pingRoundTrip() {
        val a = Packet.Ping(h, refId = Long.MIN_VALUE, echo = false)
        val b = Packet.Ping(h, refId = Long.MAX_VALUE, echo = true)
        assertEquals(a, roundTrip(a))
        assertEquals(b, roundTrip(b))
        assertEquals(PacketCodec.HEADER_SIZE + 9, PacketCodec.encode(a).size)
    }

    @Test fun helloRoundTrip() {
        HelloRole.entries.forEach { role ->
            val p = Packet.Hello(h.copy(ttl = PacketCodec.TTL_HELLO), 87, role, listOf(1, -2, 0x7FFFFFFF), "Ravi")
            assertEquals(p, roundTrip(p))
        }
    }

    @Test fun positionRoundTrip() {
        HelloRole.entries.forEach { role ->
            val p = Packet.Position(h, lat, lon, 3, role, "Kiran")
            assertEquals(p, roundTrip(p))
        }
        val bare = Packet.Position(h, lat, lon, Geo.ACCURACY_UNKNOWN, HelloRole.AMBULANCE)
        assertEquals(bare, roundTrip(bare))
        assertEquals(PacketCodec.HEADER_SIZE + 10, PacketCodec.encode(bare).size)
        // Name trimmed to 16 bytes on a whole character; longer on the wire is malformed.
        val long = roundTrip(Packet.Position(h, lat, lon, 1, HelloRole.NDRF, "ಎನ್‌ಡಿಆರ್‌ಎಫ್ ತಂಡ")) as Packet.Position
        assertTrue(long.name.toByteArray().size <= PacketCodec.MAX_POSITION_NAME)
        val tooLong = ByteBuffer.allocate(10 + 17).apply { putInt(lat); putInt(lon); put(1); put(5); put(ByteArray(17) { 'a'.code.toByte() }) }.array()
        assertEquals("name too long", malformed(raw(0x17, tooLong)))
    }

    @Test fun ambulanceNeedBitRoundTrips() {
        val p = Packet.Alert(h, lat, lon, 2, Need.toMask(listOf(Need.AMBULANCE, Need.MEDICAL)), 3, 1, "")
        assertEquals(p, roundTrip(p))
        assertEquals(setOf(Need.MEDICAL, Need.AMBULANCE), Need.fromMask((roundTrip(p) as Packet.Alert).needs))
    }

    @Test fun helloNoNeighborsNoName() {
        val p = Packet.Hello(h, 0, HelloRole.VOLUNTEER, emptyList())
        assertEquals(p, roundTrip(p))
        assertEquals(PacketCodec.HEADER_SIZE + 3, PacketCodec.encode(p).size)
    }

    @Test fun summaryRoundTrip() {
        val empty = Packet.Summary(h.copy(ttl = 0), emptyList())
        val full = Packet.Summary(h.copy(ttl = 0), List(PacketCodec.MAX_SUMMARY_IDS) { it * 7919L - 1 })
        assertEquals(empty, roundTrip(empty))
        assertEquals(full, roundTrip(full))
        assertTrue(PacketCodec.encode(full).size <= PacketCodec.MAX_PAYLOAD)
    }

    // ------------------------------------------------------------ header layout

    @Test fun headerLayoutIsBigEndianAtSpecOffsets() {
        val p = Packet.Ack(h.copy(ttl = 4, hops = 1, time = 0xF000_0001L), 9L, 20)
        val b = PacketCodec.encode(p)
        assertEquals(0x13, b[0].toInt() and 0xFF) // version 1, type 3
        assertEquals(0x01, b[1].toInt() and 0xFF) // message id MSB first
        assertEquals(0xEF, b[8].toInt() and 0xFF)
        assertEquals(0x3F, b[9].toInt() and 0xFF) // origin
        assertEquals(4, b[13].toInt())             // ttl
        assertEquals(1, b[14].toInt())             // hops
        assertEquals(0xF0, b[15].toInt() and 0xFF) // time, unsigned past 2^31
        assertEquals(0x01, b[18].toInt() and 0xFF)
        assertEquals(0xF000_0001L, roundTrip(p).header.time)
    }

    @Test fun ttlAndHopsUseFullUnsignedByte() {
        val p = Packet.Ping(h.copy(ttl = 255, hops = 200), 1L, false)
        assertEquals(p, roundTrip(p))
    }

    // ------------------------------------------------------------ text limits and trimming

    @Test fun maxLengthTextIsKeptExactly() {
        val a60 = "a".repeat(60)
        assertEquals(a60, (roundTrip(Packet.Alert(h, lat, lon, 1, 1, 1, 1, a60)) as Packet.Alert).note)
        assertEquals("b".repeat(24), (roundTrip(Packet.CheckIn(h, lat, lon, 1, "b".repeat(24))) as Packet.CheckIn).name)
        assertEquals("c".repeat(40), (roundTrip(Packet.Ack(h, 1, 1, "c".repeat(40))) as Packet.Ack).note)
        assertEquals("d".repeat(60), (roundTrip(Packet.Report(h, lat, lon, 1, ReportType.SHELTER_OPEN, "d".repeat(60))) as Packet.Report).note)
        assertEquals("e".repeat(16), (roundTrip(Packet.Hello(h, 1, HelloRole.RESCUER, emptyList(), "e".repeat(16))) as Packet.Hello).name)
    }

    @Test fun overlongAsciiIsTrimmedToLimit() {
        val note = (roundTrip(Packet.Alert(h, lat, lon, 1, 1, 1, 1, "x".repeat(200))) as Packet.Alert).note
        assertEquals("x".repeat(60), note)
        val ack = (roundTrip(Packet.Ack(h, 1, 1, "y".repeat(41))) as Packet.Ack).note
        assertEquals(40, ack.length)
    }

    @Test fun hindiNoteTrimmedOnWholeCharacters() {
        assertTrue(hindi.toByteArray().size > 60)
        val bytes = PacketCodec.utf8Trimmed(hindi, 60)
        assertTrue(bytes.size <= 60)
        assertTrue(isValidUtf8(bytes))
        val decoded = (roundTrip(Packet.Alert(h, lat, lon, 1, 1, 3, 5, hindi)) as Packet.Alert).note
        assertTrue("decoded note must be a prefix of the original", hindi.startsWith(decoded))
        assertEquals(String(bytes, Charsets.UTF_8), decoded)
        assertTrue(decoded.isNotEmpty())
    }

    @Test fun kannadaNoteTrimmedOnWholeCharacters() {
        assertTrue(kannada.toByteArray().size > 60)
        for (limit in listOf(60, 40, 24, 16, 1, 2, 3, 4)) {
            val bytes = PacketCodec.utf8Trimmed(kannada, limit)
            assertTrue(bytes.size <= limit)
            assertTrue("limit $limit", isValidUtf8(bytes))
            assertTrue(kannada.startsWith(String(bytes, Charsets.UTF_8)))
        }
        val decoded = (roundTrip(Packet.Ack(h, 1, 30, kannada)) as Packet.Ack).note
        assertTrue(kannada.startsWith(decoded))
    }

    @Test fun cutThatWouldLandMidCharacterBacksOff() {
        // 'a' + Devanagari (3 bytes each): 1 + 3*19 = 58 fits, the 20th char would end at byte 61.
        val s = "a" + "क".repeat(30)
        val b = PacketCodec.utf8Trimmed(s, 60)
        assertEquals(58, b.size)
        assertEquals("a" + "क".repeat(19), String(b, Charsets.UTF_8))
    }

    @Test fun fourByteCharactersAreNotSplit() {
        val s = "SOS " + "🆘".repeat(20) // 🆘, 4 bytes each, a surrogate pair in Kotlin
        val b = PacketCodec.utf8Trimmed(s, 16) // 4 + 3*4 = 16
        assertEquals(16, b.size)
        val back = String(b, Charsets.UTF_8)
        assertEquals("SOS " + "🆘".repeat(3), back)
        assertTrue(!back.last().isHighSurrogate())
    }

    @Test fun trimToBytesMatchesWhatIsSent() {
        val shown = PacketCodec.trimToBytes(hindi, PacketCodec.MAX_ALERT_NOTE)
        val sent = (roundTrip(Packet.Alert(h, lat, lon, 1, 1, 3, 5, hindi)) as Packet.Alert).note
        assertEquals(shown, sent)
    }

    // ------------------------------------------------------------ malformed input, never a crash

    @Test fun tooShort() {
        assertEquals("too short", malformed(ByteArray(0)))
        assertEquals("too short", malformed(ByteArray(18) { 0x11 }))
        assertEquals("too short", malformed(raw(0x11, ByteArray(11)))) // alert body needs 12
        assertEquals("too short", malformed(raw(0x15, ByteArray(8))))  // ping body needs 9
        assertEquals("too short", malformed(raw(0x13, ByteArray(0))))  // ack, empty body
        assertEquals("too short", malformed(raw(0x1F, ByteArray(0))))  // summary, no count
    }

    @Test fun unknownVersion() {
        assertTrue(malformed(raw(0x01, alertBody(ByteArray(0)))).startsWith("unknown version"))
        assertTrue(malformed(raw(0x21, alertBody(ByteArray(0)))).startsWith("unknown version"))
        assertTrue(malformed(raw(0xF1, alertBody(ByteArray(0)))).startsWith("unknown version"))
    }

    @Test fun unknownType() {
        for (t in listOf(0, 9, 10, 11, 12, 13, 14)) { // 8 = official warning (10 Oct 2026)
            assertTrue("type $t", malformed(raw(0x10 or t, ByteArray(20))).startsWith("unknown type"))
        }
    }

    @Test fun oversizedTextIsRejected() {
        assertEquals("note too long", malformed(raw(0x11, alertBody(ByteArray(61) { 'a'.code.toByte() }))))
        val ack = ByteBuffer.allocate(9 + 41).apply { putLong(1); put(5); put(ByteArray(41) { 'a'.code.toByte() }) }.array()
        assertEquals("note too long", malformed(raw(0x13, ack)))
        val checkIn = ByteBuffer.allocate(9 + 25).apply { putInt(lat); putInt(lon); put(1); put(ByteArray(25) { 'a'.code.toByte() }) }.array()
        assertEquals("name too long", malformed(raw(0x12, checkIn)))
        val report = ByteBuffer.allocate(10 + 61).apply { putInt(lat); putInt(lon); put(1); put(1); put(ByteArray(61) { 'a'.code.toByte() }) }.array()
        assertEquals("note too long", malformed(raw(0x14, report)))
        val hello = ByteBuffer.allocate(3 + 17).apply { put(50); put(1); put(0); put(ByteArray(17) { 'a'.code.toByte() }) }.array()
        assertEquals("name too long", malformed(raw(0x16, hello)))
    }

    @Test fun outOfRangeFields() {
        assertEquals("severity out of range", malformed(raw(0x11, alertBody(ByteArray(0), sev = 4))))
        assertEquals("unknown needs bits", malformed(raw(0x11, alertBody(ByteArray(0), needs = 128))))
        assertEquals("latitude out of range", malformed(raw(0x11, alertBody(ByteArray(0), lat = 90_000_001))))
        val badLon = ByteBuffer.allocate(12).apply { putInt(0); putInt(-180_000_001); put(0); put(1); put(1); put(1) }.array()
        assertEquals("longitude out of range", malformed(raw(0x11, badLon)))
        for (k in listOf(0, 5, 255)) {
            val r = ByteBuffer.allocate(10).apply { putInt(lat); putInt(lon); put(1); put(k.toByte()) }.array()
            assertEquals("unknown report type", malformed(raw(0x14, r)))
        }
        for (role in listOf(0, 8)) {
            assertEquals("unknown role", malformed(raw(0x16, byteArrayOf(50, role.toByte(), 0))))
            val pos = ByteBuffer.allocate(10).apply { putInt(lat); putInt(lon); put(1); put(role.toByte()) }.array()
            assertEquals("unknown role", malformed(raw(0x17, pos)))
        }
        val ping2 = ByteBuffer.allocate(9).apply { putLong(1); put(2) }.array()
        assertEquals("bad echo flag", malformed(raw(0x15, ping2)))
    }

    @Test fun lengthMismatches() {
        val pingTrailing = ByteBuffer.allocate(10).apply { putLong(1); put(0); put(0) }.array()
        assertEquals("trailing bytes", malformed(raw(0x15, pingTrailing)))
        assertEquals("too many neighbors", malformed(raw(0x16, byteArrayOf(50, 1, 17) + ByteArray(68))))
        // says 3 neighbors, carries 2
        assertEquals("too short", malformed(raw(0x16, byteArrayOf(50, 1, 3) + ByteArray(8))))
        // summary says 2 ids, carries 1, or carries 3
        assertEquals("summary length mismatch", malformed(raw(0x1F, byteArrayOf(2) + ByteArray(8))))
        assertEquals("summary length mismatch", malformed(raw(0x1F, byteArrayOf(2) + ByteArray(24))))
        assertEquals("too many ids", malformed(raw(0x1F, byteArrayOf(101) + ByteArray(808))))
        assertEquals("too long", malformed(ByteArray(PacketCodec.MAX_PAYLOAD + 1) { 0x11 }))
    }

    @Test fun invalidUtf8IsDecodedSafely() {
        // Note ends halfway through a Devanagari character (3-byte sequence cut after 2 bytes): drop the stub.
        val ka = "क".toByteArray()
        val cutTail = "ok ".toByteArray() + ka.copyOf(2)
        val a = PacketCodec.decode(raw(0x11, alertBody(cutTail)))
        assertEquals("ok ", ((a as DecodeResult.Ok).packet as Packet.Alert).note)

        // A stray continuation byte and an invalid 0xFF in the middle become U+FFFD; the rest survives.
        val junk = "a".toByteArray() + byteArrayOf(0x80.toByte(), 0xFF.toByte()) + "b".toByteArray()
        val b = PacketCodec.decode(raw(0x11, alertBody(junk)))
        val note = ((b as DecodeResult.Ok).packet as Packet.Alert).note
        assertTrue(note.startsWith("a") && note.endsWith("b") && note.contains('�'))

        // Overlong encoding of '/' (C0 AF) is invalid UTF-8.
        val overlong = PacketCodec.decode(raw(0x13, ByteBuffer.allocate(11).apply { putLong(1); put(1); put(0xC0.toByte()); put(0xAF.toByte()) }.array()))
        assertTrue(overlong is DecodeResult.Ok)
        assertTrue(((overlong as DecodeResult.Ok).packet as Packet.Ack).note.none { it == '/' })
    }

    @Test fun randomBytesNeverThrow() {
        val rnd = Random(1234)
        repeat(20_000) {
            val bytes = rnd.nextBytes(rnd.nextInt(0, 140))
            if (bytes.isNotEmpty() && rnd.nextBoolean()) bytes[0] = (0x10 or rnd.nextInt(0, 16)).toByte()
            PacketCodec.decode(bytes) // must not throw
        }
    }

    @Test fun mutatedValidPacketsNeverThrow() {
        val rnd = Random(99)
        val samples = listOf(
            Packet.Alert(h, lat, lon, 1, 3, 3, 5, hindi),
            Packet.CheckIn(h, lat, lon, 1, kannada),
            Packet.Ack(h, 7, 20, "Help is coming"),
            Packet.Report(h, lat, lon, 1, ReportType.ROAD_BLOCKED, "Bridge"),
            Packet.Ping(h, 7, false),
            Packet.Hello(h, 80, HelloRole.RESCUER, listOf(1, 2, 3), "Ravi"),
            Packet.Position(h, lat, lon, 2, HelloRole.AMBULANCE, "Kiran"),
            Packet.Summary(h, listOf(1, 2, 3)),
        ).map { PacketCodec.encode(it) }
        repeat(20_000) {
            val b = samples[rnd.nextInt(samples.size)].copyOf()
            repeat(rnd.nextInt(1, 4)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val cut = if (rnd.nextInt(4) == 0) b.copyOf(rnd.nextInt(b.size + 1)) else b
            PacketCodec.decode(cut)
        }
    }

    @Test fun reEncodingDecodedPacketGivesSameBytes() {
        val bytes = PacketCodec.encode(Packet.Hello(h, 55, HelloRole.VOLUNTEER, listOf(9, 8), "ಅನು"))
        val again = PacketCodec.encode((PacketCodec.decode(bytes) as DecodeResult.Ok).packet)
        assertArrayEquals(bytes, again)
    }

    @Test fun withHeaderKeepsBody() {
        val p = Packet.Alert(h, lat, lon, 1, 3, 3, 5, "x")
        val fwd = p.withHeader(p.header.copy(ttl = 4, hops = 1)) as Packet.Alert
        assertEquals(p.copy(header = p.header.copy(ttl = 4, hops = 1)), fwd)
    }

    @Test fun geoHelpers() {
        assertEquals(12.971599, Geo.fromE6(Geo.toE6(12.971599)), 1e-9)
        assertEquals(Geo.ACCURACY_UNKNOWN, Geo.accuracyStep(null))
        assertEquals(0, Geo.accuracyStep(0f))
        assertEquals(1, Geo.accuracyStep(4.2f))   // rounded up: never claim better than measured
        assertEquals(5, Geo.accuracyStep(50f))
        assertEquals(254, Geo.accuracyStep(99_999f))
        assertEquals(50, Geo.accuracyMeters(5))
        assertEquals(null, Geo.accuracyMeters(255))
    }

    // ------------------------------------------------------------ sample sizes (printed for the phase report)

    @Test fun printSampleSizes() {
        val hinglish = "Paani badh gaya hai, chhat par 5 log hain, ek bujurg beemar hain"
        val samples = listOf(
            "Alert, no note" to Packet.Alert(h, lat, lon, 2, 3, 3, 5),
            "Alert, Hinglish demo sentence (${hinglish.toByteArray().size} bytes typed)" to
                Packet.Alert(h, lat, lon, 2, 3, 3, 5, hinglish),
            "Alert, Hindi note (${hindi.toByteArray().size} bytes typed)" to Packet.Alert(h, lat, lon, 2, 3, 3, 5, hindi),
            "Check-in, name \"Asha\"" to Packet.CheckIn(h, lat, lon, 1, "Asha"),
            "Ack, ETA 20, no note" to Packet.Ack(h, 7, 20),
            "Ack, note \"Boat coming, stay on roof\"" to Packet.Ack(h, 7, 20, "Boat coming, stay on roof"),
            "Ping" to Packet.Ping(h, 7, false),
            "Hello, 3 neighbors, name \"Asha\"" to Packet.Hello(h.copy(ttl = 2), 80, HelloRole.NEEDS_HELP, listOf(1, 2, 3), "Asha"),
            "Position, ambulance, name \"Kiran\"" to Packet.Position(h, lat, lon, 2, HelloRole.AMBULANCE, "Kiran"),
            "Summary, 100 ids (largest packet)" to Packet.Summary(h, List(100) { it.toLong() }),
        )
        println("PACKET SIZES (encoded bytes, header ${PacketCodec.HEADER_SIZE}):")
        samples.forEach { (label, p) ->
            val size = PacketCodec.encode(p).size
            println("  %-52s %4d".format(label, size))
            assertTrue(size <= PacketCodec.MAX_PAYLOAD)
        }
        val sent = (roundTrip(samples[1].second) as Packet.Alert).note
        println("  Hinglish note as sent: \"$sent\"")
    }

    // ------------------------------------------------------------------ official warnings (type 8)

    @Test fun warningRoundTripAndSize() {
        val w = Packet.Warning(h, level = 3, drill = false, expires = 1_791_600_000L, event = "Tsunami",
            headline = "Tsunami warning for the Karnataka coast. Move inland now.")
        assertEquals(w, roundTrip(w))
        val bytes = PacketCodec.encode(w)
        println("warning: ${bytes.size} bytes")
        assertEquals(PacketCodec.HEADER_SIZE + 7 + "Tsunami".length + w.headline.length, bytes.size)
        assertTrue(roundTrip(w.copy(drill = true)).let { it is Packet.Warning && it.drill })
        assertTrue(roundTrip(w.copy(forecast = true)).let { it is Packet.Warning && it.forecast && !it.drill })
    }

    @Test fun warningTextIsTrimmedOnWholeCharacters() {
        val w = Packet.Warning(h, 2, false, 1_791_600_000L, event = kannada, headline = hindi.repeat(5))
        val back = roundTrip(w) as Packet.Warning
        assertTrue(back.event.toByteArray().size <= PacketCodec.MAX_WARNING_EVENT && kannada.startsWith(back.event))
        assertTrue(back.headline.toByteArray().size <= PacketCodec.MAX_WARNING_TEXT && hindi.repeat(5).startsWith(back.headline))
        assertTrue(PacketCodec.encode(w).size <= PacketCodec.MAX_PAYLOAD)
    }

    @Test fun malformedWarningsAreRejected() {
        val event = "Flood".toByteArray()
        fun body(level: Int = 2, flags: Int = 0, eventLen: Int = event.size, text: ByteArray = "x".toByteArray()) =
            ByteBuffer.allocate(7 + event.size + text.size).apply {
                put(level.toByte()); put(flags.toByte()); putInt(1_791_600_000); put(eventLen.toByte()); put(event); put(text)
            }.array()
        val first = (PacketCodec.VERSION shl 4) or PacketType.WARNING.code
        assertTrue(PacketCodec.decode(raw(first, body())) is DecodeResult.Ok)
        assertEquals("level out of range", malformed(raw(first, body(level = 4))))
        assertEquals("bad warning flags", malformed(raw(first, body(flags = 4))))
        assertTrue((PacketCodec.decode(raw(first, body(flags = 3))) as DecodeResult.Ok).packet.let { it is Packet.Warning && it.drill && it.forecast })
        assertEquals("event too long", malformed(raw(first, body(eventLen = 25))))
        assertEquals("too short", malformed(raw(first, body(eventLen = 20, text = ByteArray(0)))))
        assertEquals("too short", malformed(raw(first, byteArrayOf(2, 0, 0))))
        assertEquals("headline too long", malformed(raw(first, body(text = ByteArray(161) { 'a'.code.toByte() }))))
    }

    @Test fun warningIdsComeFromTheCapIdentifier() {
        assertEquals(Warnings.messageId("IN-1791553740889019_19"), Warnings.messageId("IN-1791553740889019_19"))
        assertTrue(Warnings.messageId("IN-1791553740889019_19") != Warnings.messageId("IN-1791553740889019_20"))
        assertEquals(3, Warnings.level("Extreme")); assertEquals(2, Warnings.level("severe ")); assertEquals(0, Warnings.level("Unknown"))
    }
}
