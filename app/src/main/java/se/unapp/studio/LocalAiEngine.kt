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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalAiEngine(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /**
     * Loads a downloaded .litertlm model from local storage.
     */
    @Volatile
    private var selectedBackend: String = "Not loaded"

    val activeBackend: String
        get() = selectedBackend

    /**
     * Tries GPU acceleration first. If the device's GPU runtime or driver
     * cannot initialize this model, safely falls back to CPU.
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
                                samplerConfig = SamplerConfig(topK = 40, topP = 0.90, temperature = 0.85)
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
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.90, temperature = 0.85)
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
     * Sends a prompt to the local AI and returns its answer.
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
                // Use LiteRT-LM's blocking request/response API here.
                // This avoids leaving the chat waiting for an async Flow completion
                // callback on devices/runtime versions where streaming does not close.
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
