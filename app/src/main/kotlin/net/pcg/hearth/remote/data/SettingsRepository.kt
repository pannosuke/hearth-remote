package net.pcg.hearth.remote.data

import android.content.Context

class SettingsRepository(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "hearth_remote_settings"
        private const val KEY_TV_HOST = "tv_host"
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
}
