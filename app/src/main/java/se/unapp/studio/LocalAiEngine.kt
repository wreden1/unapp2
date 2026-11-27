package se.unapp.studio

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal const val CHAT_HISTORY_MESSAGE_LIMIT = 24

class LocalAiEngine(context: Context) {
    private val inference: InferenceEngine = AiChat.getInferenceEngine(context.applicationContext)
    private val mutex = Mutex()
    @Volatile private var loaded = false

    val activeBackend: String
        get() = "llama.cpp (ARM CPU acceleration when available)"

    suspend fun loadModel(
        modelFile: File,
        characterInstruction: String,
        initialHistory: List<Pair<Boolean, String>> = emptyList()
    ) {
        mutex.withLock {
            require(modelFile.isFile) { "AI-modellen hittades inte: \${modelFile.absolutePath}" }
            val readyState = inference.state.first {
                it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
            }
            if (readyState is InferenceEngine.State.Error) throw readyState.exception
            when (inference.state.value) {
                is InferenceEngine.State.ModelReady -> inference.cleanUp()
                is InferenceEngine.State.Error -> inference.cleanUp()
                else -> Unit
            }
            inference.loadModel(modelFile.absolutePath)
            inference.setSystemPrompt(characterInstruction)
            inference.setConversationHistory(initialHistory.takeLast(CHAT_HISTORY_MESSAGE_LIMIT))
            loaded = true
        }
    }

    suspend fun ask(prompt: String, recentHistory: List<Pair<Boolean, String>> = emptyList()): String {
        require(prompt.isNotBlank()) { "Skriv en fråga först." }
        return mutex.withLock {
            check(loaded && inference.state.value is InferenceEngine.State.ModelReady) {
                "AI-modellen är inte laddad ännu."
            }
            // Only Testchatt supplies a selected history; other callers keep existing behavior.
            if (recentHistory.isNotEmpty()) {
                inference.setConversationHistory(recentHistory.takeLast(CHAT_HISTORY_MESSAGE_LIMIT))
            }
            val response = StringBuilder()
            inference.sendUserPrompt("$prompt\n/no_think", predictLength = 1536).collect { token ->
                response.append(token)
            }
            val visibleReply = response.toString()
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .substringBefore("<think>")
                .replace("</think>", "")
                .trim()
            visibleReply.ifBlank { "Jag fick inget tydligt svar den här gången. Försök igen." }
        }
    }

    suspend fun close() {
        mutex.withLock {
            if (loaded && inference.state.value is InferenceEngine.State.ModelReady) inference.cleanUp()
            loaded = false
        }
    }

    fun isLoaded(): Boolean =
        loaded && inference.state.value is InferenceEngine.State.ModelReady
}
