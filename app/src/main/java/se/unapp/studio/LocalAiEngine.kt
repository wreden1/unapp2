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

    private fun conversationConfig(initialMessages: List<Message> = emptyList()) = ConversationConfig(
        systemInstruction = Contents.of(systemInstruction),
        initialMessages = initialMessages,
        samplerConfig = SamplerConfig(
            topK = 20,
            topP = 0.95,
            temperature = 0.6,
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

            if (engine != null && conversation != null) return@withLock

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

    /**
     * Every request is a fresh model conversation. The prior turns are inserted as
     * actual user/model messages (not a single plain-text transcript), avoiding the
     * LiteRT-LM 0.18.x multi-turn KV-cache issue while preserving roleplay context.
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
                conversation?.close()

                val initialMessages = recentHistory.map { (fromUser, text) ->
                    if (fromUser) Message.user(text) else Message.model(text)
                }

                val freshConversation = activeEngine.createConversation(
                    conversationConfig(initialMessages)
                )
                conversation = freshConversation

                val response = freshConversation.sendMessage(
                    prompt,
                    repetitionPenaltyConfig = RepetitionPenaltyConfig(
                        repetitionPenalty = 1.12f,
                        presencePenalty = 0.15f,
                        frequencyPenalty = 0.10f,
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

                response.toString().trim()
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
