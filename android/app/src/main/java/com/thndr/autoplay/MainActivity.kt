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
import android.widget.CheckBox
import android.widget.EditText
import com.thndr.autoplay.engine.RemotePlanner
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
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { startService(Intent(this, BotService::class.java).setAction(BotService.ACTION_STOP)) }
        findViewById<Button>(R.id.btnShareLog).setOnClickListener { if (!GameLog.share(this)) toast("مافيش سجل جولة لسه — العب جولة بالمرشد الأول") }
        findViewById<Button>(R.id.btnResetCal).setOnClickListener { prefs.edit().remove("offX").remove("offY").putBoolean("calibrated", false).apply(); toast("تم تصفير المعايرة") }

        val rgMode = findViewById<android.widget.RadioGroup>(R.id.rgMode)
        rgMode.check(if (prefs.getInt("mode", 0) == 1) R.id.modeAuto else R.id.modeGuide)
        val autoOnly = findViewById<android.view.View>(R.id.autoOnly); val resetCal = findViewById<Button>(R.id.btnResetCal)
        fun refreshMode() { val auto = prefs.getInt("mode", 0) == 1; autoOnly.visibility = if (auto) android.view.View.VISIBLE else android.view.View.GONE; resetCal.visibility = autoOnly.visibility }
        rgMode.setOnCheckedChangeListener { _, id -> prefs.edit().putInt("mode", if (id == R.id.modeAuto) 1 else 0).apply(); refreshMode() }
        refreshMode()

        val chk = findViewById<android.widget.CheckBox>(R.id.chkConfirm)
        chk.isChecked = prefs.getBoolean("confirmPieces", false)
        chk.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("confirmPieces", v).apply() }
        val chkDeep = findViewById<android.widget.CheckBox>(R.id.chkDeep)
        chkDeep.isChecked = prefs.getBoolean("deepEnd", true)
        chkDeep.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("deepEnd", v).apply() }
        val seekFill = findViewById<SeekBar>(R.id.seekFill); val lblFill = findViewById<TextView>(R.id.lblFill)
        seekFill.max = 3; seekFill.progress = (prefs.getInt("fillMoves", 9) / 3 - 2).coerceIn(0, 3)
        lblFill.text = fillName(seekFill.progress)
        seekFill.setOnSeekBarChangeListener(simple { prefs.edit().putInt("fillMoves", (it + 2) * 3).apply(); lblFill.text = fillName(it) })

        val chkServer = findViewById<CheckBox>(R.id.chkServer); val edtServer = findViewById<EditText>(R.id.edtServer); val lblServer = findViewById<TextView>(R.id.lblServer)
        chkServer.isChecked = prefs.getBoolean("server", true)
        chkServer.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("server", v).apply(); if (v) pingServer(lblServer) }
        edtServer.setText(prefs.getString("serverUrl", RemotePlanner.DEFAULT_URL))
        edtServer.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { val u = e?.toString()?.trim().orEmpty(); prefs.edit().putString("serverUrl", if (u.isEmpty()) RemotePlanner.DEFAULT_URL else u).apply() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        if (chkServer.isChecked) pingServer(lblServer)
        val seekLevel = findViewById<SeekBar>(R.id.seekLevel); val lblLevel = findViewById<TextView>(R.id.lblLevel)
        seekLevel.max = 3; seekLevel.progress = prefs.getInt("level", 3).coerceIn(1, 4) - 1
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

        tick()
    }

    private fun fillName(i: Int) = "ملء اللوحة في آخر ${i + 2} لفلات" + (if (i == 1) " (موصى به)" else "")
    private fun levelName(l: Int) = when (l) { 1 -> "سريع"; 2 -> "قوي"; 3 -> "الأقصى — أعلى نقاط في الـ 25 لفل (موصى به)"; else -> "ULTRA — بحث شامل بلا قص + أعمق نظرة أمامية (30–90 ث للجولة؛ لا يوجد أعمق منه)" }
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
            toast(if (prefs.getInt("mode", 0) == 1) "افتح لعبة THNDR واضغط ▶ — البوت هيسحب لوحده" else "افتح لعبة THNDR واضغط ▶ — هيوريك فين تحط كل قطعة")
        }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun pingServer(lbl: TextView) {
        lbl.text = "بفحص السيرفر…"
        Thread {
            val url = prefs.getString("serverUrl", RemotePlanner.DEFAULT_URL) ?: RemotePlanner.DEFAULT_URL
            val (cpus, via) = RemotePlanner.pingAny(url)
            ui.post { lbl.text = if (cpus > 0) "✔ السيرفر شغال ($cpus كور)${if (via == RemotePlanner.PROXY_URL && url.trimEnd('/') != via) " — عبر Cloudflare" else ""} — الخطة هتتحسب هناك" else "✖ السيرفر مش متاح (لا fly.dev ولا Cloudflare) — هيحسب على الموبايل تلقائيًا" }
        }.start()
    }
}
