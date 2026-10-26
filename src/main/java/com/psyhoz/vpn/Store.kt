package com.psyhoz.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("psyhoz", Context.MODE_PRIVATE)

    fun hwid(c: Context): String =
        sp(c).getString("hwid", null) ?: UUID.randomUUID().toString().also { sp(c).edit().putString("hwid", it).apply() }

    fun subUrl(c: Context): String = sp(c).getString("sub_url", "") ?: ""
    fun title(c: Context): String = sp(c).getString("title", "") ?: ""
    fun updated(c: Context): Long = sp(c).getLong("updated", 0)

    fun links(c: Context): List<String> {
        val a = JSONArray(sp(c).getString("links", "[]"))
        return (0 until a.length()).map { a.getString(it) }
    }

    fun servers(c: Context): List<Server> = Subscription.parseLinks(links(c))

    fun userinfo(c: Context): Map<String, Long> {
        val o = JSONObject(sp(c).getString("userinfo", "{}"))
        val m = HashMap<String, Long>()
        for (k in o.keys()) m[k] = o.getLong(k)
        return m
    }

    fun saveSub(c: Context, url: String, d: SubData) {
        val e = sp(c).edit()
        if (url != subUrl(c)) e.putString("selected", "auto")
        e.putString("sub_url", url).putString("links", JSONArray(d.links).toString())
            .putString("title", d.title).putString("userinfo", JSONObject(d.userinfo as Map<*, *>).toString())
            .putLong("updated", System.currentTimeMillis() / 1000).apply()
    }

    fun selected(c: Context): String = sp(c).getString("selected", "auto") ?: "auto"
    fun setSelected(c: Context, id: String) = sp(c).edit().putString("selected", id).apply()

    fun autoconnect(c: Context) = sp(c).getBoolean("autoconnect", false)
    fun setAutoconnect(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoconnect", v).apply()

    fun autoupdate(c: Context) = sp(c).getBoolean("autoupdate", true)
    fun setAutoupdate(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoupdate", v).apply()

    fun theme(c: Context): String = sp(c).getString("theme", "dark") ?: "dark"
    fun setTheme(c: Context, v: String) = sp(c).edit().putString("theme", v).apply()

    fun askedNotif(c: Context) = sp(c).getBoolean("asked_notif", false)
    fun setAskedNotif(c: Context) = sp(c).edit().putBoolean("asked_notif", true).apply()
}
