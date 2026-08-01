package ceui.pixiv.ui.novel

import android.util.Log
import ceui.lisa.R
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch
import timber.log.Timber

class NovelTextViewModel(
    private val novelId: Long,
) : HoldersViewModel() {

    private val _webNovel = MutableLiveData<WebNovel>()
    val webNovel: LiveData<WebNovel> = _webNovel
    private var renderedText: String? = null

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        val context = Shaft.getContext()
        val html = Client.appApi.getNovelText(novelId).string()
        val wNovel = WebNovelParser.parsePixivObject(html)?.novel

        val result = mutableListOf<ListItemHolder>()
        result.add(SpaceHolder())
        result.add(NovelHeaderHolder(novelId))
        result.add(RedSectionHeaderHolder(context.getString(R.string.string_432)))
        result.add(UserInfoHolder(ObjectPool.get<Novel>(novelId).value?.user?.id ?: 0L))
        result.add(RedSectionHeaderHolder("简介"))
        result.add(NovelCaptionHolder(novelId))
        result.add(RedSectionHeaderHolder("正文"))
        result.add(SpaceHolder())

        wNovel?.let {
            (it.text?.split("\n") ?: listOf()).forEach { oneLineText ->
                result.addAll(
                    WebNovelParser.buildNovelHolders(it, oneLineText)
                )
            }
            _webNovel.value = it
            renderedText = it.text
        }
        result.add(SpaceHolder())
        result.add(NovelTextHolder("<===== End =====>", Common.getNovelTextColor()))
        result.add(SpaceHolder())

        _itemHolders.value = result
        _refreshState.value = RefreshState.LOADED(
            hasContent = true, hasNext = false
        )
    }

    fun updateNovelText(translatedText: String) {
        if (renderedText == translatedText) return
        renderedText = translatedText
        val head = if (translatedText.length <= 50) translatedText else "${translatedText.take(50)}..."
        Log.d("Translation", "ViewModel received text to update: $head")
        val wNovel = _webNovel.value
        val context = Shaft.getContext()
        val result = mutableListOf<ListItemHolder>()
        result.add(SpaceHolder())
        result.add(NovelHeaderHolder(novelId))
        result.add(RedSectionHeaderHolder(context.getString(R.string.string_432)))
        result.add(UserInfoHolder(ObjectPool.get<Novel>(novelId).value?.user?.id ?: 0L))
        result.add(RedSectionHeaderHolder("简介"))
        result.add(NovelCaptionHolder(novelId))
        result.add(RedSectionHeaderHolder("正文"))
        result.add(SpaceHolder())

        wNovel?.let {
            (translatedText.split("\n") ?: listOf()).forEach { oneLineText ->
                result.addAll(
                    WebNovelParser.buildNovelHolders(it, oneLineText)
                )
            }
        }
        result.add(SpaceHolder())
        result.add(NovelTextHolder("<===== End =====>", Common.getNovelTextColor()))
        result.add(SpaceHolder())

        _itemHolders.value = result
    }
}
