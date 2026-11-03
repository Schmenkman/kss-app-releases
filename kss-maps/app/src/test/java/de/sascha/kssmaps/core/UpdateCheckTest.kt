package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    private fun rel(tag: String, apk: Boolean = true, pre: Boolean = false, body: String = "- Neu: A\n- Neu: B\n\n**Installation:** …") =
        """{"tag_name":"$tag","draft":false,"prerelease":$pre,"body":${org.json.JSONObject.quote(body)},
            "assets":[${if (apk) """{"name":"kss-maps.apk","browser_download_url":"https://github.com/x/y/releases/download/$tag/kss-maps.apk"}""" else """{"name":"testfahrt_01.png","browser_download_url":"https://x/1.png"}"""}]}"""

    @Test
    fun picksNewestKssMapsReleaseAndIgnoresOthers() {
        val json = "[" + listOf(
            rel("dokubw-v1.3.4"),
            rel("kssmaps-testfahrt", apk = false, pre = true),
            rel("kssmaps-v1.0.0"),
            rel("kssmaps-v1.1.1"),
            rel("kssmaps-v1.1.0"),
            rel("kssmaps-v1.2.0", apk = false),
        ).joinToString(",") + "]"
        val info = UpdateCheck.latest(json)!!
        assertEquals("1.1.1", info.version)
        assertEquals(listOf("Neu: A", "Neu: B"), info.notes)
        assertTrue(info.apkUrl.endsWith("/kssmaps-v1.1.1/kss-maps.apk"))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(UpdateCheck.compare("1.10.0", "1.9.3") > 0)
        assertTrue(UpdateCheck.compare("1.1.1", "1.1.1") == 0)
        assertTrue(UpdateCheck.compare("1.1", "1.1.1") < 0)
        assertNull(UpdateCheck.latest("[]"))
    }
}
