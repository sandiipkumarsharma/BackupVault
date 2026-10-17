package com.sandip.backupvault

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Call history backup (JSON) and restore. */
object CallsKit {
    fun count(c: Context): Int = try {
        c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, null)?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    fun backup(c: Context, p: Progress, auto: Boolean = false): String {
        val arr = JSONArray()
        c.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME, CallLog.Calls.NEW),
            null, null, CallLog.Calls.DATE + " ASC")?.use { cur ->
            val total = cur.count; var i = 0
            while (cur.moveToNext()) {
                arr.put(JSONObject().put("number", cur.getString(0) ?: "").put("date", cur.getLong(1)).put("duration", cur.getLong(2))
                    .put("type", cur.getInt(3)).put("name", cur.getString(4) ?: "").put("new", cur.getInt(5)))
                if (++i % 100 == 0) p.update(i, total, "Saving call history")
            }
        }
        val name = (if (auto) "auto_" else "") + "calls_${Vault.stamp()}.json"
        val f = Vault.create(c, Kind.CALLS, name) ?: error("Choose a backup folder first")
        c.contentResolver.openOutputStream(f.uri)!!.use { it.write(JSONObject().put("made_by", "Backup Vault").put("calls", arr).toString().toByteArray()) }
        Prefs.setLastBackup(c, Kind.CALLS)
        return "${arr.length()} calls saved to $name"
    }

    fun restore(c: Context, input: InputStream, p: Progress): String {
        val arr = JSONObject(input.bufferedReader().readText()).getJSONArray("calls")
        val existing = HashSet<String>()
        c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.DATE, CallLog.Calls.NUMBER), null, null, null)?.use { cur ->
            while (cur.moveToNext()) existing.add("${cur.getLong(0)}|${cur.getString(1)}")
        }
        var added = 0; var skipped = 0
        val batch = ArrayList<ContentValues>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!existing.add("${o.getLong("date")}|${o.optString("number")}")) { skipped++; continue }
            batch.add(ContentValues().apply {
                put(CallLog.Calls.NUMBER, o.optString("number")); put(CallLog.Calls.DATE, o.getLong("date"))
                put(CallLog.Calls.DURATION, o.optLong("duration")); put(CallLog.Calls.TYPE, o.optInt("type", CallLog.Calls.INCOMING_TYPE))
                put(CallLog.Calls.CACHED_NAME, o.optString("name")); put(CallLog.Calls.NEW, 0)
            })
            added++
            if (batch.size >= 200) { c.contentResolver.bulkInsert(CallLog.Calls.CONTENT_URI, batch.toTypedArray()); batch.clear() }
            if (i % 100 == 0) p.update(i, arr.length(), "Restoring call history")
        }
        if (batch.isNotEmpty()) c.contentResolver.bulkInsert(CallLog.Calls.CONTENT_URI, batch.toTypedArray())
        return "$added calls restored · $skipped already on the phone"
    }
}

/** Calendar events backup (.ics) and restore. */
object CalendarKit {
    data class Cal(val id: Long, val name: String, val account: String, val writable: Boolean)

    fun calendars(c: Context): List<Cal> {
        val out = ArrayList<Cal>()
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL), null, null, null)?.use { cur ->
            while (cur.moveToNext()) out.add(Cal(cur.getLong(0), cur.getString(1) ?: "Calendar", cur.getString(2) ?: "",
                cur.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR))
        }
        return out
    }

    fun count(c: Context): Int = try {
        c.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            CalendarContract.Events.DELETED + "=0", null, null)?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    private val utc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun fold(line: String): String {
        if (line.length <= 74) return line + "\r\n"
        val sb = StringBuilder(); var i = 0
        while (i < line.length) { val end = minOf(line.length, i + if (i == 0) 74 else 73); sb.append(if (i == 0) "" else " ").append(line, i, end).append("\r\n"); i = end }
        return sb.toString()
    }

    fun backup(c: Context, p: Progress, auto: Boolean = false): String {
        val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Backup Vault//EN\r\n")
        var n = 0
        c.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(
            CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION, CalendarContract.Events.ALL_DAY, CalendarContract.Events.RRULE,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME), CalendarContract.Events.DELETED + "=0", null, null)?.use { cur ->
            val total = cur.count
            while (cur.moveToNext()) {
                val start = cur.getLong(4); val allDay = cur.getInt(7) == 1
                sb.append("BEGIN:VEVENT\r\n")
                sb.append("UID:bv-${cur.getLong(0)}-$start@backupvault\r\n")
                sb.append(if (allDay) "DTSTART;VALUE=DATE:${day.format(start)}\r\n" else "DTSTART:${utc.format(start)}\r\n")
                if (!cur.isNull(5) && cur.getLong(5) > 0) {
                    val end = cur.getLong(5)
                    sb.append(if (allDay) "DTEND;VALUE=DATE:${day.format(end)}\r\n" else "DTEND:${utc.format(end)}\r\n")
                } else cur.getString(6)?.let { sb.append("DURATION:$it\r\n") }
                cur.getString(8)?.takeIf { it.isNotBlank() }?.let { sb.append("RRULE:$it\r\n") }
                sb.append(fold("SUMMARY:" + Esc.ics(cur.getString(1) ?: "")))
                cur.getString(2)?.takeIf { it.isNotBlank() }?.let { sb.append(fold("DESCRIPTION:" + Esc.ics(it))) }
                cur.getString(3)?.takeIf { it.isNotBlank() }?.let { sb.append(fold("LOCATION:" + Esc.ics(it))) }
                cur.getString(9)?.let { sb.append(fold("X-BV-CALENDAR:" + Esc.ics(it))) }
                sb.append("END:VEVENT\r\n")
                if (++n % 50 == 0) p.update(n, total, "Saving events")
            }
        }
        sb.append("END:VCALENDAR\r\n")
        val name = (if (auto) "auto_" else "") + "calendar_${Vault.stamp()}.ics"
        val f = Vault.create(c, Kind.CALENDAR, name) ?: error("Choose a backup folder first")
        c.contentResolver.openOutputStream(f.uri)!!.use { it.write(sb.toString().toByteArray()) }
        Prefs.setLastBackup(c, Kind.CALENDAR)
        return "$n events saved to $name"
    }

    private fun parseTime(v: String, params: String): Pair<Long, Boolean>? {
        val allDay = params.contains("VALUE=DATE") && !params.contains("DATE-TIME") || v.length == 8
        return try {
            if (allDay) day.parse(v.take(8))!!.time to true
            else if (v.endsWith("Z")) utc.parse(v)!!.time to false
            else {
                val tz = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)
                val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = if (tz != null) TimeZone.getTimeZone(tz) else TimeZone.getDefault() }
                f.parse(v.take(15))!!.time to false
            }
        } catch (_: Exception) { null }
    }

    fun restore(c: Context, input: InputStream, calId: Long, p: Progress): String {
        val raw = input.bufferedReader().readText().replace("\r\n", "\n")
        val lines = ArrayList<String>()
        for (l in raw.split("\n")) if ((l.startsWith(" ") || l.startsWith("\t")) && lines.isNotEmpty()) lines[lines.size - 1] += l.substring(1) else lines.add(l)
        val existing = HashSet<String>()
        c.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
            CalendarContract.Events.DELETED + "=0", null, null)?.use { cur ->
            while (cur.moveToNext()) existing.add("${cur.getString(0)}|${cur.getLong(1)}")
        }
        val total = lines.count { it == "BEGIN:VEVENT" }
        var added = 0; var skipped = 0; var seen = 0
        var ev: HashMap<String, Pair<String, String>>? = null
        for (l in lines) {
            when {
                l == "BEGIN:VEVENT" -> ev = HashMap()
                l == "END:VEVENT" -> {
                    val e = ev ?: continue; ev = null; seen++
                    val (startV, startP) = e["DTSTART"] ?: continue
                    val (start, allDay) = parseTime(startV, startP) ?: continue
                    val title = Esc.unIcs(e["SUMMARY"]?.first ?: "")
                    if (!existing.add("$title|$start")) { skipped++; continue }
                    val rrule = e["RRULE"]?.first
                    val end = e["DTEND"]?.let { parseTime(it.first, it.second)?.first }
                    val v = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.TITLE, title)
                        e["DESCRIPTION"]?.let { put(CalendarContract.Events.DESCRIPTION, Esc.unIcs(it.first)) }
                        e["LOCATION"]?.let { put(CalendarContract.Events.EVENT_LOCATION, Esc.unIcs(it.first)) }
                        put(CalendarContract.Events.DTSTART, start)
                        put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                        put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
                        if (rrule != null) {
                            put(CalendarContract.Events.RRULE, rrule)
                            val dur = e["DURATION"]?.first ?: if (end != null && end > start) {
                                if (allDay) "P${maxOf(1, (end - start) / 86_400_000)}D" else "P${(end - start) / 1000}S"
                            } else if (allDay) "P1D" else "P3600S"
                            put(CalendarContract.Events.DURATION, dur)
                        } else {
                            put(CalendarContract.Events.DTEND, end ?: (start + if (allDay) 86_400_000 else 3_600_000))
                        }
                    }
                    try { c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v); added++ } catch (_: Exception) { skipped++ }
                    if (seen % 20 == 0) p.update(seen, total, "Restoring events")
                }
                ev != null -> {
                    val i = l.indexOf(':'); if (i <= 0) continue
                    val head = l.substring(0, i); val key = head.substringBefore(';').uppercase()
                    ev!![key] = l.substring(i + 1) to head.uppercase()
                }
            }
        }
        return "$added events restored · $skipped skipped"
    }
}
