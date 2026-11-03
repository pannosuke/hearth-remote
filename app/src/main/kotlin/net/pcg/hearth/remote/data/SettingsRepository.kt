package net.pcg.hearth.remote.data

import android.content.Context
import net.pcg.hearth.remote.protocol.SubtitleMacro

class SettingsRepository(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "hearth_remote_settings"
        private const val KEY_TV_HOST = "tv_host"
        private const val KEY_SUBTITLE_MACRO = "subtitle_macro"
        private const val KEY_POWER_ON_IP = "rspw2_sent"
        private const val DEFAULT_TV_HOST = "192.168.10.105"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun getTvHost(): String? = prefs.getString(KEY_TV_HOST, null)
    
    fun setTvHost(host: String) {
        prefs.edit().putString(KEY_TV_HOST, host).apply()
    }
    
    fun clearTvHost() {
        prefs.edit().remove(KEY_TV_HOST).apply()
    }
    
    fun getDefaultTvHost(): String = DEFAULT_TV_HOST

    fun getSubtitleMacro(): String {
        return prefs.getString(KEY_SUBTITLE_MACRO, null) ?: SubtitleMacro.DEFAULT
    }

    fun setSubtitleMacro(value: String) {
        prefs.edit().putString(KEY_SUBTITLE_MACRO, value).apply()
    }

    fun hasEnabledPowerOnIp(): Boolean = prefs.getBoolean(KEY_POWER_ON_IP, false)

    fun setPowerOnIpEnabled() {
        prefs.edit().putBoolean(KEY_POWER_ON_IP, true).apply()
    }
}
