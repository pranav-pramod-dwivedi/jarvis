package com.pr4nav.jarvis.registry

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.pr4nav.jarvis.R
import kotlin.concurrent.thread

class CapabilitiesActivity : AppCompatActivity() {

    private lateinit var recyclerCapabilities: RecyclerView
    private lateinit var inputSearch: EditText
    private lateinit var chipGroupCategories: ChipGroup
    private lateinit var badgeTotalCount: TextView
    private lateinit var adapter: CapabilityAdapter

    private var allCapabilities: List<CapabilityDef> = emptyList()
    private var selectedCategory: String = "ALL"
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capabilities)

        recyclerCapabilities = findViewById(R.id.recycler_capabilities)
        inputSearch = findViewById(R.id.input_search)
        chipGroupCategories = findViewById(R.id.chip_group_categories)
        badgeTotalCount = findViewById(R.id.badge_total_count)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        allCapabilities = CapabilityRegistry.getAll()
        badgeTotalCount.text = "${allCapabilities.size} READY"

        adapter = CapabilityAdapter(
            items = allCapabilities,
            onTestClicked = { cap, statusView -> runCapabilityTest(cap, statusView) },
            onInspectClicked = { cap -> showDetailDialog(cap) }
        )

        recyclerCapabilities.layoutManager = LinearLayoutManager(this)
        recyclerCapabilities.adapter = adapter

        setupSearch()
        setupCategoryChips()
    }

    private fun setupSearch() {
        inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                filterList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupCategoryChips() {
        chipGroupCategories.setOnCheckedChangeListener { group, checkedId ->
            if (checkedId != View.NO_ID) {
                val chip = group.findViewById<Chip>(checkedId)
                selectedCategory = when (chip?.id) {
                    R.id.chip_device -> "device"
                    R.id.chip_media -> "media"
                    R.id.chip_apps -> "app"
                    R.id.chip_navigation -> "navigation"
                    R.id.chip_filesystem -> "filesystem"
                    R.id.chip_linux -> "terminal"
                    R.id.chip_agents -> "agy"
                    R.id.chip_system -> "clock"
                    R.id.chip_workflows -> "workflow"
                    else -> "ALL"
                }
                filterList()
            }
        }
    }

    private fun filterList() {
        val filtered = allCapabilities.filter { cap ->
            val matchesCategory = when (selectedCategory) {
                "ALL" -> true
                "terminal" -> cap.category == "terminal" || cap.category == "ubuntu"
                "navigation" -> cap.category == "navigation" || cap.category == "communication"
                "clock" -> cap.category == "clock" || cap.category == "camera" || cap.category == "clipboard" || cap.category == "settings" || cap.category == "memory" || cap.category == "gui"
                "agy" -> cap.category == "agy" || cap.category == "opencode"
                else -> cap.category.equals(selectedCategory, ignoreCase = true)
            }

            val matchesSearch = if (searchQuery.isBlank()) true else {
                val q = searchQuery.lowercase()
                cap.id.lowercase().contains(q) ||
                cap.name.lowercase().contains(q) ||
                cap.description.lowercase().contains(q) ||
                cap.aliases.any { it.lowercase().contains(q) }
            }

            matchesCategory && matchesSearch
        }

        adapter.updateList(filtered)
        badgeTotalCount.text = "${filtered.size} / ${allCapabilities.size}"
    }

    private fun runCapabilityTest(cap: CapabilityDef, statusView: TextView) {
        statusView.text = "RUNNING..."
        statusView.setTextColor(Color.parseColor("#F59E0B"))

        thread {
            val res = CapabilityRegistry.execute(cap.id, emptyMap(), this)
            runOnUiThread {
                if (res.success) {
                    statusView.text = "✓ ${res.latencyMs}ms"
                    statusView.setTextColor(Color.parseColor("#10B981"))
                    Toast.makeText(this, "⚡ [${cap.id}]: ${res.summary}", Toast.LENGTH_SHORT).show()
                } else {
                    statusView.text = "✗ ${res.latencyMs}ms"
                    statusView.setTextColor(Color.parseColor("#EF4444"))
                    Toast.makeText(this, "Error: ${res.error ?: res.summary}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showDetailDialog(cap: CapabilityDef) {
        val dialog = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.dialog_capability_detail, null)

        val textCategory = sheetView.findViewById<TextView>(R.id.dialog_category)
        val textRisk = sheetView.findViewById<TextView>(R.id.dialog_risk)
        val textName = sheetView.findViewById<TextView>(R.id.dialog_name)
        val textId = sheetView.findViewById<TextView>(R.id.dialog_id)
        val textDesc = sheetView.findViewById<TextView>(R.id.dialog_desc)
        val textBackend = sheetView.findViewById<TextView>(R.id.dialog_backend)
        val textAliases = sheetView.findViewById<TextView>(R.id.dialog_aliases)
        val textCli = sheetView.findViewById<TextView>(R.id.dialog_cli)
        val btnCopy = sheetView.findViewById<Button>(R.id.dialog_btn_copy)
        val paramInput = sheetView.findViewById<EditText>(R.id.dialog_param_input)
        val btnRunTest = sheetView.findViewById<Button>(R.id.dialog_btn_run_test)
        val textOutput = sheetView.findViewById<TextView>(R.id.dialog_output)

        textCategory.text = cap.category.uppercase()
        textRisk.text = "RISK: ${cap.risk.name}"
        when (cap.risk) {
            RiskLevel.LOW -> textRisk.setTextColor(Color.parseColor("#10B981"))
            RiskLevel.MEDIUM -> textRisk.setTextColor(Color.parseColor("#F59E0B"))
            RiskLevel.HIGH -> textRisk.setTextColor(Color.parseColor("#EF4444"))
        }

        textName.text = cap.name
        textId.text = cap.id
        textDesc.text = cap.description
        textBackend.text = "${cap.backend.name} · Risk: ${cap.risk.name} · Confirmation: ${cap.requiresConfirmation}"

        val aliasList = if (cap.aliases.isEmpty()) "• No aliases declared" else cap.aliases.joinToString("\n") { "• $it" }
        textAliases.text = aliasList

        val cliCommand = "jarvis ${cap.id}"
        textCli.text = cliCommand

        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("JARVIS_CLI", cliCommand))
            Toast.makeText(this, "Copied: $cliCommand", Toast.LENGTH_SHORT).show()
        }

        btnRunTest.setOnClickListener {
            btnRunTest.isEnabled = false
            btnRunTest.text = "EXECUTING..."
            textOutput.visibility = View.VISIBLE
            textOutput.text = "Running capability live on device..."
            textOutput.setTextColor(Color.parseColor("#94A3B8"))

            val paramText = paramInput.text.toString().trim()
            val params = parseParams(paramText)

            thread {
                val res = CapabilityRegistry.execute(cap.id, params, this)
                runOnUiThread {
                    btnRunTest.isEnabled = true
                    btnRunTest.text = "⚡ EXECUTE CAPABILITY LIVE"
                    if (res.success) {
                        textOutput.setTextColor(Color.parseColor("#10B981"))
                        textOutput.text = "✓ SUCCESS (${res.latencyMs}ms):\n${res.summary}\n${res.data?.toString() ?: ""}"
                    } else {
                        textOutput.setTextColor(Color.parseColor("#EF4444"))
                        textOutput.text = "✗ FAILED (${res.latencyMs}ms):\n${res.error ?: res.summary}"
                    }
                }
            }
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun parseParams(input: String): Map<String, Any?> {
        if (input.isBlank()) return emptyMap()
        val map = mutableMapOf<String, Any?>()
        val pairs = input.split(",")
        for (p in pairs) {
            val kv = p.split("=")
            if (kv.size == 2) {
                val k = kv[0].trim()
                val v = kv[1].trim()
                val num = v.toIntOrNull()
                val bool = if (v.equals("true", true)) true else if (v.equals("false", true)) false else null
                map[k] = num ?: bool ?: v
            }
        }
        return map
    }

    class CapabilityAdapter(
        private var items: List<CapabilityDef>,
        private val onTestClicked: (CapabilityDef, TextView) -> Unit,
        private val onInspectClicked: (CapabilityDef) -> Unit
    ) : RecyclerView.Adapter<CapabilityAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val badgeCategory: TextView = view.findViewById(R.id.badge_category)
            val badgeRisk: TextView = view.findViewById(R.id.badge_risk)
            val textName: TextView = view.findViewById(R.id.cap_name)
            val textId: TextView = view.findViewById(R.id.cap_id)
            val textDesc: TextView = view.findViewById(R.id.cap_description)
            val textStatus: TextView = view.findViewById(R.id.text_test_status)
            val btnInspect: Button = view.findViewById(R.id.btn_inspect)
            val btnTest: Button = view.findViewById(R.id.btn_test)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_capability_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val cap = items[position]
            holder.badgeCategory.text = cap.category.uppercase()
            holder.badgeRisk.text = cap.risk.name
            when (cap.risk) {
                RiskLevel.LOW -> {
                    holder.badgeRisk.setTextColor(Color.parseColor("#10B981"))
                    holder.badgeRisk.setBackgroundColor(Color.parseColor("#064E3B"))
                }
                RiskLevel.MEDIUM -> {
                    holder.badgeRisk.setTextColor(Color.parseColor("#F59E0B"))
                    holder.badgeRisk.setBackgroundColor(Color.parseColor("#451A03"))
                }
                RiskLevel.HIGH -> {
                    holder.badgeRisk.setTextColor(Color.parseColor("#EF4444"))
                    holder.badgeRisk.setBackgroundColor(Color.parseColor("#450A0A"))
                }
            }

            holder.textName.text = cap.name
            holder.textId.text = cap.id
            holder.textDesc.text = cap.description
            holder.textStatus.text = "READY"
            holder.textStatus.setTextColor(Color.parseColor("#64748B"))

            holder.btnTest.setOnClickListener { onTestClicked(cap, holder.textStatus) }
            holder.btnInspect.setOnClickListener { onInspectClicked(cap) }
            holder.itemView.setOnClickListener { onInspectClicked(cap) }
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newList: List<CapabilityDef>) {
            items = newList
            notifyDataSetChanged()
        }
    }
}
