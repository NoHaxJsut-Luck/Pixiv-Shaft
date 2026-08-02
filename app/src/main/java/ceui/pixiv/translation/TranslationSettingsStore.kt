package ceui.pixiv.translation

import com.tencent.mmkv.MMKV

object TranslationSettingsStore {

    const val DEFAULT_MODEL = "grok-4.3"
    private const val RETIRED_DEFAULT_MODEL = "grok-3-mini"
    private const val STORE_ID = "shaft-session"
    private const val MODEL_KEY = "translation_xai_model"
    private const val PRIORITY_KEY = "translation_xai_priority"
    private val MODEL_PATTERN = Regex("""[A-Za-z0-9._:-]{1,100}""")

    private fun store(): MMKV = MMKV.mmkvWithID(STORE_ID)

    @JvmStatic
    fun getModel(): String {
        val stored = store().decodeString(MODEL_KEY, DEFAULT_MODEL).orEmpty().trim()
        if (stored == RETIRED_DEFAULT_MODEL) {
            store().encode(MODEL_KEY, DEFAULT_MODEL)
            return DEFAULT_MODEL
        }
        return stored.takeIf { MODEL_PATTERN.matches(it) } ?: DEFAULT_MODEL
    }

    @JvmStatic
    fun setModel(model: String): Boolean {
        val normalized = model.trim()
        if (!MODEL_PATTERN.matches(normalized)) return false
        return store().encode(MODEL_KEY, normalized)
    }

    @JvmStatic
    fun isPriorityEnabled(): Boolean = store().decodeBool(PRIORITY_KEY, false)

    @JvmStatic
    fun setPriorityEnabled(enabled: Boolean): Boolean = store().encode(PRIORITY_KEY, enabled)
}
