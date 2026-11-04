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
            assertEquals(HearthLaunch.https(app.packageName), sent.getValue(id))
            assertEquals(HearthLaunch.scheme(app.packageName), app.fallback)
            assertEquals(app.fallback, HearthLaunch.fallback(app.link))
        }
        TvAppLinks.ALL.filter { it.fallback == null }.forEach { app ->
            assertTrue(app.link.startsWith("https://"))
            assertFalse(app.link.contains("hearth.local"))
            assertEquals(null, HearthLaunch.fallback(app.link))
        }
        sent.values.forEach { link ->
            assertFalse(link.startsWith("market://"))
            assertFalse(link.startsWith("intent:"))
        }
    }

    @Test
    fun `rejected links become the hearth trampoline`() {
        assertEquals(
            HearthLaunch.https("org.jellyfin.androidtv"),
            RemoteMessages.normalizeAppLink("market://launch?id=org.jellyfin.androidtv")
        )
        assertEquals(
            HearthLaunch.https("com.netflix.ninja"),
            RemoteMessages.normalizeAppLink("com.netflix.ninja")
        )
        assertEquals(
            HearthLaunch.https("org.jellyfin.androidtv"),
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
        assertEquals(
            "hearth://launch?pkg=org.jellyfin.androidtv",
            HearthLaunch.fallback("https://hearth.local/launch?pkg=org.jellyfin.androidtv")
        )
        assertEquals(null, HearthLaunch.fallback("hearth://launch?pkg=org.jellyfin.androidtv"))
    }

    @Test
    fun `remote error does not close and retries the hearth scheme`() {
        val https = HearthLaunch.https("org.jellyfin.androidtv")
        val rejected = RemoteMessage.newBuilder()
            .setRemoteAppLinkLaunchRequest(
                net.pcg.hearth.remote.proto.RemoteProto.RemoteAppLinkLaunchRequest.newBuilder()
                    .setAppLink(https)
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
        assertEquals(https, rejectedAppLink(message))
        assertEquals(HearthLaunch.scheme("org.jellyfin.androidtv"), HearthLaunch.fallback(rejectedAppLink(message)!!))
        val text = describeRemoteMessage(message)
        assertTrue(text.contains("value=true"))
        assertTrue(text.contains("app_link $https"))
        assertFalse(text.contains("session kept"))
        val plan = planReconnect(wasUp = true, quietLeft = 0, afterError = true)
        assertTrue(plan.quiet)
        assertEquals(0L, plan.delayMs)
    }

    private fun sentLink(link: String): String =
        RemoteMessages.appLink(link).remoteAppLinkLaunchRequest.appLink
}
