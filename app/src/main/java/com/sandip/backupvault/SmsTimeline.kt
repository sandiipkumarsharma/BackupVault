package com.sandip.backupvault

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What kind of SMS this is. */
enum class Cat(val label: String, val color: Int) {
    OTP("🔑 OTP", R.color.cat_otp), BANK("💰 Bank", R.color.cat_bank), COURIER("📦 Courier", R.color.cat_courier),
    OTHER("💬 SMS", R.color.cat_other), PROMO("🏷 Offer", R.color.cat_other)
}

data class Sms(val id: Long, val address: String, val sender: String, val body: String, val date: Long,
               val cat: Cat, val valueLabel: String?, val value: String?) {
    val important get() = cat == Cat.OTP || cat == Cat.BANK || cat == Cat.COURIER
}

object SmsClassifier {
    private val I = setOf(RegexOption.IGNORE_CASE)
    private val otpKw = Regex("\\b(otp|one[- ]?time[- ]?pass(word|code)?|verification code|security code|auth(entication)? code|login code|passcode|delivery code|pin)\\b|code is|ओटीपी|सत्यापन", I)
    private val otpAfter = Regex("(?:otp|code|password|passcode|pin)[^0-9]{0,30}?(\\d{4,8})\\b", I)
    private val otpBefore = Regex("\\b(\\d{4,8})\\b[^0-9]{0,30}?(?:is your|is the|is ur|as your)[^0-9]{0,20}(?:otp|code|password|pin)", I)
    private val anyCode = Regex("\\b(\\d{4,8})\\b")

    private val money = "(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)"
    private val accountKw = Regex("\\b(a/c|acct|account|avl\\.? ?bal|avbl\\.? ?bal|available bal|balance|credited|debited|withdrawn|txn|transaction|neft|imps|rtgs|salary)\\b", I)
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

    private val promoKw = Regex("\\b(offer|sale|discount|% ?off|cashback|coupon|voucher|win|winner|free|deal|hurry|limited time|shop now|click|download|pre-?approved|loan up ?to|apply now|exclusive|grab|lowest price|flat \\d+|upto|up to \\d+%|subscribe|recharge now|plan)\\b|bit\\.ly|https?://", I)
    private val promoSender = Regex("-P$", I)

    fun classify(body: String, address: String = ""): Triple<Cat, String?, String?> {
        val b = body.replace('\n', ' ')
        val hasCourier = courierKw.containsMatchIn(b)
        if (otpKw.containsMatchIn(b)) {
            val code = otpAfter.find(b)?.groupValues?.get(1) ?: otpBefore.find(b)?.groupValues?.get(1)
                ?: anyCode.findAll(b).map { it.groupValues[1] }.firstOrNull { !(it.length == 4 && (it.startsWith("19") || it.startsWith("20"))) }
            if (code != null) return Triple(Cat.OTP, if (hasCourier) "Delivery OTP" else "OTP", code)
        }
        val promo = promoSender.containsMatchIn(address.trim()) || promoKw.containsMatchIn(b)
        if (promo && !accountKw.containsMatchIn(b)) return Triple(Cat.PROMO, null, null)
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

/** One-click clean-up (Android lets only the default SMS app delete messages). */
object SmsCleaner {
    fun count(c: Context, which: String): Int = ids(c, which).size

    private fun ids(c: Context, which: String): List<Long> {
        val out = ArrayList<Long>()
        c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id", "body", "address"), null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val keep = when (which) {
                    "ALL" -> true
                    "OTP" -> SmsClassifier.classify(cur.getString(1) ?: "", cur.getString(2) ?: "").first == Cat.OTP
                    "PROMO" -> SmsClassifier.classify(cur.getString(1) ?: "", cur.getString(2) ?: "").first == Cat.PROMO
                    else -> false
                }
                if (keep) out.add(cur.getLong(0))
            }
        }
        return out
    }

    fun delete(c: Context, which: String, p: Progress): Int {
        if (!SmsKit.isDefault(c)) error("${c.getString(R.string.app_name)} must be the SMS app to delete messages")
        val list = ids(c, which)
        var n = 0
        list.chunked(200).forEach { chunk ->
            n += c.contentResolver.delete(Telephony.Sms.CONTENT_URI, "_id IN (${chunk.joinToString(",")})", null)
            p.update(n, list.size, "Deleting")
        }
        return n
    }
}

/** Colours for each background choice. */
data class Look(val name: String, val top: Int, val bottom: Int, val card: Int, val text: Int, val sub: Int,
                val header: Int, val recentBg: Int, val stroke: Int, val chip: Int)

object Looks {
    val all = listOf(
        Look("🌌 Midnight blue", 0xFF050816.toInt(), 0xFF0F1E46.toInt(), 0xFF141C33.toInt(), 0xFFF1F5F9.toInt(), 0xFFA5B4CF.toInt(), 0xFF60A5FA.toInt(), 0xFF2A2410.toInt(), 0xFF24304F.toInt(), 0xFF1B2747.toInt()),
        Look("🔵 Royal blue", 0xFF0B2A6F.toInt(), 0xFF1E40AF.toInt(), 0xFF10275F.toInt(), 0xFFFFFFFF.toInt(), 0xFFC7D2FE.toInt(), 0xFFFCD34D.toInt(), 0xFF3A2E0F.toInt(), 0xFF23407F.toInt(), 0xFF173375.toInt()),
        Look("⚫ Pure black", 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF0F0F12.toInt(), 0xFFF5F5F5.toInt(), 0xFF9CA3AF.toInt(), 0xFF60A5FA.toInt(), 0xFF261F0A.toInt(), 0xFF1F2937.toInt(), 0xFF18181B.toInt()),
        Look("⚪ Light", 0xFFEEF2FF.toInt(), 0xFFE0E7FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF0F172A.toInt(), 0xFF475569.toInt(), 0xFF1D4ED8.toInt(), 0xFFFFF7E6.toInt(), 0xFFCBD5E1.toInt(), 0xFFFFFFFF.toInt()),
        Look("🖼 My photo", 0xFF050816.toInt(), 0xFF0F1E46.toInt(), 0xE6141C33.toInt(), 0xFFF1F5F9.toInt(), 0xFFC7D2FE.toInt(), 0xFF93C5FD.toInt(), 0xEE2A2410.toInt(), 0x6624304F, 0xE61B2747.toInt()),
    )
    fun current(c: Context) = all.getOrElse(Prefs.getInt(c, "tl_look", 0)) { all[0] }
}

/** Today's SMS first, date-wise and time-wise — not conversation-wise. */
class SmsTimelineActivity : BaseActivity() {
    private sealed class Row {
        data class Day(val label: String, val count: Int, val important: Int) : Row()
        data class Msg(val sms: Sms) : Row()
        object More : Row()
    }

    private var rows: List<Row> = emptyList()
    private var filter = "ALL"
    private var query = ""
    private var day: Long? = null
    private var daysBack = 7
    private val names = HashMap<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var adapter: Adapter
    private lateinit var look: Look
    private val dayFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private var pendingClear: String? = null

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(reload); main.postDelayed(reload, 600) }
    }
    private val reload = Runnable { load() }
    private val tick = object : Runnable { override fun run() { adapter.notifyDataSetChanged(); main.postDelayed(this, 60_000) } }

    private val smsRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val w = pendingClear; pendingClear = null
        if (w != null && SmsKit.isDefault(this)) runClear(w)
        else if (w != null) info("Not changed", "Deleting needs ${getString(R.string.app_name)} as the SMS app for a moment. Nothing was deleted.")
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Prefs.putString(this, "tl_photo", uri.toString()); Prefs.putInt(this, "tl_look", Looks.all.size - 1)
            applyLook(); adapter.notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timeline)
        toolbar("SMS Timeline", "Date-wise · newest first")
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.timeline_menu)
            overflowIcon?.setTint(0xFFFFFFFF.toInt())
            setOnMenuItemClickListener { m ->
                when (m.itemId) {
                    R.id.theme -> chooseLook()
                    R.id.clearOtp -> askClear("OTP")
                    R.id.clearOffers -> askClear("PROMO")
                    R.id.clearAll -> askClear("ALL")
                    R.id.backToApp -> startActivity(SmsKit.restoreDefaultIntent(this@SmsTimelineActivity))
                }
                true
            }
        }
        adapter = Adapter()
        findViewById<RecyclerView>(R.id.list).apply { layoutManager = LinearLayoutManager(this@SmsTimelineActivity); adapter = this@SmsTimelineActivity.adapter }
        findViewById<ChipGroup>(R.id.filters).setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.fImportant -> "IMP"; R.id.fOtp -> "OTP"; R.id.fBank -> "BANK"; R.id.fCourier -> "COURIER"; R.id.fOffers -> "PROMO"; else -> "ALL"
            }
            load()
        }
        findViewById<TextView>(R.id.search).doAfterTextChanged { s ->
            query = s?.toString()?.trim() ?: ""
            main.removeCallbacks(reload); main.postDelayed(reload, 350)
        }
        findViewById<View>(R.id.boxToday).setOnClickListener { selectDay(startOfDay(System.currentTimeMillis())) }
        findViewById<View>(R.id.boxRecent).setOnClickListener { selectDay(null); findViewById<RecyclerView>(R.id.list).scrollToPosition(0) }
        findViewById<View>(R.id.boxImportant).setOnClickListener { findViewById<Chip>(R.id.fImportant).isChecked = true }
        applyLook()
        withPerms(Manifest.permission.READ_SMS) {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            load()
        }
    }

    override fun onResume() {
        super.onResume(); main.postDelayed(tick, 60_000)
        findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(R.id.backToApp)?.isVisible = SmsKit.isDefault(this)
    }
    override fun onPause() { super.onPause(); main.removeCallbacks(tick) }
    override fun onDestroy() { super.onDestroy(); try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {} }

    // ---------------- look & feel ----------------
    private fun applyLook() {
        look = Looks.current(this)
        val root = findViewById<View>(R.id.root)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(look.top, look.bottom))
        val img = findViewById<ImageView>(R.id.bgImage); val shade = findViewById<View>(R.id.bgShade)
        val photo = Prefs.getString(this, "tl_photo", "")
        if (look == Looks.all.last() && photo.isNotEmpty()) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = contentResolver.openInputStream(Uri.parse(photo))?.use { BitmapFactory.decodeStream(it, null, opts) }
                img.setImageBitmap(bmp); img.visibility = View.VISIBLE
                shade.setBackgroundColor(0x80000000.toInt()); shade.visibility = View.VISIBLE
            } catch (_: Exception) { img.visibility = View.GONE; shade.visibility = View.GONE }
        } else { img.visibility = View.GONE; shade.visibility = View.GONE }
        findViewById<TextInputLayout>(R.id.searchBox).apply {
            boxBackgroundColor = look.card
            defaultHintTextColor = ColorStateList.valueOf(look.sub)
            setEndIconTintList(ColorStateList.valueOf(look.sub))
        }
        findViewById<TextView>(R.id.search).setTextColor(look.text)
        styleChips(findViewById(R.id.filters))
    }

    private fun styleChip(ch: Chip) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        ch.chipBackgroundColor = ColorStateList(states, intArrayOf(0xFF2563EB.toInt(), look.chip))
        ch.setTextColor(ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), look.text)))
        ch.chipStrokeWidth = 0f
        ch.checkedIconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
    }
    private fun styleChips(g: ChipGroup) { for (i in 0 until g.childCount) (g.getChildAt(i) as? Chip)?.let { styleChip(it) } }

    private fun chooseLook() {
        MaterialAlertDialogBuilder(this).setTitle("🎨 Background")
            .setSingleChoiceItems(Looks.all.map { it.name }.toTypedArray(), Prefs.getInt(this, "tl_look", 0)) { d, i ->
                d.dismiss()
                if (i == Looks.all.size - 1) photoPicker.launch(arrayOf("image/*"))
                else { Prefs.putInt(this, "tl_look", i); applyLook(); load() }
            }.show()
    }

    // ---------------- data ----------------
    private fun startOfDay(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun dayLabel(start: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val dm = SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(start))
        return when (start) {
            today -> "Today, $dm"
            today - DateUtils.DAY_IN_MILLIS -> "Yesterday, $dm"
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
                val (cat, lbl, v) = SmsClassifier.classify(body, addr)
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
            val list = try {
                when {
                    q.isNotEmpty() -> read("body LIKE ? OR address LIKE ?", arrayOf("%$q%", "%$q%"), 400)
                    d != null -> read("date >= ? AND date < ?", arrayOf(d.toString(), (d + DateUtils.DAY_IN_MILLIS).toString()), 2000)
                    else -> read("date >= ?", arrayOf((today - (back - 1) * DateUtils.DAY_IN_MILLIS).toString()), 3000)
                }
            } catch (_: Exception) { emptyList() }.filter { s ->
                when (f) {
                    "IMP" -> s.important; "OTP" -> s.cat == Cat.OTP; "BANK" -> s.cat == Cat.BANK
                    "COURIER" -> s.cat == Cat.COURIER; "PROMO" -> s.cat == Cat.PROMO; else -> true
                }
            }
            val built = ArrayList<Row>()
            list.groupBy { startOfDay(it.date) }.toSortedMap(compareByDescending { it }).forEach { (start, msgs) ->
                built.add(Row.Day(dayLabel(start), msgs.size, msgs.count { it.important })); msgs.forEach { built.add(Row.Msg(it)) }
            }
            if (q.isEmpty() && d == null) built.add(Row.More)
            val dates = ArrayList<Long>()
            try {
                contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("date"), "date >= ?",
                    arrayOf((today - 13 * DateUtils.DAY_IN_MILLIS).toString()), "date DESC")?.use { c -> while (c.moveToNext()) dates.add(c.getLong(0)) }
            } catch (_: Exception) {}
            val todayMsgs = try { read("date >= ?", arrayOf(today.toString()), 2000) } catch (_: Exception) { emptyList() }
            val stats = Triple(todayMsgs.size, todayMsgs.count { now - it.date < 2 * DateUtils.HOUR_IN_MILLIS }, todayMsgs.count { it.important })
            val perDay = dates.groupingBy { startOfDay(it) }.eachCount()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                rows = built; adapter.notifyDataSetChanged()
                findViewById<TextView>(R.id.statToday).text = stats.first.toString()
                findViewById<TextView>(R.id.statRecent).text = stats.second.toString()
                findViewById<TextView>(R.id.statImportant).text = stats.third.toString()
                buildDayChips(today, perDay)
                findViewById<MaterialToolbar>(R.id.toolbar).subtitle = when {
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
                id = View.generateViewId(); text = "$label · $n"; isCheckable = true; isChecked = day == start; tag = start
                styleChip(this)
            })
        }
        g.addView(Chip(this).apply {
            id = View.generateViewId(); text = "📅 Pick date"; isCheckable = false
            styleChip(this)
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

    // ---------------- one-click clear ----------------
    private fun askClear(which: String) {
        val what = when (which) { "OTP" -> "OTP messages"; "PROMO" -> "offer / promo messages"; else -> "messages in your inbox" }
        runWithProgress("Counting", { SmsCleaner.count(this, which) }) { n ->
            if (n == 0) { info("Nothing to clear", "No $what found."); return@runWithProgress }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
            box.addView(TextView(this).apply {
                textSize = 15f
                text = "Delete $n $what?" + (if (which == "ALL") "\n\n⚠️ This empties your whole inbox." else "") +
                    "\n\nAndroid lets only the default SMS app delete messages, so ${getString(R.string.app_name)} becomes the SMS app for a moment — you switch back right after."
            })
            val backup = MaterialCheckBox(this).apply { text = "Save a backup first (recommended)"; isChecked = true }
            box.addView(backup)
            MaterialAlertDialogBuilder(this).setTitle(if (which == "ALL") "🗑 Clear whole inbox" else if (which == "OTP") "🧹 Clear OTPs" else "🏷 Clear offers")
                .setView(box)
                .setPositiveButton("Delete $n") { _, _ ->
                    val go = { ensureDefault(which) }
                    if (backup.isChecked) needFolder {
                        runWithProgress("Saving backup first", { p -> SmsKit.backup(this, p) }) { go() }
                    } else go()
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun ensureDefault(which: String) {
        if (SmsKit.isDefault(this)) { runClear(which); return }
        pendingClear = which
        try { smsRole.launch(SmsKit.requestDefaultIntent(this)) }
        catch (e: Exception) { pendingClear = null; info("Not available", "This phone did not allow changing the SMS app: ${e.message}") }
    }

    private fun runClear(which: String) {
        runWithProgress("Deleting", { p -> SmsCleaner.delete(this, which, p) }) { n ->
            load()
            MaterialAlertDialogBuilder(this).setTitle("✅ Deleted $n messages")
                .setMessage("Now switch back to your usual messages app so new SMS arrive there as normal.")
                .setPositiveButton("Switch back") { _, _ -> startActivity(SmsKit.restoreDefaultIntent(this)) }
                .setCancelable(false).show()
        }
    }

    // ---------------- list ----------------
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
                    h.itemView.findViewById<TextView>(R.id.day).apply { text = r.label; setTextColor(look.header) }
                    h.itemView.findViewById<TextView>(R.id.count).apply {
                        text = "${r.count} SMS" + if (r.important > 0) " · ⭐ ${r.important}" else ""
                        setTextColor(look.sub)
                    }
                }
                Row.More -> (h.itemView as MaterialButton).apply {
                    text = "Show older (load 7 more days)"; setTextColor(look.header)
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
            card.setCardBackgroundColor(if (recent) look.recentBg else look.card)
            card.strokeColor = if (recent) getColor(R.color.recent_stroke) else look.stroke
            card.strokeWidth = if (recent) dp(2) else dp(1)
            card.alpha = if (s.cat == Cat.PROMO && filter != "PROMO") 0.6f else 1f
            v.findViewById<View>(R.id.bar).setBackgroundColor(color)
            v.findViewById<TextView>(R.id.tag).apply { text = s.cat.label; backgroundTintList = ColorStateList.valueOf(color) }
            v.findViewById<TextView>(R.id.sender).apply { text = s.sender; setTextColor(look.text) }
            v.findViewById<TextView>(R.id.time).apply { text = timeFmt.format(Date(s.date)); setTextColor(look.text) }
            v.findViewById<TextView>(R.id.fresh).apply {
                visibility = if (recent) View.VISIBLE else View.GONE
                text = "● NEW · " + DateUtils.getRelativeTimeSpanString(s.date, now, DateUtils.MINUTE_IN_MILLIS)
            }
            val row = v.findViewById<View>(R.id.valueRow)
            val copyBtn = v.findViewById<MaterialButton>(R.id.copy)
            v.findViewById<TextView>(R.id.valueLabel).apply { text = (s.valueLabel ?: "").uppercase(); setTextColor(look.sub) }
            val value = s.value
            if (value != null) {
                row.visibility = View.VISIBLE
                v.findViewById<TextView>(R.id.value).apply { text = value; visibility = View.VISIBLE; setTextColor(if (s.cat == Cat.BANK && look.text == 0xFFFFFFFF.toInt()) 0xFF93C5FD.toInt() else lighten(color)) }
                copyBtn.visibility = View.VISIBLE
                copyBtn.setOnClickListener { copy(value.removePrefix("₹").replace(",", "")) }
            } else {
                row.visibility = if (s.valueLabel != null) View.VISIBLE else View.GONE
                v.findViewById<TextView>(R.id.value).visibility = View.GONE
                copyBtn.visibility = View.GONE
            }
            v.findViewById<TextView>(R.id.body).apply {
                text = s.body; setTextColor(look.sub)
                maxLines = if (s.cat == Cat.PROMO) 2 else 4
            }
            v.setOnClickListener { showFull(s) }
        }

        /** Brighter accent on dark backgrounds so big numbers stay readable. */
        private fun lighten(c: Int): Int {
            if (look.text != 0xFFFFFFFF.toInt() && look.text != 0xFFF1F5F9.toInt() && look.text != 0xFFF5F5F5.toInt()) return c
            val r = (c shr 16 and 0xFF); val g = (c shr 8 and 0xFF); val b = (c and 0xFF)
            return (0xFF shl 24) or ((r + (255 - r) * 45 / 100) shl 16) or ((g + (255 - g) * 45 / 100) shl 8) or (b + (255 - b) * 45 / 100)
        }
    }
}
