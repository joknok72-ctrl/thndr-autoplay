package com.thndr.autoplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val REQ_PROJ = 11
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bot", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnAccess).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) { toast("فعّل إذن الظهور فوق التطبيقات أولاً"); return@setOnClickListener }
            if (prefs.getInt("mode", 0) == 1 && !GestureService.isRunning) { toast("وضع البوت التلقائي يحتاج خدمة الوصول — فعّلها أو اختر وضع المرشد"); return@setOnClickListener }
            if (prefs.getInt("mode", 0) == 2 && (prefs.getString("relayToken", "")!!.isBlank() || prefs.getString("relayDevice", "")!!.isBlank())) { toast("اكتب توكن Device Relay ومعرّف الجهاز واضغط «اختبار الاتصال» أولاً"); return@setOnClickListener }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { startService(Intent(this, BotService::class.java).setAction(BotService.ACTION_STOP)) }
        findViewById<Button>(R.id.btnResetCal).setOnClickListener { prefs.edit().remove("offX").remove("offY").putBoolean("calibrated", false).apply(); toast("تم تصفير المعايرة") }

        val rgMode = findViewById<android.widget.RadioGroup>(R.id.rgMode)
        rgMode.check(when (prefs.getInt("mode", 0)) { 1 -> R.id.modeAuto; 2 -> R.id.modeRelay; else -> R.id.modeGuide })
        val autoOnly = findViewById<android.view.View>(R.id.autoOnly); val resetCal = findViewById<Button>(R.id.btnResetCal)
        val relayBox = findViewById<android.view.View>(R.id.relayBox)
        fun refreshMode() {
            val m = prefs.getInt("mode", 0)
            autoOnly.visibility = if (m != 0) android.view.View.VISIBLE else android.view.View.GONE; resetCal.visibility = autoOnly.visibility
            relayBox.visibility = if (m == 2) android.view.View.VISIBLE else android.view.View.GONE
        }
        rgMode.setOnCheckedChangeListener { _, id -> prefs.edit().putInt("mode", when (id) { R.id.modeAuto -> 1; R.id.modeRelay -> 2; else -> 0 }).apply(); refreshMode() }
        refreshMode()

        // ---- Device Relay settings ----
        val edServer = findViewById<android.widget.EditText>(R.id.edRelayServer); val edToken = findViewById<android.widget.EditText>(R.id.edRelayToken); val edDevice = findViewById<android.widget.EditText>(R.id.edRelayDevice)
        val stRelay = findViewById<TextView>(R.id.stRelay)
        edServer.setText(prefs.getString("relayServer", RelayClient.DEFAULT_SERVER)); edToken.setText(prefs.getString("relayToken", "")); edDevice.setText(prefs.getString("relayDevice", ""))
        fun saveRelay() = prefs.edit().putString("relayServer", edServer.text.toString().trim().ifEmpty { RelayClient.DEFAULT_SERVER }).putString("relayToken", edToken.text.toString().trim()).putString("relayDevice", edDevice.text.toString().trim()).apply()
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { saveRelay() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        edServer.addTextChangedListener(watcher); edToken.addTextChangedListener(watcher); edDevice.addTextChangedListener(watcher)
        fun client(): RelayClient? { saveRelay(); val t = prefs.getString("relayToken", "")!!; if (t.isBlank()) { stRelay.text = "❌ اكتب التوكن أولاً"; return null }; return RelayClient(prefs.getString("relayServer", RelayClient.DEFAULT_SERVER)!!, t, prefs.getString("relayDevice", "")!!) }
        findViewById<Button>(R.id.btnRelayTest).setOnClickListener {
            val rc = client() ?: return@setOnClickListener
            stRelay.text = "⏳ بتأكد…"
            Thread {
                val h = rc.health()
                if (!h.ok) { ui.post { stRelay.text = "❌ السيرفر مش متاح: ${h.error}" }; return@Thread }
                val devs = rc.devices()
                if (rc.deviceId.isBlank()) {
                    val on = devs.firstOrNull { it.second } ?: devs.firstOrNull()
                    if (on != null) { prefs.edit().putString("relayDevice", on.first).apply(); ui.post { edDevice.setText(on.first) } }
                }
                val rc2 = client() ?: return@Thread
                val (ok, d) = rc2.online()
                ui.post { stRelay.text = (if (ok) "✅ " else "❌ ") + d + "\nالأجهزة: " + (if (devs.isEmpty()) "لا شيء" else devs.joinToString { (it.first) + (if (it.second) " (متصل)" else " (غير متصل)") }) }
            }.start()
        }
        findViewById<Button>(R.id.btnRelayDrag).setOnClickListener {
            val rc = client() ?: return@setOnClickListener
            stRelay.text = "⏳ بسحب من نص الشاشة لفوق… (لو الشاشة اتحركت يبقى السحب شغال)"
            val dm = resources.displayMetrics; val w = dm.widthPixels.toFloat(); val hgt = dm.heightPixels.toFloat()
            Thread {
                val r = rc.drag(w / 2f, hgt * 0.7f, w / 2f, hgt * 0.35f, prefs.getInt("holdMs", 220).toLong(), prefs.getInt("moveMs", 420).toLong(), 250)
                ui.post { stRelay.text = if (r.ok) "✅ السحب التجريبي نٌفّذ بنجاح عبر Device Relay" else "❌ فشل السحب: ${r.error}\nتأكد إن خدمة الوصول بتاعة Device Relay مفعّلة والتطبيق متصل" }
            }.start()
        }

        val chk = findViewById<android.widget.CheckBox>(R.id.chkConfirm)
        chk.isChecked = prefs.getBoolean("confirmPieces", false)
        chk.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("confirmPieces", v).apply() }

        val seekLevel = findViewById<SeekBar>(R.id.seekLevel); val lblLevel = findViewById<TextView>(R.id.lblLevel)
        seekLevel.max = 3; seekLevel.progress = prefs.getInt("level", 4) - 1
        lblLevel.text = levelName(seekLevel.progress + 1)
        seekLevel.setOnSeekBarChangeListener(simple { prefs.edit().putInt("level", it + 1).apply(); lblLevel.text = levelName(it + 1) })

        val seekDelay = findViewById<SeekBar>(R.id.seekDelay); val lblDelay = findViewById<TextView>(R.id.lblDelay)
        seekDelay.max = 20; seekDelay.progress = (prefs.getInt("delay", 650) - 200) / 100
        lblDelay.text = "${prefs.getInt("delay", 650)} ms"
        seekDelay.setOnSeekBarChangeListener(simple { val v = 200 + it * 100; prefs.edit().putInt("delay", v).apply(); lblDelay.text = "$v ms" })

        val seekMove = findViewById<SeekBar>(R.id.seekMove); val lblMove = findViewById<TextView>(R.id.lblMove)
        seekMove.max = 16; seekMove.progress = (prefs.getInt("moveMs", 420) - 200) / 50
        lblMove.text = "${prefs.getInt("moveMs", 420)} ms"
        seekMove.setOnSeekBarChangeListener(simple { val v = 200 + it * 50; prefs.edit().putInt("moveMs", v).apply(); lblMove.text = "$v ms" })

        val seekHold = findViewById<SeekBar>(R.id.seekHold); val lblHold = findViewById<TextView>(R.id.lblHold)
        seekHold.max = 18; seekHold.progress = (prefs.getInt("holdMs", 220) - 40) / 50
        lblHold.text = "${prefs.getInt("holdMs", 220)} ms"
        seekHold.setOnSeekBarChangeListener(simple { val v = 40 + it * 50; prefs.edit().putInt("holdMs", v).apply(); lblHold.text = "$v ms" })

        tick()
    }

    private fun levelName(l: Int) = when (l) { 1 -> "سريع"; 2 -> "قوي"; 3 -> "أقصى"; else -> "خارق — بحث أعمق + توقع الجولة القادمة (موصى به)" }
    private fun simple(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { f(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun tick() {
        findViewById<TextView>(R.id.stOverlay).text = if (Settings.canDrawOverlays(this)) "✅ الظهور فوق التطبيقات" else "❌ الظهور فوق التطبيقات"
        findViewById<TextView>(R.id.stAccess).text = if (GestureService.isRunning) "✅ خدمة الوصول (الإيماءات)" else "⚪ خدمة الوصول (اختيارية — لوضع البوت التلقائي فقط)"
        findViewById<TextView>(R.id.stBot).text = "الحالة: ${BotService.status}\nحركات: ${BotService.movesDone}\n${BotService.lastBoard}"
        ui.postDelayed({ tick() }, 800)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJ && resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, BotService::class.java).setAction(BotService.ACTION_START).putExtra(BotService.EXTRA_CODE, resultCode).putExtra(BotService.EXTRA_DATA, data)
            startForegroundService(i)
            toast(when (prefs.getInt("mode", 0)) { 1, 2 -> "افتح لعبة THNDR واضغط ▶ — البوت هيسحب لوحده"; else -> "افتح لعبة THNDR واضغط ▶ — هيوريك فين تحط كل قطعة" })
        }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
