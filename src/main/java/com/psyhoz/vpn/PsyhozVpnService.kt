package com.psyhoz.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import java.io.File

class PsyhozVpnService : VpnService() {

    private var xray: XrayProcess? = null
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        foreground("Подключаюсь…")
        VpnStatus.set(VState.CONNECTING)
        val attempts = if (intent?.getBooleanExtra(EXTRA_RETRY, false) == true) 6 else 1
        Thread {
            var err: Exception? = null
            for (i in 0 until attempts) {
                if (!running) return@Thread
                try {
                    connect()
                    return@Thread
                } catch (e: Exception) {
                    err = e
                    cleanup()
                    if (i < attempts - 1) Thread.sleep(4000)
                }
            }
            fail(err?.message ?: "Не удалось подключиться")
        }.start()
        return START_NOT_STICKY
    }

    private fun connect() {
        val servers = Store.servers(this)
        if (servers.isEmpty()) throw Exception("Нет серверов. Добавь подписку.")
        val sel = Store.selected(this)
        val server = if (sel == "auto") best(servers) else (servers.firstOrNull { it.id == sel } ?: servers[0])
        if (!running) return

        // 1. Xray (SOCKS5 на 127.0.0.1:125)
        val x = XrayProcess(this, "run")
        x.start(XrayConfig.main(XrayConfig.resolve(server.outbound)), Const.SOCKS_PORT)
        xray = x
        if (!running) return

        // 2. VPN-интерфейс. Само приложение исключено из туннеля, поэтому Xray ходит к серверу напрямую.
        val b = Builder().setSession("PSYHOZ VPN").setMtu(1500)
            .addAddress("198.18.0.1", 30).addAddress("fc00::1", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
            .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
        try { b.addDisallowedApplication(packageName) } catch (e: Exception) { }
        val pfd = b.establish() ?: throw Exception("Не удалось создать VPN-интерфейс (разрешение на VPN отозвано?)")
        tun = pfd

        // 3. tun2socks: пакеты из интерфейса -> SOCKS5 Xray
        val conf = File(filesDir, "hev.yml")
        conf.writeText(
            "tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n" +
            "socks5:\n  port: ${Const.SOCKS_PORT}\n  address: 127.0.0.1\n  udp: 'udp'\n" +
            "misc:\n  task-stack-size: 81920\n  connect-timeout: 5000\n  read-write-timeout: 60000\n" +
            "  log-file: stderr\n  log-level: warn\n"
        )
        TProxyService.TProxyStartService(conf.absolutePath, pfd.fd)

        // 4. Проверяем, что трафик реально идёт через сервер
        var ok = false
        for (i in 0 until 4) {
            try { Pinger.socksGetMs(Const.SOCKS_PORT); ok = true; break } catch (e: Exception) { Thread.sleep(1000) }
        }
        if (!ok) throw Exception("Сервер не пропускает трафик")
        if (!running) return

        VpnStatus.set(VState.CONNECTED, server.name)
        foreground("Подключено: ${server.name}")

        // 5. Следим за Xray
        while (running) {
            if (!x.alive()) {
                fail("Xray остановился:\n" + x.tail())
                return
            }
            Thread.sleep(1000)
        }
    }

    private fun best(servers: List<Server>): Server {
        val p = Pinger.pingAll(this, servers)
        return servers.filter { p[it.id] != null }.minByOrNull { p[it.id]!! }
            ?: throw Exception("Ни один сервер не отвечает. Проверь интернет.")
    }

    private fun fail(msg: String) {
        if (!running) return
        running = false
        cleanup()
        VpnStatus.set(VState.IDLE, msg = msg)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopVpn() {
        running = false
        cleanup()
        VpnStatus.set(VState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        try { TProxyService.TProxyStopService() } catch (e: Throwable) { }
        try { tun?.close() } catch (e: Exception) { }
        tun = null
        xray?.stop()
        xray = null
    }

    override fun onRevoke() { stopVpn() }

    override fun onDestroy() {
        running = false
        cleanup()
        if (VpnStatus.state != VState.IDLE) VpnStatus.set(VState.IDLE)
        super.onDestroy()
    }

    private fun foreground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, PsyhozVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle("PSYHOZ VPN").setContentText(text)
            .setContentIntent(open).addAction(0, "Отключить", stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    companion object {
        const val ACTION_START = "com.psyhoz.vpn.START"
        const val ACTION_STOP = "com.psyhoz.vpn.STOP"
        const val EXTRA_RETRY = "retry"
        private const val CHANNEL = "vpn"
    }
}
