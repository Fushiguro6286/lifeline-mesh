package com.lifeline.mesh.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Getting model files onto the phone (docs/MODEL_SELECTOR.md section 6): scan the models folders, import a file the
 * user picks (any model, including gated ones downloaded on a laptop), or download an ungated model on Wi-Fi only.
 * Every new file is checked for exact size and, when the catalog knows it, sha256.
 */
class ModelInstaller(private val context: Context) {
    /** `/sdcard/Android/data/com.lifeline.mesh/files/models/` (the adb push target). */
    val externalDir: File get() = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).also { it.mkdirs() }
    private val internalDir: File get() = File(context.filesDir, "models").also { it.mkdirs() }

    /** Installed = a file with the catalog's name and exact size in either folder. */
    fun scan(catalog: List<ModelSpec>): Map<String, File> = catalog.mapNotNull { m ->
        listOf(File(externalDir, m.fileName), File(internalDir, m.fileName)).firstOrNull { it.isFile && it.length() == m.sizeBytes }
            ?.let { m.id to it }
    }.toMap()

    fun remove(model: ModelSpec) {
        File(externalDir, model.fileName).delete()
        File(internalDir, model.fileName).delete()
    }

    /** Copies a picked file into the models folder. Matched to the catalog by file name, else by exact size. */
    suspend fun import(uri: Uri, catalog: List<ModelSpec>, progress: (Long, Long) -> Unit): Result<ModelSpec> = withContext(Dispatchers.IO) {
        runCatching {
            val (name, size) = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
            } ?: (null to -1L)
            val model = catalog.firstOrNull { it.fileName == name } ?: catalog.firstOrNull { it.sizeBytes == size }
                ?: error("Not a model this app knows (${name ?: "unknown file"})")
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")
            copyVerified(input, model, progress)
            model
        }
    }

    /** True on an unmetered network (Wi-Fi). Downloads refuse otherwise. */
    fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Resumable HTTP download into the models folder (ungated models only, Wi-Fi only). */
    suspend fun download(model: ModelSpec, progress: (Long, Long) -> Unit): Result<ModelSpec> = withContext(Dispatchers.IO) {
        runCatching {
            val url = model.downloadUrl ?: error("${model.name} cannot be downloaded in the app (gated); import it instead")
            if (!onWifi()) error("Connect to Wi-Fi first. Downloads are Wi-Fi only.")
            val part = File(externalDir, model.fileName + ".part")
            var have = if (part.exists()) part.length() else 0L
            var conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code == 200 && have > 0) have = 0 // server ignored the range: start again
            if (code !in listOf(200, 206)) error("Download failed (HTTP $code)")
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        progress(have, model.sizeBytes)
                    }
                }
            }
            conn.disconnect()
            if (part.length() != model.sizeBytes) error("Download incomplete (${part.length()} of ${model.sizeBytes} bytes). Try again to resume.")
            model.sha256?.let { want ->
                val got = sha256(part.inputStream())
                if (!got.equals(want, ignoreCase = true)) { part.delete(); error("Checksum mismatch; the file was deleted") }
            }
            val dest = File(externalDir, model.fileName)
            dest.delete()
            if (!part.renameTo(dest)) error("Could not move the downloaded file into place")
            model
        }
    }

    private suspend fun copyVerified(input: InputStream, model: ModelSpec, progress: (Long, Long) -> Unit) {
        val tmp = File(externalDir, model.fileName + ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        input.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    copied += n
                    progress(copied, model.sizeBytes)
                }
            }
        }
        if (copied != model.sizeBytes) { tmp.delete(); error("Wrong size: $copied bytes, expected ${model.sizeBytes}") }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (model.sha256 != null && !got.equals(model.sha256, ignoreCase = true)) { tmp.delete(); error("Checksum mismatch; not installed") }
        val dest = File(externalDir, model.fileName)
        dest.delete()
        if (!tmp.renameTo(dest)) error("Could not move the file into place")
        Log.i("ModelInstaller", "installed ${model.id} sha256=$got")
    }

    private fun sha256(input: InputStream): String {
        val d = MessageDigest.getInstance("SHA-256")
        input.use { val b = ByteArray(1 shl 20); while (true) { val n = it.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
