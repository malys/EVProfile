package com.evsuite.profile.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an APK file from a URL to a local file.
 * Handles HTTP/HTTPS redirects (GitHub CDN).
 * The progress (0–100) is reported via [onProgress] on the calling thread.
 */
object ApkDownloader {

    /** Size ceiling: the APK is ~10 MB, beyond that we refuse rather than fill the disk. */
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    suspend fun download(
        url: String,
        destFile: File,
        onProgress: suspend (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Delete any previous file
            if (destFile.exists()) destFile.delete()

            if (!ApkUrlPolicy.isAllowedLogged(url, "Download")) return@withContext false

            val conn = openConnection(url) ?: return@withContext false
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext false
            }

            val totalBytes = conn.contentLengthLong
            if (totalBytes > MAX_APK_BYTES) {
                conn.disconnect()
                return@withContext false
            }
            var downloaded = 0L

            conn.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8_192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        // The Content-Length is declarative: we also cut on the real bytes.
                        if (downloaded > MAX_APK_BYTES) {
                            destFile.delete()
                            return@withContext false
                        }
                        if (totalBytes > 0) {
                            val pct = (downloaded * 100L / totalBytes).toInt()
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }

            conn.disconnect()
            destFile.exists() && destFile.length() > 0

        } catch (_: Exception) {
            destFile.delete()
            false
        }
    }

    /**
     * Opens a connection by manually following redirects (GitHub → CDN).
     * Each jump is revalidated: otherwise a `Location:` in http is enough to do
     * accept downgrade https → http or arbitrary host.
     * Returns null if a jump falls outside the list of allowed origins.
     */
    private fun openConnection(urlStr: String, depth: Int = 0): HttpURLConnection? {
        if (!ApkUrlPolicy.isAllowedLogged(urlStr, "Redirection")) return null
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            // Redirections are followed by hand to be able to control each jump.
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "EVProfile-Android")
            connectTimeout = 15_000
            readTimeout    = 120_000
            connect()
        }
        val code = conn.responseCode
        if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && depth < 5) {
            val location = conn.getHeaderField("Location") ?: return conn
            conn.disconnect()
            return openConnection(location, depth + 1)
        }
        return conn
    }
}
