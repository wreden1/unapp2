package se.unapp.studio

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int?,
    val stage: String = "Nedladdning"
)

object ModelDownloader {

    const val MODEL_NAME = "Qwen3-1.7B-Q4_K_M.gguf"

    private const val MODEL_URL =
        "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/daeb8e2d528a760970442092f6bf1e55c3b659eb/Qwen3-1.7B-Q4_K_M.gguf"

    private const val EXPECTED_SHA256 =
        "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"

    private const val LEGACY_MODEL_NAME =
        "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"

    private const val EXPECTED_MODEL_BYTES = 1_282_000_000L
    private const val FREE_SPACE_MARGIN_BYTES = 170_000_000L

    fun modelFile(context: Context): File {
        val directory = File(context.filesDir, "models")
        return File(directory, MODEL_NAME)
    }

    suspend fun download(
        context: Context,
        onProgress: (Int) -> Unit = {},
        onDetailedProgress: (ModelDownloadProgress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "models")
        if (!directory.exists() && !directory.mkdirs()) {
            error("Kunde inte skapa modellmappen: ${directory.absolutePath}")
        }

        val destination = File(directory, MODEL_NAME)
        val temporary = File(directory, "$MODEL_NAME.part")

        if (destination.isFile) {
            onDetailedProgress(ModelDownloadProgress(destination.length(), destination.length(), null, "Kontrollerar befintlig AI-modell…"))
            if (sha256(destination) == EXPECTED_SHA256) {
                onProgress(100)
                onDetailedProgress(ModelDownloadProgress(destination.length(), destination.length(), 100, "AI-modellen är redan installerad"))
                return@withContext destination
            }
            check(destination.delete()) {
                "Den befintliga AI-modellen är skadad och kunde inte tas bort. Kontrollera ledigt lagringsutrymme."
            }
        }

        File(directory, LEGACY_MODEL_NAME).delete()

        var downloaded = temporary.length()
        val requiredFree = (EXPECTED_MODEL_BYTES - downloaded).coerceAtLeast(0L) + FREE_SPACE_MARGIN_BYTES
        val available = StatFs(directory.absolutePath).availableBytes
        check(available >= requiredFree) {
            "För lite ledigt utrymme. Det behövs cirka ${requiredFree / 1_000_000_000.0} GB till för nedladdningen. Frigör utrymme och försök igen."
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 45_000
                readTimeout = 180_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "UnApp-Android/1.0")
                setRequestProperty("Accept-Encoding", "identity")
                if (downloaded > 0L) {
                    setRequestProperty("Range", "bytes=$downloaded-")
                }
            }

            connection.connect()
            var response = connection.responseCode

            // Some mirrors ignore Range requests. In that case restart cleanly
            // instead of appending a full response to the partial file.
            if (downloaded > 0L && response == HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                temporary.delete()
                downloaded = 0L
                connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 45_000
                    readTimeout = 180_000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("User-Agent", "UnApp-Android/1.0")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                connection.connect()
                response = connection.responseCode
            }

            val expectedResponse = if (downloaded > 0L) HttpURLConnection.HTTP_PARTIAL else HttpURLConnection.HTTP_OK
            check(response == expectedResponse) {
                val detail = runCatching { connection.responseMessage }.getOrNull()
                "Nedladdningen misslyckades: HTTP $response ${detail ?: ""}. Kontrollera internetanslutningen och försök igen."
            }

            val contentRange = connection.getHeaderField("Content-Range")
            if (downloaded > 0L) {
                val rangeStart = Regex("^bytes (\\d+)-").find(contentRange.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()
                check(rangeStart == downloaded) {
                    "Servern kunde inte fortsätta nedladdningen från rätt position. Försök igen så startar nedladdningen om."
                }
            }

            val responseLength = connection.contentLengthLong
            val rangeTotal = Regex("/(\\d+)$").find(contentRange.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()
            val total = rangeTotal ?: if (responseLength > 0L) responseLength + downloaded else -1L
            var lastReportedBytes = downloaded
            var lastReportedPercent = -1
            onDetailedProgress(
                ModelDownloadProgress(
                    downloaded,
                    total,
                    if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null,
                    if (downloaded > 0L) "Fortsätter nedladdningen där den avbröts" else "Ansluten. Laddar ner AI-modellen…"
                )
            )

            val append = downloaded > 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary, append).buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null
                        if (percent != null) onProgress(percent)
                        if ((percent != null && percent != lastReportedPercent) ||
                            downloaded - lastReportedBytes >= 4L * 1024L * 1024L
                        ) {
                            onDetailedProgress(ModelDownloadProgress(downloaded, total, percent, "Laddar ner AI-modellen…"))
                            lastReportedBytes = downloaded
                            lastReportedPercent = percent ?: -1
                        }
                    }
                }
            }

            check(total <= 0L || downloaded == total) {
                "Nedladdningen blev ofullständig (${downloaded / 1_000_000} av ${total / 1_000_000} MB). Tryck igen för att fortsätta."
            }

            onDetailedProgress(ModelDownloadProgress(downloaded, total, 100, "Kontrollerar AI-modellens fil…"))
            val actualSha256 = sha256(temporary)
            if (actualSha256 != EXPECTED_SHA256) {
                temporary.delete()
                error("AI-modellens kontrollsumma stämmer inte. Filen var ofullständig eller skadad och har tagits bort. Försök ladda ner igen.")
            }

            onDetailedProgress(ModelDownloadProgress(downloaded, total, 100, "Installerar AI-modellen…"))
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) {
                "Nedladdningen lyckades men AI-modellen kunde inte sparas. Kontrollera telefonens lagringsutrymme."
            }

            onProgress(100)
            onDetailedProgress(ModelDownloadProgress(destination.length(), destination.length(), 100, "AI-modellen är installerad och klar"))
            destination
        } catch (error: Exception) {
            // Keep the .part file after network interruptions so the next
            // attempt can resume instead of downloading the entire model again.
            throw IllegalStateException(
                error.message?.takeIf { it.isNotBlank() }
                    ?: "Nedladdningen av AI-modellen avbröts. Kontrollera internetanslutningen och försök igen.",
                error
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
