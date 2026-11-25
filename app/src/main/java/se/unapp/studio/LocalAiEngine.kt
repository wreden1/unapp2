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

    suspend fun loadModel(
        modelFile: File,
        characterInstruction: String,
        initialHistory: List<Pair<Boolean, String>> = emptyList()
    ) {
        mutex.withLock {
            val initialMessages = initialHistory.takeLast(12).map { (fromUser, text) ->
                if (fromUser) Message.user(text) else Message.model(text)
            }
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
                        conversation = engine?.createConversation(conversationConfig(initialMessages = initialMessages))
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
                        val gpuConversation = gpuEngine.createConversation(conversationConfig(initialMessages = initialMessages))
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
                    val cpuConversation = cpuEngine.createConversation(conversationConfig(initialMessages = initialMessages))
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
     * Keep one native conversation alive for the whole character chat.
     * LiteRT-LM already stores the turn history inside Conversation; rebuilding
     * it from a second, manually maintained transcript can cause context drift.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun ask(
        prompt: String,
        recentHistory: List<Pair<Boolean, String>> = emptyList()
    ): String {
        require(prompt.isNotBlank()) { "Skriv en fråga först." }

        return mutex.withLock {
            val activeConversation = checkNotNull(conversation) {
                "AI-modellen är inte laddad ännu."
            }

            withContext(Dispatchers.IO) {
                val response = activeConversation.sendMessage(
                    prompt,
                    repetitionPenaltyConfig = RepetitionPenaltyConfig(
                        repetitionPenalty = 1.08f,
                        presencePenalty = 0.0f,
                        frequencyPenalty = 0.0f,
                        windowSize = 256
                    ),
                    maxOutputToken = 512,
                    thinkingConfig = ThinkingConfig(
                        enableThinking = false,
                        thinkingTokenBudget = 0
                    )
                )

                response.contents.toString().trim().ifBlank {
                    "Jag fick inget tydligt svar den här gången. Försök igen."
                }
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
