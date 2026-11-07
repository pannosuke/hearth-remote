package net.pcg.hearth.remote.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HearthOverlayTest {

    @Test
    fun `the sample config parses on and off`() {
        val config = parseOverlay(SAMPLE)
        assertFalse(config.wxpill)
        assertTrue(config.quake)
        assertTrue(config.warn)
        assertTrue(config.sabres)
        assertTrue(config.weather)
        assertFalse(config[OverlayKey.WXPILL])
        assertTrue(config[OverlayKey.SABRES])
    }

    @Test
    fun `a missing or unexpected value is not a config`() {
        assertFails { parseOverlay("""{"wxpill":"off","quake":"on","warn":"on","weather":"on"}""") }
        assertFails { parseOverlay("""{"wxpill":"off","quake":"on","warn":"on","sabres":"maybe","weather":"on"}""") }
        assertFails { parseOverlay("not json") }
    }

    @Test
    fun `read and set use the paired tv host`() {
        val calls = mutableListOf<String>()
        var body = SAMPLE
        val client = HearthOverlay(
            hostProvider = { "192.168.10.105" },
            transport = OverlayTransport { call ->
                calls += call.url
                OverlayResult(200, body)
            }
        )
        val read = client.read()
        assertFalse(read.wxpill)
        assertEquals("http://192.168.10.105:8765/overlay", calls.single())

        body = SAMPLE.replace(""""wxpill":"off"""", """"wxpill":"on"""")
        val set = client.set(OverlayKey.WXPILL, true)
        assertTrue(set.wxpill)
        assertEquals("http://192.168.10.105:8765/overlay?wxpill=on", calls[1])
        assertEquals(2, calls.size)
    }

    @Test
    fun `turning a switch off is a query on the same path`() {
        val calls = mutableListOf<String>()
        val client = HearthOverlay(
            hostProvider = { "192.168.10.105" },
            transport = OverlayTransport { call ->
                calls += call.url
                OverlayResult(200, SAMPLE.replace(""""sabres":"on"""", """"sabres":"off""""))
            }
        )
        val set = client.set(OverlayKey.SABRES, false)
        assertFalse(set.sabres)
        assertEquals("http://192.168.10.105:8765/overlay?sabres=off", calls.single())
    }

    @Test
    fun `a non-200 or a network error fails the client and an unknown key is rejected`() {
        val http = HearthOverlay(
            hostProvider = { "192.168.10.105" },
            transport = OverlayTransport { OverlayResult(404, "missing") }
        )
        assertFails { http.read() }

        val down = HearthOverlay(
            hostProvider = { "192.168.10.105" },
            transport = OverlayTransport { throw IOException("connection refused") }
        )
        assertFails { down.read() }
        assertFails { down.set(OverlayKey.WEATHER, false) }

        val client = HearthOverlay(
            hostProvider = { "192.168.10.105" },
            transport = OverlayTransport { OverlayResult(200, SAMPLE) }
        )
        assertFails { client.set("nope", true) }
        assertFails { HearthOverlay(hostProvider = { "  " }).read() }
    }

    @Test
    fun `a custom host is used and a scheme is stripped`() {
        assertEquals(
            "http://tv.home:8765/overlay?quake=on",
            overlayUrl("http://tv.home/", mapOf(OverlayKey.QUAKE to "on"))
        )
    }

    @Test
    fun `a failed read disables the controls and does not throw`() = runBlocking {
        val client = HearthOverlay(
            hostProvider = { "192.168.10.105" },
            transport = OverlayTransport { throw IOException("unreachable") }
        )
        val controller = OverlayController(client, this)
        controller.refreshNow()
        assertFalse(controller.state.value.available)
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.config)
        controller.setNow(OverlayKey.WXPILL, true)
        assertFalse(controller.state.value.available)
    }

    @Test
    fun `a failed write keeps the last config and disables the controls`() = runBlocking {
        var failWrite = false
        val client = HearthOverlay(
            hostProvider = { "192.168.10.105" },
            transport = OverlayTransport {
                if (failWrite) throw IOException("reset")
                OverlayResult(200, SAMPLE)
            }
        )
        val controller = OverlayController(client, this)
        controller.refreshNow()
        assertTrue(controller.state.value.available)
        assertFalse(controller.state.value.config!!.wxpill)
        failWrite = true
        controller.setNow(OverlayKey.WXPILL, true)
        assertFalse(controller.state.value.available)
        assertFalse(controller.state.value.config!!.wxpill)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (e: OverlayException) {
            return
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected a failure")
    }

    companion object {
        private const val SAMPLE =
            """{"wxpill":"off","quake":"on","warn":"on","sabres":"on","weather":"on"}"""
    }
}
