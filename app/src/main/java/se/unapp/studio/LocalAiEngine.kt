package se.unapp.studio

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.NoRepeatNgramConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.Contents
import java.io.File
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalAiEngine(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var systemInstruction: String = ""

    @Volatile
    private var selectedBackend: String = "Not loaded"

    val activeBackend: String
        get() = selectedBackend

    private fun conversationConfig(
        initialMessages: List<Message> = emptyList(),
        temperature: Double = 0.72
    ) = ConversationConfig(
        systemInstruction = Contents.of(systemInstruction),
        initialMessages = initialMessages,
        samplerConfig = SamplerConfig(
            topK = 32,
            topP = 0.92,
            temperature = temperature,
            seed = ThreadLocalRandom.current().nextInt()
        ),
        thinkingConfig = ThinkingConfig(
            enableThinking = false,
            thinkingTokenBudget = 0
        ),
        maxOutputToken = 512
    )

    suspend fun loadModel(modelFile: File, characterInstruction: String) {
        mutex.withLock {
            require(modelFile.isFile) {
                "AI-modellen hittades inte: ${modelFile.absolutePath}"
            }

            // The model engine can be reused, but a character change must reset the
            // conversation and install the new system instruction.
            if (engine != null) {
                withContext(Dispatchers.IO) {
                    if (systemInstruction != characterInstruction || conversation == null) {
                        systemInstruction = characterInstruction
                        conversation?.close()
                        conversation = engine?.createConversation(conversationConfig())
                    }
                }
                return@withLock
            }

            withContext(Dispatchers.IO) {
                systemInstruction = characterInstruction
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
                        val gpuConversation = gpuEngine.createConversation(conversationConfig())
                        engine = gpuEngine
                        conversation = gpuConversation
                        selectedBackend = "GPU"
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
                    val cpuConversation = cpuEngine.createConversation(conversationConfig())
                    engine = cpuEngine
                    conversation = cpuConversation
                    selectedBackend = "CPU (GPU stöds inte här)"
                } catch (cpuError: Throwable) {
                    cpuEngine.close()
                    cpuError.addSuppressed(gpuError)
                    throw cpuError
                }
            }
        }
    }

    private fun normalizeForComparison(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isTooSimilar(candidate: String, previous: List<String>): Boolean {
        val normalized = normalizeForComparison(candidate)
        if (normalized.isBlank()) return true

        return previous.any { old ->
            val other = normalizeForComparison(old)
            if (other.isBlank()) {
                false
            } else if (normalized == other) {
                true
            } else {
                val a = normalized.split(" ").filter { it.length > 2 }.toSet()
                val b = other.split(" ").filter { it.length > 2 }.toSet()
                if (a.size < 8 || b.size < 8) {
                    false
                } else {
                    val overlap = a.intersect(b).size.toDouble()
                    val similarity = overlap / a.union(b).size.coerceAtLeast(1)
                    similarity >= 0.78
                }
            }
        }
    }

    /**
     * A clean native conversation is created for each turn to avoid the LiteRT-LM
     * multi-turn cache issue. Repeated/near-identical replies are detected and
     * automatically regenerated with a different sampling temperature.
     */
    suspend fun ask(
        prompt: String,
        recentHistory: List<Pair<Boolean, String>> = emptyList()
    ): String {
        require(prompt.isNotBlank()) { "Skriv en fråga först." }

        return mutex.withLock {
            val activeEngine = checkNotNull(engine) {
                "AI-modellen är inte laddad ännu."
            }

            withContext(Dispatchers.IO) {
                val initialMessages = recentHistory.map { (fromUser, text) ->
                    if (fromUser) Message.user(text) else Message.model(text)
                }
                val previousReplies = recentHistory
                    .filter { !it.first }
                    .map { it.second }
                    .takeLast(5)
                val candidates = mutableListOf<String>()
                var bestAnswer = ""

                // First attempt is normal; retries explicitly ask for a genuinely
                // different response and use more varied sampling.
                for (attempt in 0..2) {
                    conversation?.close()
                    val freshConversation = activeEngine.createConversation(
                        conversationConfig(
                            initialMessages = initialMessages,
                            temperature = when (attempt) {
                                0 -> 0.72
                                1 -> 0.86
                                else -> 0.94
                            }
                        )
                    )
                    conversation = freshConversation

                    val retryInstruction = if (attempt == 0) {
                        prompt
                    } else {
                        prompt + "\n\n[Internal response guidance: Your previous draft was too similar " +
                            "to something already said. Answer the latest message in a clearly different " +
                            "way. Introduce a new relevant thought, action, detail, or question. Do not " +
                            "paraphrase or repeat earlier wording. Stay in character.]"
                    }

                    val response = freshConversation.sendMessage(
                        retryInstruction,
                        repetitionPenaltyConfig = RepetitionPenaltyConfig(
                            repetitionPenalty = 1.18f,
                            presencePenalty = 0.22f,
                            frequencyPenalty = 0.16f,
                            windowSize = 512
                        ),
                        noRepeatNgramConfig = NoRepeatNgramConfig(
                            noRepeatNgramSize = 4,
                            windowSize = 512
                        ),
                        maxOutputToken = 512,
                        thinkingConfig = ThinkingConfig(
                            enableThinking = false,
                            thinkingTokenBudget = 0
                        )
                    )

                    val answer = response.toString().trim()
                    if (answer.isNotBlank()) {
                        if (bestAnswer.isBlank()) bestAnswer = answer
                        val comparisonSet = previousReplies + candidates
                        if (!isTooSimilar(answer, comparisonSet)) {
                            return@withContext answer
                        }
                        candidates.add(answer)
                        bestAnswer = answer
                    }
                }

                // If all attempts are similar, return the latest non-empty generation
                // rather than blocking the chat or returning an error.
                bestAnswer.ifBlank { "Jag tappade tråden en stund. Kan du säga det på ett annat sätt?" }
            }
        }
    }

    suspend fun close() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                conversation?.close()
                conversation = null
                engine?.close()
                engine = null
                systemInstruction = ""
                selectedBackend = "Not loaded"
            }
        }
    }

    fun isLoaded(): Boolean = engine != null && conversation != null
}
