package ceui.pixiv.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackStrategyTest {

    private val strategy = FallbackStrategy()

    @Test
    fun ordinaryPolicyTextIsNotClassifiedAsRefusal() {
        val result = strategy.detectErrorType(null, "这项政策违反了旧规则。")

        assertEquals(FallbackStrategy.ErrorType.UNKNOWN, result)
    }

    @Test
    fun explicitRefusalIsDetected() {
        val result = strategy.detectErrorType(null, "抱歉，我无法为您提供这段内容的翻译。")

        assertEquals(FallbackStrategy.ErrorType.BLOCKED_CONTENT, result)
    }

    @Test
    fun unchangedJapaneseIsRejectedButChineseTranslationPasses() {
        val source = "これはテストです。よろしくお願いします。"

        assertTrue(strategy.looksMostlyUntranslated(source, source))
        assertFalse(strategy.looksMostlyUntranslated(source, "这是一个测试，请多关照。"))
    }
}
