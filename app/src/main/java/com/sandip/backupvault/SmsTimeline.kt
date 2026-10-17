package com.sandip.backupvault

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What kind of SMS this is, and the key value pulled out of it (OTP, balance, AWB…). */
enum class Cat(val label: String, val color: Int) {
    OTP("🔑 OTP", R.color.cat_otp), BANK("💰 Bank", R.color.cat_bank), COURIER("📦 Courier", R.color.cat_courier), OTHER("💬 SMS", R.color.cat_other)
}

data class Sms(val id: Long, val address: String, val sender: String, val body: String, val date: Long,
               val cat: Cat, val valueLabel: String?, val value: String?)

object SmsClassifier {
    private val I = setOf(RegexOption.IGNORE_CASE)
    private val otpKw = Regex("\\b(otp|one[- ]?time[- ]?pass(word|code)?|verification code|security code|auth(entication)? code|login code|passcode|delivery code|pin)\\b|code is|ओटीपी|सत्यापन", I)
    private val otpAfter = Regex("(?:otp|code|password|passcode|pin)[^0-9]{0,30}?(\\d{4,8})\\b", I)
    private val otpBefore = Regex("\\b(\\d{4,8})\\b[^0-9]{0,30}?(?:is your|is the|is ur|as your)[^0-9]{0,20}(?:otp|code|password|pin)", I)
    private val anyCode = Regex("\\b(\\d{4,8})\\b")

    private val money = "(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)"
    private val bankKw = Regex("\\b(a/c|acct|account|avl\\.? ?bal|avbl\\.? ?bal|available bal|balance|credited|debited|withdrawn|spent|upi|neft|imps|rtgs|txn|transaction|emi|salary)\\b", I)
    private val balance = Regex("(?:avl|avbl|available|clr|ledger)?\\.?\\s*bal(?:ance)?\\.?\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([\\d,]+(?:\\.\\d{1,2})?)", I)
    private val amount = Regex(money, I)
    private val credited = Regex("\\b(credited|received|deposited|refund)\\b", I)
    private val debited = Regex("\\b(debited|withdrawn|spent|paid|sent|purchase)\\b", I)

    private val courierKw = Regex("\\b(delivered|out for delivery|shipped|dispatched|in transit|awb|tracking|track your|consignment|courier|docket|shipment|parcel|package|delivery)\\b", I)
    private val awb = Regex("(?:awb|tracking|track(?:ing)? id|consignment|docket|shipment|article)\\s*(?:no\\.?|number|id|#)?\\s*[:\\-]?\\s*([A-Z0-9]{8,22})\\b", I)
    private val speedPost = Regex("\\b([A-Z]{2}\\d{9}[A-Z]{2})\\b")
    private val status = listOf("out for delivery" to "Out for delivery", "delivered" to "Delivered", "dispatched" to "Dispatched",
        "shipped" to "Shipped", "in transit" to "In transit")

    fun classify(body: String): Triple<Cat, String?, String?> {
        val b = body.replace('\n', ' ')
        val hasCourier = courierKw.containsMatchIn(b)
        if (otpKw.containsMatchIn(b)) {
            val code = otpAfter.find(b)?.groupValues?.get(1) ?: otpBefore.find(b)?.groupValues?.get(1)
                ?: anyCode.findAll(b).map { it.groupValues[1] }.firstOrNull { !(it.length == 4 && (it.startsWith("19") || it.startsWith("20"))) }
            if (code != null) return Triple(Cat.OTP, if (hasCourier) "Delivery OTP" else "OTP", code)
        }
        if (bankKw.containsMatchIn(b) && amount.containsMatchIn(b)) {
            balance.find(b)?.groupValues?.get(1)?.takeIf { it.any(Char::isDigit) }?.let { return Triple(Cat.BANK, "Balance", "₹$it") }
            val amt = amount.find(b)?.groupValues?.get(1)
            val lbl = when { debited.containsMatchIn(b) -> "Debited"; credited.containsMatchIn(b) -> "Credited"; else -> "Amount" }
            return Triple(Cat.BANK, lbl, amt?.let { "₹$it" })
        }
        if (hasCourier) {
            val id = awb.find(b)?.groupValues?.get(1)?.uppercase()?.takeIf { it.any(Char::isDigit) } ?: speedPost.find(b)?.groupValues?.get(1)
            val st = status.firstOrNull { b.contains(it.first, true) }?.second
            return Triple(Cat.COURIER, if (id != null) "AWB / Tracking no." + (st?.let { " · $it" } ?: "") else st, id)
        }
        return Triple(Cat.OTHER, null, null)
    }

    /** "VM-HDFCBK-S" → "HDFCBK"; phone numbers stay as they are. */
    fun cleanSender(a: String): String {
        val m = Regex("^[A-Z]{2}-([A-Z0-9]{3,})(?:-[A-Z])?$", RegexOption.IGNORE_CASE).find(a.trim())
        return m?.groupValues?.get(1)?.uppercase() ?: a
    }
}

/** Today's SMS first, date-wise and time-wise — not conversation-wise. */
class SmsTimelineActivity : BaseActivity() {
    private sealed class Row {
        data class Day(val label: String, val count: Int) : Row()
        data class Msg(val sms: Sms) : Row()
        object More : Row()
    }

    private var rows: List<Row> = emptyList()
    private var filter = "ALL"
    private var query = ""
    private var day: Long? = null          // start of a picked day, or null for "recent"
    private var daysBack = 7
    private val names = HashMap<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var adapter: Adapter
    private val dayFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(reload); main.postDelayed(reload, 600) }
    }
    private val reload = Runnable { load() }
    private val tick = object : Runnable { override fun run() { adapter.notifyDataSetChanged(); main.postDelayed(this, 60_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timeline)
        toolbar("SMS Timeline", "Date-wise · newest first")
        adapter = Adapter()
        findViewById<RecyclerView>(R.id.list).apply { layoutManager = LinearLayoutManager(this@SmsTimelineActivity); adapter = this@SmsTimelineActivity.adapter }
        findViewById<ChipGroup>(R.id.filters).setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) { R.id.fImportant -> "IMP"; R.id.fOtp -> "OTP"; R.id.fBank -> "BANK"; R.id.fCourier -> "COURIER"; else -> "ALL" }
            load()
        }
        findViewById<TextView>(R.id.search).doAfterTextChanged { s ->
            query = s?.toString()?.trim() ?: ""
            main.removeCallbacks(reload); main.postDelayed(reload, 350)
        }
        findViewById<View>(R.id.boxToday).setOnClickListener { selectDay(startOfDay(System.currentTimeMillis())) }
        findViewById<View>(R.id.boxRecent).setOnClickListener { selectDay(null); findViewById<RecyclerView>(R.id.list).scrollToPosition(0) }
        findViewById<View>(R.id.boxImportant).setOnClickListener { findViewById<Chip>(R.id.fImportant).isChecked = true }
        withPerms(Manifest.permission.READ_SMS) {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            load()
        }
    }

    override fun onResume() { super.onResume(); main.postDelayed(tick, 60_000) }
    override fun onPause() { super.onPause(); main.removeCallbacks(tick) }
    override fun onDestroy() { super.onDestroy(); try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {} }

    private fun startOfDay(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun dayLabel(start: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        return when (start) {
            today -> "Today, " + SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(start))
            today - DateUtils.DAY_IN_MILLIS -> "Yesterday, " + SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(start))
            else -> dayFmt.format(Date(start))
        }
    }

    private fun selectDay(d: Long?) { day = d; load() }

    private fun senderName(addr: String): String {
        names[addr]?.let { return it }
        var n = SmsClassifier.cleanSender(addr)
        if (addr.count(Char::isDigit) >= 7 && has(Manifest.permission.READ_CONTACTS)) {
            try {
                contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(addr)),
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) n = it.getString(0) ?: n }
            } catch (_: Exception) {}
        }
        names[addr] = n
        return n
    }

    private fun read(sel: String?, args: Array<String>?, limit: Int): List<Sms> {
        val out = ArrayList<Sms>()
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id", "address", "body", "date"), sel, args, "date DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val body = c.getString(2) ?: ""; val addr = c.getString(1) ?: ""
                val (cat, lbl, v) = SmsClassifier.classify(body)
                out.add(Sms(c.getLong(0), addr, senderName(addr), body, c.getLong(3), cat, lbl, v))
            }
        }
        return out
    }

    private fun load() {
        val q = query; val f = filter; val d = day; val back = daysBack
        Thread {
            val now = System.currentTimeMillis()
            val today = startOfDay(now)
            val list = when {
                q.isNotEmpty() -> read("body LIKE ? OR address LIKE ?", arrayOf("%$q%", "%$q%"), 400)
                d != null -> read("date >= ? AND date < ?", arrayOf(d.toString(), (d + DateUtils.DAY_IN_MILLIS).toString()), 2000)
                else -> read("date >= ?", arrayOf((today - (back - 1) * DateUtils.DAY_IN_MILLIS).toString()), 3000)
            }.filter { s ->
                when (f) { "IMP" -> s.cat != Cat.OTHER; "OTP" -> s.cat == Cat.OTP; "BANK" -> s.cat == Cat.BANK; "COURIER" -> s.cat == Cat.COURIER; else -> true }
            }
            val built = ArrayList<Row>()
            list.groupBy { startOfDay(it.date) }.toSortedMap(compareByDescending { it }).forEach { (start, msgs) ->
                built.add(Row.Day(dayLabel(start), msgs.size)); msgs.forEach { built.add(Row.Msg(it)) }
            }
            if (q.isEmpty() && d == null) built.add(Row.More)
            // header stats + day chips (last 14 days)
            val dates = ArrayList<Long>()
            contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("date"), "date >= ?",
                arrayOf((today - 13 * DateUtils.DAY_IN_MILLIS).toString()), "date DESC")?.use { c -> while (c.moveToNext()) dates.add(c.getLong(0)) }
            val todayMsgs = read("date >= ?", arrayOf(today.toString()), 2000)
            val stats = Triple(todayMsgs.size, todayMsgs.count { now - it.date < 2 * DateUtils.HOUR_IN_MILLIS }, todayMsgs.count { it.cat != Cat.OTHER })
            val perDay = dates.groupingBy { startOfDay(it) }.eachCount()
            runOnUiThread {
                rows = built; adapter.notifyDataSetChanged()
                findViewById<TextView>(R.id.statToday).text = stats.first.toString()
                findViewById<TextView>(R.id.statRecent).text = stats.second.toString()
                findViewById<TextView>(R.id.statImportant).text = stats.third.toString()
                buildDayChips(today, perDay)
                findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).subtitle = when {
                    q.isNotEmpty() -> "Search: ${list.size} found"
                    d != null -> dayLabel(d) + " · ${list.size} messages"
                    else -> "Last $back days · newest first"
                }
            }
        }.start()
    }

    private fun buildDayChips(today: Long, perDay: Map<Long, Int>) {
        val g = findViewById<ChipGroup>(R.id.days)
        g.setOnCheckedStateChangeListener(null)
        g.removeAllViews()
        val shortFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        for (i in 0 until 14) {
            val start = today - i * DateUtils.DAY_IN_MILLIS
            val n = perDay[start] ?: 0
            val label = when (i) { 0 -> "Today"; 1 -> "Yesterday"; else -> shortFmt.format(Date(start)) }
            g.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId(); text = "$label  $n"; isCheckable = true; isChecked = day == start; tag = start
            })
        }
        g.addView(Chip(this).apply {
            id = View.generateViewId(); text = "📅 Pick date"; isCheckable = false
            setOnClickListener { pickDate() }
        })
        g.setOnCheckedStateChangeListener { grp, ids ->
            val chosen = ids.firstOrNull()?.let { grp.findViewById<Chip>(it)?.tag as? Long }
            if (chosen != day) selectDay(chosen)
        }
    }

    private fun pickDate() {
        MaterialDatePicker.Builder.datePicker().setTitleText("Show SMS of").build().apply {
            addOnPositiveButtonClickListener { sel ->
                val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                val local = Calendar.getInstance().apply { clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH)) }
                selectDay(local.timeInMillis)
            }
        }.show(supportFragmentManager, "d")
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("sms", text))
        Toast.makeText(this, "Copied  $text", Toast.LENGTH_SHORT).show()
    }

    private fun showFull(s: Sms) {
        MaterialAlertDialogBuilder(this).setTitle("${s.sender}  ·  ${timeFmt.format(Date(s.date))}")
            .setMessage(s.body)
            .setPositiveButton("Copy message") { _, _ -> copy(s.body) }
            .setNeutralButton("Open in Messages") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + Uri.encode(s.address)))) } catch (_: Exception) {}
            }
            .setNegativeButton("Close", null).show()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(p: Int) = when (rows[p]) { is Row.Day -> 0; is Row.Msg -> 1; Row.More -> 2 }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(p.context)
            val v = when (t) {
                0 -> inf.inflate(R.layout.item_day, p, false)
                1 -> inf.inflate(R.layout.item_sms, p, false)
                else -> MaterialButton(p.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) }
                }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            when (val r = rows[pos]) {
                is Row.Day -> {
                    h.itemView.findViewById<TextView>(R.id.day).text = r.label
                    h.itemView.findViewById<TextView>(R.id.count).text = "${r.count} SMS"
                }
                Row.More -> (h.itemView as MaterialButton).apply {
                    text = "Show older (load 7 more days)"
                    setOnClickListener { daysBack += 7; load() }
                }
                is Row.Msg -> bindSms(h.itemView, r.sms)
            }
        }

        private fun bindSms(v: View, s: Sms) {
            val now = System.currentTimeMillis()
            val color = getColor(s.cat.color)
            val card = v as MaterialCardView
            val recent = now - s.date < 2 * DateUtils.HOUR_IN_MILLIS
            card.setCardBackgroundColor(if (recent) getColor(R.color.recent_bg) else com.google.android.material.color.MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurface))
            card.strokeColor = if (recent) getColor(R.color.recent_stroke) else com.google.android.material.color.MaterialColors.getColor(card, com.google.android.material.R.attr.colorOutlineVariant)
            card.strokeWidth = if (recent) dp(2) else dp(1)
            v.findViewById<View>(R.id.bar).setBackgroundColor(color)
            v.findViewById<TextView>(R.id.tag).apply { text = s.cat.label; backgroundTintList = ColorStateList.valueOf(color) }
            v.findViewById<TextView>(R.id.sender).text = s.sender
            v.findViewById<TextView>(R.id.time).text = timeFmt.format(Date(s.date))
            v.findViewById<TextView>(R.id.fresh).apply {
                visibility = if (recent) View.VISIBLE else View.GONE
                text = "● NEW · " + DateUtils.getRelativeTimeSpanString(s.date, now, DateUtils.MINUTE_IN_MILLIS)
            }
            val row = v.findViewById<View>(R.id.valueRow)
            if (s.value != null) {
                row.visibility = View.VISIBLE
                v.findViewById<TextView>(R.id.valueLabel).text = (s.valueLabel ?: "").uppercase()
                v.findViewById<TextView>(R.id.value).apply { text = s.value; setTextColor(color) }
                v.findViewById<MaterialButton>(R.id.copy).setOnClickListener { copy(s.value.removePrefix("₹").replace(",", "")) }
            } else {
                row.visibility = if (s.valueLabel != null) View.VISIBLE else View.GONE
                v.findViewById<TextView>(R.id.valueLabel).text = (s.valueLabel ?: "").uppercase()
                v.findViewById<TextView>(R.id.value).text = ""
                v.findViewById<MaterialButton>(R.id.copy).visibility = View.GONE
            }
            if (s.value != null) v.findViewById<MaterialButton>(R.id.copy).visibility = View.VISIBLE
            v.findViewById<TextView>(R.id.body).text = s.body
            v.setOnClickListener { showFull(s) }
        }
    }
}
