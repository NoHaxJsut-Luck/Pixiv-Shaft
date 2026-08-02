package ceui.pixiv.translation

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStreamReader
import java.io.IOException
import java.util.Properties

class TranslationConfig private constructor() {

    companion object {
        private const val TAG = "TranslationConfig"
        private const val CONFIG_FILE_NAME = "translation_prompt.properties"
        private const val LEGACY_CONFIG_FILE_NAME =
            "以系统身份发送给Grok的翻译请求，其中{{text}}表示需要翻译的段落内容，.ini"

        @Volatile
        private var instance: TranslationConfig? = null

        @JvmStatic
        fun getInstance(): TranslationConfig {
            return instance ?: synchronized(this) {
                instance ?: TranslationConfig().also { instance = it }
            }
        }
    }

    private val config = mutableMapOf<String, String>()
    private var isLoaded = false

    @Synchronized
    fun loadConfig(context: Context) {
        if (isLoaded) return
        loadDefaultConfig()
        try {
            val configFile = listOf(
                File(context.filesDir, CONFIG_FILE_NAME),
                File(context.filesDir, LEGACY_CONFIG_FILE_NAME),
            ).firstOrNull { it.exists() }
            if (configFile != null) {
                loadFromFile(configFile)
            } else {
                Log.d(TAG, "Custom translation prompt not found; using defaults")
            }
            isLoaded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config", e)
            isLoaded = true
        }
    }

    fun loadFromFile(file: File) {
        try {
            InputStreamReader(file.inputStream(), Charsets.UTF_8).use { reader ->
                val properties = Properties()
                properties.load(reader)
                properties.forEach { key, value ->
                    val normalizedKey = key.toString()
                    val normalizedValue = value.toString()
                    if (normalizedKey in config && normalizedValue.isNotBlank()) {
                        config[normalizedKey] = normalizedValue
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading config file", e)
        }
    }

    private fun loadDefaultConfig() {
        config.clear()
        // 系统身份提示 - 优化版本，移除敏感词
        config["system_prompt"] = "你是专业的日轻/小说日译中译者。将原文译为自然流畅的简体中文，贴合叙事节奏与人物口吻。" +
            "对话与独白中的感叹、迟疑、应答（如「えっ」「あっ」「ん」「ううん」「はぁ」等）、以及呻吟、喘息、肢体声等拟声描写，须译为符合语境的中文语气词或拟声，不要因「无实义」而整段保留假名。" +
            "专有名词、人名可用通用译名或保留原文。严格保留[ruby:…]、[uploadedimage:…]等标记与原文换行、空行。{{summary_prompt}}{{terms_prompt}}"

        config["single_paragraph_prompt"] = "请将以下{{from}}文本翻译成{{to}}。保持段落结构（换行、空行）、语气与情绪层次；装饰符号（如◇）与特殊标记（如[ruby:…]、[uploadedimage:…]）须原样保留。" +
            "文中所有假名表达的语气词、拟声、断续声均应译为自然的中文表达，避免在中文正文里残留大段日文假名。" +
            "禁止使用 YAML、Markdown、列表包裹；不要输出 - id:、text: 等。只输出译文正文。{{html_only}}\n\n{{text}}"

        // 多段落翻译提示 - 保持原有格式
        config["multi_paragraph_prompt"] = "You will be given a YAML formatted input containing entries with \"id\" and \"{{imt_source_field}}\" fields. Here is the input:\n\n<yaml>\n{{yaml}}\n</yaml>\n\nFor each entry in the YAML, translate the contents of the \"{{imt_source_field}}\" field into {{to}},{{html_only}} Write the translation back into the \"{{imt_source_field}}\" field for that entry.\n\nHere is an example of the expected format:\n\n<example>\nInput:\n  - id: 1\n    {{imt_source_field}}: Source\nOutput:\n  - id: 1\n    {{imt_trans_field}}: Translation\n</example>\n\nPlease return the translated YAML directly without wrapping <yaml> tag or include any additional information."
    }

    fun getConfig(key: String, defaultValue: String = ""): String {
        return config.getOrDefault(key, defaultValue)
    }

    fun isConfigLoaded(): Boolean = isLoaded

    fun replaceVariables(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
