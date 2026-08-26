package com.pr4nav.jarvis

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Text editor over Fs.read / Fs.write — the SAME layer as GUI + agent.
 * Binary files are refused honestly (no fake text mode).
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var text: EditText
    private lateinit var state: TextView
    private lateinit var pathView: TextView
    private var path: String = ""
    private var dirty = false
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        text = findViewById(R.id.editor_text)
        state = findViewById(R.id.editor_state)
        pathView = findViewById(R.id.editor_path)

        path = Fs.resolve(intent?.getStringExtra("path") ?: "")

        text.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!loading) { dirty = true; paintState() }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<android.widget.Button>(R.id.editor_save).setOnClickListener { save() }
        findViewById<android.widget.Button>(R.id.editor_saveas).setOnClickListener { saveAs() }
        findViewById<android.widget.Button>(R.id.editor_reload).setOnClickListener { reload() }
        findViewById<android.widget.Button>(R.id.editor_discard).setOnClickListener {
            if (dirty) confirmDiscard() else finish()
        }

        load()
    }

    private fun paintState() {
        state.text = if (dirty) "● unsaved changes" else "saved"
        state.setTextColor(if (dirty) 0xFFF9A825.toInt() else 0xFF4FD1C5.toInt())
        pathView.text = "$path · backend=${Fs.backendFor(path).id}"
    }

    private fun load() {
        loading = true
        try {
            val content = Fs.read(path)
            if (content.contains('\u0000')) {
                Toast.makeText(this, "Binary file — not editable as text. Use Open/Share/Properties.", Toast.LENGTH_LONG).show()
                finish(); return
            }
            text.setText(content)
            dirty = false
        } catch (e: Exception) {
            Toast.makeText(this, "Fs.read failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish(); return
        } finally {
            loading = false
        }
        paintState()
    }

    private fun save() {
        try {
            Fs.write(path, text.text.toString())
            dirty = false
            paintState()
            Toast.makeText(this, "Saved via ${Fs.backendFor(path).id}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Fs.write failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAs() {
        val input = EditText(this); input.setText(path)
        AlertDialog.Builder(this).setTitle("Save as (full path)").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val np = Fs.resolve(input.text.toString().trim())
                if (np.isNotEmpty()) {
                    try {
                        Fs.write(np, text.text.toString())
                        path = np; dirty = false; paintState()
                        Toast.makeText(this, "Saved → $np", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Fs.write failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun reload() {
        if (dirty) {
            AlertDialog.Builder(this).setTitle("Discard changes and reload?")
                .setPositiveButton("Reload") { _, _ -> load() }
                .setNegativeButton("Cancel", null).show()
        } else load()
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this).setTitle("Discard unsaved changes?")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton("Keep editing", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (dirty) confirmDiscard() else super.onBackPressed()
    }
}
