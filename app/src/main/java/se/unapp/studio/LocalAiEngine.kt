
package se.unapp.studio

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
    suspend fun loadModel(modelFile: File) {
        mutex.withLock {
            require(modelFile.isFile) {
                "AI-modellen hittades inte: ${modelFile.absolutePath}"
            }

            if (conversation != null) return@withLock

            withContext(Dispatchers.IO) {
                val newEngine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                )

                try {
                    newEngine.initialize()

                    val newConversation =
                        newEngine.createConversation()

                    engine = newEngine
                    conversation = newConversation

                } catch (error: Throwable) {
                    newEngine.close()
                    throw error
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
                val result = StringBuilder()

                activeConversation
                    .sendMessageAsync(prompt)
                    .collect { message ->
                        val text = message.toString()

                        if (text.isNotEmpty()) {
                            result.append(text)
                        }
                    }

                result.toString().trim()
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
            }
        }
    }

    fun isLoaded(): Boolean {
        return engine != null && conversation != null
    }
}
