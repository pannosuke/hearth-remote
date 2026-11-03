package net.pcg.hearth.remote.protocol

import net.pcg.hearth.remote.proto.RemoteProto.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAppLinkTest {

    @Test
    fun `active features keep app link when the TV offers it`() {
        // 639 is the bitmask androidtvremote2 logs for a typical TV:
        // PING|KEY|IME|VOICE|UNKNOWN|POWER|VOLUME|APP_LINK
        val device = 639
        val active = RemoteMessages.activeFeatures(device)
        assertEquals(RemoteMessages.REQUESTED_FEATURES, active)
        assertEquals(615, active)
        assertTrue(active and RemoteMessages.FEATURE_APP_LINK != 0)
        assertEquals(0, active and (1 shl 3))
    }

    @Test
    fun `configure response is code 622 with model and vendor`() {
        val message = RemoteMessages.configureResponse()
        assertEquals(622, message.remoteConfigure.code1)
        val info = message.remoteConfigure.deviceInfo
        assertEquals("Hearth Remote", info.model)
        assertEquals("PCG", info.vendor)
        assertEquals(1, info.unknown1)
        assertEquals("1", info.unknown2)
        assertEquals("atvremote", info.packageName)
        assertEquals("1.0.0", info.appVersion)
        assertFalse(message.hasRemoteAppLinkLaunchRequest())
    }

    @Test
    fun `set active is 622`() {
        val message = RemoteMessages.setActiveResponse()
        assertEquals(622, message.remoteSetActive.active)
    }

    @Test
    fun `youtube launch is an https app link on field 90`() {
        val message = RemoteMessages.appLink(TvAppLinks.YOUTUBE)
        assertEquals("https://www.youtube.com", message.remoteAppLinkLaunchRequest.appLink)
        val bytes = message.toByteArray()
        assertEquals(message, RemoteMessage.parseFrom(bytes))
        // field 90, length-delimited: tag varint (90 << 3) | 2 = 722 = 0xD2 0x05
        assertEquals(0xD2.toByte(), bytes[0])
        assertEquals(0x05.toByte(), bytes[1])
    }

    @Test
    fun `package ids become market launch links`() {
        assertEquals(
            "market://launch?id=com.netflix.ninja",
            RemoteMessages.normalizeAppLink("com.netflix.ninja")
        )
        assertEquals(TvAppLinks.NETFLIX, RemoteMessages.appLink(TvAppLinks.NETFLIX).remoteAppLinkLaunchRequest.appLink)
        assertEquals("com.netflix.ninja", packageId(TvAppLinks.NETFLIX))
        assertEquals("com.disney.disneyplus", packageId(TvAppLinks.DISNEY_PLUS))
        assertEquals("com.amazon.amazonvideo.livingroom", packageId(TvAppLinks.PRIME_VIDEO))
        assertEquals("org.jellyfin.androidtv", packageId(TvAppLinks.JELLYFIN))
        assertEquals("jp.co.tver.tvapp", packageId(TvAppLinks.TVER))
        assertEquals("jp.nhk.plus.tv", packageId(TvAppLinks.NHK))
        assertEquals("jp.happyon.android", packageId(TvAppLinks.HULU))
        assertEquals("com.google.android.tv", packageId(TvAppLinks.LIVE_TV))
    }

    private fun packageId(link: String): String = link.substringAfter("id=")
}
