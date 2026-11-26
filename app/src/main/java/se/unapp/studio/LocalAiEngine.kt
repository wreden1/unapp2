package se.unapp.studio

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Offline inference adapter. The native llama.cpp engine is shared by the
 * Android binding; each character chat reloads its own system prompt/history.
 */
class LocalAiEngine(context: Context) {

    private val inference: InferenceEngine = AiChat.getInferenceEngine(context.applicationContext)
    private val mutex = Mutex()

    @Volatile
    private var loaded = false

    val activeBackend: String
        get() = "llama.cpp (ARM CPU acceleration when available)"

    suspend fun loadModel(
        modelFile: File,
        characterInstruction: String,
        initialHistory: List<Pair<Boolean, String>> = emptyList()
    ) {
        mutex.withLock {
            require(modelFile.isFile) {
                "AI-modellen hittades inte: ${modelFile.absolutePath}"
            }

            val readyState = inference.state.first {
                it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
            }
            if (readyState is InferenceEngine.State.Error) {
                throw readyState.exception
            }

            when (inference.state.value) {
                is InferenceEngine.State.ModelReady -> inference.cleanUp()
                is InferenceEngine.State.Error -> inference.cleanUp()
                else -> Unit
            }

            inference.loadModel(modelFile.absolutePath)
            inference.setSystemPrompt(characterInstruction)
            inference.setConversationHistory(initialHistory.takeLast(12))
            loaded = true
        }
    }

    suspend fun ask(
        prompt: String,
        recentHistory: List<Pair<Boolean, String>> = emptyList()
    ): String {
        require(prompt.isNotBlank()) { "Skriv en fråga först." }

        return mutex.withLock {
            check(loaded && inference.state.value is InferenceEngine.State.ModelReady) {
                "AI-modellen är inte laddad ännu."
            }

            val response = StringBuilder()
            inference.sendUserPrompt(prompt, predictLength = 1536).collect { token ->
                response.append(token)
            }
            response.toString().trim().ifBlank {
                "Jag fick inget tydligt svar den här gången. Försök igen."
            }
        }
    }

    suspend fun close() {
        mutex.withLock {
            if (loaded && inference.state.value is InferenceEngine.State.ModelReady) {
                inference.cleanUp()
            }
            loaded = false
        }
    }

    fun isLoaded(): Boolean =
        loaded && inference.state.value is InferenceEngine.State.ModelReady
}
