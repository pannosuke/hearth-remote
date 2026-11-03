package net.pcg.hearth.remote.protocol

import net.pcg.hearth.remote.proto.RemoteProto.RemoteAppLinkLaunchRequest
import net.pcg.hearth.remote.proto.RemoteProto.RemoteConfigure
import net.pcg.hearth.remote.proto.RemoteProto.RemoteDeviceInfo
import net.pcg.hearth.remote.proto.RemoteProto.RemoteMessage
import net.pcg.hearth.remote.proto.RemoteProto.RemoteSetActive

/**
 * Remote-session messages, matching tronikos/androidtvremote2.
 *
 * The TV speaks first. Its RemoteConfigure.code1 is a bitmask of features it
 * supports. The client answers with the intersection, then answers
 * RemoteSetActive with that same value. App launches are RemoteAppLinkLaunchRequest
 * (field 90) and are only valid after that handshake, which ends with RemoteStart.
 */
internal object RemoteMessages {
    const val FEATURE_PING = 1 shl 0
    const val FEATURE_KEY = 1 shl 1
    const val FEATURE_IME = 1 shl 2
    const val FEATURE_POWER = 1 shl 5
    const val FEATURE_VOLUME = 1 shl 6
    const val FEATURE_APP_LINK = 1 shl 9

    /** PING | KEY | IME | POWER | VOLUME | APP_LINK = 615. */
    const val REQUESTED_FEATURES =
        FEATURE_PING or FEATURE_KEY or FEATURE_IME or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

    fun activeFeatures(deviceCode: Int): Int = REQUESTED_FEATURES and deviceCode

    fun configureResponse(deviceCode: Int): RemoteMessage {
        val deviceInfo = RemoteDeviceInfo.newBuilder()
            .setUnknown1(1)
            .setUnknown2("1")
            .setPackageName("atvremote")
            .setAppVersion("1.0.0")
            .build()
        val configure = RemoteConfigure.newBuilder()
            .setCode1(activeFeatures(deviceCode))
            .setDeviceInfo(deviceInfo)
            .build()
        return RemoteMessage.newBuilder()
            .setRemoteConfigure(configure)
            .build()
    }

    fun setActiveResponse(activeFeatures: Int): RemoteMessage {
        val setActive = RemoteSetActive.newBuilder()
            .setActive(activeFeatures)
            .build()
        return RemoteMessage.newBuilder()
            .setRemoteSetActive(setActive)
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
    const val LIVE_TV = "market://launch?id=com.google.android.tv"
}
