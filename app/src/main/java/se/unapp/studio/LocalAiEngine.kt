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
                                samplerConfig = SamplerConfig(topK = 50, topP = 0.95, temperature = 1.0)
                            )
                        )
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
                    val cpuConversation = cpuEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(characterInstruction),
                            samplerConfig = SamplerConfig(topK = 50, topP = 0.95, temperature = 1.0)
                        )
                    )
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
     * Sends exactly one user message and returns exactly one model response.
     * Do not inject retry/correction messages into the persistent conversation:
     * those messages become part of its history and can create repetition loops.
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
                activeConversation.sendMessage(prompt).toString().trim()
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
                selectedBackend = "Not loaded"
            }
        }
    }

    fun isLoaded(): Boolean {
        return engine != null && conversation != null
    }
}
