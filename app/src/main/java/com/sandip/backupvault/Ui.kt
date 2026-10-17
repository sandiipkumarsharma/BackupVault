package com.sandip.backupvault

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared helpers: permissions, file pickers and building screens in code. */
open class BaseActivity : AppCompatActivity() {
    private var permThen: (() -> Unit)? = null
    private var docThen: ((Uri) -> Unit)? = null
    private var treeThen: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val then = permThen; permThen = null
        if (res.values.all { it }) then?.invoke()
        else info("Permission needed", "Backup Vault needs this permission to read or restore this data. You can allow it in App info → Permissions.")
    }
    private val docLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val then = docThen; docThen = null
        if (uri != null) then?.invoke(uri)
    }
    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { Vault.setTree(this, uri); treeThen?.invoke() }
        treeThen = null
    }

    fun withPerms(vararg perms: String, then: () -> Unit) {
        val missing = perms.filter { !has(it) }
        if (missing.isEmpty()) then() else { permThen = then; permLauncher.launch(missing.toTypedArray()) }
    }

    fun pickFile(mimes: Array<String>, then: (Uri) -> Unit) { docThen = then; docLauncher.launch(mimes) }

    fun chooseFolder(then: () -> Unit = {}) {
        treeThen = then
        info("Choose backup folder", "Pick or create a folder like  Documents › BackupVault.\n\nTip: a folder on a memory card or synced by Google Drive keeps backups safe even if the phone is lost.") {
            treeLauncher.launch(null)
        }
    }

    /** Runs [then] only when a backup folder is set. */
    fun needFolder(then: () -> Unit) { if (Vault.root(this) != null) then() else chooseFolder(then) }

    fun toolbar(title: String, subtitle: String? = null) {
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            this.title = title; this.subtitle = subtitle
            setNavigationOnClickListener { finish() }
        }
    }

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- building blocks ----------
    fun LinearLayout.header(emoji: String, title: String, line1: String, line2: String): TextView {
        val card = MaterialCardView(this@BaseActivity).apply {
            radius = dp(20).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(getColor(R.color.brand))
        }
        val box = LinearLayout(this@BaseActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)) }
        box.addView(TextView(this@BaseActivity).apply { text = emoji; textSize = 40f })
        val col = LinearLayout(this@BaseActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        col.addView(TextView(this@BaseActivity).apply { text = title; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val l1 = TextView(this@BaseActivity).apply { text = line1; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()) }
        col.addView(l1)
        col.addView(TextView(this@BaseActivity).apply { text = line2; textSize = 13f; setTextColor(0xCCFFFFFF.toInt()) })
        box.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(box)
        addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(6), dp(14), dp(10)) })
        return l1
    }

    fun LinearLayout.button(text: String, primary: Boolean = false, onClick: () -> Unit): MaterialButton {
        val b = if (primary) MaterialButton(this@BaseActivity) else MaterialButton(this@BaseActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = text; b.textSize = 15f; b.isAllCaps = false
        b.cornerRadius = dp(14); b.minHeight = dp(54)
        if (primary) b.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand))
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setPadding(dp(18), 0, dp(18), 0)
        b.setOnClickListener { onClick() }
        addView(b, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(4), dp(14), dp(4)) })
        return b
    }

    fun LinearLayout.note(text: String) = addView(TextView(this@BaseActivity).apply {
        this.text = text; textSize = 13f; alpha = 0.7f; setPadding(dp(20), dp(4), dp(20), dp(6))
    })

    fun LinearLayout.title(text: String) = addView(TextView(this@BaseActivity).apply {
        this.text = text; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.08f
        setTextColor(getColor(R.color.brand)); setPadding(dp(20), dp(18), dp(20), dp(6))
    })

    private val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

    /** A saved backup file as a card with a menu of actions. */
    fun LinearLayout.fileRow(f: DocumentFile, actions: List<Pair<String, () -> Unit>>) {
        val v = LayoutInflater.from(this@BaseActivity).inflate(R.layout.item_row, this, false)
        val name = f.name ?: "?"
        v.findViewById<TextView>(R.id.avatar).text = when {
            name.endsWith(".xlsx") -> "📊"; name.endsWith(".apk") || name.endsWith(".apks") -> "📦"
            name.startsWith("auto_") -> "⏰"; else -> "💾"
        }
        v.findViewById<TextView>(R.id.title).text = name
        v.findViewById<TextView>(R.id.subtitle).text = "${fmt.format(Date(f.lastModified()))} · ${size(this@BaseActivity, f.length())}"
        v.setOnClickListener {
            PopupMenu(this@BaseActivity, v, Gravity.END).apply {
                actions.forEachIndexed { i, (label, _) -> menu.add(0, i, i, label) }
                setOnMenuItemClickListener { actions[it.itemId].second(); true }
            }.show()
        }
        addView(v)
    }

    fun openFile(f: DocumentFile, mime: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { info("No app found", "No app on this phone can open this file. Use Share instead.") }
    }

    fun confirmDelete(f: DocumentFile, after: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Delete ${f.name}?")
            .setPositiveButton("Delete") { _, _ -> f.delete(); after() }.setNegativeButton("Cancel", null).show()
    }

    fun notifPerm() { if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9) }
}

const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Dashboard. */
class MainActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        notifPerm()
        findViewById<View>(R.id.changeFolder).setOnClickListener { chooseFolder { refresh() } }
        findViewById<View>(R.id.backupAll).setOnClickListener { backupAll() }
        if (Vault.root(this) == null) chooseFolder { refresh() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private data class Tile(val emoji: String, val title: String, val color: Int, val open: () -> Unit, val count: () -> String, val last: () -> String)

    private fun tiles() = listOf(
        Tile("👤", "Contacts", R.color.tile_contacts, { open(Kind.CONTACTS) },
            { if (has(Manifest.permission.READ_CONTACTS)) "${ContactsKit.count(this)} contacts" else "Tap to allow" }, { ago(Prefs.lastBackup(this, Kind.CONTACTS)) }),
        Tile("💬", "Messages", R.color.tile_sms, { open(Kind.SMS) },
            { if (has(Manifest.permission.READ_SMS)) "${SmsKit.count(this)} SMS" else "Tap to allow" }, { ago(Prefs.lastBackup(this, Kind.SMS)) }),
        Tile("📞", "Call logs", R.color.tile_calls, { open(Kind.CALLS) },
            { if (has(Manifest.permission.READ_CALL_LOG)) "${CallsKit.count(this)} calls" else "Tap to allow" }, { ago(Prefs.lastBackup(this, Kind.CALLS)) }),
        Tile("📅", "Calendar", R.color.tile_calendar, { open(Kind.CALENDAR) },
            { if (has(Manifest.permission.READ_CALENDAR)) "${CalendarKit.count(this)} events" else "Tap to allow" }, { ago(Prefs.lastBackup(this, Kind.CALENDAR)) }),
        Tile("📦", "Apps", R.color.tile_apps, { startActivity(Intent(this, AppsActivity::class.java)) },
            { "Save apps as APK" }, { ago(Prefs.lastBackup(this, Kind.APPS)) }),
        Tile("⏰", "Auto-backup", R.color.tile_auto, { startActivity(Intent(this, AutoBackupActivity::class.java)) },
            { if (AutoBackup.enabled(this)) (if (AutoBackup.days(this) == 1) "Daily" else "Weekly") + " at ${AutoBackup.hour(this)}:00" else "Off" },
            { if (AutoBackup.enabled(this)) "Keeps last ${AutoBackup.keep(this)} backups" else "Tap to set up" }),
    )

    private fun open(k: Kind) = startActivity(Intent(this, SectionActivity::class.java).putExtra("kind", k.name))

    private fun refresh() {
        findViewById<TextView>(R.id.folder).text = Vault.folderName(this)
        val grid = findViewById<android.widget.GridLayout>(R.id.grid)
        grid.removeAllViews()
        val list = tiles()
        list.forEach { t ->
            val v = LayoutInflater.from(this).inflate(R.layout.tile, grid, false) as MaterialCardView
            v.findViewById<TextView>(R.id.emoji).apply {
                text = t.emoji
                background.mutate().setTint((getColor(t.color) and 0x00FFFFFF) or 0x26000000)
            }
            v.findViewById<TextView>(R.id.title).text = t.title
            v.findViewById<TextView>(R.id.count).text = "…"
            v.findViewById<TextView>(R.id.last).text = t.last()
            v.setOnClickListener { t.open() }
            grid.addView(v, android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply { width = 0; setMargins(dp(6), dp(6), dp(6), dp(6)) })
            Thread {
                val c = try { t.count() } catch (_: Exception) { "" }
                runOnUiThread { v.findViewById<TextView>(R.id.count).text = c }
            }.start()
        }
    }

    private fun backupAll() = needFolder {
        withPerms(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CALENDAR) {
            runWithProgress("Backing up everything", { p ->
                AutoBackup.runAll(this, p, false, listOf(Kind.CONTACTS, Kind.SMS, Kind.CALLS, Kind.CALENDAR))
            }) { info("✅ Backup complete", it); refresh() }
        }
    }
}
