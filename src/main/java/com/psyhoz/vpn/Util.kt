package com.psyhoz.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Const {
    const val SOCKS_PORT = 10125                     // локальный SOCKS5-порт (Android не даёт приложениям порты ниже 1024, поэтому не 125)
    val ALLOWED_HOSTS = setOf("psyholove.com.bot.nu") // принимаем подписки только с нашего домена
    const val UA = "PsyhozVPN/1.0 (Android)"
    const val PING_HOST = "cp.cloudflare.com"
    const val PING_PATH = "/generate_204"
    const val UPDATE_EVERY_SEC = 12 * 3600L
}

fun jo(vararg p: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in p) o.put(k, v)
    return o
}

fun ja(vararg v: Any?): JSONArray {
    val a = JSONArray()
    for (x in v) a.put(x)
    return a
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
