package com.psyhoz.vpn

import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress

object XrayConfig {
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /**
     * Go-версия Xray на Android не умеет надёжно резолвить домены системным DNS,
     * поэтому адрес сервера превращаем в IP заранее (до включения VPN), а SNI/Host оставляем доменом.
     */
    fun resolve(ob0: JSONObject): JSONObject {
        val ob = JSONObject(ob0.toString())
        val settings = ob.getJSONObject("settings")
        val holder = settings.optJSONArray("vnext")?.getJSONObject(0)
            ?: settings.optJSONArray("servers")?.getJSONObject(0)
            ?: settings
        val host = holder.getString("address")
        if (ipv4.matches(host) || host.contains(':')) return ob
        val ip = try {
            val all = InetAddress.getAllByName(host)
            (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress
        } catch (e: Exception) {
            throw Exception("Не удалось найти адрес сервера $host. Проверь интернет.")
        }
        holder.put("address", ip)
        val st = ob.optJSONObject("streamSettings")
        if (st != null) {
            st.optJSONObject("wsSettings")?.let { ws ->
                val h = ws.optJSONObject("headers") ?: JSONObject().also { ws.put("headers", it) }
                if (!h.has("Host")) h.put("Host", host)
            }
            st.optJSONObject("httpupgradeSettings")?.let { if (it.optString("host").isEmpty()) it.put("host", host) }
            st.optJSONObject("xhttpSettings")?.let { if (it.optString("host").isEmpty()) it.put("host", host) }
            st.optJSONObject("tlsSettings")?.let { if (it.optString("serverName").isEmpty()) it.put("serverName", host) }
        }
        return ob
    }

    /** Основной конфиг: SOCKS5 на 127.0.0.1:125, трафик в туннель заворачивает hev-socks5-tunnel. */
    fun main(ob: JSONObject): JSONObject = jo(
        "log" to jo("loglevel" to "warning"),
        "dns" to jo("servers" to ja("1.1.1.1", "8.8.8.8"), "queryStrategy" to "UseIPv4"),
        "inbounds" to ja(jo(
            "tag" to "socks-in", "listen" to "127.0.0.1", "port" to Const.SOCKS_PORT, "protocol" to "socks",
            "settings" to jo("udp" to true, "auth" to "noauth"),
            "sniffing" to jo("enabled" to true, "destOverride" to ja("http", "tls", "quic"))
        )),
        "outbounds" to ja(
            JSONObject(ob.toString()).put("tag", "proxy"),
            jo("tag" to "direct", "protocol" to "freedom"),
            jo("tag" to "block", "protocol" to "blackhole")
        ),
        "routing" to jo("domainStrategy" to "AsIs", "rules" to ja(jo(
            "type" to "field",
            "ip" to ja("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16"),
            "outboundTag" to "direct"
        )))
    )
}
