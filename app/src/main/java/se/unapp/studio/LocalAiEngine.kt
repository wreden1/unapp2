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
            require(modelFile.isFile && modelFile.canRead()) {
                "AI-modellen saknas eller kan inte läsas: ${modelFile.absolutePath}"
            }

            loaded = false
            loadedInstruction = null
            activeInstruction = null

            // AiChat exposes one process-wide native engine. It may still be in
            // an Error state after a failed first load. cleanUp() explicitly
            // resets that state to Initialized; throwing here made every later
            // retry fail immediately without attempting recovery.
            val state = inference.state.first {
                it is InferenceEngine.State.Initialized ||
                    it is InferenceEngine.State.ModelReady ||
                    it is InferenceEngine.State.Error
            }
            when (state) {
                is InferenceEngine.State.ModelReady,
                is InferenceEngine.State.Error -> inference.cleanUp()
                else -> Unit
            }

            check(inference.state.value is InferenceEngine.State.Initialized) {
                "AI-motorn kunde inte återställas. Aktuellt läge: ${inference.state.value.javaClass.simpleName}"
            }

            try {
                inference.loadModel(modelFile.absolutePath)
                inference.setSystemPrompt(characterInstruction)
                inference.setConversationHistory(initialHistory.takeLast(CHAT_HISTORY_MESSAGE_LIMIT))
                loadedInstruction = characterInstruction
                activeInstruction = characterInstruction
                loaded = true
            } catch (error: Throwable) {
                loaded = false
                loadedInstruction = null
                activeInstruction = null
                // Leave the singleton in a retryable state if native loading failed.
                if (inference.state.value is InferenceEngine.State.Error) {
                    runCatching { inference.cleanUp() }
                }
                throw IllegalStateException(
                    "Kunde inte starta den lokala AI:n: ${error.message ?: error.javaClass.simpleName}",
                    error
                )
            }
        }
    }

    suspend fun ask(
        prompt: String,
        recentHistory: List<Pair<Boolean, String>>? = null,
        testChatMode: Boolean = false,
        expectedSystemInstruction: String? = null
    ): String {
        require(prompt.isNotBlank()) { "Skriv en fråga först." }
        return mutex.withLock {
            check(loaded && loadedInstruction != null &&
                activeInstruction == loadedInstruction &&
                (expectedSystemInstruction == null || loadedInstruction == expectedSystemInstruction) &&
                inference.state.value is InferenceEngine.State.ModelReady) {
                "Fel karaktärsinstruktion är laddad. Ladda om Testchattens AI för den valda karaktären."
            }
            if (recentHistory != null) {
                inference.setConversationHistory(
                    recentHistory.takeLast(CHAT_HISTORY_MESSAGE_LIMIT),
                    resetSampler = testChatMode
                )
            }
            suspend fun generateReply(input: String): String {
                val response = StringBuilder()
                inference.sendUserPrompt("$input\n/no_think", predictLength = 1536).collect { token ->
                    response.append(token)
                }
                return response.toString()
                    .replace(Regex("(?s)<think>.*?</think>"), "")
                    .substringBefore("<think>")
                    .replace("</think>", "")
                    .trim()
            }

            var visibleReply = generateReply(prompt)
            val previousAssistantReplies = recentHistory.orEmpty()
                .filter { !it.first }
                .map { it.second }
                .takeLast(6)

            fun normalizeReply(value: String): String =
                value.lowercase().replace(Regex("\\s+"), " ").trim()

            // Compare the opening words as well as the complete answer. This catches
            // the common failure where every reply starts identically but continues
            // with slightly different text.
            fun openingSignature(value: String): String {
                val words = value.lowercase()
                    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                    .trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                return if (words.size >= 6) words.take(8).joinToString(" ") else ""
            }

            val currentOpening = openingSignature(visibleReply)
            val repeatsFullReply = previousAssistantReplies.any {
                normalizeReply(it) == normalizeReply(visibleReply)
            }
            val repeatsOpening = currentOpening.isNotBlank() &&
                previousAssistantReplies.any { openingSignature(it) == currentOpening }

            // Testchat-only recovery: detect both identical full replies and repeated
            // openings, then rebuild the exact same history and reset sampler state.
            if (testChatMode && visibleReply.isNotBlank() &&
                (repeatsFullReply || repeatsOpening)
            ) {
                inference.setConversationHistory(
                    recentHistory.orEmpty().takeLast(CHAT_HISTORY_MESSAGE_LIMIT),
                    resetSampler = true
                )
                val reason = if (repeatsOpening) {
                    "Dina första ord upprepades från ett tidigare svar."
                } else {
                    "Ditt senaste svar blev identiskt med ett tidigare svar."
                }
                visibleReply = generateReply(
                    "$prompt\n\nVIKTIGT: $reason Börja på ett helt nytt sätt. " +
                        "Använd inte samma inledning, fras eller meningsbyggnad som i dina tidigare svar. " +
                        "Bemöt användarens senaste meddelande konkret och naturligt."
                )
            }
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
        private val sharedMutex = Mutex()
        @Volatile private var activeInstruction: String? = null
    }
}
