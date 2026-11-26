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

object OfflineImageModelDownloader {
    private const val MODEL_NAME = "sd-v1-5-fp16.safetensors"
    private const val MODEL_URL =
        "https://huggingface.co/Comfy-Org/stable-diffusion-1.5/resolve/ce8e3e3657a9b767b33fc0171ff91fb04aed1b2f/v1-5-pruned-emaonly-fp16.safetensors"
    private const val EXPECTED_SHA256 =
        "e9476a13728cd75d8279f6ec8bad753a66a1957ca375a1464dc63b37db6e3916"
    const val MODEL_SIZE_LABEL = "cirka 2,13 GB"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "image-models"), MODEL_NAME)

    suspend fun download(context: Context, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val directory = modelFile(context).parentFile!!
            if (!directory.exists() && !directory.mkdirs()) error("Kunde inte skapa modellmappen.")
            val destination = modelFile(context)
            val partial = File(directory, "$MODEL_NAME.part")

            if (destination.isFile && sha256(destination) == EXPECTED_SHA256) {
                onProgress(100)
                return@withContext destination
            }
            if (destination.exists()) destination.delete()

            val available = StatFs(directory.absolutePath).availableBytes
            check(available >= 2_700_000_000L || partial.exists()) {
                "Det behövs minst cirka 2,7 GB ledigt lagringsutrymme för bildmodellen."
            }

            val offset = if (partial.exists()) partial.length() else 0L
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30000
                connection.readTimeout = 180000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "UnApp-Android")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                connection.connect()

                val append = offset > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
                check(connection.responseCode == HttpURLConnection.HTTP_OK || append) {
                    "Modellservern svarade HTTP ${connection.responseCode}."
                }
                val start = if (append) offset else 0L
                val total = if (append) {
                    val range = connection.getHeaderField("Content-Range")
                    range?.substringAfterLast("/")?.toLongOrNull() ?: (start + connection.contentLengthLong)
                } else connection.contentLengthLong

                connection.inputStream.use { input ->
                    FileOutputStream(partial, append).buffered().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                var downloaded = start
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    downloaded += count
                                    if (total > 0) onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 99))
                                }
                    }
                }

                check(total <= 0 || partial.length() == total) {
                    "Nedladdningen är ofullständig. Försök igen så fortsätter den om servern stöder det."
                }
                if (sha256(partial) != EXPECTED_SHA256) {
                    partial.delete()
                    error("Bildmodellens kontrollsumma stämmer inte. Ladda ner modellen igen.")
                }
                check(partial.renameTo(destination)) { "Kunde inte spara bildmodellen." }
                onProgress(100)
                destination
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                throw error
            } finally {
                connection.disconnect()
            }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
