package com.sandip.backupvault

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as CDK
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One contact, read from the phone or from a VCF / Excel file. */
data class Rec(
    val id: Long = 0,
    val name: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val org: String = "",
    val birthday: String = "",
    val note: String = "",
    val rawIds: List<Long> = emptyList(),
) {
    fun richness() = phones.size * 3 + emails.size * 2 + (if (org.isNotBlank()) 1 else 0) + (if (birthday.isNotBlank()) 2 else 0) + (if (note.isNotBlank()) 1 else 0)
}

/** A set of contacts that look like the same person. */
data class DupGroup(val no: Int, val members: List<Rec>, val reason: String, val keeper: Int)

object ContactsKit {

    fun count(c: Context): Int = try {
        c.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    // ---------------- backup (system vCard export — keeps photos, addresses, everything) ----------------
    fun backup(c: Context, p: Progress, auto: Boolean = false): String {
        val keys = ArrayList<String>()
        c.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use { cur ->
            while (cur.moveToNext()) cur.getString(0)?.let { keys.add(it) }
        }
        val name = (if (auto) "auto_" else "") + "contacts_${Vault.stamp()}.vcf"
        val f = Vault.create(c, Kind.CONTACTS, name) ?: error("Choose a backup folder first")
        var ok = 0
        c.contentResolver.openOutputStream(f.uri)!!.use { out ->
            keys.forEachIndexed { i, k ->
                try {
                    val u = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, Uri.encode(k))
                    c.contentResolver.openAssetFileDescriptor(u, "r")?.use { fd -> fd.createInputStream().use { it.copyTo(out) }; ok++ }
                } catch (_: Exception) {}
                p.update(i + 1, keys.size, "Saving contacts")
            }
        }
        Prefs.setLastBackup(c, Kind.CONTACTS)
        return "$ok contacts saved to $name"
    }

    // ---------------- read contacts from the phone ----------------
    fun readPhone(c: Context, p: Progress): List<Rec> {
        data class M(var name: String = "", val phones: LinkedHashSet<String> = LinkedHashSet(), val emails: LinkedHashSet<String> = LinkedHashSet(),
                     var org: String = "", var bday: String = "", var note: String = "", val raws: LinkedHashSet<Long> = LinkedHashSet())
        val map = LinkedHashMap<Long, M>()
        c.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null,
            ContactsContract.Contacts.DISPLAY_NAME + " COLLATE NOCASE")?.use { cur ->
            while (cur.moveToNext()) map[cur.getLong(0)] = M(cur.getString(1) ?: "")
        }
        val proj = arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1, ContactsContract.Data.DATA2)
        c.contentResolver.query(ContactsContract.Data.CONTENT_URI, proj, null, null, null)?.use { cur ->
            val total = cur.count; var i = 0
            while (cur.moveToNext()) {
                val m = map[cur.getLong(0)] ?: continue
                m.raws.add(cur.getLong(1))
                val v = cur.getString(3)?.trim() ?: ""
                if (v.isNotEmpty()) when (cur.getString(2)) {
                    CDK.Phone.CONTENT_ITEM_TYPE -> m.phones.add(v)
                    CDK.Email.CONTENT_ITEM_TYPE -> m.emails.add(v)
                    CDK.Organization.CONTENT_ITEM_TYPE -> if (m.org.isEmpty()) m.org = v
                    CDK.Note.CONTENT_ITEM_TYPE -> if (m.note.isEmpty()) m.note = v
                    CDK.Event.CONTENT_ITEM_TYPE -> if (cur.getInt(4) == CDK.Event.TYPE_BIRTHDAY) m.bday = v
                }
                if (++i % 200 == 0) p.update(i, total, "Reading contacts")
            }
        }
        return map.map { (id, m) ->
            Rec(id, m.name, dedupePhones(m.phones.toList()), m.emails.toList(), m.org, m.bday, m.note, m.raws.toList())
        }
    }

    // ---------------- VCF parsing (any .vcf file) ----------------
    fun parseVcf(input: InputStream): List<Rec> {
        val raw = input.bufferedReader(Charsets.UTF_8).readText().replace("\r\n", "\n").replace("\r", "\n")
        // unfold: lines starting with space/tab continue the previous line; QP soft breaks end with '='
        val lines = ArrayList<String>()
        for (l in raw.split("\n")) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && lines.isNotEmpty()) lines[lines.size - 1] = lines.last() + l.substring(1)
            else if (lines.isNotEmpty() && lines.last().endsWith("=") && lines.last().uppercase().contains("QUOTED-PRINTABLE")) lines[lines.size - 1] = lines.last().dropLast(1) + l
            else lines.add(l)
        }
        val out = ArrayList<Rec>()
        var fn = ""; var n = ""; var org = ""; var bday = ""; var note = ""
        var phones = ArrayList<String>(); var emails = ArrayList<String>()
        for (line in lines) {
            val idx = line.indexOf(':'); if (idx <= 0) continue
            val head = line.substring(0, idx); val headU = head.uppercase()
            var value = line.substring(idx + 1)
            if (headU.contains("QUOTED-PRINTABLE")) value = qp(value)
            val prop = headU.substringBefore(';').substringAfter('.')
            when (prop) {
                "BEGIN" -> { fn = ""; n = ""; org = ""; bday = ""; note = ""; phones = ArrayList(); emails = ArrayList() }
                "FN" -> fn = unesc(value)
                "N" -> n = value.split(";").let { s -> listOf(s.getOrNull(3), s.getOrNull(1), s.getOrNull(2), s.getOrNull(0), s.getOrNull(4)) }
                    .filterNotNull().map { unesc(it).trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                "TEL" -> value.trim().takeIf { it.isNotEmpty() }?.let { phones.add(it.removePrefix("tel:")) }
                "EMAIL" -> value.trim().takeIf { it.isNotEmpty() }?.let { emails.add(it) }
                "ORG" -> org = unesc(value.replace(";", " ")).trim()
                "BDAY" -> bday = value.trim()
                "NOTE" -> note = unesc(value)
                "END" -> {
                    val name = fn.ifBlank { n }.ifBlank { phones.firstOrNull() ?: emails.firstOrNull() ?: "(no name)" }
                    out.add(Rec(0, name, dedupePhones(phones), emails.distinct(), org, bday, note))
                }
            }
        }
        return out
    }

    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun qp(s: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '=' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val b = hex.toIntOrNull(16)
                if (hex.length == 2 && b != null) { bytes.write(b); i += 3; continue }
            }
            bytes.write(ch.code and 0xFF); i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    // ---------------- duplicates ----------------
    fun phoneKey(p: String): String = p.filter { it.isDigit() }.let { if (it.length >= 7) it.takeLast(10) else "" }
    fun nameKey(n: String): String = n.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

    fun pretty(p: String): String {
        val d = p.filter { it.isDigit() }
        return when {
            d.length == 10 -> "+91 ${d.take(5)} ${d.drop(5)}"
            d.length == 12 && d.startsWith("91") -> "+91 ${d.substring(2, 7)} ${d.substring(7)}"
            d.length == 11 && d.startsWith("0") -> "+91 ${d.substring(1, 6)} ${d.substring(6)}"
            else -> p.trim()
        }
    }

    fun dedupePhones(list: List<String>): List<String> {
        val seen = HashSet<String>(); val out = ArrayList<String>()
        for (p in list) { val k = phoneKey(p).ifEmpty { p }; if (seen.add(k)) out.add(pretty(p)) }
        return out
    }

    private fun lev1(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var diff = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++diff > 1) return false
            when { a.length > b.length -> i++; b.length > a.length -> j++; else -> { i++; j++ } }
        }
        return diff + (a.length - i) + (b.length - j) <= 1
    }

    fun findDuplicates(recs: List<Rec>, p: Progress): List<DupGroup> {
        val parent = IntArray(recs.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var y = x; while (parent[y] != r) { val n = parent[y]; parent[y] = r; y = n }; return r }
        val reason = HashMap<Int, MutableSet<String>>()
        fun union(a: Int, b: Int, why: String) {
            val ra = find(a); val rb = find(b)
            val merged = (reason[ra] ?: mutableSetOf()).apply { addAll(reason[rb] ?: emptySet()); add(why) }
            if (ra != rb) parent[rb] = ra
            reason[find(a)] = merged
        }
        val byPhone = HashMap<String, Int>(); val byName = HashMap<String, Int>()
        recs.forEachIndexed { i, r ->
            r.phones.forEach { ph -> val k = phoneKey(ph); if (k.isNotEmpty()) byPhone[k]?.let { union(it, i, "Same number") } ?: run { byPhone[k] = i } }
            val nk = nameKey(r.name)
            if (nk.length >= 2) byName[nk]?.let { union(it, i, "Same name") } ?: run { byName[nk] = i }
            if (i % 200 == 0) p.update(i, recs.size, "Checking numbers and names")
        }
        // similar names (one letter different), compared within small buckets
        val buckets = byName.keys.filter { it.length >= 5 }.groupBy { it.take(2) }
        for ((_, names) in buckets) {
            if (names.size > 400) continue
            for (x in names.indices) for (y in x + 1 until names.size) {
                if (lev1(names[x], names[y])) union(byName[names[x]]!!, byName[names[y]]!!, "Similar name")
            }
        }
        val groups = recs.indices.groupBy { find(it) }.filter { it.value.size > 1 }
        var no = 0
        return groups.map { (root, idx) ->
            val members = idx.map { recs[it] }
            val keeper = members.indices.maxWith(compareBy<Int> { members[it].richness() }.thenBy { members[it].name.length })
            DupGroup(++no, members, (reason[find(root)] ?: setOf("Duplicate")).sorted().joinToString(" + "), keeper)
        }.sortedByDescending { it.members.size }.mapIndexed { i, g -> g.copy(no = i + 1) }
    }

    // ---------------- fix duplicates on the phone ----------------
    /** Joins the contacts into one (Android's own "merge/link" — can be undone in the Contacts app). */
    fun merge(c: Context, g: DupGroup): Boolean {
        val keep = g.members[g.keeper].rawIds.firstOrNull() ?: return false
        val ops = ArrayList<ContentProviderOperation>()
        g.members.forEachIndexed { i, m ->
            if (i == g.keeper) return@forEachIndexed
            m.rawIds.forEach { r ->
                ops.add(ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, keep)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, r).build())
            }
        }
        if (ops.isEmpty()) return false
        c.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return true
    }

    /** Deletes every member except the keeper. */
    fun deleteExtras(c: Context, g: DupGroup): Int {
        var n = 0
        g.members.forEachIndexed { i, m ->
            if (i != g.keeper && m.id != 0L)
                n += c.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, m.id), null, null)
        }
        return n
    }

    // ---------------- Excel ----------------
    val HEADERS = listOf("Group", "Action", "Reason", "Name", "Phone 1", "Phone 2", "Phone 3", "More phones", "Email", "Company", "Birthday", "Note")

    /** Writes an Excel file: duplicates first (highlighted, with suggested action), then everyone else. */
    fun writeExcel(out: OutputStream, recs: List<Rec>, groups: List<DupGroup>) {
        val rows = ArrayList<Pair<List<String>, Int>>()   // cells, style
        val inGroup = HashSet<Rec>()
        for (g in groups) {
            g.members.forEachIndexed { i, r ->
                inGroup.add(r)
                val action = when {
                    i == g.keeper -> "KEEP"
                    g.reason == "Similar name" -> "KEEP"
                    else -> "MERGE"
                }
                rows.add(recRow(g.no.toString(), action, g.reason, r) to 2)
            }
        }
        recs.filter { it !in inGroup }.forEach { rows.add(recRow("", "KEEP", "", it) to 0) }
        val summary = listOf(
            listOf("Contacts total", recs.size.toString()),
            listOf("Duplicate groups", groups.size.toString()),
            listOf("Contacts in duplicate groups", inGroup.size.toString()),
            listOf("Contacts after merging", (recs.size - inGroup.size + groups.size).toString()),
            listOf("", ""),
            listOf("How to use", "In column B (Action) choose KEEP, MERGE or DELETE."),
            listOf("KEEP", "Contact is kept as it is."),
            listOf("MERGE", "Contact is combined into the KEEP contact of the same Group (all numbers/emails kept)."),
            listOf("DELETE", "Contact is removed."),
            listOf("Next", "Save the file, then in Backup Vault → Contacts → ‘Excel → clean VCF’ pick this file."),
        )
        Xlsx.write(out, listOf(
            Xlsx.Sheet("Contacts", HEADERS, rows, listOf(8, 10, 22, 28, 18, 18, 18, 22, 28, 22, 12, 30), actionCol = 1),
            Xlsx.Sheet("Summary", listOf("Item", "Value"), summary.map { it to 0 }, listOf(30, 80)),
        ))
    }

    private fun recRow(group: String, action: String, reason: String, r: Rec): List<String> = listOf(
        group, action, reason, r.name,
        r.phones.getOrElse(0) { "" }, r.phones.getOrElse(1) { "" }, r.phones.getOrElse(2) { "" }, r.phones.drop(3).joinToString(", "),
        r.emails.joinToString(", "), r.org, r.birthday, r.note.replace("\n", " "))

    /** Reads the corrected Excel and applies KEEP / MERGE / DELETE. */
    fun excelToRecs(input: InputStream): Pair<List<Rec>, String> {
        val table = Xlsx.readFirstSheet(input)
        if (table.isEmpty()) error("The Excel file is empty")
        val head = table[0].map { it.trim().lowercase() }
        fun col(n: String) = head.indexOf(n.lowercase())
        val cG = col("Group"); val cA = col("Action"); val cN = col("Name")
        if (cN < 0) error("Couldn't find a ‘Name’ column — use the Excel made by Backup Vault")
        fun cell(r: List<String>, i: Int) = if (i >= 0) r.getOrElse(i) { "" }.trim() else ""
        data class Row(val group: String, val action: String, val rec: Rec)
        val rows = table.drop(1).filter { r -> r.any { it.isNotBlank() } }.map { r ->
            val phones = listOf(cell(r, col("Phone 1")), cell(r, col("Phone 2")), cell(r, col("Phone 3"))) +
                cell(r, col("More phones")).split(',', ';')
            Row(cell(r, cG), cell(r, cA).uppercase().ifBlank { "KEEP" }, Rec(0, cell(r, cN).ifBlank { "(no name)" },
                dedupePhones(phones.map { it.trim() }.filter { it.isNotEmpty() }),
                cell(r, col("Email")).split(',', ';').map { it.trim() }.filter { it.isNotEmpty() },
                cell(r, col("Company")), cell(r, col("Birthday")), cell(r, col("Note"))))
        }
        var deleted = 0; var merged = 0
        val out = ArrayList<Rec>()
        val byGroup = rows.filter { it.group.isNotBlank() }.groupBy { it.group }
        rows.filter { it.group.isBlank() }.forEach { if (it.action == "DELETE") deleted++ else out.add(it.rec) }
        for ((_, gr) in byGroup) {
            val keeps = gr.filter { it.action == "KEEP" }.toMutableList()
            val merges = gr.filter { it.action == "MERGE" }
            deleted += gr.count { it.action == "DELETE" }
            if (keeps.isEmpty() && merges.isNotEmpty()) keeps.add(merges.first())
            val target = keeps.first()
            val extra = merges.filter { it !== target }
            merged += extra.size
            val all = listOf(target) + extra
            val combined = target.rec.copy(
                phones = dedupePhones(all.flatMap { it.rec.phones }),
                emails = all.flatMap { it.rec.emails }.distinct(),
                org = all.firstOrNull { it.rec.org.isNotBlank() }?.rec?.org ?: "",
                birthday = all.firstOrNull { it.rec.birthday.isNotBlank() }?.rec?.birthday ?: "",
                note = all.map { it.rec.note }.filter { it.isNotBlank() }.distinct().joinToString("\n"))
            out.add(combined)
            keeps.drop(1).forEach { out.add(it.rec) }
        }
        return out to "${out.size} contacts · $merged merged · $deleted deleted"
    }

    // ---------------- VCF writing ----------------
    fun writeVcf(out: OutputStream, recs: List<Rec>) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        for (r in recs) {
            val parts = r.name.trim().split(Regex("\\s+"))
            val first = if (parts.size > 1) parts.dropLast(1).joinToString(" ") else parts.firstOrNull() ?: ""
            val last = if (parts.size > 1) parts.last() else ""
            w.write("BEGIN:VCARD\r\nVERSION:3.0\r\n")
            w.write("N:${Esc.vcf(last)};${Esc.vcf(first)};;;\r\n")
            w.write("FN:${Esc.vcf(r.name)}\r\n")
            r.phones.forEach { w.write("TEL;TYPE=CELL:${it.replace(" ", "")}\r\n") }
            r.emails.forEach { w.write("EMAIL;TYPE=INTERNET:$it\r\n") }
            if (r.org.isNotBlank()) w.write("ORG:${Esc.vcf(r.org)}\r\n")
            if (r.birthday.isNotBlank()) w.write("BDAY:${r.birthday}\r\n")
            if (r.note.isNotBlank()) w.write("NOTE:${Esc.vcf(r.note)}\r\n")
            w.write("END:VCARD\r\n")
        }
        w.flush()
    }
}

/** Minimal Excel (.xlsx) writer/reader — no external libraries. */
object Xlsx {
    class Sheet(val name: String, val header: List<String>, val rows: List<Pair<List<String>, Int>>, val widths: List<Int>, val actionCol: Int = -1)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
    private fun colName(i: Int): String { var n = i + 1; var s = ""; while (n > 0) { val m = (n - 1) % 26; s = ('A' + m) + s; n = (n - 1) / 26 }; return s }

    fun write(out: OutputStream, sheets: List<Sheet>) {
        val z = ZipOutputStream(out)
        fun put(path: String, text: String) { z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
            sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
            sheets.mapIndexed { i, s -> """<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
        put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
            """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1E3A8A"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFEF3C7"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="49" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyNumberFormat="1"/><xf numFmtId="49" fontId="0" fillId="3" borderId="0" xfId="0" applyFill="1" applyNumberFormat="1"/></cellXfs></styleSheet>""")
        sheets.forEachIndexed { si, s ->
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>""")
            s.widths.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
            sb.append("</cols><sheetData>")
            fun row(r: Int, cells: List<String>, style: Int) {
                sb.append("""<row r="$r">""")
                cells.forEachIndexed { i, v -> sb.append("""<c r="${colName(i)}$r" t="inlineStr" s="$style"><is><t xml:space="preserve">${esc(v)}</t></is></c>""") }
                sb.append("</row>")
            }
            row(1, s.header, 1)
            s.rows.forEachIndexed { i, (cells, st) -> row(i + 2, cells, st) }
            sb.append("</sheetData>")
            if (s.actionCol >= 0) {
                val c = colName(s.actionCol)
                sb.append("""<dataValidations count="1"><dataValidation type="list" allowBlank="1" showDropDown="0" sqref="${c}2:${c}${s.rows.size + 500}"><formula1>"KEEP,MERGE,DELETE"</formula1></dataValidation></dataValidations>""")
            }
            sb.append("</worksheet>")
            put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
        }
        z.finish(); z.flush()
    }

    /** Reads the first worksheet into rows of strings (handles shared strings and inline strings). */
    fun readFirstSheet(input: InputStream): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "xl/sharedStrings.xml" || e.name == "xl/workbook.xml" || e.name.startsWith("xl/worksheets/sheet") || e.name == "xl/_rels/workbook.xml.rels")
                    files[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val shared = ArrayList<String>()
        files["xl/sharedStrings.xml"]?.let { bytes ->
            val p = Xml.newPullParser(); p.setInput(bytes.inputStream(), "UTF-8")
            var cur: StringBuilder? = null; var inT = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) { "si" -> cur = StringBuilder(); "t" -> inT = true }
                    XmlPullParser.TEXT -> if (inT) cur?.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) { "t" -> inT = false; "si" -> { shared.add(cur?.toString() ?: ""); cur = null } }
                }
            }
        }
        val sheetPath = files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minByOrNull { it.filter(Char::isDigit).toIntOrNull() ?: 99 }
            ?: error("No worksheet found in the Excel file")
        val rows = ArrayList<List<String>>()
        val p = Xml.newPullParser(); p.setInput(files[sheetPath]!!.inputStream(), "UTF-8")
        var row: MutableMap<Int, String>? = null
        var col = 0; var type = ""; var text = StringBuilder(); var inV = false; var inT = false
        fun colIndex(ref: String): Int { var n = 0; for (ch in ref) { if (!ch.isLetter()) break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }; return n - 1 }
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = HashMap()
                    "c" -> { col = colIndex(p.getAttributeValue(null, "r") ?: ""); type = p.getAttributeValue(null, "t") ?: ""; text = StringBuilder() }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val v = text.toString()
                        row?.set(col, if (type == "s") shared.getOrElse(v.toIntOrNull() ?: -1) { "" } else v)
                    }
                    "row" -> row?.let { r ->
                        val max = (r.keys.maxOrNull() ?: -1)
                        rows.add(List(max + 1) { r[it] ?: "" })
                    }
                }
            }
        }
        return rows
    }
}
