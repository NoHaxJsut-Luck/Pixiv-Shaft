package ceui.pixiv.translation

class PromptBuilder {

    private val config = TranslationConfig.getInstance()

    fun buildSystemPrompt(
        from: String = "Japanese",
        to: String = "Chinese",
        summaryPrompt: String = "",
        termsPrompt: String = ""
    ): String {
        val template = config.getConfig("system_prompt")
        val variables = mapOf(
            "from" to from,
            "to" to to,
            "summary_prompt" to summaryPrompt,
            "terms_prompt" to termsPrompt
        )
        return config.replaceVariables(template, variables)
    }

    fun buildSingleParagraphPrompt(
        text: String,
        from: String = "Japanese",
        to: String = "Chinese",
        contentType: String = "text",
        htmlOnly: String = ""
    ): String {
        val template = config.getConfig("single_paragraph_prompt")
        val variables = mapOf(
            "text" to text,
            "from" to from,
            "to" to to,
            "content_type" to contentType,
            "html_only" to htmlOnly
        )
        return config.replaceVariables(template, variables)
    }

    fun buildMultiParagraphPrompt(
        yaml: String,
        from: String = "Japanese",
        to: String = "Chinese",
        imtSourceField: String = "text",
        imtTransField: String = "text",
        htmlOnly: String = ""
    ): String {
        val template = config.getConfig("multi_paragraph_prompt")
        val variables = mapOf(
            "yaml" to yaml,
            "from" to from,
            "to" to to,
            "imt_source_field" to imtSourceField,
            "imt_trans_field" to imtTransField,
            "html_only" to htmlOnly
        )
        return config.replaceVariables(template, variables)
    }

    fun buildTranslationRequest(
        text: String,
        from: String = "Japanese",
        to: String = "Chinese",
        isMultiParagraph: Boolean = false,
        summaryPrompt: String = "",
        termsPrompt: String = "",
        contentType: String = "text",
        htmlOnly: String = ""
    ): Pair<String, String> {
        val systemPrompt = buildSystemPrompt(from, to, summaryPrompt, termsPrompt)
        val userPrompt = if (isMultiParagraph) {
            // For multi-paragraph, we need to convert text to YAML format
            val yaml = convertToYaml(text)
            buildMultiParagraphPrompt(yaml, from, to, htmlOnly = htmlOnly)
        } else {
            buildSingleParagraphPrompt(text, from, to, contentType, htmlOnly)
        }
        return Pair(systemPrompt, userPrompt)
    }

    private fun convertToYaml(text: String): String {
        val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
        val yamlBuilder = StringBuilder()
        paragraphs.forEachIndexed { index, paragraph ->
            yamlBuilder.append("- id: ${index + 1}\n")
            yamlBuilder.append("  text: |\n")
            // Indent each line of the paragraph
            paragraph.lines().forEach { line ->
                yamlBuilder.append("    $line\n")
            }
        }
        return yamlBuilder.toString()
    }
}
