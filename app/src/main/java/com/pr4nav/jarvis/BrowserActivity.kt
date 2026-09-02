package com.pr4nav.jarvis

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import androidx.appcompat.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class BrowserActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var pathView: TextView
    private lateinit var crumbView: TextView
    private lateinit var searchBox: EditText
    private lateinit var adapter: FilesAdapter
    private val selected = LinkedHashSet<Fs.Entry>()
    private var actionMode: ActionMode? = null
    private var showHidden = false
    private var sortMode = SORT_NAME
    private var sortAsc = true

    data class Clip(val cut: Boolean, val items: List<Fs.Entry>)
    private var clip: Clip? = null

    companion object {
        const val SORT_NAME = 0; const val SORT_SIZE = 1; const val SORT_DATE = 2; const val SORT_TYPE = 3
        const val REQ_SAF = 7
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_browser)

        listView = findViewById(R.id.file_list)
        pathView = findViewById(R.id.path_view)
        crumbView = findViewById(R.id.breadcrumb_view)
        searchBox = findViewById(R.id.search_box)
        adapter = FilesAdapter()
        listView.adapter = adapter

        val prefs = getSharedPreferences("jarvis_fs", MODE_PRIVATE)
        sortMode = prefs.getInt("sort_mode", SORT_NAME)
        sortAsc = prefs.getBoolean("sort_asc", true)
        showHidden = prefs.getBoolean("show_hidden", false)

        findViewById<View>(R.id.btn_back).setOnClickListener { goUp() }
        findViewById<View>(R.id.btn_search).setOnClickListener {
            searchBox.visibility = if (searchBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (searchBox.visibility == View.GONE) load(SessionState.dir) else searchBox.requestFocus()
        }
        findViewById<View>(R.id.btn_more).setOnClickListener { v -> showOverflow(v) }
        findViewById<View>(R.id.btn_new_folder).setOnClickListener { newFolder() }
        findViewById<View>(R.id.btn_new_file).setOnClickListener { newFile() }
        findViewById<View>(R.id.btn_sort).setOnClickListener { sortByDialog() }
        findViewById<View>(R.id.btn_saf).setOnClickListener { pickSaf() }
        findViewById<View>(R.id.btn_paste).setOnClickListener {
            if (clip == null) Toast.makeText(this, "Nothing on clipboard — select files, then COPY/CUT", Toast.LENGTH_SHORT).show()
            else paste()
        }

        // Selection bottom bar (same ops as the ActionMode menu — one implementation each via Fs)
        findViewById<View>(R.id.act_copy).setOnClickListener { doClip(cut = false) }
        findViewById<View>(R.id.act_cut).setOnClickListener { doClip(cut = true) }
        findViewById<View>(R.id.act_delete).setOnClickListener { if (selected.isNotEmpty()) confirmDelete(selected.toList()) { } }
        findViewById<View>(R.id.act_share).setOnClickListener { if (selected.isNotEmpty()) share(selected.toList()) }

        searchBox.setOnEditorActionListener { _, _, _ ->
            val q = searchBox.text.toString().trim()
            if (q.isNotEmpty()) search(q)
            true
        }

        listView.onItemClickListener { pos ->
            val e = currentEntries.getOrNull(pos) ?: return@onItemClickListener
            if (selected.isNotEmpty()) { toggleSelect(e); return@onItemClickListener }
            if (e.isDir) load(e.path)
            else if (isTextLike(e)) startActivity(Intent(this, EditorActivity::class.java).putExtra("path", e.path))
            else openFile(e)
        }
        listView.onItemLongClickListener { pos ->
            toggleSelect(currentEntries.getOrNull(pos) ?: return@onItemLongClickListener)
        }

        load(SessionState.dir)
    }

    // ---------- data ----------

    private var currentEntries: List<Fs.Entry> = emptyList()

    private fun load(path: String) {
        SessionState.dir = path
        pathView.text = path
        renderCrumbs(path)
        thread {
            try {
                var list = Fs.list(path)
                if (!showHidden) list = list.filter { !it.hidden }
                currentEntries = sort(list)
                runOnUiThread { adapter.notifyDataSetChanged(); updateCrumbMeta() }
            } catch (ex: Exception) {
                currentEntries = emptyList()
                runOnUiThread {
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "Cannot open: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateCrumbMeta() {
        crumbView.text = "${currentEntries.size} items · ${Fs.accessLevel} · backend: ${Fs.backendFor(SessionState.dir).id}"
    }

    private fun sort(list: List<Fs.Entry>): List<Fs.Entry> {
        val cmp = when (sortMode) {
            SORT_SIZE -> compareBy<Fs.Entry> { it.size }
            SORT_DATE -> compareBy<Fs.Entry> { it.modified }
            SORT_TYPE -> compareBy<Fs.Entry> { it.name.substringAfterLast('.', "").lowercase() }
            else -> compareBy<Fs.Entry> { it.name.lowercase() }
        }
        return list.sortedWith(compareByDescending<Fs.Entry> { it.isDir }.then(cmp)).let { if (sortAsc) it else it.reversed() }
    }

    private fun goUp() {
        val d = SessionState.dir.trimEnd('/')
        if (d.startsWith("saf:")) { if (d != "saf:") load("saf:") else finish(); return }
        val parent = d.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent.length < 2) { finish(); return }
        load(parent)
    }

    private fun renderCrumbs(path: String) {
        val sb = SpannableStringBuilder()
        var idx = 0
        val parts = path.split("/").filter { it.isNotEmpty() }
        var acc = if (path.startsWith("/")) "" else ""
        parts.forEachIndexed { i, part ->
            acc += "/$part"
            val start = sb.length
            sb.append(part)
            sb.append("  ›  ")
            val span = object : ClickableSpan() {
                override fun onClick(widget: View) { load(acc) }
                override fun updateDrawState(ds: TextPaint) { ds.color = 0xFF4FD1C5.toInt(); ds.isUnderlineText = false }
            }
            sb.setSpan(span, start, start + part.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            idx++
        }
        crumbView.movementMethod = LinkMovementMethod.getInstance()
        crumbView.text = sb
    }

    private fun search(q: String) {
        val root = SessionState.dir
        thread {
            try {
                val res = Fs.search(root, q, 200)
                currentEntries = res
                runOnUiThread {
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "${res.size} results for '$q'", Toast.LENGTH_SHORT).show()
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, "Search failed: ${ex.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------- selection / action mode ----------

    private fun toggleSelect(e: Fs.Entry) {
        if (selected.contains(e)) selected.remove(e) else selected.add(e)
        adapter.notifyDataSetChanged()
        if (selected.isEmpty()) actionMode?.finish()
        else if (actionMode == null) startSupportActionMode(actionModeCallback)
        else actionMode?.title = "${selected.size} selected"
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_browser_actions, menu)
            mode.title = "${selected.size} selected"
            actionMode = mode
            findViewById<View>(R.id.action_bar).visibility = View.VISIBLE
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.act_copy -> { clip = Clip(false, selected.toList()); mode.finish() }
                R.id.act_cut -> { clip = Clip(true, selected.toList()); mode.finish() }
                R.id.act_delete -> { confirmDelete(selected.toList()) { mode.finish() } }
                R.id.act_share -> { share(selected.toList()); mode.finish() }
                R.id.act_rename -> { renameDialog(selected.first()); mode.finish() }
                R.id.act_edit -> { editFile(selected.first()); mode.finish() }
                R.id.act_open -> { openFile(selected.first()); mode.finish() }
                R.id.act_props -> { properties(selected.first()); mode.finish() }
            }
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            selected.clear()
            actionMode = null
            adapter.notifyDataSetChanged()
            findViewById<View>(R.id.action_bar).visibility = View.GONE
        }
    }

    // ---------- operations ----------

    private fun doClip(cut: Boolean) {
        if (selected.isEmpty()) return
        val items = selected.toList()
        clip = Clip(cut, items)
        actionMode?.finish()
        findViewById<View>(R.id.btn_paste).isEnabled = true
        Toast.makeText(this, "${items.size} ${if (cut) "cut" else "copied"} — open destination, tap PASTE", Toast.LENGTH_LONG).show()
    }

    private fun confirmDelete(items: List<Fs.Entry>, onDone: () -> Unit) {
        val big = items.any { it.isDir }
        AlertDialog.Builder(this)
            .setTitle("Delete ${items.size} item(s)?")
            .setMessage(if (big) "Folders will be deleted recursively. This cannot be undone." else null)
            .setPositiveButton("Delete") { _, _ ->
                thread {
                    val errs = ArrayList<String>()
                    for (e in items) try { Fs.delete(e.path) } catch (ex: Exception) { errs.add("${e.name}: ${ex.message}") }
                    runOnUiThread {
                        if (errs.isNotEmpty()) Toast.makeText(this, errs.joinToString("\n"), Toast.LENGTH_LONG).show()
                        load(SessionState.dir); onDone()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun paste() {
        val c = clip ?: return
        val dstDir = SessionState.dir.trimEnd('/')
        thread {
            val errs = ArrayList<String>()
            var done = 0
            for (e in c.items) {
                var dst = "$dstDir/${e.name}"
                if (dst == e.path) {
                    // same folder: auto-rename instead of silently doing nothing
                    val base = e.name.substringBeforeLast('.', e.name)
                    val ext = if (e.name.contains('.')) "." + e.name.substringAfterLast('.', "") else ""
                    var i = 1
                    while (Fs.exists("$dstDir/$base ($i)$ext")) i++
                    dst = "$dstDir/$base ($i)$ext"
                } else {
                    var i = 1
                    var target = dst
                    while (Fs.exists(target)) { target = "$dstDir/${e.name.substringBeforeLast('.', e.name)} ($i)${if (e.name.contains('.')) "." + e.name.substringAfterLast('.', "") else ""}"; i++ }
                    dst = target
                }
                try {
                    if (c.cut) Fs.move(e.path, dst) else Fs.copy(e.path, dst)
                    done++
                } catch (ex: Exception) { errs.add("${e.name}: ${ex.message}") }
            }
            if (c.cut) clip = null
            runOnUiThread {
                if (errs.isNotEmpty()) Toast.makeText(this, errs.joinToString("\n"), Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "$done item(s) ${if (c.cut) "moved" else "copied"}", Toast.LENGTH_SHORT).show()
                findViewById<View>(R.id.btn_paste).isEnabled = clip != null
                load(SessionState.dir)
            }
        }
    }

    private fun share(items: List<Fs.Entry>) {
        val files = items.filter { !it.isDir }
        if (files.isEmpty()) { Toast.makeText(this, "Only files can be shared", Toast.LENGTH_SHORT).show(); return }
        val uris = ArrayList<Uri>()
        for (f in files) {
            try {
                uris.add(FileProvider.getUriForFile(this, "com.pr4nav.jarvis.fileprovider", java.io.File(f.path)))
            } catch (_: Exception) {}
        }
        val i = Intent(Intent.ACTION_SEND_MULTIPLE)
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.type = "*/*"; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share"))
    }

    private fun renameDialog(e: Fs.Entry) {
        val input = EditText(this); input.setText(e.name)
        AlertDialog.Builder(this).setTitle("Rename").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != e.name) {
                    try { Fs.rename(e.path, e.path.substringBeforeLast('/') + "/" + newName); load(SessionState.dir) }
                    catch (ex: Exception) { Toast.makeText(this, ex.message, Toast.LENGTH_LONG).show() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun isTextLike(e: Fs.Entry): Boolean =
        e.mime.startsWith("text/") || listOf(
            "kt", "java", "py", "js", "ts", "sh", "json", "xml", "md", "gradle", "kts",
            "txt", "csv", "log", "yml", "yaml", "toml", "ini", "conf", "html", "css", "c", "cpp", "h"
        ).contains(e.name.substringAfterLast('.', "").lowercase()) || !e.name.contains('.')

    private fun editFile(e: Fs.Entry) {
        if (!isTextLike(e)) {
            Toast.makeText(this, "Binary file — not editable as text. Use Open / Share / Properties.", Toast.LENGTH_LONG).show()
            return
        }
        if (e.size > 2_000_000) { Toast.makeText(this, "File too large for inline editor (>2MB)", Toast.LENGTH_LONG).show(); return }
        startActivity(Intent(this, EditorActivity::class.java).putExtra("path", e.path))
    }

    private fun properties(e: Fs.Entry) {
        val msg = buildString {
            append("Name: ${e.name}\nPath: ${e.path}\nType: ${if (e.isDir) "Folder" else e.mime}")
            append("\nSize: ${human(e.size)}\nModified: ${fmtDate(e.modified)}\nBackend: ${Fs.backendFor(e.path).id}")
        }
        AlertDialog.Builder(this).setTitle("Properties").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun newFolder() = promptName("New folder") { Fs.mkdir(SessionState.dir + "/" + it); load(SessionState.dir) }
    private fun newFile() = promptName("New file") { Fs.create(SessionState.dir + "/" + it); load(SessionState.dir) }

    private fun promptName(title: String, go: (String) -> Unit) {
        val input = EditText(this); input.hint = "name"
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Create") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) try { go(n) } catch (ex: Exception) { Toast.makeText(this, ex.message, Toast.LENGTH_LONG).show() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun sortByDialog() {
        val names = arrayOf("Name", "Size", "Date", "Type")
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(names, sortMode) { d, which -> sortMode = which; d.dismiss(); saveSort(); load(SessionState.dir) }
            .setNeutralButton(if (sortAsc) "↑ Ascending" else "↓ Descending") { d, _ ->
                sortAsc = !sortAsc; d.dismiss(); saveSort(); load(SessionState.dir)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveSort() {
        getSharedPreferences("jarvis_fs", MODE_PRIVATE).edit()
            .putInt("sort_mode", sortMode).putBoolean("sort_asc", sortAsc)
            .putBoolean("show_hidden", showHidden).apply()
    }

    private fun pickSaf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_SAF)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAF && resultCode == RESULT_OK && data?.data != null) {
            Fs.Saf.save(this, data.data!!)
            load("saf:")
        }
    }

    private fun openFile(e: Fs.Entry) {
        try {
            val uri = FileProvider.getUriForFile(this, "com.pr4nav.jarvis.fileprovider", java.io.File(e.path))
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (ex: Exception) {
            Toast.makeText(this, "No app can open this (${ex.message})", Toast.LENGTH_LONG).show()
        }
    }

    private fun showOverflow(v: View) {
        val pop = androidx.appcompat.widget.PopupMenu(this, v)
        pop.menuInflater.inflate(R.menu.menu_browser_overflow, pop.menu)
        pop.menu.findItem(R.id.menu_paste).isVisible = clip != null
        pop.menu.findItem(R.id.menu_hidden).isChecked = showHidden
        pop.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_paste -> paste()
                R.id.menu_new_folder -> newFolder()
                R.id.menu_new_file -> newFile()
                R.id.menu_sort -> sortByDialog()
                R.id.menu_hidden -> { showHidden = !showHidden; saveSort(); load(SessionState.dir) }
                R.id.menu_storage -> requestAllFiles()
                R.id.menu_refresh -> load(SessionState.dir)
            }
            true
        }
        pop.show()
    }

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        } else Toast.makeText(this, "Storage already fully accessible", Toast.LENGTH_SHORT).show()
    }

    // ---------- adapter ----------

    private fun human(b: Long): String = when {
        b >= 1e9 -> String.format(Locale.US, "%.1f GB", b / 1e9)
        b >= 1e6 -> String.format(Locale.US, "%.1f MB", b / 1e6)
        b >= 1e3 -> String.format(Locale.US, "%.1f KB", b / 1e3)
        else -> "$b B"
    }

    private fun fmtDate(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    private fun iconFor(e: Fs.Entry): String = when {
        e.isDir -> "📁"
        e.mime.startsWith("image/") -> "🖼️"
        e.mime.startsWith("video/") -> "🎬"
        e.mime.startsWith("audio/") -> "🎵"
        e.name.endsWith(".apk") -> "📦"
        e.name.endsWith(".zip") || e.name.endsWith(".tar.gz") || e.name.endsWith(".tgz") -> "🗜️"
        e.mime.startsWith("text/") || listOf("kt", "java", "py", "js", "ts", "sh", "json", "xml", "md", "gradle", "kts").contains(e.name.substringAfterLast('.', "")) -> "📜"
        else -> "📄"
    }

    inner class FilesAdapter : BaseAdapter() {
        override fun getCount() = currentEntries.size
        override fun getItem(p: Int) = currentEntries[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@BrowserActivity).inflate(R.layout.item_file, parent, false)
            val e = currentEntries[p]
            v.findViewById<TextView>(R.id.icon).text = iconFor(e)
            v.findViewById<TextView>(R.id.name).text = e.name
            v.findViewById<TextView>(R.id.meta).text =
                if (e.isDir) "folder · ${fmtDate(e.modified)}" else "${human(e.size)} · ${fmtDate(e.modified)}"
            val check = v.findViewById<TextView>(R.id.check)
            check.visibility = if (selected.contains(e)) View.VISIBLE else View.GONE
            check.text = "✓"
            v.alpha = if (selected.contains(e)) 0.75f else 1f
            v.setBackgroundColor(if (selected.contains(e)) 0xFF16232E.toInt() else 0x00000000)
            return v
        }
    }

    // ---------- android.widget shorthands ----------
    private fun ListView.onItemClickListener(f: (Int) -> Unit) {
        onItemClickListener = AdapterViewOnItemClick { _, _, pos, _ -> f(pos) }
    }

    private fun ListView.onItemLongClickListener(f: (Int) -> Unit) {
        onItemLongClickListener = AdapterViewOnItemLongClick { _, _, pos, _ -> f(pos); true }
    }

    private class AdapterViewOnItemClick(val f: (android.widget.AdapterView<*>?, View, Int, Long) -> Unit) : android.widget.AdapterView.OnItemClickListener {
        override fun onItemClick(parent: android.widget.AdapterView<*>, view: View, position: Int, id: Long) = f(parent, view, position, id)
    }

    private class AdapterViewOnItemLongClick(val f: (android.widget.AdapterView<*>?, View, Int, Long) -> Boolean) : android.widget.AdapterView.OnItemLongClickListener {
        override fun onItemLongClick(parent: android.widget.AdapterView<*>, view: View, position: Int, id: Long) = f(parent, view, position, id)
    }
}
