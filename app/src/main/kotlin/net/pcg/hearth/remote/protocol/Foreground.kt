package net.pcg.hearth.remote.protocol

/**
 * Which remote to show. Live TV is the Sharp tuner package. CHRC values such
 * as TB10 are only a fallback while the TV has not reported a package: a
 * known app stays on the generic remote even if the tuner still remembers
 * the last terrestrial channel.
 */
internal object Foreground {
    const val LIVE_TV_PACKAGE = "jp.co.sharp.av.android.aquostvapp"
    const val JELLYFIN_PACKAGE = "org.jellyfin.androidtv"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube.tv"

    fun isJellyfin(packageName: String?): Boolean = packageName == JELLYFIN_PACKAGE

    fun isYouTube(packageName: String?): Boolean = packageName == YOUTUBE_PACKAGE

    fun isTerrestrial(packageName: String?, channel: String?): Boolean {
        if (packageName == LIVE_TV_PACKAGE) return true
        if (!packageName.isNullOrBlank()) return false
        return channel?.startsWith("TB") == true
    }
}

data class TerrestrialChannel(
    val button: Int,
    val name: String,
    /** J:COM XMLTV channel id, or null when this button has no listing. */
    val epgId: String? = null
)

/**
 * Osaka one-touch buttons, in the order Eric asked for: 10, 8, 7, 6, then
 * 1, 2, 4, 12, then the rest. Names are the ones used on the cards.
 *
 * Button 1 is NHK総合 (NHK Osaka), not MBS. MBS is button 4. Button 12 has
 * no confirmed station. [epgId] values are the Osaka terrestrial ids in
 * dbghelp/JCOM-TV-EPG `jcom.xml`: 読売テレビ, 関西テレビ, テレビ大阪,
 * ABCテレビ, NHK大阪総合, NHK大阪教育, 毎日テレビ, サンテレビ.
 */
internal object OsakaChannels {
    val buttons: List<TerrestrialChannel> = listOf(
        TerrestrialChannel(10, "YTV", "jcom_2_2088_32725"),
        TerrestrialChannel(8, "KTV", "jcom_2_2080_32724"),
        TerrestrialChannel(7, "TVO", "jcom_2_41008_32118"),
        TerrestrialChannel(6, "ABC", "jcom_2_2072_32723"),
        TerrestrialChannel(1, "NHK", "jcom_2_40960_32112"),
        TerrestrialChannel(2, "NHK E", "jcom_2_2056_32721"),
        TerrestrialChannel(4, "MBS", "jcom_2_2064_32722"),
        TerrestrialChannel(12, "12", null),
        TerrestrialChannel(3, "SUN", "jcom_2_43056_32086")
    )

    val epgIds: Set<String> = buttons.mapNotNull { it.epgId }.toSet()

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
