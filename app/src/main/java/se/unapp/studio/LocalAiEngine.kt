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
    private var systemInstruction: String = ""

    @Volatile
    private var selectedBackend: String = "Not loaded"

    val activeBackend: String
        get() = selectedBackend

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(systemInstruction),
        samplerConfig = SamplerConfig(
            topK = 40,
            topP = 0.90,
            temperature = 0.85
        )
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
     * Starts each turn with a clean conversation and explicitly supplies a
     * bounded transcript. This prevents stale internal state from accumulating
     * or diverging from the chat transcript saved by the UI.
     */
    suspend fun ask(prompt: String, recentHistory: String = ""): String {
        require(prompt.isNotBlank()) { "Skriv en fråga först." }

        return mutex.withLock {
            val activeEngine = checkNotNull(engine) {
                "AI-modellen är inte laddad ännu."
            }

            withContext(Dispatchers.IO) {
                conversation?.close()
                val freshConversation = activeEngine.createConversation(conversationConfig())
                conversation = freshConversation

                val completePrompt = buildString {
                    if (recentHistory.isNotBlank()) {
                        append("Recent conversation (context only; do not repeat it):\n")
                        append(recentHistory)
                        append("\n\n")
                    }
                    append("Respond only to this latest user message. Continue naturally and do not repeat earlier answers:\n")
                    append(prompt)
                }

                freshConversation.sendMessage(completePrompt).toString().trim()
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
