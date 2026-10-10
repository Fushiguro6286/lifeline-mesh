package com.lifeline.mesh.routing

import com.lifeline.mesh.triage.GeoMath
import java.io.ByteArrayInputStream
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A route on the road network: the points to draw, the length, the time, and how many blocked roads it goes around. */
data class Route(val points: List<Pair<Double, Double>>, val meters: Double, val minutes: Int, val avoidedBlocks: Int)

/** A place someone reported as blocked (a "road blocked" report or alert): roads within [radiusM] are not used. */
data class Block(val lat: Double, val lon: Double, val radiusM: Double = 40.0)

/** How a responder moves on roads. Speeds are assumptions (km/h) for a city in an emergency, shown with every estimate. */
enum class RoadMode(val kmh: Map<String, Double>) {
    VEHICLE(mapOf("highway" to 40.0, "major_road" to 30.0, "minor_road" to 20.0, "other" to 12.0)),
    FOOT(mapOf("highway" to 4.0, "major_road" to 4.0, "minor_road" to 4.0, "other" to 4.0, "path" to 4.0)),
}

/**
 * Offline road routing (10 Oct 2026, on request): reads the roads layer of the bundled Protomaps basemap (PMTiles v3,
 * Mapbox Vector Tiles at zoom 15), builds a road graph, and finds the fastest route with A*, skipping roads near reported
 * blockages. Pure Kotlin and deterministic: the same map, start, end and blockages always give the same route. No model.
 */
class RoadMap private constructor(
    private val lat: DoubleArray, private val lon: DoubleArray,
    private val edgeStart: IntArray, private val edgeTo: IntArray, private val edgeM: FloatArray, private val edgeKind: ByteArray,
) {
    val nodes: Int get() = lat.size
    internal fun point(i: Int) = lat[i] to lon[i]

    /** (edges out of [n], their kinds) for diagnostics and tests. */
    internal fun edgesOf(n: Int): List<Pair<Int, String>> = (edgeStart[n] until edgeStart[n + 1]).map { edgeTo[it] to KINDS[edgeKind[it].toInt()] }

    /** Size of the connected part of the network containing [start] (diagnostics and tests). */
    internal fun componentSize(start: Int): Int {
        val seen = BooleanArray(nodes); val stack = ArrayDeque<Int>(); stack.addLast(start); seen[start] = true; var n = 0
        while (stack.isNotEmpty()) { val u = stack.removeLast(); n++; for (e in edgeStart[u] until edgeStart[u + 1]) { val v = edgeTo[e]; if (!seen[v]) { seen[v] = true; stack.addLast(v) } } }
        return n
    }
    val edges: Int get() = edgeTo.size

    /**
     * The node nearest to a point that [mode] can use (a car does not start on a footpath), or -1 if none is within
     * [maxM] metres. Null [mode] = any road.
     */
    fun nearest(la: Double, lo: Double, maxM: Double = 300.0, mode: RoadMode? = null): Int {
        var best = -1; var bestD = maxM
        // Cheap box filter first: 0.004 degrees is about 440 m.
        for (i in lat.indices) {
            if (kotlin.math.abs(lat[i] - la) > 0.004 || kotlin.math.abs(lon[i] - lo) > 0.004) continue
            if (mode != null && (edgeStart[i] until edgeStart[i + 1]).none { speedMs(edgeKind[it], mode) != null }) continue
            val d = GeoMath.distanceM(la, lo, lat[i], lon[i])
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * Fastest route from ([fromLat], [fromLon]) to ([toLat], [toLon]) for [mode], avoiding [blocks]. Null if either end
     * is more than 300 m from a road or no road path exists. The short walks or drives to and from the nearest road
     * points are added as straight lines.
     */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: RoadMode, blocks: List<Block> = emptyList()): Route? {
        val a = nearest(fromLat, fromLon, mode = mode); val b = nearest(toLat, toLon, mode = mode)
        if (a < 0 || b < 0) return null
        val blockedNode = BooleanArray(nodes)
        if (blocks.isNotEmpty()) for (i in lat.indices) for (bk in blocks) {
            if (kotlin.math.abs(lat[i] - bk.lat) < 0.002 && kotlin.math.abs(lon[i] - bk.lon) < 0.002 &&
                GeoMath.distanceM(lat[i], lon[i], bk.lat, bk.lon) <= bk.radiusM) { blockedNode[i] = true; break }
        }
        blockedNode[a] = false; blockedNode[b] = false // never block where help starts or where the person is
        val path = astar(a, b, mode, blockedNode) ?: return null
        val pts = ArrayList<Pair<Double, Double>>(path.size + 2)
        pts += fromLat to fromLon
        path.forEach { pts += lat[it] to lon[it] }
        pts += toLat to toLon
        var meters = 0.0
        for (i in 1 until pts.size) meters += GeoMath.distanceM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
        val roadSeconds = seconds(path, mode)
        val endsSeconds = (GeoMath.distanceM(fromLat, fromLon, lat[a], lon[a]) + GeoMath.distanceM(lat[b], lon[b], toLat, toLon)) /
            (mode.kmh.getValue("minor_road").coerceAtMost(20.0) / 3.6)
        val minutes = kotlin.math.ceil((roadSeconds + endsSeconds) / 60.0).toInt().coerceAtLeast(1)
        // Blockages the plain fastest route would have gone through: what this route went around.
        val avoided = if (blocks.isEmpty()) 0 else {
            val plain = astar(a, b, mode, BooleanArray(nodes))
            blocks.count { bk -> plain != null && plain.any { GeoMath.distanceM(lat[it], lon[it], bk.lat, bk.lon) <= bk.radiusM } }
        }
        return Route(pts, meters, minutes, avoided)
    }

    private fun speedMs(kind: Byte, mode: RoadMode): Double? = mode.kmh[KINDS[kind.toInt()]]?.let { it / 3.6 }

    private fun seconds(path: List<Int>, mode: RoadMode): Double {
        var s = 0.0
        for (i in 1 until path.size) {
            val u = path[i - 1]; val v = path[i]
            for (e in edgeStart[u] until edgeStart[u + 1]) if (edgeTo[e] == v) { s += edgeM[e] / (speedMs(edgeKind[e], mode) ?: 1.0); break }
        }
        return s
    }

    private fun astar(a: Int, b: Int, mode: RoadMode, blocked: BooleanArray): List<Int>? {
        val vmax = mode.kmh.values.max() / 3.6
        val g = DoubleArray(nodes) { Double.MAX_VALUE }
        val prev = IntArray(nodes) { -1 }
        val done = BooleanArray(nodes)
        val open = PriorityQueue<DoubleArray>(compareBy { it[0] })
        g[a] = 0.0
        open.add(doubleArrayOf(GeoMath.distanceM(lat[a], lon[a], lat[b], lon[b]) / vmax, a.toDouble()))
        while (open.isNotEmpty()) {
            val u = open.poll()[1].toInt()
            if (done[u]) continue
            if (u == b) break
            done[u] = true
            for (e in edgeStart[u] until edgeStart[u + 1]) {
                val v = edgeTo[e]
                if (done[v] || blocked[v]) continue
                val sp = speedMs(edgeKind[e], mode) ?: continue
                val ng = g[u] + edgeM[e] / sp
                if (ng < g[v]) {
                    g[v] = ng; prev[v] = u
                    open.add(doubleArrayOf(ng + GeoMath.distanceM(lat[v], lon[v], lat[b], lon[b]) / vmax, v.toDouble()))
                }
            }
        }
        if (a != b && prev[b] < 0) return null
        val path = ArrayList<Int>()
        var cur = b
        while (cur >= 0) { path += cur; if (cur == a) break; cur = prev[cur] }
        return path.asReversed()
    }

    companion object {
        /** Road kinds in the Protomaps basemap "roads" layer; index = stored byte. */
        val KINDS = listOf("highway", "major_road", "minor_road", "other", "path")
        private const val ZOOM = 15

        /** Builds the graph from a PMTiles archive's bytes, using the zoom-15 tiles inside the archive's bounds. */
        fun fromPmtiles(archive: ByteArray): RoadMap {
            val pm = PmTiles(archive)
            val b = RoadGraphBuilder()
            val (minX, maxY) = tileXY(pm.minLon, pm.minLat, ZOOM)
            val (maxX, minY) = tileXY(pm.maxLon, pm.maxLat, ZOOM)
            for (x in minX..maxX) for (y in minY..maxY) {
                val tile = pm.tile(ZOOM, x, y) ?: continue
                Mvt.roads(tile).forEach { (kind, line) -> b.addLine(kind, line, x, y) }
            }
            return b.build()
        }

        fun tileXY(lon: Double, lat: Double, z: Int): Pair<Int, Int> {
            val n = 1 shl z
            val x = ((lon + 180.0) / 360.0 * n).toInt()
            val r = Math.toRadians(lat)
            val y = ((1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * n).toInt()
            return x to y
        }

        internal fun create(lat: DoubleArray, lon: DoubleArray, start: IntArray, to: IntArray, m: FloatArray, kind: ByteArray) =
            RoadMap(lat, lon, start, to, m, kind)
    }
}

/**
 * Joins road lines from many tiles into one graph. Points are in global zoom-15 pixels (about 0.3 m), so the same point
 * in two tiles is the same node. The map's lines were simplified for drawing, which drops many shared junction points,
 * so junctions are rebuilt: where two roads cross, and where a road ends within [SNAP] pixels (about 6 m) of another.
 */
internal class RoadGraphBuilder {
    private class Seg(val u: Int, val v: Int, val kind: Byte) { val splits = ArrayList<Pair<Double, Int>>() }

    private val index = HashMap<Long, Int>()
    private val gx = ArrayList<Double>(); private val gy = ArrayList<Double>()
    private val segs = ArrayList<Seg>()
    private val extra = ArrayList<Seg>() // connectors from a road end to the road it nearly touches

    private fun node(x: Double, y: Double): Int {
        val key = (Math.round(x) shl 32) or (Math.round(y) and 0xFFFFFFFFL)
        return index.getOrPut(key) { gx += x; gy += y; gx.size - 1 }
    }

    /** [line] = points in tile pixels (0..4096, may go a little outside: the tile buffer). */
    fun addLine(kind: Int, line: List<Pair<Int, Int>>, tileX: Int, tileY: Int) {
        var prev = -1
        for ((px, py) in line) {
            val n = node(tileX.toDouble() * Mvt.EXTENT + px, tileY.toDouble() * Mvt.EXTENT + py)
            if (prev >= 0 && prev != n) segs += Seg(prev, n, kind.toByte())
            prev = n
        }
    }

    private fun connectJunctions() {
        // Grid of segments, cell CELL pixels (about 75 m).
        val grid = HashMap<Long, MutableList<Int>>()
        fun cellKey(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
        segs.forEachIndexed { i, sg ->
            val x0 = minOf(gx[sg.u], gx[sg.v]) - SNAP; val x1 = maxOf(gx[sg.u], gx[sg.v]) + SNAP
            val y0 = minOf(gy[sg.u], gy[sg.v]) - SNAP; val y1 = maxOf(gy[sg.u], gy[sg.v]) + SNAP
            for (cx in (x0 / CELL).toInt()..(x1 / CELL).toInt()) for (cy in (y0 / CELL).toInt()..(y1 / CELL).toInt())
                grid.getOrPut(cellKey(cx, cy)) { ArrayList() } += i
        }
        // 1. Crossings: two segments that properly intersect get a shared node there.
        val seen = HashSet<Long>()
        for (cell in grid.values) for (a in cell.indices) for (b in a + 1 until cell.size) {
            val i = cell[a]; val j = cell[b]
            val pairKey = (minOf(i, j).toLong() shl 32) or maxOf(i, j).toLong()
            if (!seen.add(pairKey)) continue
            val s1 = segs[i]; val s2 = segs[j]
            if (s1.u == s2.u || s1.u == s2.v || s1.v == s2.u || s1.v == s2.v) continue
            val t = intersect(s1, s2) ?: continue
            val x = gx[s1.u] + (gx[s1.v] - gx[s1.u]) * t.first; val y = gy[s1.u] + (gy[s1.v] - gy[s1.u]) * t.first
            val n = node(x, y)
            s1.splits += t.first to n; s2.splits += t.second to n
        }
        // 2. T-junctions: a road end (a node used by one segment only) near another segment joins it.
        val degree = IntArray(gx.size)
        segs.forEach { degree[it.u]++; degree[it.v]++ }
        for (n in degree.indices) {
            if (degree[n] != 1) continue
            val x = gx[n]; val y = gy[n]
            val near = grid[cellKey((x / CELL).toInt(), (y / CELL).toInt())] ?: continue
            var best = -1; var bestD = SNAP; var bestT = 0.0
            for (i in near) {
                val sg = segs[i]
                if (sg.u == n || sg.v == n) continue
                val dx = gx[sg.v] - gx[sg.u]; val dy = gy[sg.v] - gy[sg.u]
                val len2 = dx * dx + dy * dy
                if (len2 == 0.0) continue
                val t = (((x - gx[sg.u]) * dx + (y - gy[sg.u]) * dy) / len2).coerceIn(0.0, 1.0)
                val px = gx[sg.u] + dx * t; val py = gy[sg.u] + dy * t
                val d = kotlin.math.hypot(x - px, y - py)
                if (d < bestD) { bestD = d; best = i; bestT = t }
            }
            if (best < 0) continue
            val sg = segs[best]
            val p = node(gx[sg.u] + (gx[sg.v] - gx[sg.u]) * bestT, gy[sg.u] + (gy[sg.v] - gy[sg.u]) * bestT)
            if (p != sg.u && p != sg.v) sg.splits += bestT to p
            if (p != n) extra += Seg(n, p, sg.kind)
        }
    }

    /** Parameters (t on s1, t on s2) where the segments cross, strictly inside both; null if they do not. */
    private fun intersect(s1: Seg, s2: Seg): Pair<Double, Double>? {
        val x1 = gx[s1.u]; val y1 = gy[s1.u]; val x2 = gx[s1.v]; val y2 = gy[s1.v]
        val x3 = gx[s2.u]; val y3 = gy[s2.u]; val x4 = gx[s2.v]; val y4 = gy[s2.v]
        val den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (kotlin.math.abs(den) < 1e-9) return null
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den
        val u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / den
        return if (t > 1e-6 && t < 1 - 1e-6 && u > 1e-6 && u < 1 - 1e-6) t to u else null
    }

    fun build(): RoadMap {
        connectJunctions()
        val from = ArrayList<Int>(); val to = ArrayList<Int>(); val kinds = ArrayList<Byte>()
        fun edge(a: Int, b: Int, k: Byte) { if (a == b) return; from += a; to += b; kinds += k; from += b; to += a; kinds += k }
        for (sg in segs) {
            var prev = sg.u
            for ((_, n) in sg.splits.sortedBy { it.first }) { edge(prev, n, sg.kind); prev = n }
            edge(prev, sg.v, sg.kind)
        }
        extra.forEach { edge(it.u, it.v, it.kind) }
        // Both directions are stored: one-way streets are not known offline.
        val nodeCount = gx.size
        val n15 = 1L shl 15; val extent = Mvt.EXTENT.toDouble()
        val la = DoubleArray(nodeCount) { Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * (gy[it] / extent / n15))))) }
        val lo = DoubleArray(nodeCount) { gx[it] / extent / n15 * 360.0 - 180.0 }
        val count = IntArray(nodeCount + 1)
        from.forEach { count[it + 1]++ }
        for (i in 1..nodeCount) count[i] += count[i - 1]
        val start = count.copyOf(); val fill = count.copyOf()
        val edgeTo = IntArray(to.size); val edgeM = FloatArray(to.size); val edgeKind = ByteArray(to.size)
        for (i in from.indices) {
            val u = from[i]; val v = to[i]; val slot = fill[u]++
            edgeTo[slot] = v; edgeKind[slot] = kinds[i]
            edgeM[slot] = GeoMath.distanceM(la[u], lo[u], la[v], lo[v]).toFloat()
        }
        return RoadMap.create(la, lo, start, edgeTo, edgeM, edgeKind)
    }

    private companion object {
        const val SNAP = 20.0   // pixels at zoom 15, about 6 m
        const val CELL = 256.0  // pixels, about 75 m
    }
}

/** PMTiles v3 reader: header, directories (gzip, varints), and gzip-compressed tiles by zoom/x/y. */
internal class PmTiles(private val b: ByteArray) {
    private fun u64(at: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF); return v }
    private fun i32(at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)

    private val rootOff: Long; private val rootLen: Long; private val leafOff: Long; private val dataOff: Long
    private val compression: Int
    val minLon: Double; val minLat: Double; val maxLon: Double; val maxLat: Double
    private val entries = HashMap<Long, LongArray>() // tile id -> [offset, length] (within tile data)

    init {
        require(String(b, 0, 7, Charsets.US_ASCII) == "PMTiles" && b[7].toInt() == 3) { "not a PMTiles v3 archive" }
        rootOff = u64(8); rootLen = u64(16); leafOff = u64(40); dataOff = u64(56)
        compression = b[98].toInt()
        minLon = i32(102) / 1e7; minLat = i32(106) / 1e7; maxLon = i32(110) / 1e7; maxLat = i32(114) / 1e7
        readDirectory(rootOff, rootLen)
    }

    private fun gunzip(off: Long, len: Long): ByteArray =
        GZIPInputStream(ByteArrayInputStream(b, off.toInt(), len.toInt())).use { it.readBytes() }

    private fun readDirectory(off: Long, len: Long) {
        val d = gunzip(off, len)
        var i = 0
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val c = d[i++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl s); if (c < 0x80) return r; s += 7 } }
        val n = varint().toInt()
        val ids = LongArray(n); var last = 0L
        for (k in 0 until n) { last += varint(); ids[k] = last }
        val runs = LongArray(n) { varint() }
        val lens = LongArray(n) { varint() }
        val offs = LongArray(n)
        for (k in 0 until n) { val v = varint(); offs[k] = if (v == 0L && k > 0) offs[k - 1] + lens[k - 1] else v - 1 }
        for (k in 0 until n) {
            if (runs[k] == 0L) readDirectory(leafOff + offs[k], lens[k]) // a leaf directory
            else for (r in 0 until runs[k]) entries[ids[k] + r] = longArrayOf(offs[k], lens[k])
        }
    }

    /** The decompressed tile, or null if the archive has none at that position. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val e = entries[tileId(z, x, y)] ?: return null
        return if (compression == 2) gunzip(dataOff + e[0], e[1]) else b.copyOfRange((dataOff + e[0]).toInt(), (dataOff + e[0] + e[1]).toInt())
    }

    companion object {
        /** PMTiles tile id: all tiles of lower zooms, then the position on the zoom's Hilbert curve. */
        fun tileId(z: Int, x: Int, y: Int): Long {
            var acc = 0L
            for (t in 0 until z) acc += 1L shl (2 * t)
            val n = 1 shl z
            var tx = x; var ty = y; var d = 0L
            var s = n / 2
            while (s > 0) {
                val rx = if (tx and s > 0) 1 else 0
                val ry = if (ty and s > 0) 1 else 0
                d += s.toLong() * s * ((3 * rx) xor ry)
                if (ry == 0) {
                    if (rx == 1) { tx = n - 1 - tx; ty = n - 1 - ty }
                    val t = tx; tx = ty; ty = t
                }
                s /= 2
            }
            return acc + d
        }
    }
}

/** Mapbox Vector Tile decoding, just enough for line features of the "roads" layer and their "kind". */
internal object Mvt {
    const val EXTENT = 4096

    private class Reader(val b: ByteArray, var i: Int, val end: Int) {
        fun more() = i < end
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl s); if (c < 0x80) return r; s += 7 } }
        fun sub(): Reader { val len = varint().toInt(); val r = Reader(b, i, i + len); i += len; return r }
        fun skip(wire: Int) { when (wire) { 0 -> varint(); 1 -> i += 8; 2 -> i += varint().toInt(); 5 -> i += 4 } }
        fun string(): String { val len = varint().toInt(); val s = String(b, i, len, Charsets.UTF_8); i += len; return s }
    }

    /** (kind index in [RoadMap.KINDS], line points in tile pixels) for each line of the roads layer. */
    fun roads(tile: ByteArray): List<Pair<Int, List<Pair<Int, Int>>>> {
        val out = ArrayList<Pair<Int, List<Pair<Int, Int>>>>()
        val t = Reader(tile, 0, tile.size)
        while (t.more()) {
            val key = t.varint().toInt()
            if (key shr 3 != 3 || key and 7 != 2) { t.skip(key and 7); continue }
            val layer = t.sub()
            var name = ""; val keys = ArrayList<String>(); val values = ArrayList<String?>(); val features = ArrayList<Reader>()
            var extent = EXTENT
            while (layer.more()) {
                val k = layer.varint().toInt()
                when (k shr 3) {
                    1 -> name = layer.string()
                    2 -> features += layer.sub()
                    3 -> keys += layer.string()
                    4 -> { val v = layer.sub(); var s: String? = null; while (v.more()) { val vk = v.varint().toInt(); if (vk shr 3 == 1) s = v.string() else v.skip(vk and 7) }; values += s }
                    5 -> extent = layer.varint().toInt()
                    else -> layer.skip(k and 7)
                }
            }
            if (name != "roads") continue
            val kindKey = keys.indexOf("kind")
            for (f in features) {
                var type = 0; var tags = IntArray(0); var geom = IntArray(0)
                while (f.more()) {
                    val k = f.varint().toInt()
                    when (k shr 3) {
                        2 -> { val p = f.sub(); val l = ArrayList<Int>(); while (p.more()) l += p.varint().toInt(); tags = l.toIntArray() }
                        3 -> type = f.varint().toInt()
                        4 -> { val p = f.sub(); val l = ArrayList<Int>(); while (p.more()) l += p.varint().toInt(); geom = l.toIntArray() }
                        else -> f.skip(k and 7)
                    }
                }
                if (type != 2) continue // LINESTRING only
                var kind: String? = null
                var ti = 0
                while (ti + 1 < tags.size) { if (tags[ti] == kindKey) kind = values.getOrNull(tags[ti + 1]); ti += 2 }
                val kindIndex = RoadMap.KINDS.indexOf(kind).takeIf { it >= 0 } ?: continue
                val scale = EXTENT.toDouble() / extent
                var x = 0; var y = 0; var gi = 0
                var line = ArrayList<Pair<Int, Int>>()
                while (gi < geom.size) {
                    val cmd = geom[gi] and 7; val count = geom[gi] ushr 3; gi++
                    repeat(count) {
                        if (cmd == 1 || cmd == 2) {
                            x += zigzag(geom[gi++]); y += zigzag(geom[gi++])
                            if (cmd == 1 && line.size > 1) { out += kindIndex to line; line = ArrayList() } else if (cmd == 1) line.clear()
                            line += (x * scale).toInt() to (y * scale).toInt()
                        }
                    }
                }
                if (line.size > 1) out += kindIndex to line
            }
        }
        return out
    }

    private fun zigzag(n: Int): Int = (n ushr 1) xor -(n and 1)
}
