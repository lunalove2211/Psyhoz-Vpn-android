package com.psyhoz.vpn

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Xray запускается как отдельный процесс: бинарник лежит в jniLibs под именем libxray.so. */
class XrayProcess(private val ctx: Context, private val name: String) {
    private var proc: Process? = null
    private val logFile get() = File(ctx.filesDir, "$name.log")

    fun alive(): Boolean = proc?.isAlive == true

    fun start(cfg: JSONObject, waitPort: Int) {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) throw Exception("Не найден xray для архитектуры этого телефона")
        val cfgFile = File(ctx.filesDir, "$name.json")
        cfgFile.writeText(cfg.toString())
        val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfgFile.absolutePath)
            .directory(ctx.filesDir)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
        pb.environment()["XRAY_LOCATION_ASSET"] = ctx.filesDir.absolutePath
        proc = pb.start()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 8000) {
            if (!alive()) throw Exception("Xray упал при запуске:\n" + tail())
            if (portOpen(waitPort)) return
            Thread.sleep(100)
        }
        stop()
        throw Exception("Xray не запустился за 8 секунд:\n" + tail())
    }

    fun tail(): String = try { logFile.readLines().takeLast(6).joinToString("\n") } catch (e: Exception) { "" }

    fun stop() {
        val p = proc ?: return
        p.destroy()
        try { if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() } catch (e: Exception) { }
        proc = null
    }

    companion object {
        fun portOpen(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200); true }
        } catch (e: Exception) { false }
    }
}
