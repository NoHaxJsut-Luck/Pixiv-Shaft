package ceui.pixiv.utils

data class PixivLinkInfo(val type: String, val value: String)

fun extractPixivId(url: String): PixivLinkInfo {
    PIXIV_DEEP_LINK.find(url)?.let { match ->
        return PixivLinkInfo(match.groupValues[1], match.groupValues[2])
    }
    PIXIV_ARTWORK_URL.find(url)?.let { match ->
        return PixivLinkInfo("illusts", match.groupValues[1])
    }
    PIXIV_USER_URL.find(url)?.let { match ->
        return PixivLinkInfo("users", match.groupValues[1])
    }
    PIXIV_NOVEL_URL.find(url)?.let { match ->
        return PixivLinkInfo("novels", match.groupValues[1])
    }

    return PixivLinkInfo("others", url)
}

private val PIXIV_DEEP_LINK = Regex("""pixiv://(novels|illusts|users)/(\d+)""")
private val PIXIV_ARTWORK_URL =
    Regex("""https?://(?:www\.)?pixiv\.net/(?:[a-z]{2}/)?artworks/(\d+)""", RegexOption.IGNORE_CASE)
private val PIXIV_USER_URL =
    Regex("""https?://(?:www\.)?pixiv\.net/(?:[a-z]{2}/)?users/(\d+)""", RegexOption.IGNORE_CASE)
private val PIXIV_NOVEL_URL =
    Regex("""https?://(?:www\.)?pixiv\.net/novel/show\.php\?[^#]*\bid=(\d+)""", RegexOption.IGNORE_CASE)
