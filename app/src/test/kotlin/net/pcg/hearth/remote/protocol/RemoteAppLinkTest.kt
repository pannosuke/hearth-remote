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
    fun `configure response intersects the device code and names this remote`() {
        val message = RemoteMessages.configureResponse(639)
        assertEquals(615, message.remoteConfigure.code1)
        val info = message.remoteConfigure.deviceInfo
        assertEquals("Hearth Remote", info.model)
        assertEquals("pannosuke", info.vendor)
        assertEquals(1, info.unknown1)
        assertEquals("1", info.unknown2)
        assertEquals("atvremote", info.packageName)
        assertEquals("1.0.0", info.appVersion)
        assertFalse(message.hasRemoteAppLinkLaunchRequest())
        // 622 includes voice and drops ping. The reply keeps only shared bits.
        assertEquals(614, RemoteMessages.configureResponse(622).remoteConfigure.code1)
    }

    @Test
    fun `set active repeats the negotiated mask`() {
        val message = RemoteMessages.setActiveResponse(615)
        assertEquals(615, message.remoteSetActive.active)
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
    fun `apps tab sends one launchable link per shortcut`() {
        assertEquals(
            listOf("youtube", "netflix", "disney", "prime", "jellyfin", "tver", "nhk", "hulu", "live_tv"),
            TvAppLinks.ALL.map { it.id }
        )
        val sent = TvAppLinks.ALL.associate { it.id to sentLink(it.link) }
        assertEquals("https://www.youtube.com", sent.getValue("youtube"))
        assertEquals("https://www.netflix.com/title", sent.getValue("netflix"))
        assertEquals("https://www.disneyplus.com", sent.getValue("disney"))
        assertEquals("https://app.primevideo.com", sent.getValue("prime"))
        assertEquals("https://tver.jp", sent.getValue("tver"))
        listOf("jellyfin", "nhk", "hulu", "live_tv").forEach { id ->
            val app = TvAppLinks.ALL.first { it.id == id }
            assertEquals(HearthLaunch.scheme(app.packageName), sent.getValue(id))
            assertTrue(sent.getValue(id).startsWith("hearth://launch?pkg="))
        }
        assertEquals(Foreground.JELLYFIN_PACKAGE, TvAppLinks.ALL.first { it.id == "jellyfin" }.packageName)
        assertEquals("org.jellyfin.androidtv", Foreground.JELLYFIN_PACKAGE)
        assertEquals(
            "hearth://launch?pkg=org.jellyfin.androidtv",
            sent.getValue("jellyfin")
        )
        sent.values.forEach { link ->
            assertFalse(link.contains("hearth.local"))
            assertFalse(link.startsWith("market://"))
            assertFalse(link.startsWith("intent:"))
        }
    }

    @Test
    fun `hearth https and package ids become the hearth scheme`() {
        assertEquals(
            "hearth://launch?pkg=org.jellyfin.androidtv",
            RemoteMessages.normalizeAppLink("https://hearth.local/launch?pkg=org.jellyfin.androidtv")
        )
        assertEquals(
            "hearth://launch?pkg=org.jellyfin.androidtv",
            RemoteMessages.normalizeAppLink("market://launch?id=org.jellyfin.androidtv")
        )
        assertEquals(
            "hearth://launch?pkg=com.netflix.ninja",
            RemoteMessages.normalizeAppLink("com.netflix.ninja")
        )
        assertEquals(
            "hearth://launch?pkg=org.jellyfin.androidtv",
            RemoteMessages.normalizeAppLink(
                "intent://#Intent;package=org.jellyfin.androidtv;" +
                    "action=android.intent.action.MAIN;" +
                    "category=android.intent.category.LEANBACK_LAUNCHER;end"
            )
        )
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            RemoteMessages.normalizeAppLink("https://www.youtube.com/watch?v=abc")
        )
        assertEquals(Foreground.JELLYFIN_PACKAGE, HearthLaunch.jellyfinPackage(null))
        assertEquals(Foreground.JELLYFIN_PACKAGE, HearthLaunch.jellyfinPackage("com.google.android.youtube.tv"))
        assertEquals("org.jellyfin.androidtv", HearthLaunch.jellyfinPackage("org.jellyfin.androidtv"))
        assertEquals("org.jellyfin.androidtv.debug", HearthLaunch.jellyfinPackage("org.jellyfin.androidtv.debug"))
    }

    @Test
    fun `a rejected hearth link is reported and does not close the session`() {
        val link = "hearth://launch?pkg=org.jellyfin.androidtv"
        val rejected = RemoteMessage.newBuilder()
            .setRemoteAppLinkLaunchRequest(
                net.pcg.hearth.remote.proto.RemoteProto.RemoteAppLinkLaunchRequest.newBuilder()
                    .setAppLink(link)
            )
            .build()
        val message = RemoteMessage.newBuilder()
            .setRemoteError(
                net.pcg.hearth.remote.proto.RemoteProto.RemoteError.newBuilder()
                    .setValue(true)
                    .setMessage(rejected)
            )
            .build()
        assertFalse(remoteErrorClosesSession(message))
        assertEquals(link, rejectedAppLink(message))
        assertEquals(link, HearthLaunch.rejectedLink(rejectedAppLink(message)))
        assertEquals(null, HearthLaunch.rejectedLink("https://www.youtube.com"))
        val text = describeRemoteMessage(message)
        assertTrue(text.contains("value=true"))
        assertTrue(text.contains("app_link $link"))
        val plan = planReconnect(wasUp = true, quietLeft = 0, afterError = true)
        assertTrue(plan.quiet)
        assertEquals(0L, plan.delayMs)
    }

    private fun sentLink(link: String): String =
        RemoteMessages.appLink(link).remoteAppLinkLaunchRequest.appLink
}
