package com.example.listscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.listscanner.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class Person(val nom: String, val prenom: String, val pere: String)

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    private var nom = ""
    private var prenom = ""
    private var pere = ""
    private val people = mutableListOf<Person>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.captureButton.setOnClickListener {
            if (nom.isNotBlank() && prenom.isNotBlank() && pere.isNotBlank()) {
                people.add(Person(nom, prenom, pere))
                binding.countText.text = "عدد المسجلين: ${people.size}"
                binding.statusText.text = "تمت الإضافة. وجّه الورقة التالية."
                nom = ""; prenom = ""; pere = ""
                binding.fieldsText.text = "NOM: —    PRÉNOM: —    PÈRE: —"
                binding.captureButton.isEnabled = false
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                if (!busy.compareAndSet(false, true)) { proxy.close(); return@setAnalyzer }
                val media = proxy.image
                if (media == null) { proxy.close(); busy.set(false); return@setAnalyzer }
                recognizer.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { parseResult(it) }
                    .addOnCompleteListener { proxy.close(); busy.set(false) }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun parseResult(result: Text) {
        val lines = result.text.lines().map { it.trim() }.filter { it.isNotBlank() }
        var n = ""; var p = ""; var f = ""
        for (i in lines.indices) {
            val x = lines[i].uppercase(Locale.ROOT)
                .replace("É","E").replace("È","E").replace("Ê","E")
                .replace("À","A").replace(":", " ").trim()
            fun after(label: String): String {
                val at = x.indexOf(label)
                return if (at >= 0) clean(x.substring(at + label.length)) else ""
            }
            if (n.isBlank() && Regex("\\bNOM\\b").containsMatchIn(x)) {
                n = after("NOM"); if (n.isBlank() && i + 1 < lines.size) n = clean(lines[i+1])
            }
            if (p.isBlank() && x.contains("PRENOM")) {
                p = after("PRENOM"); if (p.isBlank() && i + 1 < lines.size) p = clean(lines[i+1])
            }
            if (f.isBlank() && Regex("\\bPERE\\b").containsMatchIn(x)) {
                f = after("PERE"); if (f.isBlank() && i + 1 < lines.size) f = clean(lines[i+1])
            }
        }
        nom = n; prenom = p; pere = f
        runOnUiThread {
            binding.fieldsText.text = "NOM: ${n.ifBlank { "—" }}    PRÉNOM: ${p.ifBlank { "—" }}    PÈRE: ${f.ifBlank { "—" }}"
            val ready = n.isNotBlank() && p.isNotBlank() && f.isNotBlank()
            binding.captureButton.isEnabled = ready
            binding.statusText.text = if (ready) "✓ الحقول الثلاثة جاهزة — اضغط إضافة" else "جارٍ التعرف على NOM / PRÉNOM / PÈRE..."
        }
    }

    private fun clean(s: String) = s.uppercase(Locale.ROOT).replace(Regex("[^A-ZÀ-ÖØ-Ý' -]"), "").trim()

    override fun onDestroy() {
        recognizer.close()
        executor.shutdown()
        super.onDestroy()
    }
}
