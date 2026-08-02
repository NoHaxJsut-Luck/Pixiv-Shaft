package ceui.pixiv.ui.novel

import ceui.lisa.R
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.SpaceHolder
import ceui.loxia.WebNovel
import ceui.loxia.novel.NovelTextHolder
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.common.HoldersContainer
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.RefreshOwner
import ceui.pixiv.ui.detail.UserInfoHolder

class NovelTextViewModel(
    private val novelId: Long,
) : HoldersViewModel() {

    private val _webNovel = MutableLiveData<WebNovel>()
    val webNovel: LiveData<WebNovel> = _webNovel
    private var renderedText: String? = null
    private var originalText: String? = null
    private var translatedText: String? = null

    val hasTranslation: Boolean
        get() = translatedText != null

    val isShowingTranslation: Boolean
        get() = translatedText != null && renderedText == translatedText

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        val context = Shaft.getContext()
        val html = Client.appApi.getNovelText(novelId).string()
        val wNovel = WebNovelParser.parsePixivObject(html)?.novel

        wNovel?.let {
            _webNovel.value = it
            originalText = it.text
            if (translatedText == null) {
                renderedText = it.text
            }
        }
        _itemHolders.value = buildNovelHolders(renderedText.orEmpty(), wNovel, context)
        _refreshState.value = RefreshState.LOADED(
            hasContent = true, hasNext = false
        )
    }

    fun applyTranslation(text: String) {
        translatedText = text
        renderText(text)
    }

    fun showOriginal() {
        originalText?.let(::renderText)
    }

    fun showTranslation() {
        translatedText?.let(::renderText)
    }

    fun sourceText(): String? = originalText ?: _webNovel.value?.text

    private fun renderText(text: String) {
        if (renderedText == text) return
        renderedText = text
        val wNovel = _webNovel.value
        val context = Shaft.getContext()
        _itemHolders.value = buildNovelHolders(text, wNovel, context)
    }

    private fun buildNovelHolders(
        text: String,
        webNovel: WebNovel?,
        context: android.content.Context,
    ): List<ListItemHolder> {
        val result = mutableListOf<ListItemHolder>()
        result.add(SpaceHolder())
        result.add(NovelHeaderHolder(novelId))
        result.add(RedSectionHeaderHolder(context.getString(R.string.string_432)))
        result.add(UserInfoHolder(ObjectPool.get<Novel>(novelId).value?.user?.id ?: 0L))
        result.add(RedSectionHeaderHolder("简介"))
        result.add(NovelCaptionHolder(novelId))
        result.add(RedSectionHeaderHolder("正文"))
        result.add(SpaceHolder())

        webNovel?.let {
            text.split("\n").forEach { oneLineText ->
                result.addAll(
                    WebNovelParser.buildNovelHolders(it, oneLineText)
                )
            }
        }
        result.add(SpaceHolder())
        result.add(NovelTextHolder("<===== End =====>", Common.getNovelTextColor()))
        result.add(SpaceHolder())

        return result
    }
}
