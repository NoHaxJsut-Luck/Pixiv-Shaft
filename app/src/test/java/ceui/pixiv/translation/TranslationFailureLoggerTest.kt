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
            session = sessionDiagnostics(),
            failures = listOf(
                TranslationFailureRecord(
                    chunkIndex = 2,
                    totalChunks = 6,
                    sourceChunk = error.sourceChunk.orEmpty(),
                    modelOutput = error.modelOutput,
                    error = error,
                    attempts = listOf(
                        TranslationAttemptRecord(
                            topLevelChunkIndex = 2,
                            totalTopLevelChunks = 6,
                            splitPath = "3",
                            splitDepth = 0,
                            attempt = 2,
                            maxAttempts = 4,
                            startedAtUtc = "2026-08-02T03:00:00.000Z",
                            durationMs = 842,
                            sourceText = "失败分块原文",
                            contextBeforeLength = 320,
                            contextAfterLength = 0,
                            promptMode = "alternative",
                            systemPrompt = "只输出译文 Authorization: Bearer should-not-leak",
                            userPrompt = "翻译这段文本 xai-super-secret-key",
                            model = "grok-test",
                            reasoningEffort = "none",
                            temperature = 0.1,
                            priorityProcessing = false,
                            response = TranslationResponseDiagnostics(
                                httpStatus = 200,
                                contentType = "text/event-stream",
                                requestId = "request-123",
                                completionId = "completion-456",
                                responseModel = "grok-test-build",
                                systemFingerprint = "fp_test",
                                finishReason = "stop",
                                promptTokens = 100,
                                completionTokens = 20,
                                totalTokens = 120,
                                cachedPromptTokens = 80,
                                reasoningTokens = 0,
                                sseDataEvents = 21,
                                malformedSseEvents = 0,
                            ),
                            rawModelOutput = "模型返回片段",
                            normalizedModelOutput = "模型返回片段",
                            validationClassification = "FORMAT_ERROR",
                            sourceEqualsNormalizedOutput = false,
                            errorType = "TranslationOutputException",
                            errorMessage = "quality validation failed",
                        ),
                    ),
                    retryDecisions = listOf(
                        TranslationRetryDecisionRecord(
                            topLevelChunkIndex = 2,
                            splitPath = "3",
                            splitDepth = 0,
                            completedAttempt = 2,
                            classifiedError = "FORMAT_ERROR",
                            consecutiveRefusals = 0,
                            action = "retry same chunk",
                            shouldRetry = true,
                            splitRequested = true,
                            splitPerformed = false,
                            currentChunkLength = 467,
                            requestedMaxChunkSize = 480,
                            nextAlternativePrompt = false,
                            nextTemperature = 0.1,
                            baseDelayMs = 200,
                            terminalReason = null,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(log.contains("Chunk: 3/6"))
        assertTrue(log.contains("失败分块原文"))
        assertTrue(log.contains("模型返回片段"))
        assertTrue(log.contains("Reasoning effort: none"))
        assertTrue(log.contains("Finish reason: stop"))
        assertTrue(log.contains("Split requested: true"))
        assertTrue(log.contains("Split performed: false"))
        assertTrue(log.contains("Current chunk length: 467"))
        assertTrue(log.contains("Requested max chunk size: 480"))
        assertTrue(log.contains("API keys"))
        assertFalse(log.contains("should-not-leak"))
        assertFalse(log.contains("super-secret-key"))
    }

    private fun sessionDiagnostics() = TranslationSessionDiagnostics(
        conversationId = "conversation-test",
        promptVersion = 5,
        promptConfigSource = "built-in defaults",
        topLevelChunkMaxChars = 1250,
        parallelChunkCap = 8,
        globalRequestCap = 8,
        chunkAttemptCap = 4,
        maxSplitDepth = 2,
        priorityProcessing = false,
    )
}
