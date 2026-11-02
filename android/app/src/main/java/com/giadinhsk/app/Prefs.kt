package com.giadinhsk.app

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("giadinhsk", Context.MODE_PRIVATE)

    var name: String
        get() = sp.getString(KEY_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_NAME, v).apply()

    var server: String
        get() = sp.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = sp.edit().putString(KEY_SERVER, v).apply()

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var status: String
        get() = sp.getString(KEY_STATUS, "") ?: ""
        set(v) = sp.edit().putString(KEY_STATUS, v).apply()

    /** Mã định danh cố định của máy này, tạo một lần. */
    val deviceId: String
        get() {
            sp.getString(KEY_DEVICE_ID, null)?.let { return it }
            val id = UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    companion object {
        const val KEY_NAME = "name"
        const val KEY_SERVER = "server"
        const val KEY_ENABLED = "enabled"
        const val KEY_STATUS = "status"
        const val KEY_DEVICE_ID = "device_id"

        // Địa chỉ server mặc định, điền sẵn vào ô "Địa chỉ server" khi mới cài.
        const val DEFAULT_SERVER = "https://gia-dinh-sk.onrender.com"

        fun normalizeServer(input: String): String {
            var s = input.trim().trimEnd('/')
            if (s.isNotEmpty() && !s.startsWith("http://") && !s.startsWith("https://")) {
                s = "http://$s"
            }
            return s
        }
    }
}
