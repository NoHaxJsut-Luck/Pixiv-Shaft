package ceui.pixiv.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationFailureLoggerTest {

    @Test
    fun logContainsOnlyExplicitDiagnosticInputs() {
        val error = TranslationOutputException(
            message = "quality validation failed",
            sourceChunk = "失败分块原文",
            modelOutput = "模型返回片段",
        )
        val log = TranslationFailureLogger.buildLogText(
            model = "grok-test",
            from = "Japanese",
            to = "Chinese",
            totalSourceChars = 7103,
            totalChunks = 6,
            failures = listOf(
                TranslationFailureRecord(
                    chunkIndex = 2,
                    totalChunks = 6,
                    sourceChunk = error.sourceChunk.orEmpty(),
                    modelOutput = error.modelOutput,
                    error = error,
                ),
            ),
        )

        assertTrue(log.contains("Chunk: 3/6"))
        assertTrue(log.contains("失败分块原文"))
        assertTrue(log.contains("模型返回片段"))
        assertTrue(log.contains("API keys"))
        assertFalse(log.contains("Bearer "))
    }
}
