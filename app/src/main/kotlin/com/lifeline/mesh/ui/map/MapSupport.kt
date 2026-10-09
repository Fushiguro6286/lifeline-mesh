package com.lifeline.mesh.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The offline map archive. MapLibre Android cannot read PMTiles from APK assets (`pmtiles://asset://` is documented as
 * unsupported), so the bundled `assets/map/venue.pmtiles` is copied once into app storage and opened as a file.
 */
object MapAssets {
    private const val ASSET = "map/venue.pmtiles"
    private const val TAG = "MapAssets"

    /** Venue box the archive was cut for (central Bengaluru): west, south, east, north. */
    const val WEST = 77.55
    const val SOUTH = 12.93
    const val EAST = 77.65
    const val NORTH = 13.01
    const val CENTER_LAT = (SOUTH + NORTH) / 2
    const val CENTER_LON = (WEST + EAST) / 2

    fun contains(lat: Double, lon: Double) = lat in SOUTH..NORTH && lon in WEST..EAST

    /** The archive as a file, copying it from assets when missing or changed. Null if the app has no map file. */
    suspend fun prepare(context: Context): File? = withContext(Dispatchers.IO) {
        val out = File(context.filesDir, ASSET)
        try {
            val assetLength = context.assets.openFd(ASSET).use { it.length }
            if (out.exists() && out.length() == assetLength) return@withContext out
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".tmp")
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
            out
        } catch (e: IOException) {
            Log.w(TAG, "no offline map: ${e.message}")
            null
        }
    }
}

/** Fills the `{{token}}` placeholders in assets/map/style.json for the current theme. */
object MapStyle {
    private val night = mapOf(
        "bg" to "#0B1620", "land" to "#0F1D27", "green" to "#12261F", "civic" to "#18242E", "water" to "#0A3342",
        "building" to "#1A2E3D", "roadMinor" to "#22394A", "roadMajor" to "#33506A", "roadHighway" to "#4A6A80",
    )
    private val day = mapOf(
        "bg" to "#EAF0F2", "land" to "#F4F7F8", "green" to "#DCEBDD", "civic" to "#EFE8E8", "water" to "#BCDCE8",
        "building" to "#E2E8EB", "roadMinor" to "#D3DDE1", "roadMajor" to "#F0D9A2", "roadHighway" to "#E6BC70",
    )

    fun json(context: Context, archive: File, isNight: Boolean): String {
        var s = context.assets.open("map/style.json").bufferedReader().use { it.readText() }
        s = s.replace("{{PMTILES}}", "pmtiles://file://${archive.absolutePath}")
        for ((k, v) in if (isNight) night else day) s = s.replace("{{$k}}", v)
        return s
    }
}

/** Marker images drawn on the phone (no sprite sheet, no glyphs needed). */
object MarkerBitmaps {
    /** Alert: circle sized by people (26–56 dp), severity color, rank number inside. */
    fun alert(rank: Int, fill: Int, text: Int, ring: Int, people: Int, density: Float): Bitmap {
        val sizeDp = 26f + people.coerceIn(0, 20) * 1.5f
        val size = (sizeDp * density).toInt()
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.color = ring
        c.drawCircle(r, r, r, p)
        p.color = fill
        c.drawCircle(r, r, r - 2.5f * density, p)
        p.color = text
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = (if (rank < 10) 15f else 13f) * density
        c.drawText(rank.toString(), r, r - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    /** Help point: rounded square with an icon. */
    fun square(context: Context, iconRes: Int, fill: Int, iconTint: Int, ring: Int, density: Float): Bitmap {
        val size = (30 * density).toInt()
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rr = 6 * density
        p.color = ring
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), rr, rr, p)
        p.color = fill
        val inset = 2f * density
        c.drawRoundRect(RectF(inset, inset, size - inset, size - inset), rr, rr, p)
        ContextCompat.getDrawable(context, iconRes)?.mutate()?.let { d ->
            val pad = (6 * density).toInt()
            d.setBounds(pad, pad, size - pad, size - pad)
            d.setTint(iconTint)
            d.draw(c)
        }
        return bmp
    }

    /** Me: a dot with a ring (no compass heading, so no arrow pretending to know my direction). */
    fun me(fill: Int, ring: Int, density: Float): Bitmap {
        val size = (22 * density).toInt()
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.color = ring
        c.drawCircle(r, r, r, p)
        p.color = fill
        c.drawCircle(r, r, r - 4 * density, p)
        return bmp
    }
}

