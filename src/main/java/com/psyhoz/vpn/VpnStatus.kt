package com.psyhoz.vpn

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

enum class VState { IDLE, CONNECTING, CONNECTED }

/** Общее состояние VPN между сервисом и экраном. */
object VpnStatus {
    @Volatile var state = VState.IDLE
    @Volatile var serverName = ""
    @Volatile var message = ""
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    fun add(l: () -> Unit) { listeners.add(l) }
    fun remove(l: () -> Unit) { listeners.remove(l) }

    fun set(s: VState, name: String = "", msg: String = "") {
        state = s; serverName = name; message = msg
        handler.post { for (l in listeners) l() }
    }
}
