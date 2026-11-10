package com.llamalite

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Natívna apka: vyberie model, cez Start spustí llama-server (bionic binárka
 * zabalená v APK), Stop ho zabije (uvoľní RAM). Chat = vstavané WebUI llama-servera.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var modelInfo: TextView
    private lateinit var scanDir: EditText
    private lateinit var ctxE: EditText
    private lateinit var threadsE: EditText
    private lateinit var loadModeS: Spinner
    private lateinit var flashS: Spinner
    private lateinit var ctkS: Spinner
    private lateinit var ctvS: Spinner
    private lateinit var repackC: CheckBox
    private lateinit var reasoningC: CheckBox
    private lateinit var extraE: EditText

    private var models: List<ModelEntry> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private val KV = listOf("f16", "bf16", "q8_0", "q4_0", "q4_1", "q5_0", "q5_1", "iq4_nl", "q6_k")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinner = findViewById(R.id.spinnerModel)
        status = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        modelInfo = findViewById(R.id.modelInfo)
        scanDir = findViewById(R.id.scanDir)
        ctxE = findViewById(R.id.ctx)
        threadsE = findViewById(R.id.threads)
        loadModeS = findViewById(R.id.loadMode)
        flashS = findViewById(R.id.flashAttn)
        ctkS = findViewById(R.id.ctk); ctvS = findViewById(R.id.ctv)
        repackC = findViewById(R.id.repack); reasoningC = findViewById(R.id.reasoning)
        extraE = findViewById(R.id.extraArgs)

        loadModeS.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("auto", "mmap", "mlock", "mmap+mlock"))
        flashS.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("on", "off", "auto"))
        ctkS.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, KV)
        ctvS.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, KV)

        loadSettings()
        scan()
        refreshStatus()

        findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings(); toast("Uložené") }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startServer() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopServer() }
        findViewById<Button>(R.id.btnChat).setOnClickListener { openChat() }

        if (!LlamaServerManager.hasBinary(this)) {
            status.text = "Server: zastavený  ⚠ chýba binárka llama-server (len vo finálnom APK)"
        }
    }

    private fun loadSettings() {
        val s = Settings.load(this)
        val dir = if (s.modelPath.isNotBlank()) File(s.modelPath).parent ?: "/sdcard/Download" else "/sdcard/Download"
        scanDir.setText(dir)
        ctxE.setText(s.ctx.toString())
        threadsE.setText(s.threads.toString())
        loadModeS.setSelection(listOf("auto", "mmap", "mlock", "mmap+mlock").indexOf(s.loadMode).coerceAtLeast(0))
        flashS.setSelection(listOf("on", "off", "auto").indexOf(s.flashAttn).coerceAtLeast(0))
        ctkS.setSelection(KV.indexOf(s.ctk).coerceAtLeast(0))
        ctvS.setSelection(KV.indexOf(s.ctv).coerceAtLeast(0))
        repackC.isChecked = s.repack
        reasoningC.isChecked = s.reasoning
        extraE.setText(s.extraArgs)
    }

    private fun scan() {
        val dir = scanDir.text.toString().trim().ifBlank { "/sdcard/Download" }
        val roots = listOf(File(dir), File("/sdcard/Download/Operit/models"))
        models = try { ModelScanner.scan(roots, 3) } catch (e: Exception) { emptyList() }
        val labels = models.map {
            val sz = if (it.sizeMb >= 1024) "%.1fG".format(it.sizeMb / 1024.0) else "${it.sizeMb}M"
            "${it.name.take(30)} · $sz" + (if (it.broken) " ⚠" else "") + (if (it.mmproj.isNotBlank()) " 👁" else "")
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (models.isNotEmpty()) updateModelInfo()
        toast("Nájdených: ${models.size}")
    }

    private fun updateModelInfo() {
        val m = models.getOrNull(spinner.selectedItemPosition) ?: return
        modelInfo.text = m.path + (if (m.mmproj.isNotBlank()) "\nmmproj: ${File(m.mmproj).name}" else "") +
                (if (m.broken) "\n⚠ POŠKODENÝ (zlá GGUF hlavička)" else "")
    }

    private fun collect(): Settings {
        val s = Settings()
        val m = models.getOrNull(spinner.selectedItemPosition)
        s.modelPath = m?.path ?: ""
        s.mmprojPath = m?.mmproj ?: ""
        s.alias = m?.id ?: ""
        s.ctx = ctxE.text.toString().toIntOrNull() ?: 16384
        s.threads = threadsE.text.toString().toIntOrNull() ?: 4
        s.loadMode = loadModeS.selectedItem?.toString() ?: "mmap"
        s.flashAttn = flashS.selectedItem?.toString() ?: "on"
        s.ctk = ctkS.selectedItem?.toString() ?: "q8_0"
        s.ctv = ctvS.selectedItem?.toString() ?: "q8_0"
        s.repack = repackC.isChecked
        s.reasoning = reasoningC.isChecked
        s.extraArgs = extraE.text.toString()
        s.port = 8080
        return s
    }

    private fun saveSettings() { collect().save(this) }

    private fun toast(m: String) { android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show() }

    private fun startServer() {
        val s = collect()
        if (s.modelPath.isBlank()) { toast("Vyber model"); return }
        saveSettings()
        status.text = "Server: štartujem… (môže trvať 15–60 s)"
        Thread {
            val err = LlamaServerManager.start(this, s)
            handler.post {
                if (err.isEmpty()) { status.text = "Server: BEŽÍ  →  http://127.0.0.1:${s.port}"; toast("Beží ✔") }
                else { status.text = "Server: chyba"; logView.text = err; toast("Chyba – pozri log") }
                updateLog()
            }
        }.start()
    }

    private fun stopServer() {
        LlamaServerManager.stop()
        status.text = "Server: zastavený"
        toast("Zastavené, RAM uvoľnená")
    }

    private fun openChat() {
        val url = "http://127.0.0.1:8080"
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) { toast("Nepodarilo sa otvoriť prehliadač") }
    }

    private fun refreshStatus() {
        status.text = if (LlamaServerManager.running) "Server: BEŽÍ" else "Server: zastavený"
        handler.postDelayed({ updateLog() }, 600)
    }

    private fun updateLog() {
        logView.text = LlamaServerManager.tailLog(this, 18)
    }

    override fun onResume() { super.onResume(); refreshStatus() }
}