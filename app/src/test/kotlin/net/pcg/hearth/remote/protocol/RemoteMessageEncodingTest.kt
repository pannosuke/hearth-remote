package net.pcg.hearth.remote.protocol

import net.pcg.hearth.remote.proto.RemoteProto.RemoteDirection
import net.pcg.hearth.remote.proto.RemoteProto.RemoteKeyCode
import net.pcg.hearth.remote.proto.RemoteProto.RemoteMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden bytes for the messages the TV actually has to accept.
 * Field numbers: configure=1, set_active=2, ping_response=9, key_inject=10.
 * Direction SHORT is 3. code1 and active are 622.
 */
class RemoteMessageEncodingTest {

    @Test
    fun `configure reply is 622 with a full device info`() {
        val bytes = RemoteMessages.configureResponse().toByteArray()
        assertArrayEquals(hex(CONFIGURE_622), bytes)
        val parsed = RemoteMessage.parseFrom(bytes)
        assertEquals(622, parsed.remoteConfigure.code1)
        assertEquals("Hearth Remote", parsed.remoteConfigure.deviceInfo.model)
        assertEquals("PCG", parsed.remoteConfigure.deviceInfo.vendor)
        assertEquals(1, parsed.remoteConfigure.deviceInfo.unknown1)
        assertEquals("1", parsed.remoteConfigure.deviceInfo.unknown2)
        assertEquals("atvremote", parsed.remoteConfigure.deviceInfo.packageName)
        assertEquals("1.0.0", parsed.remoteConfigure.deviceInfo.appVersion)
    }

    @Test
    fun `set active reply is 622`() {
        val bytes = RemoteMessages.setActiveResponse().toByteArray()
        assertArrayEquals(hex(SET_ACTIVE_622), bytes)
        assertEquals(622, RemoteMessage.parseFrom(bytes).remoteSetActive.active)
    }

    @Test
    fun `ping response echoes val1 on field 9`() {
        val bytes = RemoteMessages.pingResponse(5).toByteArray()
        assertArrayEquals(hex(PING_VAL1_5), bytes)
        assertEquals(5, RemoteMessage.parseFrom(bytes).remotePingResponse.val1)
    }

    @Test
    fun `home key is field 10 with direction short`() {
        val bytes = RemoteMessages.keyInject(RemoteKeyCode.KEYCODE_HOME).toByteArray()
        assertArrayEquals(hex(KEY_HOME_SHORT), bytes)
        val parsed = RemoteMessage.parseFrom(bytes)
        assertEquals(RemoteKeyCode.KEYCODE_HOME, parsed.remoteKeyInject.keyCode)
        assertEquals(RemoteDirection.SHORT, parsed.remoteKeyInject.direction)
        assertEquals(3, parsed.remoteKeyInject.direction.number)
    }

    @Test
    fun `mute key encodes key code 164 and direction short`() {
        val bytes = RemoteMessages.keyInject(RemoteKeyCode.KEYCODE_VOLUME_MUTE).toByteArray()
        assertArrayEquals(hex(KEY_MUTE_SHORT), bytes)
        val parsed = RemoteMessage.parseFrom(bytes)
        assertEquals(164, parsed.remoteKeyInject.keyCode.number)
        assertEquals(RemoteDirection.SHORT, parsed.remoteKeyInject.direction)
    }

    private fun hex(value: String): ByteArray {
        return ByteArray(value.length / 2) { i ->
            value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val CONFIGURE_622 =
            "0a3008ee04122b0a0d4865617274682052656d6f7465120350434718012201312a0961747672656d6f74653205312e302e30"
        private const val SET_ACTIVE_622 = "120308ee04"
        private const val PING_VAL1_5 = "4a020805"
        private const val KEY_HOME_SHORT = "520408031003"
        private const val KEY_MUTE_SHORT = "520508a4011003"
    }
}
