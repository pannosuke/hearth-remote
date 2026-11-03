package net.pcg.hearth.remote.protocol

import net.pcg.hearth.remote.proto.RemoteProto.RemoteDirection
import net.pcg.hearth.remote.proto.RemoteProto.RemoteKeyCode
import net.pcg.hearth.remote.proto.RemoteProto.RemoteMessage
import net.pcg.hearth.remote.proto.RemoteProto.RemoteStart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden bytes for the remote configure reply.
 * A typical TV offers code1 639. The reply is the intersection, 615,
 * with model and vendor filled in. code1 is not the hardcoded 622.
 * Field numbers: configure=1, set_active=2, ping_response=9, key_inject=10, start=40.
 * Direction SHORT is 3.
 */
class RemoteMessageEncodingTest {

    @Test
    fun `configure reply for device 639 is 615 with model and vendor`() {
        val bytes = RemoteMessages.configureResponse(639).toByteArray()
        assertArrayEquals(hex(CONFIGURE_615), bytes)
        val parsed = RemoteMessage.parseFrom(bytes)
        assertEquals(615, parsed.remoteConfigure.code1)
        assertEquals("Hearth Remote", parsed.remoteConfigure.deviceInfo.model)
        assertEquals("pannosuke", parsed.remoteConfigure.deviceInfo.vendor)
        assertEquals(1, parsed.remoteConfigure.deviceInfo.unknown1)
        assertEquals("1", parsed.remoteConfigure.deviceInfo.unknown2)
        assertEquals("atvremote", parsed.remoteConfigure.deviceInfo.packageName)
        assertEquals("1.0.0", parsed.remoteConfigure.deviceInfo.appVersion)
    }

    @Test
    fun `set active reply is the negotiated mask on field 2`() {
        val bytes = RemoteMessages.setActiveResponse(615).toByteArray()
        assertArrayEquals(hex(SET_ACTIVE_615), bytes)
        assertEquals(0x12.toByte(), bytes[0])
        assertEquals(615, RemoteMessage.parseFrom(bytes).remoteSetActive.active)
        assertEquals(2, RemoteMessage.REMOTE_SET_ACTIVE_FIELD_NUMBER)
    }

    @Test
    fun `ping response echoes val1 on field 9`() {
        val bytes = RemoteMessages.pingResponse(5).toByteArray()
        assertArrayEquals(hex(PING_VAL1_5), bytes)
        assertEquals(5, RemoteMessage.parseFrom(bytes).remotePingResponse.val1)
        assertEquals(9, RemoteMessage.REMOTE_PING_RESPONSE_FIELD_NUMBER)
        assertEquals(8, RemoteMessage.REMOTE_PING_REQUEST_FIELD_NUMBER)
    }

    @Test
    fun `home key is field 10 with direction short`() {
        val bytes = RemoteMessages.keyInject(RemoteKeyCode.KEYCODE_HOME).toByteArray()
        assertArrayEquals(hex(KEY_HOME_SHORT), bytes)
        val parsed = RemoteMessage.parseFrom(bytes)
        assertEquals(RemoteKeyCode.KEYCODE_HOME, parsed.remoteKeyInject.keyCode)
        assertEquals(RemoteDirection.SHORT, parsed.remoteKeyInject.direction)
        assertEquals(3, parsed.remoteKeyInject.direction.number)
        assertEquals(10, RemoteMessage.REMOTE_KEY_INJECT_FIELD_NUMBER)
    }

    @Test
    fun `mute key encodes key code 164 and direction short`() {
        val bytes = RemoteMessages.keyInject(RemoteKeyCode.KEYCODE_VOLUME_MUTE).toByteArray()
        assertArrayEquals(hex(KEY_MUTE_SHORT), bytes)
        val parsed = RemoteMessage.parseFrom(bytes)
        assertEquals(164, parsed.remoteKeyInject.keyCode.number)
        assertEquals(RemoteDirection.SHORT, parsed.remoteKeyInject.direction)
    }

    @Test
    fun `remote start is field 40`() {
        val message = RemoteMessage.newBuilder()
            .setRemoteStart(RemoteStart.newBuilder().setStarted(true))
            .build()
        val bytes = message.toByteArray()
        assertArrayEquals(hex(START_TRUE), bytes)
        assertEquals(40, RemoteMessage.REMOTE_START_FIELD_NUMBER)
        assertEquals(1, RemoteMessage.REMOTE_CONFIGURE_FIELD_NUMBER)
        assertTrueStarted(bytes)
    }

    private fun assertTrueStarted(bytes: ByteArray) {
        assertEquals(true, RemoteMessage.parseFrom(bytes).remoteStart.started)
    }

    private fun hex(value: String): ByteArray {
        return ByteArray(value.length / 2) { i ->
            value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val CONFIGURE_615 =
            "0a3608e70412310a0d4865617274682052656d6f7465120970616e6e6f73756b6518012201312a0961747672656d6f74653205312e302e30"
        private const val SET_ACTIVE_615 = "120308e704"
        private const val PING_VAL1_5 = "4a020805"
        private const val KEY_HOME_SHORT = "520408031003"
        private const val KEY_MUTE_SHORT = "520508a4011003"
        private const val START_TRUE = "c202020801"
    }
}
