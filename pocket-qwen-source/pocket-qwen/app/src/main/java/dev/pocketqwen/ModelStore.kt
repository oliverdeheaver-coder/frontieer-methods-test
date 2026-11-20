package dev.pocketqwen

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class CatalogModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val fileName: String,
    val url: String,
    val approxBytes: Long,
)

object ModelCatalog {
    val models = listOf(
        CatalogModel(
            id = "qwen2.5-1.5b-q4km",
            title = "Qwen2.5 1.5B Instruct",
            subtitle = "Q4_K_M · ~1.1 GB · fast, runs on most phones (4 GB+ RAM)",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            approxBytes = 1_120_000_000L,
        ),
        CatalogModel(
            id = "qwen2.5-3b-q4km",
            title = "Qwen2.5 3B Instruct",
            subtitle = "Q4_K_M · ~2.1 GB · smarter, needs 6 GB+ RAM",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            approxBytes = 2_100_000_000L,
        ),
    )
}

/** Stores GGUF files in app-private storage (no storage permission needed). */
class ModelStore(private val context: Context) {

    val dir: File = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(model: CatalogModel) = File(dir, model.fileName)

    fun installed(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()

    fun delete(file: File) {
        file.delete()
        File(file.path + ".part").delete()
    }

    /**
     * Downloads with HTTP Range resume: if interrupted, calling again continues from the
     * `.part` file instead of starting over.
     */
    suspend fun download(model: CatalogModel, onProgress: (downloaded: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = fileFor(model)
            if (target.exists()) return@withContext target
            val part = File(target.path + ".part")

            var existing = if (part.exists()) part.length() else 0L
            val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "PocketQwen/0.1")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    existing = 0 // server ignored Range: restart
                } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("Download failed: HTTP $code")
                }
                val remaining = conn.contentLengthLong
                val total = if (remaining > 0) existing + remaining else model.approxBytes

                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(existing)
                    out.seek(existing)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        var done = existing
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 1_000_000) {
                                lastReport = done
                                onProgress(done, total)
                            }
                        }
                        onProgress(done, total)
                    }
                }
            } finally {
                conn.disconnect()
            }
            checkGguf(part)
            if (!part.renameTo(target)) throw IOException("Could not finalize ${target.name}")
            target
        }

    /** Copies a user-picked .gguf (e.g. from Downloads) into app storage. */
    suspend fun import(uri: Uri, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "imported.gguf"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0) size = c.getLong(si)
            }
        }
        if (!name.endsWith(".gguf", ignoreCase = true)) name += ".gguf"
        val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val tmp = File(target.path + ".part")
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var last = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 4_000_000) { last = done; onProgress(done, size) }
                }
            }
        } ?: throw IOException("Cannot open the selected file")
        try {
            checkGguf(tmp)
        } catch (e: IOException) {
            tmp.delete(); throw e
        }
        tmp.renameTo(target)
        target
    }

    private fun checkGguf(f: File) {
        val magic = ByteArray(4)
        RandomAccessFile(f, "r").use { it.readFully(magic) }
        if (String(magic, Charsets.US_ASCII) != "GGUF") {
            f.delete()
            throw IOException("Not a GGUF file")
        }
    }
}
