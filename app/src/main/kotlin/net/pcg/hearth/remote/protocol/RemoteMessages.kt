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
     * A bare package name becomes market://launch?id=…, which is how
     * androidtvremote2 launches apps that have no https link. URLs that
     * already have a scheme, including https://www.youtube.com, are sent as-is.
     */
    fun normalizeAppLink(appLinkOrId: String): String {
        return if ("://" in appLinkOrId) appLinkOrId else "market://launch?id=$appLinkOrId"
    }
}

internal object TvAppLinks {
    const val YOUTUBE = "https://www.youtube.com"
    const val NETFLIX = "market://launch?id=com.netflix.ninja"
    const val DISNEY_PLUS = "market://launch?id=com.disney.disneyplus"
    const val PRIME_VIDEO = "market://launch?id=com.amazon.amazonvideo.livingroom"
    const val JELLYFIN = "market://launch?id=org.jellyfin.androidtv"
    const val TVER = "market://launch?id=jp.co.tver.tvapp"
    const val NHK = "market://launch?id=jp.nhk.plus.tv"
    const val HULU = "market://launch?id=jp.happyon.android"
    const val LIVE_TV = "market://launch?id=jp.co.sharp.av.android.aquostvapp"
}
