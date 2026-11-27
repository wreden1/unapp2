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
    private val mutex = sharedMutex
    @Volatile private var loaded = false
    private var loadedInstruction: String? = null

    val activeBackend: String
        get() = "llama.cpp (ARM CPU acceleration when available)"

    suspend fun loadModel(
        modelFile: File,
        characterInstruction: String,
        initialHistory: List<Pair<Boolean, String>> = emptyList()
    ) {
        mutex.withLock {
            require(modelFile.isFile) { "AI-modellen hittades inte: ${modelFile.absolutePath}" }
            val hadActiveSession = activeInstruction != null
            loaded = false
            loadedInstruction = null
            activeInstruction = null
            val readyState = inference.state.first {
                it is InferenceEngine.State.Initialized ||
                    it is InferenceEngine.State.ModelReady ||
                    it is InferenceEngine.State.Error
            }
            if (readyState is InferenceEngine.State.Error) {
                if (hadActiveSession) inference.cleanUp() else throw readyState.exception
            } else if (readyState is InferenceEngine.State.ModelReady) {
                inference.cleanUp()
            }

            inference.loadModel(modelFile.absolutePath)
            inference.setSystemPrompt(characterInstruction)
            inference.setConversationHistory(initialHistory.takeLast(CHAT_HISTORY_MESSAGE_LIMIT))
            loadedInstruction = characterInstruction
            activeInstruction = characterInstruction
            loaded = true
        }
    }

    suspend fun ask(prompt: String, recentHistory: List<Pair<Boolean, String>>? = null): String {
        require(prompt.isNotBlank()) { "Skriv en fråga först." }
        return mutex.withLock {
            check(loaded && loadedInstruction != null &&
                activeInstruction == loadedInstruction &&
                inference.state.value is InferenceEngine.State.ModelReady) {
                "AI-sessionen har bytts till en annan chatt. Ladda Testchattens AI igen."
            }
            // A non-null history means the caller owns the conversation context.
            // Legacy callers pass null and keep the inference engine's current history.
            if (recentHistory != null) {
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
            if (loaded && activeInstruction == loadedInstruction &&
                inference.state.value is InferenceEngine.State.ModelReady) {
                inference.cleanUp()
                activeInstruction = null
            }
            loaded = false
            loadedInstruction = null
        }
    }

    fun isLoaded(): Boolean =
        loaded && loadedInstruction != null &&
            activeInstruction == loadedInstruction &&
            inference.state.value is InferenceEngine.State.ModelReady

    companion object {
        // AiChat returns one process-wide inference engine. All wrappers must share one lock.
        private val sharedMutex = Mutex()
        @Volatile private var activeInstruction: String? = null
    }
}
