package se.unapp.studio

/**
 * Local Stable Diffusion engine bridge. Calls are blocking and must be run on Dispatchers.IO.
 * The model is kept separate from the Qwen chat model and never uses a network service.
 */
class OfflineImageEngine {
    companion object {
        init {
            System.loadLibrary("stable-diffusion")
            System.loadLibrary("unapp-image")
        }
    }

    private external fun nativeCreate(modelPath: String, threadCount: Int): Long
    private external fun nativeGenerate(
        context: Long,
        prompt: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Long
    ): IntArray
    private external fun nativeDestroy(context: Long)

    private var handle: Long = 0L

    fun load(modelFile: java.io.File, threadCount: Int = 4) {
        check(modelFile.isFile) { "Bildmodellen hittades inte: ${modelFile.absolutePath}" }
        close()
        handle = nativeCreate(modelFile.absolutePath, threadCount.coerceIn(1, 8))
        check(handle != 0L) { "Bildmotorn kunde inte startas." }
    }

    fun generate(prompt: String, width: Int = 512, height: Int = 512, steps: Int = 20, seed: Long = -1L): IntArray {
        check(handle != 0L) { "Bildmotorn är inte laddad." }
        require(prompt.isNotBlank()) { "Beskriv bilden först." }
        return nativeGenerate(handle, prompt, width, height, steps, seed)
    }

    fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    fun isLoaded(): Boolean = handle != 0L
}
