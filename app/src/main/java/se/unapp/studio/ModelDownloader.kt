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

    const val MODEL_NAME =
        "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"

    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"

    private const val EXPECTED_SHA256 =
        "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b"

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

        if (destination.exists() &&
            sha256(destination) == EXPECTED_SHA256
        ) {
            onProgress(100)
            return@withContext destination
        }

        val requiredBytes = 1_200_000_000L
        val availableBytes = StatFs(directory.absolutePath)
            .availableBytes

        check(availableBytes >= requiredBytes) {
            "Det finns inte tillräckligt med ledigt lagringsutrymme."
        }

        val connection =
            URL(MODEL_URL).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "UnApp-Android"
            )

            connection.connect()

            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Nedladdningen misslyckades: HTTP ${connection.responseCode}"
            }

            val total = connection.contentLengthLong
            var downloaded = 0L

            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        output.write(buffer, 0, count)
                        downloaded += count

                        if (total > 0) {
                            onProgress(
                                ((downloaded * 100) / total)
                                    .toInt()
                                    .coerceIn(0, 100)
                            )
                        }
                    }
                }
            }

            check(total <= 0 || downloaded == total) {
                "Nedladdningen blev ofullständig."
            }

            check(sha256(temporary) == EXPECTED_SHA256) {
                "Modellfilens kontrollsumma stämmer inte."
            }

            if (destination.exists()) {
                destination.delete()
            }

            check(temporary.renameTo(destination)) {
                "Kunde inte spara modellfilen."
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
            val buffer = ByteArray(64 * 1024)

            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }

        return digest.digest().joinToString("") {
            "%02x".format(it)
        }
    }
}