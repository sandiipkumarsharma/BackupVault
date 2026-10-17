package com.sandip.backupvault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What can be backed up. */
enum class Kind(val title: String, val emoji: String, val folder: String, val ext: String, val mime: String) {
    CONTACTS("Contacts", "👤", "Contacts", "vcf", "text/x-vcard"),
    SMS("Messages", "💬", "Messages", "xml", "text/xml"),
    CALLS("Call logs", "📞", "CallLogs", "json", "application/json"),
    CALENDAR("Calendar", "📅", "Calendar", "ics", "text/calendar"),
    APPS("Apps", "📦", "Apps", "apk", "application/vnd.android.package-archive"),
}

/** The backup folder the user picked (Storage Access Framework) and the files in it. */
object Vault {
    private fun p(c: Context) = c.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun treeUri(c: Context): Uri? = p(c).getString("tree", null)?.let(Uri::parse)

    fun setTree(c: Context, uri: Uri) {
        c.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        p(c).edit().putString("tree", uri.toString()).apply()
    }

    fun root(c: Context): DocumentFile? =
        treeUri(c)?.let { DocumentFile.fromTreeUri(c, it) }?.takeIf { it.exists() && it.canWrite() }

    fun folderName(c: Context): String = root(c)?.let { r ->
        val path = treeUri(c)?.lastPathSegment ?: r.name ?: "?"
        path.substringAfter(":").ifBlank { "Internal storage" }.let { "📁 $it" }
    } ?: "No folder chosen"

    fun dir(c: Context, k: Kind): DocumentFile? {
        val r = root(c) ?: return null
        return r.findFile(k.folder)?.takeIf { it.isDirectory } ?: r.createDirectory(k.folder)
    }

    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())

    fun create(c: Context, k: Kind, name: String, mime: String = k.mime): DocumentFile? =
        dir(c, k)?.createFile(mime, name)

    fun list(c: Context, k: Kind): List<DocumentFile> =
        dir(c, k)?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Keep only the newest [keep] automatic backups of a type. */
    fun prune(c: Context, k: Kind, keep: Int) {
        list(c, k).filter { it.name?.startsWith("auto_") == true }.drop(keep).forEach { it.delete() }
    }
}

/** Small persistent facts: last backup times, settings. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("vault", Context.MODE_PRIVATE)
    fun lastBackup(c: Context, k: Kind) = p(c).getLong("last_${k.name}", 0)
    fun setLastBackup(c: Context, k: Kind) = p(c).edit().putLong("last_${k.name}", System.currentTimeMillis()).apply()
    fun getString(c: Context, key: String, def: String) = p(c).getString(key, def) ?: def
    fun putString(c: Context, key: String, v: String) = p(c).edit().putString(key, v).apply()
    fun getInt(c: Context, key: String, def: Int) = p(c).getInt(key, def)
    fun putInt(c: Context, key: String, v: Int) = p(c).edit().putInt(key, v).apply()
    fun getBool(c: Context, key: String, def: Boolean) = p(c).getBoolean(key, def)
    fun putBool(c: Context, key: String, v: Boolean) = p(c).edit().putBoolean(key, v).apply()
}

fun ago(t: Long): String {
    if (t == 0L) return "Never backed up"
    return "Last backup: " + SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(t))
}

fun size(c: Context, bytes: Long): String = Formatter.formatShortFileSize(c, bytes)

/** Progress reporting from background work. */
fun interface Progress { fun update(done: Int, total: Int, msg: String) }

/**
 * Runs [work] off the main thread with a progress dialog; then [done] on the main thread.
 * Errors are shown in a dialog instead of crashing.
 */
fun <T> AppCompatActivity.runWithProgress(title: String, work: (Progress) -> T, done: (T) -> Unit) {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(64, 24, 64, 8) }
    val label = TextView(this).apply { text = "Starting…" }
    val bar = LinearProgressIndicator(this).apply { isIndeterminate = true; setPadding(0, 24, 0, 0) }
    box.addView(label); box.addView(bar)
    val dlg = MaterialAlertDialogBuilder(this).setTitle(title).setView(box).setCancelable(false).show()
    var lastUi = 0L
    val progress = Progress { d, t, m ->
        val now = System.currentTimeMillis()
        if (now - lastUi < 120 && d < t) return@Progress
        lastUi = now
        runOnUiThread {
            label.text = if (t > 0) "$m  ($d / $t)" else m
            if (t > 0) { bar.isIndeterminate = false; bar.max = t; bar.progress = d }
        }
    }
    lifecycleScope.launch {
        try {
            val r = withContext(Dispatchers.IO) { work(progress) }
            dlg.dismiss(); done(r)
        } catch (e: Throwable) {
            dlg.dismiss()
            MaterialAlertDialogBuilder(this@runWithProgress).setTitle("Something went wrong")
                .setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show()
        }
    }
}

fun Activity.info(title: String, msg: String, onOk: (() -> Unit)? = null): AlertDialog =
    MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg)
        .setPositiveButton("OK") { _, _ -> onOk?.invoke() }.show()

fun Context.has(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

/** Generic card row (avatar, title, subtitle, optional checkbox / button). */
class RowAdapter(
    private val bind: (RowAdapter.VH, Int) -> Unit,
    private val count: () -> Int,
) : RecyclerView.Adapter<RowAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val icon: android.widget.ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val check: MaterialCheckBox = v.findViewById(R.id.check)
        val action: MaterialButton = v.findViewById(R.id.action)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false))
    override fun getItemCount() = count()
    override fun onBindViewHolder(h: VH, pos: Int) {
        h.check.visibility = View.GONE; h.action.visibility = View.GONE; h.icon.visibility = View.GONE
        h.avatar.visibility = View.VISIBLE
        h.itemView.setOnClickListener(null); h.itemView.setOnLongClickListener(null)
        bind(h, pos)
    }
}

/** Shares a backup file with any app (Drive, WhatsApp, Gmail…). */
fun Activity.shareFile(f: DocumentFile) {
    val i = Intent(Intent.ACTION_SEND).setType(f.type ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, f.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(i, "Share ${f.name}"))
}

/** XML / text escaping helpers. */
object Esc {
    fun ics(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")
    fun unIcs(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
    fun vcf(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")
}
