package com.psyhoz.vpn

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Row(val id: String, val title: String, val sub: String)

class ServerAdapter(private val onPick: (String) -> Unit) : RecyclerView.Adapter<ServerAdapter.VH>() {
    var rows: List<Row> = emptyList()
    var selected = "auto"
    var pings: Map<String, Int?> = emptyMap()
    var pinging = false

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val ping: TextView = v.findViewById(R.id.ping)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_server, p, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val r = rows[i]
        val ctx = h.itemView.context
        h.name.text = r.title
        h.sub.text = r.sub
        h.sub.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
        val sel = r.id == selected
        h.itemView.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(14).toFloat()
            setColor(ContextCompat.getColor(ctx, R.color.card))
            setStroke(ctx.dp(if (sel) 2 else 1), ContextCompat.getColor(ctx, if (sel) R.color.accent else R.color.line))
        }
        if (r.id == "auto") {
            h.ping.text = ""
        } else if (pings.containsKey(r.id)) {
            val ms = pings[r.id]
            if (ms == null) {
                h.ping.text = "нет ответа"; h.ping.setTextColor(ContextCompat.getColor(ctx, R.color.red))
            } else {
                h.ping.text = "$ms мс"
                h.ping.setTextColor(ContextCompat.getColor(ctx,
                    if (ms < 150) R.color.green else if (ms < 350) R.color.amber else R.color.red))
            }
        } else {
            h.ping.text = if (pinging) "…" else ""
            h.ping.setTextColor(ContextCompat.getColor(ctx, R.color.dim))
        }
        h.itemView.setOnClickListener { onPick(r.id) }
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: ServerAdapter
    private lateinit var btnConnect: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvUse: TextView
    private lateinit var tvExpire: TextView
    private lateinit var welcome: View
    private lateinit var main: View
    private lateinit var etUrl: EditText

    private var servers: List<Server> = emptyList()
    private var pings: Map<String, Int?> = emptyMap()
    private var pinging = false
    private var busy = false
    private val listener: () -> Unit = { renderState() }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) startVpn() else toast("Разрешение на VPN не выдано")
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askVpn() }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(Store.theme(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.btnConnect)
        tvTitle = findViewById(R.id.tvTitle); tvSub = findViewById(R.id.tvSub); tvUse = findViewById(R.id.tvUse); tvExpire = findViewById(R.id.tvExpire)
        welcome = findViewById(R.id.welcome); main = findViewById(R.id.main); etUrl = findViewById(R.id.etUrl)

        adapter = ServerAdapter { id -> Store.setSelected(this, id); refreshList() }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity); adapter = this@MainActivity.adapter
        }

        findViewById<View>(R.id.btnAdd).setOnClickListener { askSub() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { addSub(Store.subUrl(this)) }
        findViewById<View>(R.id.btnPing).setOnClickListener { runPing() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showSettings() }
        findViewById<View>(R.id.btnAddSub).setOnClickListener { addSub(etUrl.text.toString()) }
        findViewById<View>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            etUrl.setText(cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: "")
        }
        btnConnect.setOnClickListener {
            when (VpnStatus.state) {
                VState.IDLE -> if (servers.isEmpty()) toast("Сначала добавь подписку") else requestConnect()
                else -> stopVpn()
            }
        }

        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark

        reload()
        renderState()
    }

    override fun onStart() {
        super.onStart()
        VpnStatus.add(listener)
        renderState()
    }

    override fun onStop() {
        VpnStatus.remove(listener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        val url = Store.subUrl(this)
        if (url.isNotEmpty() && Store.autoupdate(this) &&
            System.currentTimeMillis() / 1000 - Store.updated(this) > Const.UPDATE_EVERY_SEC) addSub(url)
    }

    // ---------- тема ----------
    private fun applyTheme(t: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (t) { "light" -> AppCompatDelegate.MODE_NIGHT_NO; "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- отображение ----------
    private fun reload() {
        servers = Store.servers(this)
        refreshList()
    }

    private fun refreshList() {
        welcome.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
        main.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
        val rows = ArrayList<Row>()
        rows.add(Row("auto", "⚡ Авто", "Сервер с наименьшим пингом"))
        servers.forEach { rows.add(Row(it.id, it.name, "")) }
        var sel = Store.selected(this)
        if (rows.none { it.id == sel }) sel = "auto"
        adapter.rows = rows; adapter.selected = sel; adapter.pings = pings; adapter.pinging = pinging
        adapter.notifyDataSetChanged()
        val u = Store.userinfo(this)
        val total = u["total"] ?: 0L
        tvUse.text = when {
            total > 0 -> {
                val used = (u["upload"] ?: 0L) + (u["download"] ?: 0L)
                "Трафик: использовано %.1f ГБ из %.1f ГБ".format(Locale.US, used / 1073741824.0, total / 1073741824.0)
            }
            u.isNotEmpty() -> "Трафик: без ограничений"
            else -> Store.title(this)
        }
        showExpire(u)
    }

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val m100 = n % 100; val m10 = n % 10
        return if (m100 in 11..14) many else when (m10) { 1L -> one; 2L, 3L, 4L -> few; else -> many }
    }

    /** Сколько осталось до окончания подписки (по заголовку Subscription-Userinfo, поле expire). */
    private fun showExpire(u: Map<String, Long>) {
        if (u.isEmpty()) { tvExpire.visibility = View.GONE; return }
        val exp = u["expire"] ?: 0L
        val text: String
        val colorRes: Int
        if (exp <= 0L) {
            text = "Подписка без срока"; colorRes = R.color.green
        } else {
            val left = exp - System.currentTimeMillis() / 1000
            val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(exp * 1000))
            if (left <= 0) {
                text = "Подписка истекла $date"; colorRes = R.color.red
            } else {
                val days = left / 86400
                val hours = (left % 86400) / 3600
                text = if (days >= 1) {
                    "Осталось $days ${plural(days, "день", "дня", "дней")} · до $date"
                } else {
                    val h = maxOf(hours, 1L)
                    "Осталось $h ${plural(h, "час", "часа", "часов")} · до $date"
                }
                colorRes = if (days > 7) R.color.green else if (days >= 3) R.color.amber else R.color.red
            }
        }
        tvExpire.text = text
        tvExpire.setTextColor(ContextCompat.getColor(this, colorRes))
        tvExpire.visibility = View.VISIBLE
    }

    private fun renderState() {
        var color = 0
        var iconColor = 0
        when (VpnStatus.state) {
            VState.CONNECTED -> {
                tvTitle.text = "Подключено"; tvSub.text = VpnStatus.serverName
                color = ContextCompat.getColor(this, R.color.green); iconColor = 0xFFFFFFFF.toInt()
            }
            VState.CONNECTING -> {
                tvTitle.text = "Подключаюсь…"; tvSub.text = ""
                color = ContextCompat.getColor(this, R.color.amber); iconColor = 0xFFFFFFFF.toInt()
            }
            VState.IDLE -> {
                tvTitle.text = "Не подключено"
                tvSub.text = VpnStatus.message.ifEmpty { "Нажми на кнопку, чтобы подключиться" }
                color = ContextCompat.getColor(this, R.color.card); iconColor = ContextCompat.getColor(this, R.color.text)
            }
        }
        btnConnect.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color)
            if (VpnStatus.state == VState.IDLE) setStroke(dp(2), ContextCompat.getColor(this@MainActivity, R.color.line))
        }
        btnConnect.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
    }

    // ---------- подключение ----------
    private fun requestConnect() {
        if (Build.VERSION.SDK_INT >= 33 && !Store.askedNotif(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Store.setAskedNotif(this)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else askVpn()
    }

    private fun askVpn() {
        val i = VpnService.prepare(this)
        if (i != null) vpnPermission.launch(i) else startVpn()
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(this,
            Intent(this, PsyhozVpnService::class.java).setAction(PsyhozVpnService.ACTION_START))
    }

    private fun stopVpn() {
        startService(Intent(this, PsyhozVpnService::class.java).setAction(PsyhozVpnService.ACTION_STOP))
    }

    // ---------- подписка и пинг ----------
    private fun askSub() {
        val et = EditText(this).apply { setText(Store.subUrl(this@MainActivity)); hint = "https://psyholove.com.bot.nu:2096/sub/…" }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        AlertDialog.Builder(this).setTitle("Подписка").setView(box)
            .setPositiveButton("Добавить") { _, _ -> addSub(et.text.toString()) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun addSub(url0: String) {
        val url = url0.trim()
        if (url.isEmpty() || busy) return
        busy = true
        toast("Загружаю подписку…")
        Thread {
            try {
                val data = Subscription.fetch(url, Store.hwid(this))
                runOnUiThread {
                    Store.saveSub(this, url, data)
                    pings = emptyMap()
                    reload()
                    var msg = "Серверов: " + Subscription.parseLinks(data.links).size
                    if (data.skipped.isNotEmpty()) msg += " · пропущено: " + data.skipped.toSet().joinToString()
                    toast(msg)
                    runPing()
                }
            } catch (e: SubException) {
                runOnUiThread { toast(e.message ?: "Ошибка подписки") }
            } catch (e: Exception) {
                runOnUiThread { toast("Ошибка: ${e.message}") }
            } finally {
                busy = false
            }
        }.start()
    }

    private fun runPing() {
        if (pinging || servers.isEmpty() || VpnStatus.state == VState.CONNECTING) return
        pinging = true; pings = emptyMap(); refreshList()
        val list = servers
        Thread {
            val r = Pinger.pingAll(applicationContext, list)
            runOnUiThread { pinging = false; pings = r; refreshList() }
        }.start()
    }

    // ---------- настройки ----------
    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), 0) }
        fun sw(text: String, on: Boolean, cb: (Boolean) -> Unit) {
            val s = SwitchMaterial(this)
            s.text = text; s.isChecked = on
            s.setOnCheckedChangeListener { _, v -> cb(v) }
            box.addView(s)
        }
        sw("Подключаться при включении телефона", Store.autoconnect(this)) { Store.setAutoconnect(this, it) }
        sw("Обновлять подписку раз в 12 часов", Store.autoupdate(this)) { Store.setAutoupdate(this, it) }

        val label = TextView(this); label.text = "Тема"; label.setPadding(0, dp(16), 0, dp(4)); box.addView(label)
        val rg = RadioGroup(this)
        val ctx = this
        for ((k, n) in listOf("dark" to "Тёмная", "light" to "Светлая", "system" to "Как в системе")) {
            val rb = RadioButton(ctx)
            rb.id = View.generateViewId(); rb.text = n; rb.tag = k; rb.isChecked = Store.theme(ctx) == k
            rg.addView(rb)
        }
        rg.setOnCheckedChangeListener { g, id ->
            val k = g.findViewById<View>(id).tag as String
            Store.setTheme(ctx, k); applyTheme(k)
        }
        box.addView(rg)

        val info = TextView(this)
        info.text = "Локальный SOCKS5: 127.0.0.1:${Const.SOCKS_PORT}"
        info.setPadding(0, dp(14), 0, dp(6)); box.addView(info)

        AlertDialog.Builder(this).setTitle("Настройки").setView(ScrollView(this).also { it.addView(box) })
            .setNeutralButton("Сменить подписку") { _, _ -> askSub() }
            .setPositiveButton("Закрыть", null).show()
    }
}
