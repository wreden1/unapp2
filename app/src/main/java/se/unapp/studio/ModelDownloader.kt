package se.unapp.studio

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ModelDownloader {

    const val MODEL_NAME = "Qwen3-1.7B-Q4_K_M.gguf"

    private const val MODEL_URL =
        "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/daeb8e2d528a760970442092f6bf1e55c3b659eb/Qwen3-1.7B-Q4_K_M.gguf"

    private const val EXPECTED_SHA256 =
        "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"

    private const val LEGACY_MODEL_NAME =
        "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"

    fun modelFile(context: Context): File {
        val directory = File(context.filesDir, "models")
        return File(directory, MODEL_NAME)
    }

    suspend fun download(
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "models")
        if (!directory.exists() && !directory.mkdirs()) {
            error("Kunde inte skapa modellmappen.")
        }

        val destination = File(directory, MODEL_NAME)
        val temporary = File(directory, "$MODEL_NAME.part")

        if (destination.isFile && sha256(destination) == EXPECTED_SHA256) {
            onProgress(100)
            return@withContext destination
        }

        // The old LiteRT model is no longer usable by llama.cpp. Remove it to
        // reclaim space before downloading the replacement GGUF model.
        File(directory, LEGACY_MODEL_NAME).delete()

        val requiredBytes = 1_450_000_000L
        val availableBytes = StatFs(directory.absolutePath).availableBytes
        check(availableBytes >= requiredBytes) {
            "Det behövs cirka 1,45 GB ledigt utrymme för den nya Qwen3-modellen."
        }

        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "UnApp-Android")
            connection.connect()

            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Nedladdningen misslyckades: HTTP ${connection.responseCode}"
            }

            val total = connection.contentLengthLong
            var downloaded = 0L

            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) {
                            onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }

            check(total <= 0 || downloaded == total) {
                "Nedladdningen blev ofullständig."
            }

            val actualSha256 = sha256(temporary)
            check(actualSha256 == EXPECTED_SHA256) {
                "Modellfilens kontrollsumma stämmer inte. SHA-256: $actualSha256"
            }

            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) {
                "Kunde inte spara den nya GGUF-modellen."
            }

            onProgress(100)
            destination
        } catch (error: Throwable) {
            temporary.delete()
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
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
