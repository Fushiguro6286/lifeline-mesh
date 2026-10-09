package com.lifeline.mesh.metrics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.lifeline.mesh.data.MetricEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Settings → Export measurements (CLAUDE.md 10): every stored metric as CSV, real and simulated in separate rows. */
object Metrics {
    const val HEADER = "time_utc,name,value,unit,backend,simulated,notes"

    fun csv(rows: List<MetricEntity>): String = buildString {
        appendLine(HEADER)
        val t = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        for (m in rows) {
            appendLine(listOf(t.format(Date(m.timestamp)), m.name, m.value.toString(), m.unit, m.backend ?: "",
                if (m.simulated) "yes" else "no", m.notes ?: "").joinToString(",") { cell(it) })
        }
    }

    /** RFC 4180: quote cells with commas, quotes or line breaks; double the quotes inside. */
    fun cell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Writes files/exports/measurements-….csv and returns a share-sheet intent for it, or null with nothing to export. */
    suspend fun export(context: Context): Pair<Int, Intent>? {
        val db = (context.applicationContext as com.lifeline.mesh.LifelineApp).database
        val rows = db.metrics().all()
        if (rows.isEmpty()) return null
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, "measurements-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".csv")
        file.writeText(csv(rows))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Lifeline Mesh measurements")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return rows.size to Intent.createChooser(send, "Export measurements")
    }
}
