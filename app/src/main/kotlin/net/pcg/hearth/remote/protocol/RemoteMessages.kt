package net.pcg.hearth.remote.protocol

import net.pcg.hearth.remote.proto.RemoteProto.RemoteAppLinkLaunchRequest
import net.pcg.hearth.remote.proto.RemoteProto.RemoteConfigure
import net.pcg.hearth.remote.proto.RemoteProto.RemoteDeviceInfo
import net.pcg.hearth.remote.proto.RemoteProto.RemoteDirection
import net.pcg.hearth.remote.proto.RemoteProto.RemoteKeyCode
import net.pcg.hearth.remote.proto.RemoteProto.RemoteKeyInject
import net.pcg.hearth.remote.proto.RemoteProto.RemoteMessage
import net.pcg.hearth.remote.proto.RemoteProto.RemotePingResponse
import net.pcg.hearth.remote.proto.RemoteProto.RemoteSetActive

/**
 * Remote-session messages for the Android TV remote port (6466).
 *
 * Byte layout matches tronikos/androidtvremote2 remote.py for the feature
 * mask. The TV speaks first. The configure reply uses the intersection of
 * [REQUESTED_FEATURES] and the TV's code1. device_info carries model,
 * vendor, unknown1, unknown2, package_name, and app_version. code1 stays
 * the intersection: a hardcoded 622 is what v0.1.8 sent, and this TV closed
 * that session. RemoteSetActive repeats the same intersection. Ping replies
 * echo val1 on field 9. The client does not send its own pings.
 *
 * Key presses are RemoteKeyInject (field 10) with direction SHORT (3).
 * App launches are RemoteAppLinkLaunchRequest (field 90) and are only valid
 * after RemoteStart.
 */
internal object RemoteMessages {
    const val FEATURE_PING = 1 shl 0
    const val FEATURE_KEY = 1 shl 1
    const val FEATURE_IME = 1 shl 2
    const val FEATURE_VOICE = 1 shl 3
    const val FEATURE_POWER = 1 shl 5
    const val FEATURE_VOLUME = 1 shl 6
    const val FEATURE_APP_LINK = 1 shl 9

    /** PING | KEY | IME | POWER | VOLUME | APP_LINK = 615. Voice stays off. */
    const val REQUESTED_FEATURES =
        FEATURE_PING or FEATURE_KEY or FEATURE_IME or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

    const val CLIENT_MODEL = "Hearth Remote"
    const val CLIENT_VENDOR = "pannosuke"
    const val CLIENT_PACKAGE = "atvremote"
    const val CLIENT_APP_VERSION = "1.0.0"

    init {
        check(REQUESTED_FEATURES == 615)
    }

    fun activeFeatures(deviceCode: Int): Int = REQUESTED_FEATURES and deviceCode

    fun configureResponse(deviceCode: Int): RemoteMessage {
        val deviceInfo = RemoteDeviceInfo.newBuilder()
            .setModel(CLIENT_MODEL)
            .setVendor(CLIENT_VENDOR)
            .setUnknown1(1)
            .setUnknown2("1")
            .setPackageName(CLIENT_PACKAGE)
            .setAppVersion(CLIENT_APP_VERSION)
            .build()
        val configure = RemoteConfigure.newBuilder()
            .setCode1(activeFeatures(deviceCode))
            .setDeviceInfo(deviceInfo)
            .build()
        return RemoteMessage.newBuilder()
            .setRemoteConfigure(configure)
            .build()
    }

    fun setActiveResponse(active: Int): RemoteMessage {
        val setActive = RemoteSetActive.newBuilder()
            .setActive(active)
            .build()
        return RemoteMessage.newBuilder()
            .setRemoteSetActive(setActive)
            .build()
    }

    fun pingResponse(val1: Int): RemoteMessage {
        val response = RemotePingResponse.newBuilder()
            .setVal1(val1)
            .build()
        return RemoteMessage.newBuilder()
            .setRemotePingResponse(response)
            .build()
    }

    fun keyInject(
        keyCode: RemoteKeyCode,
        direction: RemoteDirection = RemoteDirection.SHORT
    ): RemoteMessage {
        val key = RemoteKeyInject.newBuilder()
            .setKeyCode(keyCode)
            .setDirection(direction)
            .build()
        return RemoteMessage.newBuilder()
            .setRemoteKeyInject(key)
            .build()
    }

    fun appLink(appLink: String): RemoteMessage {
        val request = RemoteAppLinkLaunchRequest.newBuilder()
            .setAppLink(normalizeAppLink(appLink))
            .build()
        return RemoteMessage.newBuilder()
            .setRemoteAppLinkLaunchRequest(request)
            .build()
    }

    /**
     * The TV parses this string with `Intent.parseUri(URI_INTENT_SCHEME)`.
     * An app's own https link is sent unchanged. `https://hearth.local`
     * opens a chooser on this set, so that form, `market://`, `intent://`,
     * and a bare package name all become `hearth://launch?pkg=`.
     */
    fun normalizeAppLink(appLinkOrId: String): String {
        val value = appLinkOrId.trim()
        val marketId = marketPackage(value)
        if (marketId != null) return HearthLaunch.scheme(marketId)
        if (value.startsWith("intent:") || value.startsWith("intent://")) {
            val pkg = intentPackage(value)
            if (pkg != null) return HearthLaunch.scheme(pkg)
        }
        val hearthHttps = HearthLaunch.packageOfHttps(value)
        if (hearthHttps != null) return HearthLaunch.scheme(hearthHttps)
        if ("://" in value || value.startsWith("intent:")) return value
        if (value.isEmpty()) return value
        return HearthLaunch.scheme(value)
    }

    private fun marketPackage(value: String): String? {
        val prefix = "market://launch?id="
        if (!value.startsWith(prefix)) return null
        val id = value.removePrefix(prefix).substringBefore('&').trim()
        return id.takeIf { it.isNotEmpty() }
    }

    private fun intentPackage(value: String): String? {
        val marker = "package="
        val start = value.indexOf(marker)
        if (start < 0) return null
        val rest = value.substring(start + marker.length)
        val end = rest.indexOf(';').let { if (it < 0) rest.length else it }
        return rest.substring(0, end).trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * Hearth on the TV (`net.pcg.hearth`) starts a package from
 * `hearth://launch?pkg=`. The https form is not sent: on this set it
 * opens a chooser between Hearth and Downloader and does not launch the app.
 */
internal object HearthLaunch {
    private const val HTTPS_PREFIX = "https://hearth.local/launch?pkg="
    private const val SCHEME_PREFIX = "hearth://launch?pkg="

    fun scheme(packageName: String): String = SCHEME_PREFIX + packageName

    fun packageOfHttps(link: String): String? {
        if (!link.startsWith(HTTPS_PREFIX)) return null
        return link.removePrefix(HTTPS_PREFIX).substringBefore('&').trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Jellyfin's Android TV package is [Foreground.JELLYFIN_PACKAGE]. If the
     * TV has already reported a different `org.jellyfin.` package in front,
     * that is the one installed on the set.
     */
    fun jellyfinPackage(foreground: String?): String {
        val seen = foreground?.trim().orEmpty()
        if (seen.startsWith("org.jellyfin.")) return seen
        return Foreground.JELLYFIN_PACKAGE
    }

    /** Shown when the TV rejects a hearth:// app link. Null for any other link. */
    fun failure(sent: String?, errorValue: Boolean): String? {
        if (sent == null || !sent.startsWith(SCHEME_PREFIX)) return null
        return "Hearth rejected $sent (error value=$errorValue). The Hearth launcher on the TV needs a fix."
    }
}

internal data class TvAppLink(
    val id: String,
    val packageName: String,
    val link: String
)

/**
 * One row per Apps-tab shortcut. An app's own https VIEW link is sent as-is.
 * Jellyfin, NHK+, Hulu Japan, and the Sharp tuner have no such link, so they
 * use `hearth://launch?pkg=` only.
 */
internal object TvAppLinks {
    const val YOUTUBE = "https://www.youtube.com"
    const val NETFLIX = "https://www.netflix.com/title"
    const val DISNEY_PLUS = "https://www.disneyplus.com"
    const val PRIME_VIDEO = "https://app.primevideo.com"
    const val TVER = "https://tver.jp"
    val JELLYFIN = HearthLaunch.scheme(Foreground.JELLYFIN_PACKAGE)
    val NHK = HearthLaunch.scheme("jp.nhk.plus.tv")
    val HULU = HearthLaunch.scheme("jp.happyon.android")
    val LIVE_TV = HearthLaunch.scheme("jp.co.sharp.av.android.aquostvapp")

    val ALL: List<TvAppLink> = listOf(
        TvAppLink("youtube", "com.google.android.youtube.tv", YOUTUBE),
        TvAppLink("netflix", "com.netflix.ninja", NETFLIX),
        TvAppLink("disney", "com.disney.disneyplus", DISNEY_PLUS),
        TvAppLink("prime", "com.amazon.amazonvideo.livingroom", PRIME_VIDEO),
        TvAppLink("jellyfin", Foreground.JELLYFIN_PACKAGE, JELLYFIN),
        TvAppLink("tver", "jp.co.tver.tvapp", TVER),
        TvAppLink("nhk", "jp.nhk.plus.tv", NHK),
        TvAppLink("hulu", "jp.happyon.android", HULU),
        TvAppLink("live_tv", "jp.co.sharp.av.android.aquostvapp", LIVE_TV)
    )
}
