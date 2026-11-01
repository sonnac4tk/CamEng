package com.cameng.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("cameng", Context.MODE_PRIVATE)

    var comment: String
        get() = sp.getString("comment", "") ?: ""
        set(v) = sp.edit().putString("comment", v).apply()

    var rulerMode: RulerView.Mode
        get() = runCatching { RulerView.Mode.valueOf(sp.getString("rulerMode", null) ?: "OFF") }
            .getOrDefault(RulerView.Mode.OFF)
        set(v) = sp.edit().putString("rulerMode", v.name).apply()

    var virtualDivisions: Int
        get() = sp.getInt("virtualDivisions", 10)
        set(v) = sp.edit().putInt("virtualDivisions", v).apply()

    var driveConnected: Boolean
        get() = sp.getBoolean("driveConnected", false)
        set(v) = sp.edit().putBoolean("driveConnected", v).apply()

    var driveEmail: String?
        get() = sp.getString("driveEmail", null)
        set(v) = sp.edit().putString("driveEmail", v).apply()

    var driveRootFolderId: String?
        get() = sp.getString("driveRootFolderId", null)
        set(v) = sp.edit().putString("driveRootFolderId", v).apply()
}
