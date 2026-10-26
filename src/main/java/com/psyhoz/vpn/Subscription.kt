package com.psyhoz.vpn

import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

class SubException(msg: String) : Exception(msg)

data class Server(
    val id: String, val name: String, val host: String, val port: Int,
    val proto: String, val udp: Boolean, val link: String, val outbound: JSONObject
)

data class SubData(
    val links: List<String>, val skipped: List<String>, val title: String,
    val userinfo: Map<String, Long>, val web: String, val announce: String
)

/** Разбор подписки 3x-ui: vless / vmess / trojan / shadowsocks / hysteria2. */
object Subscription {

    fun checkUrl(url: String): String {
        val u = try { URI(url.trim()) } catch (e: Exception) { throw SubException("Неверная ссылка") }
        val host = u.host?.lowercase()
        if ((u.scheme != "https" && u.scheme != "http") || host == null || host !in Const.ALLOWED_HOSTS) {
            throw SubException("Принимаются только подписки нашего домена: " + Const.ALLOWED_HOSTS.joinToString())
        }
        return u.toString()
    }

    fun b64d(s: String): ByteArray {
        var x = s.trim().replace('-', '+').replace('_', '/')
        x += "=".repeat((4 - x.length % 4) % 4)
        return Base64.decode(x, Base64.DEFAULT)
    }

    // ---------- разбор ссылки вручную (URI падает на эмодзи и пробелах) ----------
    private class Link(
        val scheme: String, val user: String, val pass: String?, val host: String, val port: Int,
        val q: Map<String, String>, val frag: String, val body: String
    )

    private fun dec(s: String): String =
        try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }

    private fun parseQuery(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (part in s.split("&")) {
            val i = part.indexOf('=')
            if (i < 0) continue
            val v = part.substring(i + 1)
            m[part.substring(0, i)] = try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
        }
        return m
    }

    private fun splitLink(link: String): Link {
        val se = link.indexOf("://")
        require(se > 0) { "bad link" }
        val scheme = link.substring(0, se).lowercase()
        var rest = link.substring(se + 3)
        var frag = ""
        val h = rest.indexOf('#')
        if (h >= 0) { frag = dec(rest.substring(h + 1)); rest = rest.substring(0, h) }
        var q: Map<String, String> = emptyMap()
        val qi = rest.indexOf('?')
        if (qi >= 0) { q = parseQuery(rest.substring(qi + 1)); rest = rest.substring(0, qi) }
        val body = rest
        val at = rest.lastIndexOf('@')
        val auth = if (at >= 0) rest.substring(0, at) else ""
        var hp = rest.substring(at + 1)
        val sl = hp.indexOf('/')
        if (sl >= 0) hp = hp.substring(0, sl)
        var host = hp
        var port = 0
        if (hp.startsWith("[")) {
            val e = hp.indexOf(']')
            host = hp.substring(1, e)
            port = hp.substring(e + 1).removePrefix(":").toIntOrNull() ?: 0
        } else {
            val c = hp.lastIndexOf(':')
            if (c >= 0) { host = hp.substring(0, c); port = hp.substring(c + 1).toIntOrNull() ?: 0 }
        }
        val ci = auth.indexOf(':')
        val user = dec(if (ci >= 0) auth.substring(0, ci) else auth)
        val pass = if (ci >= 0) dec(auth.substring(ci + 1)) else null
        return Link(scheme, user, pass, host, port, q, frag, body)
    }

    private fun stream(q: Map<String, String>, host: String, defSec: String = "none"): JSONObject {
        var net = q["type"] ?: "tcp"
        net = when (net) { "raw" -> "tcp"; "h2" -> "http"; else -> net }
        val sec = q["security"] ?: defSec
        val s = jo("network" to net, "security" to sec)
        val sni = q["sni"]?.takeIf { it.isNotEmpty() }
        val fp = q["fp"]?.takeIf { it.isNotEmpty() } ?: "chrome"
        if (sec == "tls") {
            val t = jo(
                "serverName" to (sni ?: q["host"]?.takeIf { it.isNotEmpty() } ?: host),
                "fingerprint" to fp,
                "allowInsecure" to (q["allowInsecure"] == "1" || q["allowInsecure"] == "true")
            )
            q["alpn"]?.takeIf { it.isNotEmpty() }?.let { t.put("alpn", JSONArray(it.split(","))) }
            s.put("tlsSettings", t)
        } else if (sec == "reality") {
            s.put("realitySettings", jo(
                "serverName" to (sni ?: ""), "fingerprint" to fp,
                "publicKey" to (q["pbk"] ?: ""), "shortId" to (q["sid"] ?: ""), "spiderX" to (q["spx"] ?: "")
            ))
        }
        val path = q["path"] ?: "/"
        val hh = q["host"] ?: ""
        when (net) {
            "ws" -> s.put("wsSettings", jo("path" to path,
                "headers" to (if (hh.isNotEmpty()) jo("Host" to hh) else JSONObject())))
            "grpc" -> s.put("grpcSettings", jo("serviceName" to (q["serviceName"] ?: ""),
                "multiMode" to (q["mode"] == "multi")))
            "httpupgrade" -> s.put("httpupgradeSettings", jo("path" to path, "host" to hh))
            "xhttp" -> {
                val x = jo("path" to path, "host" to hh, "mode" to (q["mode"] ?: "auto"))
                q["extra"]?.let { try { x.put("extra", JSONObject(it)) } catch (e: Exception) { } }
                s.put("xhttpSettings", x)
            }
            "tcp" -> if (q["headerType"] == "http") {
                s.put("tcpSettings", jo("header" to jo("type" to "http", "request" to jo(
                    "path" to ja(path), "headers" to jo("Host" to ja(hh))))))
            }
        }
        return s
    }

    private class Parsed(val name: String, val host: String, val port: Int, val ob: JSONObject)

    private fun vless(l: Link) = Parsed(l.frag, l.host, l.port, jo(
        "protocol" to "vless",
        "settings" to jo("vnext" to ja(jo("address" to l.host, "port" to l.port, "users" to ja(
            jo("id" to l.user, "encryption" to (l.q["encryption"] ?: "none"), "flow" to (l.q["flow"] ?: "")))))),
        "streamSettings" to stream(l.q, l.host)
    ))

    private fun trojan(l: Link) = Parsed(l.frag, l.host, l.port, jo(
        "protocol" to "trojan",
        "settings" to jo("servers" to ja(jo("address" to l.host, "port" to l.port, "password" to l.user))),
        "streamSettings" to stream(l.q, l.host, "tls")
    ))

    private fun vmess(link: String): Parsed {
        val j = JSONObject(String(b64d(link.substring(8))))
        val add = j.getString("add")
        val port = j.get("port").toString().toInt()
        val path = j.optString("path", "/")
        val q = hashMapOf(
            "type" to j.optString("net", "tcp"),
            "security" to (if (j.optString("tls") == "tls") "tls" else "none"),
            "sni" to j.optString("sni", ""), "host" to j.optString("host", ""), "path" to path,
            "alpn" to j.optString("alpn", ""), "fp" to j.optString("fp", ""),
            "serviceName" to path, "headerType" to j.optString("type", "")
        )
        val ob = jo(
            "protocol" to "vmess",
            "settings" to jo("vnext" to ja(jo("address" to add, "port" to port, "users" to ja(
                jo("id" to j.getString("id"), "alterId" to j.optString("aid", "0").toInt(),
                    "security" to j.optString("scy", "auto")))))),
            "streamSettings" to stream(q, add)
        )
        return Parsed(j.optString("ps", add), add, port, ob)
    }

    private fun ss(l: Link): Parsed {
        val method: String; val pwd: String; val host: String; val port: Int
        if (l.body.contains('@')) {
            val ui = l.body.substringBeforeLast('@')
            val cred = if (ui.contains(':')) dec(ui) else String(b64d(dec(ui)))
            method = cred.substringBefore(':'); pwd = cred.substringAfter(':')
            host = l.host; port = l.port
        } else {
            val raw = String(b64d(dec(l.body)))
            val cred = raw.substringBeforeLast('@'); val hp = raw.substringAfterLast('@')
            method = cred.substringBefore(':'); pwd = cred.substringAfter(':')
            host = hp.substringBeforeLast(':'); port = hp.substringAfterLast(':').toInt()
        }
        return Parsed(l.frag, host, port, jo(
            "protocol" to "shadowsocks",
            "settings" to jo("servers" to ja(jo("address" to host, "port" to port, "method" to method, "password" to pwd)))
        ))
    }

    private fun hy2(l: Link): Parsed {
        val auth = l.user + (if (l.pass != null) ":" + l.pass else "")
        val port = if (l.port > 0) l.port else 443
        val hs = jo("version" to 2, "auth" to auth)
        (l.q["mport"] ?: l.q["ports"])?.let { hs.put("udphop", jo("port" to it, "interval" to 30)) }
        val st = jo(
            "network" to "hysteria", "security" to "tls",
            "tlsSettings" to jo("serverName" to (l.q["sni"]?.takeIf { it.isNotEmpty() } ?: l.host),
                "alpn" to ja("h3"), "allowInsecure" to (l.q["insecure"] == "1" || l.q["insecure"] == "true")),
            "hysteriaSettings" to hs
        )
        if (l.q["obfs"] == "salamander") {
            st.put("finalmask", jo("udp" to ja(jo("type" to "salamander",
                "settings" to jo("password" to (l.q["obfs-password"] ?: ""))))))
        }
        return Parsed(l.frag, l.host, port, jo(
            "protocol" to "hysteria",
            "settings" to jo("version" to 2, "address" to l.host, "port" to port),
            "streamSettings" to st
        ))
    }

    fun parseLink(link: String): Server? {
        return try {
            val scheme = link.substringBefore("://").lowercase()
            val p = when (scheme) {
                "vless" -> vless(splitLink(link))
                "trojan" -> trojan(splitLink(link))
                "vmess" -> vmess(link)
                "ss" -> ss(splitLink(link))
                "hysteria2", "hy2" -> hy2(splitLink(link.replaceFirst("hy2://", "hysteria2://")))
                else -> return null
            }
            val proto = if (p.ob.getString("protocol") == "hysteria") "hysteria2" else p.ob.getString("protocol")
            val id = MessageDigest.getInstance("SHA-1").digest(link.toByteArray())
                .joinToString("") { "%02x".format(it) }.substring(0, 12)
            Server(id, p.name.ifEmpty { p.host }, p.host, p.port, proto, proto == "hysteria2", link, p.ob)
        } catch (e: Exception) {
            null
        }
    }

    fun parseLinks(links: List<String>): List<Server> = links.mapNotNull { parseLink(it) }

    private fun headerValue(v: String?): String {
        if (v == null) return ""
        if (v.startsWith("base64:")) {
            return try { String(b64d(v.substring(7))) } catch (e: Exception) { v }
        }
        return v
    }

    /** Загрузка подписки (вызывать из фонового потока). */
    fun fetch(url0: String, hwid: String): SubData {
        val url = checkUrl(url0)
        var body = ""
        var hdr: Map<String, String> = emptyMap()
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 15000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", Const.UA)
            c.setRequestProperty("X-HWID", hwid)
            c.setRequestProperty("X-Device-OS", "Android")
            c.setRequestProperty("X-Device-Model", Build.MODEL ?: "Android")
            c.setRequestProperty("X-Ver-OS", Build.VERSION.RELEASE ?: "")
            if (c.responseCode !in 200..299) throw SubException("Сервер подписки ответил: ${c.responseCode}")
            body = c.inputStream.bufferedReader().use { it.readText() }
            val m = HashMap<String, String>()
            for ((k, v) in c.headerFields) if (k != null) m[k.lowercase()] = v.firstOrNull() ?: ""
            hdr = m
        } catch (e: SubException) {
            throw e
        } catch (e: Exception) {
            throw SubException("Не удалось получить подписку: ${e.message}")
        }
        var text = body.trim()
        if (!text.contains("://")) {
            text = try { String(b64d(text)) } catch (e: Exception) { throw SubException("Непонятный формат подписки") }
        }
        val links = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val parsed = parseLinks(links)
        if (parsed.isEmpty()) throw SubException("В подписке нет поддерживаемых серверов")
        val skipped = links.filter { parseLink(it) == null }.map { it.substringBefore("://") }
        val info = HashMap<String, Long>()
        for (part in (hdr["subscription-userinfo"] ?: "").replace(" ", "").split(";")) {
            val i = part.indexOf('=')
            if (i > 0) part.substring(i + 1).toDoubleOrNull()?.let { info[part.substring(0, i)] = it.toLong() }
        }
        return SubData(links, skipped, headerValue(hdr["profile-title"]), info,
            hdr["profile-web-page-url"] ?: "", headerValue(hdr["announce"]))
    }
}
