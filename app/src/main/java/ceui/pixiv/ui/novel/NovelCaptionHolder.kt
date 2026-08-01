package ceui.pixiv.ui.novel

import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCaptionBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.ShareIllust
import ceui.loxia.DateParse
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.NOVEL_URL_HEAD
import ceui.pixiv.ui.common.setCaptionHtml
import ceui.pixiv.utils.setOnClick


class NovelCaptionHolder(val novelId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return novelId
    }
}

@ItemHolder(NovelCaptionHolder::class)
class NovelCaptionViewHolder(bd: CellNovelCaptionBinding) : ListItemViewHolder<CellNovelCaptionBinding, NovelCaptionHolder>(bd) {

    override fun onBindViewHolder(holder: NovelCaptionHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveNovel = ObjectPool.get<Novel>(holder.novelId)
        binding.novel = liveNovel
        liveNovel.observe(lifecycleOwner) { novel ->
            if (novel.caption?.isNotEmpty() == true) {
                binding.caption.isVisible = true
                binding.caption.setCaptionHtml(novel.caption)
            } else {
                binding.caption.isVisible = false
            }
            binding.illustLink.text =
                context.getString(R.string.artwork_link, NOVEL_URL_HEAD + novel.id)
            binding.illustLink.setOnClick {
                Common.copy(context, NOVEL_URL_HEAD + novel.id)
            }

            binding.userLink.text =
                context.getString(R.string.user_link, ShareIllust.USER_URL_Head + novel.user?.id)
            binding.userLink.setOnClick {
                Common.copy(context, ShareIllust.USER_URL_Head + novel.user?.id)
            }

            binding.userId.setOnClick {
                Common.copy(context, novel.user?.id?.toString())
            }
            binding.publishTime.text = context.getString(
                R.string.published_on,
                DateParse.getTimeAgo(context, novel.create_date)
            )
        }
        binding.illustId.setOnClick {
            Common.copy(context, holder.novelId.toString())
        }
    }
}
