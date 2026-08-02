package ceui.pixiv.translation

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class TranslationCacheStore private constructor(
    private val cacheFile: File,
    private val expectedChunkCount: Int,
) {

    private data class CachePayload(
        val version: Int = CACHE_FORMAT_VERSION,
        val chunkCount: Int = 0,
        val chunks: MutableMap<Int, String> = mutableMapOf(),
    )

    private val mutex = Mutex()
    private var payload = CachePayload(chunkCount = expectedChunkCount)

    suspend fun load(): List<String?> = mutex.withLock {
        withContext(Dispatchers.IO) {
            payload = runCatching {
                if (!cacheFile.exists()) return@runCatching CachePayload(chunkCount = expectedChunkCount)
                GSON.fromJson(cacheFile.readText(Charsets.UTF_8), CachePayload::class.java)
            }.getOrNull()
                ?.takeIf { it.version == CACHE_FORMAT_VERSION && it.chunkCount == expectedChunkCount }
                ?: CachePayload(chunkCount = expectedChunkCount)

            MutableList<String?>(expectedChunkCount) { index -> payload.chunks[index] }
        }
    }

    suspend fun saveChunk(index: Int, translation: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            payload.chunks[index] = translation
            cacheFile.parentFile?.mkdirs()
            val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tempFile.writeText(GSON.toJson(payload), Charsets.UTF_8)
            if (!tempFile.renameTo(cacheFile)) {
                tempFile.copyTo(cacheFile, overwrite = true)
                tempFile.delete()
            }
        }
    }

    companion object {
        private const val CACHE_FORMAT_VERSION = 1
        private const val MAX_CACHE_FILES = 40
        private val GSON = Gson()

        fun create(
            context: Context,
            sourceText: String,
            model: String,
            from: String,
            to: String,
            promptVersion: Int,
            chunkCount: Int,
        ): TranslationCacheStore {
            val cacheKey = sha256("$promptVersion\u0000$model\u0000$from\u0000$to\u0000$sourceText")
            val directory = File(context.noBackupFilesDir, "translation-cache").apply { mkdirs() }
            cleanupOldEntries(directory)
            return TranslationCacheStore(File(directory, "$cacheKey.json"), chunkCount)
        }

        @JvmStatic
        fun clear(context: Context): Boolean = runCatching {
            val directory = File(context.noBackupFilesDir, "translation-cache")
            if (!directory.exists()) true else directory.deleteRecursively()
        }.getOrDefault(false)

        private fun cleanupOldEntries(directory: File) {
            runCatching {
                directory.listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.extension == "json" }
                    .sortedByDescending { it.lastModified() }
                    .drop(MAX_CACHE_FILES)
                    .forEach { it.delete() }
            }
        }

        private fun sha256(value: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
