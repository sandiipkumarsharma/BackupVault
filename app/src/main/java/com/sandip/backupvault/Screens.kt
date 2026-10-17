package com.sandip.backupvault

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

/** One screen per data type: back up, restore, and the list of saved backups. */
class SectionActivity : BaseActivity() {
    private lateinit var kind: Kind
    private lateinit var content: LinearLayout
    private var smsPending: Uri? = null

    private val smsRole = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        val u = smsPending; smsPending = null
        if (SmsKit.isDefault(this) && u != null) doSmsRestore(u)
        else if (u != null) info("Not changed", "Restore needs Backup Vault as the SMS app for a moment. Nothing was changed.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_section)
        kind = Kind.valueOf(intent.getStringExtra("kind") ?: "CONTACTS")
        toolbar(kind.title, "Back up · Restore · Your files")
        content = findViewById(R.id.content)
    }

    override fun onResume() { super.onResume(); build() }

    private fun readPerms() = when (kind) {
        Kind.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
        Kind.SMS -> arrayOf(Manifest.permission.READ_SMS)
        Kind.CALLS -> arrayOf(Manifest.permission.READ_CALL_LOG)
        Kind.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR)
        Kind.APPS -> arrayOf()
    }

    private fun build() {
        content.removeAllViews()
        val countLine = content.header(kind.emoji, kind.title, "…", ago(Prefs.lastBackup(this, kind)))
        Thread {
            val n = try {
                if (readPerms().all { has(it) }) when (kind) {
                    Kind.CONTACTS -> "${ContactsKit.count(this)} contacts on this phone"
                    Kind.SMS -> "${SmsKit.count(this)} messages on this phone"
                    Kind.CALLS -> "${CallsKit.count(this)} calls on this phone"
                    Kind.CALENDAR -> "${CalendarKit.count(this)} events on this phone"
                    Kind.APPS -> ""
                } else "Permission needed — tap Back up now"
            } catch (_: Exception) { "" }
            runOnUiThread { countLine.text = n }
        }.start()

        content.button("💾  Back up now", primary = true) { backupNow() }
        when (kind) {
            Kind.CONTACTS -> {
                content.button("🔍  Find & fix duplicate contacts") { withPerms(Manifest.permission.READ_CONTACTS) { startActivity(Intent(this, DuplicatesActivity::class.java)) } }
                content.button("📊  VCF file → Excel (check duplicates)") { needFolder { pickFile(arrayOf("text/x-vcard", "text/vcard", "text/directory", "*/*")) { vcfToExcel(it) } } }
                content.button("📇  Excel → clean VCF") { needFolder { pickFile(arrayOf(XLSX, "*/*")) { excelToVcf(it) } } }
                content.note("Restore: tap a .vcf backup below → ‘Import into phone’. Your Contacts app adds them (choose Phone or Google account).")
            }
            Kind.SMS -> {
                content.button("📬  SMS Timeline (today first, OTP · Bank · Courier)") { startActivity(Intent(this, SmsTimelineActivity::class.java)) }
                if (SmsKit.isDefault(this)) {
                    content.button("↩️  Switch back to my messages app", primary = true) { startActivity(SmsKit.restoreDefaultIntent(this)) }
                    content.note("Backup Vault is your SMS app right now (needed only for restore). Please switch back to your usual messages app.")
                }
                content.button("📂  Restore from another file") { pickFile(arrayOf("text/xml", "application/xml", "*/*")) { smsRestore(it) } }
                content.note("Restore: tap a backup below → Restore. Android requires Backup Vault to be the SMS app for a minute; you'll switch back right after. Messages already on the phone are skipped.")
            }
            Kind.CALLS -> {
                content.button("📂  Restore from another file") { pickFile(arrayOf("application/json", "*/*")) { callsRestore(it) } }
                content.note("Calls already in your history are skipped, so restoring twice is safe.")
            }
            Kind.CALENDAR -> {
                content.button("📂  Restore from another file (.ics)") { pickFile(arrayOf("text/calendar", "*/*")) { calendarRestore(it) } }
                content.note("Events are restored into the calendar you choose. Events already present are skipped.")
            }
            Kind.APPS -> {}
        }

        content.title("SAVED BACKUPS")
        val files = Vault.list(this, kind)
        if (Vault.root(this) == null) content.note("No backup folder chosen yet.")
        else if (files.isEmpty()) content.note("No backups yet. Tap ‘Back up now’.")
        files.forEach { f ->
            val name = f.name ?: ""
            val actions = ArrayList<Pair<String, () -> Unit>>()
            when (kind) {
                Kind.CONTACTS -> when {
                    name.endsWith(".xlsx") -> { actions += "Open in Excel" to { openFile(f, XLSX) }; actions += "Make clean VCF from this" to { excelToVcf(f.uri) } }
                    else -> { actions += "Import into phone" to { openFile(f, "text/x-vcard") }; actions += "Make Excel (check duplicates)" to { vcfToExcel(f.uri) } }
                }
                Kind.SMS -> actions += "Restore" to { smsRestore(f.uri) }
                Kind.CALLS -> actions += "Restore" to { callsRestore(f.uri) }
                Kind.CALENDAR -> { actions += "Restore" to { calendarRestore(f.uri) }; actions += "Open with calendar app" to { openFile(f, "text/calendar") } }
                Kind.APPS -> {}
            }
            actions += "Share (Drive, WhatsApp, email…)" to { shareFile(f) }
            actions += "Delete" to { confirmDelete(f) { build() } }
            content.fileRow(f, actions)
        }
    }

    private fun backupNow() = needFolder {
        withPerms(*readPerms()) {
            runWithProgress("Backing up ${kind.title}", { p ->
                when (kind) {
                    Kind.CONTACTS -> ContactsKit.backup(this, p)
                    Kind.SMS -> SmsKit.backup(this, p)
                    Kind.CALLS -> CallsKit.backup(this, p)
                    Kind.CALENDAR -> CalendarKit.backup(this, p)
                    Kind.APPS -> ""
                }
            }) { info("✅ Done", it); build() }
        }
    }

    // ---------- contacts ----------
    private fun vcfToExcel(uri: Uri) {
        runWithProgress("Checking contacts", { p ->
            val recs = contentResolver.openInputStream(uri)!!.use { ContactsKit.parseVcf(it) }
            val groups = ContactsKit.findDuplicates(recs, p)
            val f = Vault.create(this, Kind.CONTACTS, "contacts_review_${Vault.stamp()}.xlsx", XLSX) ?: error("Choose a backup folder first")
            contentResolver.openOutputStream(f.uri)!!.use { ContactsKit.writeExcel(it, recs, groups) }
            Triple(f, recs.size, groups)
        }) { (f, n, groups) ->
            MaterialAlertDialogBuilder(this).setTitle("📊 Excel ready")
                .setMessage("$n contacts read.\n${groups.size} duplicate groups found (${groups.sumOf { it.members.size }} contacts), highlighted in yellow at the top.\n\nIn column B choose KEEP / MERGE / DELETE, save, then use ‘Excel → clean VCF’.")
                .setPositiveButton("Open Excel") { _, _ -> openFile(f, XLSX) }
                .setNeutralButton("Share") { _, _ -> shareFile(f) }
                .setNegativeButton("Close", null).show()
            build()
        }
    }

    private fun excelToVcf(uri: Uri) {
        runWithProgress("Making clean VCF", { _ ->
            val (recs, summary) = contentResolver.openInputStream(uri)!!.use { ContactsKit.excelToRecs(it) }
            val f = Vault.create(this, Kind.CONTACTS, "contacts_clean_${Vault.stamp()}.vcf") ?: error("Choose a backup folder first")
            contentResolver.openOutputStream(f.uri)!!.use { ContactsKit.writeVcf(it, recs) }
            f to summary
        }) { (f, summary) ->
            MaterialAlertDialogBuilder(this).setTitle("📇 Clean VCF ready")
                .setMessage("$summary\n\nTo replace your contacts: first keep a backup (‘Back up now’), remove the old contacts in your Contacts app, then tap ‘Import into phone’.")
                .setPositiveButton("Import into phone") { _, _ -> openFile(f, "text/x-vcard") }
                .setNeutralButton("Share") { _, _ -> shareFile(f) }
                .setNegativeButton("Close", null).show()
            build()
        }
    }

    // ---------- SMS ----------
    private fun smsRestore(uri: Uri) {
        if (SmsKit.isDefault(this)) { doSmsRestore(uri); return }
        MaterialAlertDialogBuilder(this).setTitle("Restore messages")
            .setMessage("Android allows only the default SMS app to add messages.\n\nNext screen: choose Backup Vault as SMS app → restore runs → then switch back to your usual messages app (button shown on this screen).\n\nNew SMS arriving meanwhile are saved safely.")
            .setPositiveButton("Continue") { _, _ ->
                smsPending = uri
                try { smsRole.launch(SmsKit.requestDefaultIntent(this)) }
                catch (e: Exception) { info("Not available", "This phone did not allow changing the SMS app: ${e.message}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doSmsRestore(uri: Uri) {
        runWithProgress("Restoring messages", { p -> contentResolver.openInputStream(uri)!!.use { SmsKit.restore(this, it, p) } }) { r ->
            MaterialAlertDialogBuilder(this).setTitle("✅ Messages restored").setMessage("$r\n\nNow switch back to your usual messages app.")
                .setPositiveButton("Switch back") { _, _ -> startActivity(SmsKit.restoreDefaultIntent(this)) }
                .setCancelable(false).show()
            build()
        }
    }

    // ---------- calls ----------
    private fun callsRestore(uri: Uri) = withPerms(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG) {
        runWithProgress("Restoring call history", { p -> contentResolver.openInputStream(uri)!!.use { CallsKit.restore(this, it, p) } }) { info("✅ Done", it); build() }
    }

    // ---------- calendar ----------
    private fun calendarRestore(uri: Uri) = withPerms(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) {
        val cals = CalendarKit.calendars(this).filter { it.writable }
        if (cals.isEmpty()) { info("No calendar", "No writable calendar found. Add a Google account or open your Calendar app once."); return@withPerms }
        MaterialAlertDialogBuilder(this).setTitle("Restore into which calendar?")
            .setItems(cals.map { "${it.name}  (${it.account})" }.toTypedArray()) { _, i ->
                runWithProgress("Restoring events", { p -> contentResolver.openInputStream(uri)!!.use { CalendarKit.restore(this, it, cals[i].id, p) } }) { info("✅ Done", it); build() }
            }.show()
    }
}

/** Duplicate contacts on the phone: merge, delete, or export to Excel. */
class DuplicatesActivity : BaseActivity() {
    private var recs: List<Rec> = emptyList()
    private var groups: MutableList<DupGroup> = mutableListOf()
    private lateinit var content: LinearLayout
    private var safetyDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_section)
        toolbar("Duplicate contacts", "Same number · Same name · Similar name")
        content = findViewById(R.id.content)
        load()
    }

    private fun load() {
        runWithProgress("Finding duplicates", { p -> val r = ContactsKit.readPhone(this, p); r to ContactsKit.findDuplicates(r, p) }) { (r, g) ->
            recs = r; groups = g.toMutableList(); build()
        }
    }

    private fun build() {
        content.removeAllViews()
        content.header("🔍", "${groups.size} duplicate groups", "${recs.size} contacts checked",
            if (groups.isEmpty()) "Your contacts look clean 🎉" else "${groups.sumOf { it.members.size }} contacts involved")
        if (groups.isNotEmpty()) {
            content.button("🔗  Merge all exact duplicates", primary = true) { mergeAll() }
            content.note("Merge links duplicates into one contact (Android's own merge — can be undone with ‘Unlink’ in the Contacts app). ‘Similar name’ groups are left for you to check.")
        }
        content.button("📊  Export all to Excel") { exportExcel() }
        content.title("GROUPS")
        groups.forEachIndexed { gi, g ->
            val v = layoutInflater.inflate(R.layout.item_row, content, false)
            v.findViewById<TextView>(R.id.avatar).text = g.members.size.toString()
            v.findViewById<TextView>(R.id.title).text = "${g.members[g.keeper].name}  ·  ${g.reason}"
            v.findViewById<TextView>(R.id.subtitle).text = g.members.mapIndexed { i, m ->
                (if (i == g.keeper) "★ " else "• ") + m.name + "  " + m.phones.take(2).joinToString(", ")
            }.joinToString("\n")
            v.setOnClickListener { groupDialog(gi) }
            content.addView(v)
        }
    }

    /** Before the first change, save a VCF backup so nothing can be lost. */
    private fun safety(then: () -> Unit) {
        if (safetyDone) { then(); return }
        needFolder {
            runWithProgress("Saving a safety backup first", { p -> ContactsKit.backup(this, p) }) { safetyDone = true; then() }
        }
    }

    private fun groupDialog(gi: Int) {
        val g = groups[gi]
        MaterialAlertDialogBuilder(this).setTitle("${g.reason} (${g.members.size})")
            .setItems(arrayOf("🔗 Merge into one (recommended)", "★ Choose which one to keep", "🗑 Keep ★ and delete the others")) { _, which ->
                when (which) {
                    0 -> withPerms(Manifest.permission.WRITE_CONTACTS) { safety {
                        runWithProgress("Merging", { ContactsKit.merge(this, g) }) { groups.removeAt(gi); build() } } }
                    1 -> MaterialAlertDialogBuilder(this).setTitle("Keep which contact?")
                        .setSingleChoiceItems(g.members.map { "${it.name}  ${it.phones.firstOrNull() ?: ""}" }.toTypedArray(), g.keeper) { d, i ->
                            groups[gi] = g.copy(keeper = i); d.dismiss(); build()
                        }.show()
                    2 -> MaterialAlertDialogBuilder(this).setTitle("Delete ${g.members.size - 1} contacts?")
                        .setMessage("Keeping: ${g.members[g.keeper].name}\nA safety backup is saved first.")
                        .setPositiveButton("Delete") { _, _ -> withPerms(Manifest.permission.WRITE_CONTACTS) { safety {
                            runWithProgress("Deleting", { ContactsKit.deleteExtras(this, g) }) { groups.removeAt(gi); build() } } } }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun mergeAll() = withPerms(Manifest.permission.WRITE_CONTACTS) {
        val exact = groups.filter { it.reason != "Similar name" }
        MaterialAlertDialogBuilder(this).setTitle("Merge ${exact.size} groups?")
            .setMessage("Each group becomes one contact with all numbers and emails. A safety backup is saved first.")
            .setPositiveButton("Merge") { _, _ -> safety {
                runWithProgress("Merging duplicates", { p ->
                    var n = 0; exact.forEachIndexed { i, g -> if (ContactsKit.merge(this, g)) n++; p.update(i + 1, exact.size, "Merging") }; n
                }) { n -> info("✅ Merged", "$n groups merged."); load() }
            } }.setNegativeButton("Cancel", null).show()
    }

    private fun exportExcel() = needFolder {
        runWithProgress("Making Excel", { _ ->
            val f = Vault.create(this, Kind.CONTACTS, "contacts_review_${Vault.stamp()}.xlsx", XLSX) ?: error("Choose a backup folder first")
            contentResolver.openOutputStream(f.uri)!!.use { ContactsKit.writeExcel(it, recs, groups) }
            f
        }) { f ->
            MaterialAlertDialogBuilder(this).setTitle("📊 Excel ready").setMessage("Saved as ${f.name} in your Contacts backup folder.")
                .setPositiveButton("Open") { _, _ -> openFile(f, XLSX) }.setNeutralButton("Share") { _, _ -> shareFile(f) }
                .setNegativeButton("Close", null).show()
        }
    }
}

/** Installed apps ↔ APK backups. */
class AppsActivity : BaseActivity() {
    private var apps: List<AppsKit.App> = emptyList()
    private val selected = HashSet<String>()
    private lateinit var content: LinearLayout
    private var showSaved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_section)
        toolbar("Apps", "Save apps as APK · Reinstall later")
        content = findViewById(R.id.content)
        runWithProgress("Reading apps", { AppsKit.installed(this) }) { apps = it; build() }
    }

    override fun onResume() { super.onResume(); if (apps.isNotEmpty()) build() }

    private fun build() {
        content.removeAllViews()
        val toggle = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val b1 = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "Installed (${apps.size})" }
        val b2 = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "Saved APKs" }
        toggle.addView(b1, LinearLayout.LayoutParams(0, -2, 1f)); toggle.addView(b2, LinearLayout.LayoutParams(0, -2, 1f))
        toggle.check(if (showSaved) b2.id else b1.id)
        toggle.addOnButtonCheckedListener { _, id, checked -> if (checked) { showSaved = id == b2.id; build() } }
        content.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(4), dp(14), dp(8)) })

        if (showSaved) {
            if (!packageManager.canRequestPackageInstalls())
                content.button("⚙️  Allow installing from Backup Vault") {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
            val files = Vault.list(this, Kind.APPS)
            if (files.isEmpty()) content.note(if (Vault.root(this) == null) "No backup folder chosen yet." else "No saved APKs yet.")
            files.forEach { f ->
                content.fileRow(f, listOf(
                    "Install" to { install(f) },
                    "Share" to { shareFile(f) },
                    "Delete" to { confirmDelete(f) { build() } }))
            }
            return
        }
        content.button(if (selected.isEmpty()) "💾  Select apps below to back up" else "💾  Back up ${selected.size} selected", primary = true) { backupSelected() }
        content.note("Saves the app (APK). App data and logins are not included — Android doesn't allow that without root.")
        val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@AppsActivity); isNestedScrollingEnabled = false }
        rv.adapter = RowAdapter({ h, pos ->
            val a = apps[pos]
            h.avatar.visibility = View.GONE; h.icon.visibility = View.VISIBLE
            h.icon.setImageDrawable(AppsKit.icon(this, a))
            h.title.text = a.label
            h.subtitle.text = "v${a.version} · ${size(this, a.bytes)}" + if (a.split) " · split APK" else ""
            h.check.visibility = View.VISIBLE; h.check.isChecked = a.pkg in selected
            h.itemView.setOnClickListener { if (!selected.remove(a.pkg)) selected.add(a.pkg); build() }
        }, { apps.size })
        content.addView(rv)
    }

    private fun backupSelected() {
        if (selected.isEmpty()) return
        needFolder {
            val list = apps.filter { it.pkg in selected }
            runWithProgress("Saving apps", { p ->
                list.mapIndexed { i, a -> p.update(i, list.size, "Saving ${a.label}"); try { "✅ " + AppsKit.backup(this, a) } catch (e: Exception) { "⚠️ ${a.label}: ${e.message}" } }
            }) { r -> selected.clear(); info("Apps saved", r.joinToString("\n")); build() }
        }
    }

    private fun install(f: androidx.documentfile.provider.DocumentFile) {
        if (!packageManager.canRequestPackageInstalls()) {
            info("Allow installs", "Allow Backup Vault to install apps, then try again.") {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            return
        }
        runWithProgress("Preparing install", { AppsKit.install(this, f) }) { }
    }
}

/** Automatic backup settings. */
class AutoBackupActivity : BaseActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_section)
        toolbar("Auto-backup", "Daily or weekly, automatically")
        content = findViewById(R.id.content)
        build()
    }

    private fun build() {
        content.removeAllViews()
        content.header("⏰", "Auto-backup", if (AutoBackup.enabled(this)) "ON" else "OFF", Vault.folderName(this))
        content.addView(MaterialSwitch(this).apply {
            text = "Back up automatically"; textSize = 16f; isChecked = AutoBackup.enabled(this@AutoBackupActivity)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnCheckedChangeListener { _, on ->
                if (on && Vault.root(this@AutoBackupActivity) == null) { isChecked = false; chooseFolder { build() }; return@setOnCheckedChangeListener }
                if (on) withPerms(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CALENDAR) {}
                Prefs.putBool(this@AutoBackupActivity, "auto_on", on); save()
            }
        })
        content.title("HOW OFTEN")
        val toggle = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val d = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "Daily" }
        val w = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "Weekly" }
        toggle.addView(d, LinearLayout.LayoutParams(0, -2, 1f)); toggle.addView(w, LinearLayout.LayoutParams(0, -2, 1f))
        toggle.check(if (AutoBackup.days(this) == 7) w.id else d.id)
        toggle.addOnButtonCheckedListener { _, id, checked -> if (checked) { Prefs.putInt(this, "auto_days", if (id == w.id) 7 else 1); save() } }
        content.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), 0, dp(14), 0) })
        content.button("🕑  Time: around ${AutoBackup.hour(this)}:00") {
            MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).setHour(AutoBackup.hour(this)).setMinute(0)
                .setTitleText("Back up at about").build().apply {
                    addOnPositiveButtonClickListener { Prefs.putInt(this@AutoBackupActivity, "auto_hour", hour); save(); build() }
                }.show(supportFragmentManager, "t")
        }
        content.note("Android runs it near this time (within about an hour), when the phone is idle.")
        content.title("WHAT TO BACK UP")
        listOf(Kind.CONTACTS, Kind.SMS, Kind.CALLS, Kind.CALENDAR).forEach { k ->
            content.addView(MaterialSwitch(this).apply {
                text = "${k.emoji}  ${k.title}"; isChecked = AutoBackup.includes(this@AutoBackupActivity, k)
                setPadding(dp(20), dp(4), dp(20), dp(4))
                setOnCheckedChangeListener { _, on -> Prefs.putBool(this@AutoBackupActivity, "auto_${k.name}", on) }
            })
        }
        content.title("KEEP")
        val label = TextView(this).apply { setPadding(dp(20), 0, dp(20), 0) }
        val slider = Slider(this).apply { valueFrom = 1f; valueTo = 30f; stepSize = 1f; value = AutoBackup.keep(this@AutoBackupActivity).toFloat() }
        fun lbl() { label.text = "Keep the last ${slider.value.toInt()} automatic backups of each type (older ones are deleted)" }
        lbl(); slider.addOnChangeListener { _, v, _ -> Prefs.putInt(this, "auto_keep", v.toInt()); lbl() }
        content.addView(label)
        content.addView(slider, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), 0, dp(14), 0) })
        content.button("▶️  Run auto-backup now", primary = true) {
            needFolder {
                val kinds = listOf(Kind.CONTACTS, Kind.SMS, Kind.CALLS, Kind.CALENDAR).filter { AutoBackup.includes(this, it) }
                runWithProgress("Backing up", { p -> AutoBackup.runAll(this, p, true, kinds) }) { info("✅ Done", it) }
            }
        }
    }

    private fun save() { AutoBackup.schedule(this) }
}
