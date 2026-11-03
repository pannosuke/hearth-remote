package net.pcg.hearth.remote.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinTest {

    @Test
    fun `server url keeps the scheme and drops a trailing slash`() {
        assertEquals("http://192.168.10.114:8096", JellyfinApi.normalizeServerUrl("http://192.168.10.114:8096/"))
        assertEquals("http://192.168.10.114:8096", JellyfinApi.normalizeServerUrl("192.168.10.114:8096"))
    }

    @Test
    fun `image url does not carry the access token`() {
        val url = JellyfinApi.imageUrl("http://lan:8096", "item 1", "tag")
        assertTrue(url.startsWith("http://lan:8096/Items/item%201/Images/Primary?"))
        assertTrue(url.contains("tag=tag"))
        assertFalse(url.contains("api_key"))
        assertFalse(url.contains("Token"))
    }

    @Test
    fun `subtitles match jpn and eng and titles, and off is minus one`() {
        val streams = listOf(
            JellyfinStream(0, "Video", "und", null, null),
            JellyfinStream(2, "Subtitle", "jpn", null, "Japanese"),
            JellyfinStream(4, "Subtitle", null, "英語", "English"),
            JellyfinStream(5, "Subtitle", "und", "日本語", "Japanese SDH")
        )
        assertEquals(2, JellyfinApi.matchingSubtitle(streams, SubtitleTrack.Japanese))
        assertEquals(4, JellyfinApi.matchingSubtitle(streams, SubtitleTrack.English))
        assertEquals(-1, JellyfinApi.matchingSubtitle(streams, SubtitleTrack.Off))
        assertNull(JellyfinApi.matchingSubtitle(listOf(streams[0]), SubtitleTrack.English))
        assertEquals(
            """{"Name":"SetSubtitleStreamIndex","Arguments":{"Index":"-1"}}""",
            JellyfinApi.subtitleBody(-1)
        )
    }

    @Test
    fun `seek clamps to the programme and a missing position does not jump`() {
        val start = 5L * JellyfinApi.TICKS_PER_SECOND
        val runtime = 60L * JellyfinApi.TICKS_PER_SECOND
        assertEquals(0L, JellyfinApi.seekTicks(start, -10, runtime))
        assertEquals(35L * JellyfinApi.TICKS_PER_SECOND, JellyfinApi.seekTicks(start, 30, runtime))
        assertEquals(runtime, JellyfinApi.seekTicks(runtime - JellyfinApi.TICKS_PER_SECOND, 30, runtime))
        assertNull(JellyfinApi.seekTicks(null, -10, runtime))
    }

    @Test
    fun `one television is chosen and a phone session is not`() {
        val sessions = JellyfinApi.parseSessions(SESSIONS)
        val pick = JellyfinApi.pickSession(sessions, ourDeviceId = "phone", rememberedDeviceId = null)
        assertEquals(1, pick.choices.size)
        assertEquals("tv-session", pick.selected?.id)
        assertEquals("Ted Lasso", pick.selected?.nowPlaying?.headline())
        assertEquals("S1 E1 · The Pilot", pick.selected?.nowPlaying?.detail())
        assertEquals(
            0.5f,
            JellyfinApi.fraction(
                pick.selected?.nowPlaying?.positionTicks,
                pick.selected?.nowPlaying?.runtimeTicks
            )!!,
            0.001f
        )
        val both = JellyfinApi.pickSession(
            sessions + JellyfinSession("other", JellyfinSession.TV_CLIENT, "Bedroom", "d2", true, null),
            ourDeviceId = "phone",
            rememberedDeviceId = null
        )
        assertNull(both.selected)
        assertEquals(2, both.choices.size)
        val remembered = JellyfinApi.pickSession(both.choices, "phone", "d2")
        assertEquals("other", remembered.selected?.id)
    }

    @Test
    fun `an AQUOS device name counts even when the client string is the phone app`() {
        val session = JellyfinSession(
            id = "s",
            client = "Jellyfin for Android TV",
            deviceName = "AQUOS-TVJ19",
            deviceId = "tv",
            supportsRemoteControl = true,
            nowPlaying = null
        )
        assertTrue(session.isTelevision())
        val phone = session.copy(deviceName = "Pixel", client = "Jellyfin for Android")
        assertFalse(phone.isTelevision())
    }

    @Test
    fun `sign in keeps the token and drops the password`() = runBlocking {
        val store = MemoryStore()
        val transport = ScriptedTransport()
        val remote = JellyfinRemote(store, transport, version = "0.2.3", clock = { 1_000L })
        remote.signIn("http://192.168.10.114:8096/", "bee", PASSWORD)

        val login = store.read()
        assertEquals("token-1", login?.accessToken)
        assertEquals("user-1", login?.userId)
        assertEquals("http://192.168.10.114:8096", login?.serverUrl)
        assertFalse(login.toString().contains(PASSWORD))
        assertEquals("Ted Lasso", remote.state.value.nowPlaying?.headline())
        assertEquals("ep-2", remote.state.value.upNext.single().itemId)
        assertTrue(transport.calls.first().body!!.contains(PASSWORD))
        assertTrue(transport.calls.drop(1).all { call ->
            call.body?.contains(PASSWORD) != true && call.headers["X-Emby-Token"] == "token-1"
        })
        assertFalse(transport.calls.any { it.url.contains(PASSWORD) })

        remote.setSubtitle(SubtitleTrack.Japanese)
        remote.setSubtitle(SubtitleTrack.Off)
        val commands = transport.calls.filter { it.url.contains("/Command") }
        assertTrue(commands[commands.lastIndex - 1].body!!.contains("\"Index\":\"2\""))
        assertTrue(commands.last().body!!.contains("\"Index\":\"-1\""))

        remote.play("ep-2")
        val play = transport.calls.last { it.url.contains("playCommand=PlayNow") }
        assertTrue(play.url.contains("itemIds=ep-2"))
        assertTrue(play.url.contains("startPositionTicks=0"))

        remote.seekTo(35L * JellyfinApi.TICKS_PER_SECOND)
        val seek = transport.calls.last { it.url.contains("/Seek") }
        assertTrue(seek.url.contains("seekPositionTicks=${35L * JellyfinApi.TICKS_PER_SECOND}"))

        transport.sessionsCode = 401
        remote.refresh()
        assertNull(store.read())
        assertEquals(JellyfinProblem.Unauthorized, remote.state.value.problem)
        assertEquals("http://192.168.10.114:8096", store.serverUrl())
    }

    private class MemoryStore : JellyfinCredentialStore {
        private var login: JellyfinLogin? = null
        private var selected: String? = null
        private var url: String = ""
        override fun read(): JellyfinLogin? = login
        override fun write(login: JellyfinLogin) {
            this.login = login
            url = login.serverUrl
        }
        override fun clearLogin() {
            login = null
            selected = null
        }
        override fun serverUrl(): String = url
        override fun deviceId(): String = "phone"
        override fun selectedDeviceId(): String? = selected
        override fun setSelectedDeviceId(id: String?) {
            selected = id
        }
    }

    private class ScriptedTransport : JellyfinTransport {
        val calls = mutableListOf<JellyfinCall>()
        var sessionsCode = 200
        override fun exchange(request: JellyfinCall): JellyfinResult {
            calls += request
            val body = when {
                request.url.endsWith("/Users/AuthenticateByName") -> AUTH
                request.url.endsWith("/Sessions") && sessionsCode != 200 -> return JellyfinResult(sessionsCode, ByteArray(0))
                request.url.endsWith("/Sessions") -> SESSIONS
                request.url.contains("/Shows/NextUp") -> NEXT
                else -> ""
            }
            return JellyfinResult(if (body.isEmpty() && !request.url.contains("Authenticate")) 204 else 200, body.toByteArray())
        }
    }

    private companion object {
        const val PASSWORD = "s3cret-pw"
        val AUTH = """
            {"User":{"Name":"Bee","Id":"user-1"},"AccessToken":"token-1","ServerId":"srv"}
        """.trimIndent()
        val SESSIONS = """
            [
              {"Id":"hearth","Client":"Hearth Remote","DeviceName":"Phone","DeviceId":"phone","SupportsRemoteControl":false},
              {"Id":"handset","Client":"Jellyfin for Android","DeviceName":"Pixel","DeviceId":"pixel","SupportsRemoteControl":true},
              {
                "Id":"tv-session",
                "Client":"Jellyfin Android TV",
                "DeviceName":"AQUOS",
                "DeviceId":"tv-1",
                "SupportsRemoteControl":true,
                "PlayState":{"PositionTicks":18000000000,"IsPaused":false,"SubtitleStreamIndex":2},
                "NowPlayingItem":{
                  "Id":"ep-1",
                  "Name":"The Pilot",
                  "SeriesName":"Ted Lasso",
                  "ParentIndexNumber":1,
                  "IndexNumber":1,
                  "RunTimeTicks":36000000000,
                  "ImageTags":{"Primary":"poster"},
                  "MediaStreams":[
                    {"Index":0,"Type":"Video"},
                    {"Index":2,"Type":"Subtitle","Language":"jpn","DisplayTitle":"Japanese"},
                    {"Index":3,"Type":"Subtitle","Language":"eng","DisplayTitle":"English"}
                  ]
                }
              }
            ]
        """.trimIndent()
        val NEXT = """
            {"Items":[{"Id":"ep-2","Name":"Biscuits","SeriesName":"Ted Lasso","ParentIndexNumber":1,"IndexNumber":2,"ImageTags":{"Primary":"t2"}}]}
        """.trimIndent()
    }
}
