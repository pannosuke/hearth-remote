package net.pcg.hearth.remote.protocol

/**
 * Which remote to show. Live TV is the Sharp tuner package. CHRC values such
 * as TB10 are only a fallback while the TV has not reported a package: a
 * known app stays on the generic remote even if the tuner still remembers
 * the last terrestrial channel.
 */
internal object Foreground {
    const val LIVE_TV_PACKAGE = "jp.co.sharp.av.android.aquostvapp"

    fun isTerrestrial(packageName: String?, channel: String?): Boolean {
        if (packageName == LIVE_TV_PACKAGE) return true
        if (!packageName.isNullOrBlank()) return false
        return channel?.startsWith("TB") == true
    }
}

data class TerrestrialChannel(
    val button: Int,
    val name: String
)

/**
 * Osaka one-touch buttons, in the order Eric asked for: 10, 8, 7, 6, then
 * 1, 2, 4, 12, then the rest. Names are the ones used on the cards.
 * Button 12 has no confirmed broadcaster, so it stays "12".
 */
internal object OsakaChannels {
    val buttons: List<TerrestrialChannel> = listOf(
        TerrestrialChannel(10, "YTV"),
        TerrestrialChannel(8, "KTV"),
        TerrestrialChannel(7, "TVO"),
        TerrestrialChannel(6, "ABC"),
        TerrestrialChannel(1, "NHK"),
        TerrestrialChannel(2, "NHK E"),
        TerrestrialChannel(4, "MBS"),
        TerrestrialChannel(12, "12"),
        TerrestrialChannel(3, "SUN")
    )

    fun buttonNumber(chrc: String?): Int? {
        if (chrc == null || !chrc.startsWith("TB")) return null
        return chrc.removePrefix("TB").toIntOrNull()
    }

    /**
     * A CHRC reading such as TB10. The busy sentence the TV sends when a
     * second IP session connects is not a channel and must not be shown.
     */
    fun channelReading(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.length !in 1..8) return null
        if (value.any { it.isWhitespace() || it == '.' }) return null
        if (value.equals("ERR", ignoreCase = true) || value.equals("OK", ignoreCase = true)) return null
        if (SharpIpCommands.isServiceBusy(value)) return null
        return value
    }

    fun stationName(chrc: String?): String? {
        val button = buttonNumber(channelReading(chrc)) ?: return null
        return find(button)?.name
    }

    fun find(button: Int): TerrestrialChannel? = buttons.find { it.button == button }

    fun chrcCommand(button: Int): String = button.toString()
}

data class TunerStatus(
    val volume: Int? = null,
    val mute: Int? = null,
    val channel: String? = null
) {
    val muted: Boolean?
        get() = when (mute) {
            1 -> true
            2 -> false
            else -> null
        }
}
