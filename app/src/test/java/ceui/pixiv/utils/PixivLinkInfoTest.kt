package ceui.pixiv.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PixivLinkInfoTest {

    @Test
    fun extractsPixivDeepLinks() {
        assertEquals(PixivLinkInfo("illusts", "123"), extractPixivId("pixiv://illusts/123"))
        assertEquals(PixivLinkInfo("novels", "456"), extractPixivId("pixiv://novels/456"))
        assertEquals(PixivLinkInfo("users", "789"), extractPixivId("pixiv://users/789"))
    }

    @Test
    fun extractsPixivWebLinks() {
        assertEquals(
            PixivLinkInfo("illusts", "123"),
            extractPixivId("https://www.pixiv.net/zh/artworks/123")
        )
        assertEquals(
            PixivLinkInfo("users", "456"),
            extractPixivId("https://www.pixiv.net/users/456")
        )
        assertEquals(
            PixivLinkInfo("novels", "789"),
            extractPixivId("https://www.pixiv.net/novel/show.php?mode=cover&id=789")
        )
    }

    @Test
    fun leavesExternalLinksUntouched() {
        val url = "https://example.com/article"
        assertEquals(PixivLinkInfo("others", url), extractPixivId(url))
    }
}
