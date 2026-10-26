package com.psyhoz.vpn

import android.content.Context
import org.json.JSONArray
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Пинг: TCP-рукопожатие до сервера. Для UDP-серверов (Hysteria2) — реальный GET через прокси. */
object Pinger {

    fun tcp(host: String, port: Int): Int? {
        val addr = try {
            val all = InetAddress.getAllByName(host)
            all.firstOrNull { it is Inet4Address } ?: all.first()
        } catch (e: Exception) { return null }
        var best = -1L
        for (i in 0 until 3) {
            val t0 = System.nanoTime()
            try {
                Socket().use { it.connect(InetSocketAddress(addr, port), 3000) }
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (best < 0 || ms < best) best = ms
            } catch (e: Exception) { }
        }
        return if (best < 0) null else best.toInt()
    }

    private fun readN(i: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(b, off, n - off)
            if (r < 0) throw Exception("socks closed")
            off += r
        }
        return b
    }

    /** GET generate_204 через SOCKS5-порт; время = коннект через прокси + первый байт ответа. */
    fun socksGetMs(port: Int, timeout: Int = 5000): Long {
        val t0 = System.nanoTime()
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeout)
            s.soTimeout = timeout
            val out = s.getOutputStream(); val inp = s.getInputStream()
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            val h = readN(inp, 2)
            if (h[0].toInt() != 5 || h[1].toInt() != 0) throw Exception("socks handshake")
            val host = Const.PING_HOST.toByteArray()
            val req = byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(0, 80)
            out.write(req); out.flush()
            val r = readN(inp, 4)
            if (r[1].toInt() != 0) throw Exception("proxy connect failed")
            when (r[3].toInt()) {
                1 -> readN(inp, 6)
                4 -> readN(inp, 18)
                else -> readN(inp, (readN(inp, 1)[0].toInt() and 0xff) + 2)
            }
            out.write("GET ${Const.PING_PATH} HTTP/1.1\r\nHost: ${Const.PING_HOST}\r\nUser-Agent: ${Const.UA}\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val buf = ByteArray(16)
            val n = inp.read(buf)
            if (n < 5 || String(buf, 0, 5) != "HTTP/") throw Exception("bad reply")
        }
        return (System.nanoTime() - t0) / 1_000_000
    }

    private fun real(ctx: Context, servers: List<Server>): Map<String, Int?> {
        val res = HashMap<String, Int?>()
        val inbounds = JSONArray(); val outbounds = JSONArray(); val rules = JSONArray()
        val ports = HashMap<String, Int>()
        servers.forEachIndexed { i, s ->
            val ob = try { XrayConfig.resolve(s.outbound) } catch (e: Exception) { res[s.id] = null; return@forEachIndexed }
            val port = 24000 + i
            ports[s.id] = port
            inbounds.put(jo("tag" to "i$i", "listen" to "127.0.0.1", "port" to port, "protocol" to "socks",
                "settings" to jo("udp" to false)))
            outbounds.put(ob.put("tag", "o$i"))
            rules.put(jo("type" to "field", "inboundTag" to ja("i$i"), "outboundTag" to "o$i"))
        }
        if (ports.isEmpty()) return res
        val cfg = jo("log" to jo("loglevel" to "warning"), "inbounds" to inbounds, "outbounds" to outbounds,
            "routing" to jo("rules" to rules))
        val x = XrayProcess(ctx, "ping")
        try {
            x.start(cfg, ports.values.last())
            val pool = Executors.newFixedThreadPool(16)
            val futures = ports.map { (id, port) ->
                id to pool.submit(Callable<Int?> {
                    var best = -1L
                    for (k in 0 until 2) {
                        try { val ms = socksGetMs(port); if (best < 0 || ms < best) best = ms } catch (e: Exception) { break }
                    }
                    if (best < 0) null else best.toInt()
                })
            }
            for ((id, f) in futures) res[id] = try { f.get() } catch (e: Exception) { null }
            pool.shutdown()
        } catch (e: Exception) {
            for (id in ports.keys) if (!res.containsKey(id)) res[id] = null
        } finally {
            x.stop()
        }
        return res
    }

    /** Возвращает {id сервера -> мс или null}. Вызывать из фонового потока. */
    fun pingAll(ctx: Context, servers: List<Server>): Map<String, Int?> {
        val res = HashMap<String, Int?>()
        val pool = Executors.newFixedThreadPool(16)
        val futures = servers.filter { !it.udp }.map { s -> s.id to pool.submit(Callable<Int?> { tcp(s.host, s.port) }) }
        for ((id, f) in futures) res[id] = try { f.get() } catch (e: Exception) { null }
        pool.shutdown()
        val udp = servers.filter { it.udp }
        if (udp.isNotEmpty()) res.putAll(real(ctx, udp))
        return res
    }
}
