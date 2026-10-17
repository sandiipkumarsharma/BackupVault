package com.sandip.backupvault

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Installed apps → APK files, and installing them back. */
object AppsKit {
    data class App(val pkg: String, val label: String, val version: String, val bytes: Long, val split: Boolean, val info: PackageInfo)

    fun installed(c: Context): List<App> {
        val pm = c.packageManager
        return pm.getInstalledPackages(0).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (system || pi.packageName == c.packageName) return@mapNotNull null
            val files = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
            App(pi.packageName, ai.loadLabel(pm).toString(), pi.versionName ?: "", files.sumOf { File(it).length() },
                !ai.splitSourceDirs.isNullOrEmpty(), pi)
        }.sortedBy { it.label.lowercase() }
    }

    fun icon(c: Context, a: App): Drawable? = try { a.info.applicationInfo?.loadIcon(c.packageManager) } catch (_: Exception) { null }

    private fun safe(s: String) = s.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifEmpty { "app" }

    /** Saves the app as .apk (single) or .apks (zip of base + split APKs). */
    fun backup(c: Context, a: App, p: Progress? = null): String {
        val ai = a.info.applicationInfo ?: error("App not found")
        val base = "${safe(a.label)}_${safe(a.version)}"
        if (!a.split) {
            val f = Vault.create(c, Kind.APPS, "$base.apk", Kind.APPS.mime) ?: error("Choose a backup folder first")
            c.contentResolver.openOutputStream(f.uri)!!.use { out -> File(ai.sourceDir).inputStream().use { it.copyTo(out) } }
        } else {
            val f = Vault.create(c, Kind.APPS, "$base.apks", "application/octet-stream") ?: error("Choose a backup folder first")
            c.contentResolver.openOutputStream(f.uri)!!.use { out ->
                ZipOutputStream(out).use { z ->
                    (listOf(ai.sourceDir) + ai.splitSourceDirs!!.toList()).forEach { path ->
                        val name = File(path).name
                        z.putNextEntry(ZipEntry(if (path == ai.sourceDir) "base.apk" else name))
                        File(path).inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            }
        }
        Prefs.setLastBackup(c, Kind.APPS)
        return a.label
    }

    /** Installs an .apk or .apks backup with Android's installer (you confirm on screen). */
    fun install(c: Context, f: DocumentFile) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            val name = f.name ?: ""
            if (name.endsWith(".apks", true)) {
                c.contentResolver.openInputStream(f.uri)!!.use { input ->
                    ZipInputStream(input).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            if (e.name.endsWith(".apk", true)) s.openWrite(e.name, 0, -1).use { out -> z.copyTo(out); s.fsync(out) }
                            e = z.nextEntry
                        }
                    }
                }
            } else {
                c.contentResolver.openInputStream(f.uri)!!.use { input ->
                    s.openWrite("base.apk", 0, f.length()).use { out -> input.copyTo(out); s.fsync(out) }
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val cb = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java), flags)
            s.commit(cb.intentSender)
        }
    }
}

/** Receives installer status; opens the system "Install?" screen when needed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> AutoBackup.notify(c, "✅ App installed", "Installed from your backup.")
            else -> AutoBackup.notify(c, "App not installed", i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation was cancelled or failed.")
        }
    }
}

/** Automatic daily / weekly backups. */
object AutoBackup {
    const val WORK = "auto_backup"

    fun enabled(c: Context) = Prefs.getBool(c, "auto_on", false)
    fun days(c: Context) = Prefs.getInt(c, "auto_days", 1)        // 1 = daily, 7 = weekly
    fun hour(c: Context) = Prefs.getInt(c, "auto_hour", 2)        // 2 AM default
    fun keep(c: Context) = Prefs.getInt(c, "auto_keep", 5)
    fun includes(c: Context, k: Kind) = Prefs.getBool(c, "auto_${k.name}", k != Kind.APPS)

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!enabled(c)) { wm.cancelUniqueWork(WORK); return }
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(c)); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(days(c).toLong(), TimeUnit.DAYS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Runs the backups now (used by the worker and by "Back up everything"). */
    fun runAll(c: Context, p: Progress, auto: Boolean, kinds: List<Kind>): String {
        val lines = ArrayList<String>()
        for (k in kinds) {
            try {
                lines += when (k) {
                    Kind.CONTACTS -> if (c.has(Manifest.permission.READ_CONTACTS)) "👤 " + ContactsKit.backup(c, p, auto) else "👤 Contacts: permission needed"
                    Kind.SMS -> if (c.has(Manifest.permission.READ_SMS)) "💬 " + SmsKit.backup(c, p, auto) else "💬 Messages: permission needed"
                    Kind.CALLS -> if (c.has(Manifest.permission.READ_CALL_LOG)) "📞 " + CallsKit.backup(c, p, auto) else "📞 Call logs: permission needed"
                    Kind.CALENDAR -> if (c.has(Manifest.permission.READ_CALENDAR)) "📅 " + CalendarKit.backup(c, p, auto) else "📅 Calendar: permission needed"
                    Kind.APPS -> continue
                }
                if (auto) Vault.prune(c, k, keep(c))
            } catch (e: Exception) { lines += "${k.emoji} ${k.title}: ${e.message}" }
        }
        return lines.joinToString("\n")
    }

    fun notify(c: Context, title: String, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("backup", "Backups", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(title.hashCode(), NotificationCompat.Builder(c, "backup").setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title).setContentText(text.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build())
    }
}

class AutoBackupWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (Vault.root(c) == null) { AutoBackup.notify(c, "Backup Vault", "Auto-backup skipped: backup folder not available."); return Result.success() }
        val kinds = Kind.values().filter { it != Kind.APPS && AutoBackup.includes(c, it) }
        val summary = AutoBackup.runAll(c, { _, _, _ -> }, true, kinds)
        AutoBackup.notify(c, "🔐 Auto-backup done", summary)
        return Result.success()
    }
}
