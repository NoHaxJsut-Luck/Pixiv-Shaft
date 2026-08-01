package ceui.pixiv.ui.common

import android.text.SpannableStringBuilder
import android.text.util.Linkify
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.text.util.LinkifyCompat
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.openChromeTab
import ceui.pixiv.ui.novel.CustomLinkMovementMethod
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.extractPixivId
import timber.log.Timber

fun TextView.setCaptionHtml(html: String) {
    val content = SpannableStringBuilder(
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
    )
    LinkifyCompat.addLinks(content, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)

    text = content
    linksClickable = true
    movementMethod = CustomLinkMovementMethod { link ->
        if (!openPixivLink(link)) {
            runCatching { context.openChromeTab(link) }
                .onFailure { Timber.w(it, "Unable to open caption link: %s", link) }
        }
    }
}

private fun TextView.openPixivLink(link: String): Boolean {
    val linkInfo = extractPixivId(link)
    val id = linkInfo.value.toLongOrNull() ?: return false
    return when (linkInfo.type) {
        "novels" -> findActionReceiverOrNull<NovelActionReceiver>()?.let {
            it.visitNovelById(id)
            true
        } ?: false

        "illusts" -> findActionReceiverOrNull<IllustCardActionReceiver>()?.let {
            it.visitIllustById(id)
            true
        } ?: false

        "users" -> findActionReceiverOrNull<UserActionReceiver>()?.let {
            it.onClickUser(id)
            true
        } ?: false

        else -> false
    }
}
