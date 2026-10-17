package com.sandip.backupvault

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.provider.Telephony
import android.util.Xml
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SMS backup (open XML format) and restore. */
object SmsKit {
    private val COLS = listOf("address", "date", "date_sent", "type", "body", "read", "status", "subject",
        "service_center", "protocol", "locked")

    fun count(c: Context): Int = try {
        c.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null)?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    fun backup(c: Context, p: Progress, auto: Boolean = false): String {
        val name = (if (auto) "auto_" else "") + "sms_${Vault.stamp()}.xml"
        val f = Vault.create(c, Kind.SMS, name) ?: error("Choose a backup folder first")
        val fmt = SimpleDateFormat("d MMM yyyy h:mm:ss a", Locale.getDefault())
        var n = 0
        c.contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, "date ASC")?.use { cur ->
            val total = cur.count
            c.contentResolver.openOutputStream(f.uri)!!.use { out ->
                val x = Xml.newSerializer()
                x.setOutput(out, "UTF-8")
                x.startDocument("UTF-8", true)
                x.startTag(null, "smses")
                x.attribute(null, "count", total.toString())
                x.attribute(null, "backup_date", System.currentTimeMillis().toString())
                x.attribute(null, "made_by", "Backup Vault")
                val idx = COLS.associateWith { cur.getColumnIndex(it) }
                while (cur.moveToNext()) {
                    x.startTag(null, "sms")
                    for (col in COLS) {
                        val i = idx[col] ?: -1
                        val v = if (i >= 0 && !cur.isNull(i)) cur.getString(i) else "null"
                        x.attribute(null, col, clean(v))
                    }
                    val d = cur.getLong(idx["date"] ?: 0)
                    x.attribute(null, "readable_date", fmt.format(Date(d)))
                    x.endTag(null, "sms")
                    n++
                    if (n % 50 == 0) p.update(n, total, "Saving messages")
                }
                x.endTag(null, "smses"); x.endDocument()
            }
        }
        Prefs.setLastBackup(c, Kind.SMS)
        return "$n messages saved to $name"
    }

    /** XML can't hold some control characters. */
    private fun clean(s: String) = s.filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    fun isDefault(c: Context) = Telephony.Sms.getDefaultSmsPackage(c) == c.packageName

    /** Intent asking the user to make Backup Vault the SMS app (needed only while restoring). */
    fun requestDefaultIntent(a: Activity): Intent {
        Prefs.putString(a, "prev_sms", Telephony.Sms.getDefaultSmsPackage(a) ?: "")
        return if (Build.VERSION.SDK_INT >= 29) a.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
        else Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, a.packageName)
    }

    /** Sends the user back to choosing their normal messages app. */
    fun restoreDefaultIntent(a: Activity): Intent {
        val prev = Prefs.getString(a, "prev_sms", "")
        return if (Build.VERSION.SDK_INT < 29 && prev.isNotEmpty())
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, prev)
        else Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }

    fun restore(c: Context, input: InputStream, p: Progress): String {
        if (!isDefault(c)) error("Backup Vault must be the default SMS app while restoring")
        p.update(0, 0, "Checking existing messages")
        val existing = HashSet<String>()
        c.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("date", "address", "body"), null, null, null)?.use { cur ->
            while (cur.moveToNext()) existing.add("${cur.getLong(0)}|${cur.getString(1)}|${cur.getString(2)?.hashCode()}")
        }
        val parser = Xml.newPullParser(); parser.setInput(input, "UTF-8")
        var total = 0; var added = 0; var skipped = 0
        val batch = ArrayList<ContentValues>()
        fun flush() { if (batch.isNotEmpty()) { c.contentResolver.bulkInsert(Telephony.Sms.CONTENT_URI, batch.toTypedArray()); batch.clear() } }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "smses")
                total = parser.getAttributeValue(null, "count")?.toIntOrNull() ?: 0
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "sms") continue
            fun a(n: String) = parser.getAttributeValue(null, n)?.takeIf { it != "null" }
            val date = a("date")?.toLongOrNull() ?: continue
            val addr = a("address") ?: ""
            val body = a("body") ?: ""
            if (!existing.add("$date|$addr|${body.hashCode()}")) { skipped++; continue }
            batch.add(ContentValues().apply {
                put("address", addr); put("body", body); put("date", date)
                a("date_sent")?.toLongOrNull()?.let { put("date_sent", it) }
                put("type", a("type")?.toIntOrNull() ?: 1)
                put("read", a("read")?.toIntOrNull() ?: 1); put("seen", 1)
                a("status")?.toIntOrNull()?.let { put("status", it) }
                a("subject")?.let { put("subject", it) }
                a("service_center")?.let { put("service_center", it) }
                a("protocol")?.toIntOrNull()?.let { put("protocol", it) }
                put("locked", a("locked")?.toIntOrNull() ?: 0)
            })
            added++
            if (batch.size >= 200) flush()
            if ((added + skipped) % 100 == 0) p.update(added + skipped, total, "Restoring messages")
        }
        flush()
        return "$added messages restored · $skipped already on the phone"
    }
}

// ---------------------------------------------------------------------------------------------
// Components Android requires before an app may become the default SMS app (used only for restore).
// While Backup Vault is the default, incoming SMS are saved normally so nothing is lost.
// ---------------------------------------------------------------------------------------------

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        if (msgs.isEmpty()) return
        val from = msgs[0].displayOriginatingAddress ?: ""
        val body = msgs.joinToString("") { it.displayMessageBody ?: "" }
        c.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put("address", from); put("body", body); put("date", System.currentTimeMillis())
            put("date_sent", msgs[0].timestampMillis); put("read", 0); put("seen", 0); put("type", 1)
        })
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sms", "Messages received during restore", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(from.hashCode(), NotificationCompat.Builder(c, "sms").setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("SMS from $from").setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n\nSaved. Switch back to your messages app in Backup Vault → Messages."))
            .setAutoCancel(true).build())
    }
}

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { /* MMS are downloaded again by your normal messages app */ }
}

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { stopSelf(); return START_NOT_STICKY }
}

/** If something asks Backup Vault to write an SMS, pass it to the user's normal messages app. */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prev = Prefs.getString(this, "prev_sms", "")
        if (prev.isNotEmpty() && prev != packageName && intent.data != null) {
            try { startActivity(Intent(intent.action, intent.data).setPackage(prev).putExtras(intent.extras ?: Bundle())); finish(); return } catch (_: Exception) {}
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        box.addView(TextView(this).apply { textSize = 18f; text = "Backup Vault is not a messaging app.\n\nIt is your SMS app only while restoring a backup. Please switch back to your normal messages app." })
        box.addView(MaterialButton(this).apply {
            text = "Choose messages app"
            setOnClickListener { startActivity(SmsKit.restoreDefaultIntent(this@ComposeSmsActivity)); finish() }
        })
        setContentView(box)
    }
}
