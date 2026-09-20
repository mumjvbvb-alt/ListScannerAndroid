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
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class Person(val nom: String, val prenom: String, val pere: String)

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)

    private val nomHistory = ArrayDeque<String>()
    private val prenomHistory = ArrayDeque<String>()
    private val pereHistory = ArrayDeque<String>()

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
                resetRecognition()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(executor) { proxy ->
                if (!busy.compareAndSet(false, true)) {
                    proxy.close()
                    return@setAnalyzer
                }

                val media = proxy.image
                if (media == null) {
                    proxy.close()
                    busy.set(false)
                    return@setAnalyzer
                }

                recognizer.process(
                    InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                )
                    .addOnSuccessListener { parseResult(it) }
                    .addOnCompleteListener {
                        proxy.close()
                        busy.set(false)
                    }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun parseResult(result: Text) {
        val lines = result.text
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        var n = ""
        var p = ""
        var f = ""

        for (i in lines.indices) {
            val x = normalizeLabel(lines[i])

            if (n.isBlank() && Regex("\\bNOM\\b").containsMatchIn(x)) {
                n = valueAfterLabel(lines, i, "NOM")
            }

            if (p.isBlank() && Regex("\\bPRENOM\\b").containsMatchIn(x)) {
                p = valueAfterLabel(lines, i, "PRENOM")
            }

            if (f.isBlank() && Regex("\\bPERE\\b").containsMatchIn(x)) {
                f = valueAfterLabel(lines, i, "PERE")
            }
        }

        val stableN = updateStable(nomHistory, n)
        val stableP = updateStable(prenomHistory, p)
        val stableF = updateStable(pereHistory, f)

        if (stableN != null) nom = stableN
        if (stableP != null) prenom = stableP
        if (stableF != null) pere = stableF

        runOnUiThread {
            binding.fieldsText.text =
                "NOM: ${nom.ifBlank { "—" }}    PRÉNOM: ${prenom.ifBlank { "—" }}    PÈRE: ${pere.ifBlank { "—" }}"

            val ready = nom.isNotBlank() && prenom.isNotBlank() && pere.isNotBlank()
            binding.captureButton.isEnabled = ready

            binding.statusText.text = if (ready) {
                "✓ تم تثبيت الحقول الثلاثة — اضغط إضافة"
            } else {
                "ثبّت الورقة داخل الإطار… جاري تثبيت القراءة"
            }
        }
    }

    private fun valueAfterLabel(lines: List<String>, index: Int, label: String): String {
        val current = normalizeValue(
            normalizeLabel(lines[index]).substringAfter(label, "")
        )

        if (isGoodValue(current, label)) return current

        for (step in 1..2) {
            if (index + step < lines.size) {
                val candidate = normalizeValue(lines[index + step])
                if (isGoodValue(candidate, label)) return candidate
            }
        }
        return ""
    }

    private fun isGoodValue(value: String, label: String): Boolean {
        if (value.length < 2) return false
        val u = value.uppercase(Locale.ROOT)
        if (u == label || u == "NOM" || u == "PRENOM" || u == "PERE") return false
        return u.count { it.isLetter() } >= 2
    }

    private fun updateStable(history: ArrayDeque<String>, candidate: String): String? {
        if (!isGoodValue(candidate, "")) return null

        if (history.size >= 8) history.removeFirst()
        history.addLast(candidate)

        val counts = history.groupingBy { it }.eachCount()
        val exact = counts.maxByOrNull { it.value }
        if (exact != null && exact.value >= 3) return exact.key

        val candidates = history.toList()
        for (base in candidates) {
            val similar = candidates.count { similarText(base, it) }
            if (similar >= 3) {
                return candidates
                    .filter { similarText(base, it) }
                    .maxByOrNull { it.length } ?: base
            }
        }
        return null
    }

    private fun similarText(a: String, b: String): Boolean {
        val aa = a.replace(" ", "")
        val bb = b.replace(" ", "")
        if (aa == bb) return true
        val maxLen = maxOf(aa.length, bb.length)
        if (maxLen < 3) return false
        val distance = levenshtein(aa, bb)
        return distance <= maxOf(1, maxLen / 5)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    current[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
            val tmp = prev
            prev = current
            current = tmp
        }
        return prev[b.length]
    }

    private fun normalizeLabel(s: String): String {
        return s.uppercase(Locale.ROOT)
            .replace("É", "E")
            .replace("È", "E")
            .replace("Ê", "E")
            .replace("Ë", "E")
            .replace("À", "A")
            .replace("Â", "A")
            .replace("Î", "I")
            .replace("Ï", "I")
            .replace("Ô", "O")
            .replace("Û", "U")
            .replace("Ù", "U")
            .replace("Ç", "C")
            .replace(":", " ")
            .trim()
    }

    private fun normalizeValue(s: String): String {
        return s.uppercase(Locale.ROOT)
            .replace(Regex("[^A-ZÀ-ÖØ-Ý' -]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun resetRecognition() {
        nom = ""
        prenom = ""
        pere = ""
        nomHistory.clear()
        prenomHistory.clear()
        pereHistory.clear()
        binding.fieldsText.text = "NOM: —    PRÉNOM: —    PÈRE: —"
        binding.captureButton.isEnabled = false
    }

    override fun onDestroy() {
        recognizer.close()
        executor.shutdown()
        super.onDestroy()
    }
}
