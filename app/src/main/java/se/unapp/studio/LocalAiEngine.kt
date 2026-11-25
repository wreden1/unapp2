package se.unapp.studio

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalAiEngine(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val recentAnswers = mutableListOf<String>()

    @Volatile
    private var selectedBackend: String = "Not loaded"

    val activeBackend: String
        get() = selectedBackend

    /**
     * Loads a downloaded .litertlm model from local storage.
     */
    suspend fun loadModel(modelFile: File, characterInstruction: String) {
        mutex.withLock {
            require(modelFile.isFile) {
                "AI-modellen hittades inte: ${modelFile.absolutePath}"
            }

            if (conversation != null) return@withLock

            withContext(Dispatchers.IO) {
                var gpuError: Throwable? = null

                try {
                    val gpuEngine = Engine(
                        EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = Backend.GPU(),
                            cacheDir = appContext.cacheDir.absolutePath
                        )
                    )
                    try {
                        gpuEngine.initialize()
                        val gpuConversation = gpuEngine.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(characterInstruction),
                                samplerConfig = SamplerConfig(topK = 50, topP = 0.95, temperature = 0.95)
                            )
                        )
                        engine = gpuEngine
                        conversation = gpuConversation
                        selectedBackend = "GPU"
                        recentAnswers.clear()
                        return@withContext
                    } catch (error: Throwable) {
                        gpuEngine.close()
                        throw error
                    }
                } catch (error: Throwable) {
                    gpuError = error
                }

                val cpuEngine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                )

                try {
                    cpuEngine.initialize()
                    val cpuConversation = cpuEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(characterInstruction),
                            samplerConfig = SamplerConfig(topK = 50, topP = 0.95, temperature = 0.95)
                        )
                    )
                    engine = cpuEngine
                    conversation = cpuConversation
                    selectedBackend = "CPU (GPU stöds inte här)"
                    recentAnswers.clear()
                } catch (cpuError: Throwable) {
                    cpuEngine.close()
                    cpuError.addSuppressed(gpuError)
                    throw cpuError
                }
            }
        }
    }

    /**
     * Detects answers that are effectively the same as a recent reply.
     * Short answers are only flagged when they are identical; longer answers
     * use token overlap to avoid rejecting ordinary conversational phrases.
     */
    private fun repeatsRecentAnswer(answer: String): Boolean {
        val tokens = answer.lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()

        if (tokens.isEmpty()) return false

        return recentAnswers.any { previous ->
            val previousTokens = previous.lowercase()
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 }
                .toSet()

            if (tokens.size < 8 || previousTokens.size < 8) {
                answer.trim().equals(previous.trim(), ignoreCase = true)
            } else {
                val overlap = tokens.intersect(previousTokens).size.toDouble()
                val similarity = overlap / tokens.union(previousTokens).size.coerceAtLeast(1)
                similarity >= 0.78
            }
        }
    }

    private fun rememberAnswer(answer: String) {
        recentAnswers.add(answer)
        while (recentAnswers.size > 6) recentAnswers.removeAt(0)
    }

    /**
     * Sends a prompt to the local AI. If the model falls into a repetition loop,
     * it gets up to two chances to produce a genuinely different continuation.
     */
    suspend fun ask(prompt: String): String {
        require(prompt.isNotBlank()) {
            "Skriv en fråga först."
        }

        return mutex.withLock {
            val activeConversation =
                checkNotNull(conversation) {
                    "AI-modellen är inte laddad ännu."
                }

            withContext(Dispatchers.IO) {
                var answer = activeConversation.sendMessage(prompt).toString().trim()

                var retry = 0
                while (answer.isNotBlank() && repeatsRecentAnswer(answer) && retry < 2) {
                    retry++
                    val correction = when (retry) {
                        1 -> "Your last reply was too similar to something you already said. Do not repeat it or paraphrase it. Continue the conversation by responding specifically to the user's latest message with a fresh thought, a new detail, or a natural action. Stay in character."
                        else -> "You are stuck repeating yourself. Break the pattern completely. Give a distinctly different, specific response to the user's latest message. Do not reuse the previous wording, opening, or idea. Stay in character."
                    }
                    answer = activeConversation.sendMessage(correction).toString().trim()
                }

                if (answer.isNotBlank()) rememberAnswer(answer)
                answer
            }
        }
    }

    /**
     * Releases the model and conversation resources.
     */
    suspend fun close() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                conversation?.close()
                conversation = null

                engine?.close()
                engine = null
                recentAnswers.clear()
                selectedBackend = "Not loaded"
            }
        }
    }

    fun isLoaded(): Boolean {
        return engine != null && conversation != null
    }
}
