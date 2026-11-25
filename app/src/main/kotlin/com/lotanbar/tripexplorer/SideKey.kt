package com.lotanbar.tripexplorer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Samsung only: makes a double press of the side (power) button open the "Add POI" entry, the way
 * Settings → Advanced features → Side button → Double press → Apps would, but pointing at the Add POI
 * entry instead of the app's main one. Needs WRITE_SECURE_SETTINGS, which Android grants only over USB
 * (adb shell pm grant com.lotanbar.tripexplorer android.permission.WRITE_SECURE_SETTINGS); without it,
 * or on a phone without these settings, nothing happens.
 */
object SideKey {
    private const val TAG = "SideKey"
    private const val SHORT = "com.lotanbar.tripexplorer/.AddPoiShortcut"
    private const val FULL = "com.lotanbar.tripexplorer/com.lotanbar.tripexplorer.AddPoiShortcut"
    private const val P = "function_key_config_doublepress"

    private fun canWrite(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isSet(context: Context): Boolean {
        val cr = context.contentResolver
        return Settings.Global.getString(cr, P) == "1" &&
            Settings.Global.getString(cr, "${P}_type") == "2" &&
            Settings.Global.getString(cr, "${P}_value") == FULL
    }

    /** Points the double press at Add POI, once per install (afterwards the user's own choice is left alone). */
    fun configureOnce(context: Context) {
        val prefs = context.getSharedPreferences("side_key", Context.MODE_PRIVATE)
        if (prefs.getBoolean("done", false) || !canWrite(context)) return
        val cr = context.contentResolver
        // Not a Samsung phone with these settings: leave everything as it is.
        if (Settings.Global.getString(cr, P) == null) return
        val ok = runCatching {
            val actions = runCatching { JSONObject(Settings.Global.getString(cr, "${P}_selected_actions") ?: "{}") }.getOrDefault(JSONObject())
            actions.put("key_apps", "key_app")
            val intent = JSONObject()
                .put("intentUri", "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;component=$SHORT;end")
                .put("type", 1)
            Settings.Global.putString(cr, "${P}_app_action", FULL)
            Settings.Global.putString(cr, "${P}_value", FULL)
            Settings.Global.putString(cr, "${P}_intent", intent.toString())
            Settings.Global.putString(cr, "${P}_selected_actions", actions.toString())
            Settings.Global.putString(cr, "${P}_selected_item", "key_apps")
            Settings.Global.putString(cr, "${P}_type", "2")
            Settings.Global.putString(cr, P, "1")
        }.onFailure { Log.w(TAG, "Could not set the side key", it) }.isSuccess
        val set = ok && isSet(context)
        Log.i(TAG, "side key double press → Add POI: $set")
        if (set) prefs.edit().putBoolean("done", true).apply()
    }
}
