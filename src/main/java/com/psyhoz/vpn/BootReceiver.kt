package com.psyhoz.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/** Автоподключение после включения телефона (если включено в настройках и разрешение на VPN уже выдано). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Store.autoconnect(c) || Store.servers(c).isEmpty() || VpnService.prepare(c) != null) return
        ContextCompat.startForegroundService(c,
            Intent(c, PsyhozVpnService::class.java).setAction(PsyhozVpnService.ACTION_START)
                .putExtra(PsyhozVpnService.EXTRA_RETRY, true))
    }
}
