package ir.keyhansaze.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class Role(val code: String, val title: String) {
    ADMIN("ADMIN", "مدیر سامانه"), FACTORY("FACTORY", "کارخانه"), BAKER("BAKER", "نانوا")
}
enum class ScanMode(val code: String, val title: String, val color: Int) {
    FACTORY_OUT("FACTORY_OUT", "خروج از کارخانه", 0xFFC62828.toInt()),
    WAREHOUSE_IN("WAREHOUSE_IN", "ورود به انبار", 0xFF2E7D32.toInt()),
    DAILY_OUT("DAILY_OUT", "خروج روزانه", 0xFF1565C0.toInt())
}
data class User(val id: Int, val username: String, val password: String, val role: Role, val active: Boolean, val bakeryId: Int?)
data class Bakery(val id: Int, val name: String, val monthlyQuota: Int, val workingDays: Int, val compensatory: Int)
data class DayQuota(val baseDaily: Double, val remainingBags: Double, val remainingDays: Int, val fairShare: Double, val allowedWhole: Int, val remainderBank: Double, val usedToday: Int, val usedPeriod: Int)

class ScannerDb(ctx: Context) : SQLiteOpenHelper(ctx, "scanner_quota_v4.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, password TEXT NOT NULL, role TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, bakery_id INTEGER)")
        db.execSQL("CREATE TABLE bakeries(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, monthly_quota INTEGER NOT NULL DEFAULT 30, working_days INTEGER NOT NULL DEFAULT 13, compensatory INTEGER NOT NULL DEFAULT 0, period_start TEXT)")
        db.execSQL("CREATE TABLE scans(id INTEGER PRIMARY KEY AUTOINCREMENT, qr_code TEXT NOT NULL, bakery_id INTEGER NOT NULL, scan_type TEXT NOT NULL, scanned_by INTEGER, scanned_at TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0, UNIQUE(qr_code, scan_type))")
        db.execSQL("CREATE TABLE leave_days(id INTEGER PRIMARY KEY AUTOINCREMENT, bakery_id INTEGER NOT NULL, day TEXT NOT NULL, UNIQUE(bakery_id, day))")
        db.execSQL("CREATE TABLE remainder(bakery_id INTEGER PRIMARY KEY, bank REAL NOT NULL DEFAULT 0)")
        val start = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        db.execSQL("INSERT INTO bakeries(name,monthly_quota,working_days,compensatory,period_start) VALUES('نانوایی پیش‌فرض',30,13,0,'$start')")
        db.execSQL("INSERT INTO users(username,password,role,active,bakery_id) VALUES('admin','admin','ADMIN',1,NULL)")
        db.execSQL("INSERT INTO users(username,password,role,active,bakery_id) VALUES('factory','factory','FACTORY',1,NULL)")
        db.execSQL("INSERT INTO users(username,password,role,active,bakery_id) VALUES('baker','baker','BAKER',1,1)")
        db.execSQL("INSERT INTO remainder(bakery_id, bank) VALUES(1, 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        db.execSQL("DROP TABLE IF EXISTS leave_days"); db.execSQL("DROP TABLE IF EXISTS remainder")
        db.execSQL("DROP TABLE IF EXISTS scans"); db.execSQL("DROP TABLE IF EXISTS users"); db.execSQL("DROP TABLE IF EXISTS bakeries"); onCreate(db)
    }
    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun login(u: String, p: String): User? {
        readableDatabase.rawQuery("SELECT id,username,password,role,active,bakery_id FROM users WHERE username=? AND password=?", arrayOf(u.trim(), p)).use { c ->
            if (!c.moveToFirst() || c.getInt(4) == 0) return null
            val role = when (c.getString(3)) { "ADMIN" -> Role.ADMIN; "FACTORY" -> Role.FACTORY; else -> Role.BAKER }
            return User(c.getInt(0), c.getString(1), c.getString(2), role, true, if (c.isNull(5)) null else c.getInt(5))
        }
    }
    fun allUsers(): List<User> {
        val list = mutableListOf<User>()
        readableDatabase.rawQuery("SELECT id,username,password,role,active,bakery_id FROM users ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val role = when (c.getString(3)) { "ADMIN" -> Role.ADMIN; "FACTORY" -> Role.FACTORY; else -> Role.BAKER }
                list.add(User(c.getInt(0), c.getString(1), c.getString(2), role, c.getInt(4)==1, if (c.isNull(5)) null else c.getInt(5)))
            }
        }
        return list
    }
    fun addUser(username: String, password: String, role: Role, bakeryId: Int? = null): String? {
        if (username.trim().isEmpty() || password.isEmpty()) return "نام کاربری و رمز الزامی است."
        return try {
            val v = ContentValues().apply { put("username", username.trim()); put("password", password); put("role", role.code); put("active", 1); if (bakeryId != null) put("bakery_id", bakeryId) else putNull("bakery_id") }
            writableDatabase.insertOrThrow("users", null, v); null
        } catch (_: Exception) { "نام کاربری تکراری است." }
    }
    fun updateUserPassword(userId: Int, newPass: String): String? {
        if (newPass.isEmpty()) return "رمز خالی مجاز نیست."
        writableDatabase.execSQL("UPDATE users SET password=? WHERE id=?", arrayOf(newPass, userId)); return null
    }
    fun setUserActive(userId: Int, active: Boolean) { writableDatabase.execSQL("UPDATE users SET active=? WHERE id=?", arrayOf(if (active) 1 else 0, userId)) }
    fun deleteUser(userId: Int): String? {
        val u = allUsers().find { it.id == userId } ?: return "یافت نشد."
        if (u.role == Role.ADMIN) return "حذف مدیر مجاز نیست."
        writableDatabase.delete("users", "id=?", arrayOf(userId.toString())); return null
    }
    fun resetPassword(userId: Int) { writableDatabase.execSQL("UPDATE users SET password=? WHERE id=?", arrayOf("1234", userId)) }
    fun bakeries(): List<Bakery> {
        val list = mutableListOf<Bakery>()
        readableDatabase.rawQuery("SELECT id,name,monthly_quota,working_days,compensatory FROM bakeries ORDER BY id", null).use { c ->
            while (c.moveToNext()) list.add(Bakery(c.getInt(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4)))
        }
        return list
    }
    fun addBakery(name: String, quota: Int, days: Int = 13): String? {
        if (name.trim().isEmpty()) return "نام الزامی است."
        return try {
            val v = ContentValues().apply { put("name", name.trim()); put("monthly_quota", quota); put("working_days", days.coerceIn(1, 31)); put("compensatory", 0); put("period_start", today()) }
            val id = writableDatabase.insertOrThrow("bakeries", null, v)
            writableDatabase.execSQL("INSERT OR IGNORE INTO remainder(bakery_id, bank) VALUES(?, 0)", arrayOf(id)); null
        } catch (_: Exception) { "نام تکراری است." }
    }
    fun updateQuota(bakeryId: Int, monthly: Int, days: Int, comp: Int): String? {
        if (monthly < 0 || days < 1) return "مقادیر نامعتبر."
        writableDatabase.execSQL("UPDATE bakeries SET monthly_quota=?, working_days=?, compensatory=? WHERE id=?", arrayOf(monthly, days, comp, bakeryId)); return null
    }
    fun setLeave(bakeryId: Int, day: String, onLeave: Boolean) {
        if (onLeave) writableDatabase.execSQL("INSERT OR IGNORE INTO leave_days(bakery_id, day) VALUES(?,?)", arrayOf(bakeryId, day))
        else writableDatabase.delete("leave_days", "bakery_id=? AND day=?", arrayOf(bakeryId.toString(), day))
    }
    fun isLeave(bakeryId: Int, day: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM leave_days WHERE bakery_id=? AND day=?", arrayOf(bakeryId.toString(), day)).use { it.moveToFirst() }
    fun countToday(bakeryId: Int, type: String): Int {
        val day = today()
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM scans WHERE bakery_id=? AND scan_type=? AND scanned_at LIKE ?", arrayOf(bakeryId.toString(), type, "$day%")).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }
    fun countAll(bakeryId: Int, type: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM scans WHERE bakery_id=? AND scan_type=?", arrayOf(bakeryId.toString(), type)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun countPeriodDailyOut(bakeryId: Int): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM scans WHERE bakery_id=? AND scan_type=?", arrayOf(bakeryId.toString(), ScanMode.DAILY_OUT.code)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun getRemainderBank(bakeryId: Int): Double {
        readableDatabase.rawQuery("SELECT bank FROM remainder WHERE bakery_id=?", arrayOf(bakeryId.toString())).use { c -> if (c.moveToFirst()) return c.getDouble(0) }
        writableDatabase.execSQL("INSERT OR IGNORE INTO remainder(bakery_id, bank) VALUES(?, 0)", arrayOf(bakeryId)); return 0.0
    }
    fun setRemainderBank(bakeryId: Int, bank: Double) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO remainder(bakery_id, bank) VALUES(?, ?)", arrayOf(bakeryId, bank))
    }
    fun computeDayQuota(b: Bakery): DayQuota {
        val total = (b.monthlyQuota + b.compensatory).toDouble()
        val base = if (b.workingDays > 0) total / b.workingDays else 0.0
        val usedPeriod = countPeriodDailyOut(b.id)
        val usedToday = countToday(b.id, ScanMode.DAILY_OUT.code)
        val remainingBags = max(0.0, total - usedPeriod)
        val daysWithOut = readableDatabase.rawQuery("SELECT COUNT(DISTINCT substr(scanned_at,1,10)) FROM scans WHERE bakery_id=? AND scan_type=?", arrayOf(b.id.toString(), ScanMode.DAILY_OUT.code)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val leaveCount = readableDatabase.rawQuery("SELECT COUNT(*) FROM leave_days WHERE bakery_id=?", arrayOf(b.id.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val effectiveWorking = max(1, b.workingDays - leaveCount)
        val remainingDays = max(1, effectiveWorking - daysWithOut + if (usedToday > 0) 1 else 0)
        val fairShare = remainingBags / remainingDays
        val bank = getRemainderBank(b.id)
        val raw = fairShare + bank
        var allowed = floor(raw + 1e-9).toInt()
        val frac = raw - floor(raw)
        if (frac < 0.95 && allowed > floor(fairShare + 1e-9).toInt() && bank < 0.95) {
            allowed = floor(fairShare + 1e-9).toInt()
        }
        allowed = max(0, min(allowed, floor(remainingBags + 1e-9).toInt()))
        return DayQuota(base, remainingBags, remainingDays, fairShare, allowed, bank, usedToday, usedPeriod)
    }
    fun recordScan(bakeryId: Int, qr: String, mode: ScanMode, userId: Int): Pair<Boolean, String> {
        val code = qr.trim()
        if (code.isEmpty()) return false to "کد QR خالی است."
        val existing = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT scan_type FROM scans WHERE qr_code=?", arrayOf(code)).use { c -> while (c.moveToNext()) existing.add(c.getString(0)) }
        when (mode) {
            ScanMode.FACTORY_OUT -> if (existing.contains(ScanMode.FACTORY_OUT.code)) return false to "قبلاً خروج کارخانه ثبت شده."
            ScanMode.WAREHOUSE_IN -> {
                if (!existing.contains(ScanMode.FACTORY_OUT.code)) return false to "ابتدا خروج کارخانه ثبت شود."
                if (existing.contains(ScanMode.WAREHOUSE_IN.code)) return false to "قبلاً ورود انبار ثبت شده."
            }
            ScanMode.DAILY_OUT -> {
                if (!existing.contains(ScanMode.WAREHOUSE_IN.code)) return false to "ابتدا ورود انبار ثبت شود."
                if (existing.contains(ScanMode.DAILY_OUT.code)) return false to "قبلاً خروج روزانه ثبت شده."
                val bakery = bakeries().find { it.id == bakeryId } ?: return false to "نانوایی یافت نشد."
                if (isLeave(bakeryId, today())) return false to "امروز مرخصی/تعطیل ثبت شده."
                val q = computeDayQuota(bakery)
                if (q.usedToday + 1 > q.allowedWhole) {
                    return false to "سقف امروز ${q.allowedWhole} کیسه (شناور ${q.fairShare} + باقیمانده ${q.remainderBank}). از خروج بیشتر جلوگیری شد."
                }
            }
        }
        return try {
            val v = ContentValues().apply {
                put("qr_code", code); put("bakery_id", bakeryId); put("scan_type", mode.code)
                put("scanned_by", userId); put("scanned_at", now()); put("synced", 0)
            }
            writableDatabase.insertOrThrow("scans", null, v)
            if (mode == ScanMode.DAILY_OUT) {
                val bakery = bakeries().find { it.id == bakeryId }
                if (bakery != null) {
                    val q = computeDayQuota(bakery)
                    setRemainderBank(bakeryId, q.fairShare + q.remainderBank - 1.0)
                }
            }
            true to "${mode.title} ثبت شد."
        } catch (e: Exception) { false to "خطا: ${e.message}" }
    }
    fun discrepancy(bakeryId: Int) = countAll(bakeryId, ScanMode.WAREHOUSE_IN.code) - countAll(bakeryId, ScanMode.DAILY_OUT.code)
    fun recentScans(bakeryId: Int, limit: Int = 25): List<Array<String>> {
        val rows = mutableListOf<Array<String>>()
        readableDatabase.rawQuery("SELECT qr_code, scan_type, scanned_at FROM scans WHERE bakery_id=? ORDER BY id DESC LIMIT ?", arrayOf(bakeryId.toString(), limit.toString())).use { c ->
            while (c.moveToNext()) {
                val typeFa = when (c.getString(1)) {
                    ScanMode.FACTORY_OUT.code -> "خروج کارخانه"; ScanMode.WAREHOUSE_IN.code -> "ورود انبار"; ScanMode.DAILY_OUT.code -> "خروج روزانه"; else -> c.getString(1)
                }
                rows.add(arrayOf(c.getString(0) ?: "", typeFa, c.getString(2) ?: ""))
            }
        }
        return rows
    }
}

class MainActivity : Activity() {
    private lateinit var db: ScannerDb
    private lateinit var prefs: SharedPreferences
    private var currentUser: User? = null
    private var currentMode: ScanMode = ScanMode.WAREHOUSE_IN
    private var tone: ToneGenerator? = null
    private var rootBox: FrameLayout? = null
    private var pendingBakeryId = 1
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun cardBg(color: Int, radius: Float = 16f) = GradientDrawable().apply { setColor(color); cornerRadius = radius * resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ScannerDb(this)
        prefs = getSharedPreferences("scanner_session", MODE_PRIVATE)
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        rootBox = FrameLayout(this); setContentView(rootBox)
        val su = prefs.getString("username", null); val sp = prefs.getString("password", null)
        if (su != null && sp != null) { val u = db.login(su, sp); if (u != null) { currentUser = u; showMain(); return } }
        showLogin()
    }

    private fun showLogin() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(40), dp(28), dp(28))
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL; setBackgroundColor(0xFF0F172A.toInt()); gravity = Gravity.CENTER_HORIZONTAL
        }
        try {
            box.addView(ImageView(this).apply { setImageResource(R.drawable.scanner_logo); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { bottomMargin = dp(16); gravity = Gravity.CENTER })
        } catch (_: Exception) {}
        box.addView(TextView(this).apply { text = "سامانه سهمیه آرد"; textSize = 24f; setTextColor(0xFFF8FAFC.toInt()); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(6)) })
        box.addView(TextView(this).apply { text = "ورود امن به پنل نقش‌محور"; textSize = 13f; setTextColor(0xFF94A3B8.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(28)) })
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = cardBg(0xFF1E293B.toInt(), 20f); setPadding(dp(20), dp(22), dp(20), dp(22)) }
        val userEt = EditText(this).apply { hint = "نام کاربری"; setSingleLine(true); setTextColor(0xFFF1F5F9.toInt()); setHintTextColor(0xFF64748B.toInt()); setPadding(dp(14), dp(16), dp(14), dp(16)); background = cardBg(0xFF0F172A.toInt(), 12f) }
        val passEt = EditText(this).apply { hint = "رمز عبور"; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setTextColor(0xFFF1F5F9.toInt()); setHintTextColor(0xFF64748B.toInt()); setPadding(dp(14), dp(16), dp(14), dp(16)); background = cardBg(0xFF0F172A.toInt(), 12f) }
        card.addView(userEt, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        card.addView(passEt, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        card.addView(Button(this).apply {
            text = "ورود به سامانه"; textSize = 17f; isAllCaps = false; setTextColor(0xFFFFFFFF.toInt()); background = cardBg(0xFF0D9488.toInt(), 14f); setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                val u = db.login(userEt.text.toString(), passEt.text.toString())
                if (u == null) toast("نام کاربری یا رمز اشتباه / حساب غیرفعال")
                else { currentUser = u; prefs.edit().putString("username", u.username).putString("password", passEt.text.toString()).apply(); showMain() }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        box.addView(card, LinearLayout.LayoutParams(-1, -2))
        box.addView(TextView(this).apply { text = "admin / factory / baker"; textSize = 11f; setTextColor(0xFF64748B.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0) })
        rootBox?.removeAllViews(); rootBox?.addView(ScrollView(this).apply { addView(box) }, ViewGroup.LayoutParams(-1, -1))
    }

    private fun showMain() {
        val user = currentUser ?: return
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL; setBackgroundColor(0xFFF1F5F9.toInt()); setPadding(dp(12), dp(10), dp(12), dp(12)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = cardBg(0xFF0F172A.toInt(), 14f); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        header.addView(TextView(this).apply { text = "${user.role.title}\n${user.username}"; textSize = 14f; setTextColor(0xFFF8FAFC.toInt()); typeface = Typeface.DEFAULT_BOLD; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        header.addView(Button(this).apply { text = "خروج"; textSize = 13f; isAllCaps = false; setTextColor(0xFFFFFFFF.toInt()); background = cardBg(0xFFDC2626.toInt(), 10f); setOnClickListener { prefs.edit().clear().apply(); currentUser = null; showLogin() } }, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        when (user.role) {
            Role.ADMIN -> {
                section(root, "مدیریت"); btn(root, "افزودن کاربر", 0xFF059669.toInt()) { addUserDlg() }
                btn(root, "کاربران / فعال‌سازی / ریست", 0xFF2563EB.toInt()) { usersDlg() }
                btn(root, "تغییر رمز خودم", 0xFF78716C.toInt()) { changePass() }
                section(root, "سهمیه و نانوایی")
                btn(root, "افزودن نانوایی", 0xFF475569.toInt()) { addBakeryDlg() }
                btn(root, "تنظیم سهمیه / روز کاری / جبرانی", 0xFFB45309.toInt()) { editQuotaDlg() }
                btn(root, "ثبت مرخصی امروز", 0xFF7C3AED.toInt()) { leaveDlg() }
                btn(root, "گزارش کامل", 0xFF334155.toInt()) { showReport() }
                section(root, "اسکن"); addModes(root, listOf(ScanMode.FACTORY_OUT, ScanMode.WAREHOUSE_IN, ScanMode.DAILY_OUT))
                btn(root, "اسکن QR", 0xFF0D9488.toInt(), 100) { startScan() }
            }
            Role.FACTORY -> {
                section(root, "خروج از کارخانه"); addModes(root, listOf(ScanMode.FACTORY_OUT)); currentMode = ScanMode.FACTORY_OUT
                btn(root, "اسکن خروج کارخانه", 0xFFDC2626.toInt(), 110) { startScan() }
                btn(root, "تغییر رمز خودم", 0xFF78716C.toInt()) { changePass() }
            }
            Role.BAKER -> {
                section(root, "ورود انبار و خروج روزانه"); addModes(root, listOf(ScanMode.WAREHOUSE_IN, ScanMode.DAILY_OUT)); currentMode = ScanMode.WAREHOUSE_IN
                btn(root, "اسکن QR", 0xFF0D9488.toInt(), 110) { startScan() }
                btn(root, "گزارش و سهمیه", 0xFF334155.toInt()) { showReport() }
                btn(root, "تغییر رمز خودم", 0xFF78716C.toInt()) { changePass() }
            }
        }
        showQuotaCards(root)
        rootBox?.removeAllViews()
        rootBox?.addView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)) }, ViewGroup.LayoutParams(-1, -1))
    }

    private fun section(p: LinearLayout, t: String) { p.addView(TextView(this).apply { text = t; textSize = 13f; setTextColor(0xFF64748B.toInt()); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), dp(14), dp(4), dp(6)) }) }
    private fun btn(p: LinearLayout, label: String, color: Int, h: Int = 56, action: () -> Unit) {
        p.addView(Button(this).apply { text = label; textSize = if (h >= 90) 17f else 14f; isAllCaps = false; setTextColor(0xFFFFFFFF.toInt()); background = cardBg(color, 12f); setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, dp(h)).apply { bottomMargin = dp(8) })
    }
    private fun addModes(p: LinearLayout, modes: List<ScanMode>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL }
        modes.forEach { m ->
            row.addView(Button(this).apply { text = m.title; textSize = 11f; isAllCaps = false; setTextColor(0xFFFFFFFF.toInt()); background = cardBg(m.color, 10f); setOnClickListener { currentMode = m; toast("حالت: ${m.title}") } }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(4) })
        }
        p.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
    private fun showQuotaCards(p: LinearLayout) {
        db.bakeries().forEach { b ->
            val q = db.computeDayQuota(b)
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = cardBg(0xFFFFFFFF.toInt(), 16f); setPadding(dp(14), dp(12), dp(14), dp(12)); elevation = 4f }
            card.addView(TextView(this).apply { text = b.name; textSize = 15f; setTextColor(0xFF0F172A.toInt()); typeface = Typeface.DEFAULT_BOLD })
            card.addView(TextView(this).apply {
                text = "سهمیه کل: ${b.monthlyQuota}+${b.compensatory} | پایه روزانه: ${q.baseDaily}\nباقی‌مانده دوره: ${q.remainingBags} | روزهای باقی: ${q.remainingDays}\nسهم منصفانه امروز: ${q.fairShare} | بانک کسری: ${q.remainderBank}\nمجاز امروز (صحیح): ${q.allowedWhole} کیسه | مصرف امروز: ${q.usedToday}"
                textSize = 12f; setTextColor(0xFF334155.toInt()); setPadding(0, dp(6), 0, 0)
            })
            p.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(6) })
        }
    }
    private fun addUserDlg() {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
        val name = EditText(this).apply { hint = "نام کاربری"; setSingleLine(true) }
        val pass = EditText(this).apply { hint = "رمز"; setSingleLine(true) }
        val roleSp = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, listOf("کارخانه", "نانوا")).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) } }
        val bakeries = db.bakeries()
        val bakSp = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, bakeries.map { it.name }.ifEmpty { listOf("-") }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) } }
        lay.addView(name); lay.addView(pass); lay.addView(roleSp); lay.addView(bakSp)
        AlertDialog.Builder(this).setTitle("افزودن کاربر").setView(lay).setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val role = if (roleSp.selectedItemPosition == 0) Role.FACTORY else Role.BAKER
                val bid = if (role == Role.BAKER && bakeries.isNotEmpty()) bakeries[bakSp.selectedItemPosition].id else null
                toast(db.addUser(name.text.toString(), pass.text.toString(), role, bid) ?: "اضافه شد.")
            }.show()
    }
    private fun usersDlg() {
        val users = db.allUsers()
        AlertDialog.Builder(this).setTitle("کاربران").setItems(users.map { "${it.username} (${it.role.title}) — ${if (it.active) "فعال" else "غیرفعال"}" }.toTypedArray()) { _, which ->
            val u = users[which]
            AlertDialog.Builder(this).setTitle(u.username).setItems(arrayOf("ریست رمز ۱۲۳۴", if (u.active) "غیرفعال" else "فعال", "حذف")) { _, i ->
                when (i) {
                    0 -> { db.resetPassword(u.id); toast("رمز: 1234") }
                    1 -> { db.setUserActive(u.id, !u.active); toast("تغییر کرد") }
                    2 -> toast(db.deleteUser(u.id) ?: "حذف شد")
                }
            }.setNegativeButton("بستن", null).show()
        }.setNegativeButton("بستن", null).show()
    }
    private fun changePass() {
        val user = currentUser ?: return
        val et = EditText(this).apply { hint = "رمز جدید"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("تغییر رمز").setView(et).setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val err = db.updateUserPassword(user.id, et.text.toString())
                if (err == null) { prefs.edit().putString("password", et.text.toString()).apply(); toast("رمز تغییر کرد") } else toast(err)
            }.show()
    }
    private fun addBakeryDlg() {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
        val name = EditText(this).apply { hint = "نام نانوایی"; setSingleLine(true) }
        val quota = EditText(this).apply { hint = "سهمیه ماهانه (کیسه)"; inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true) }
        val days = EditText(this).apply { hint = "روز کاری"; setText("13"); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true) }
        lay.addView(name); lay.addView(quota); lay.addView(days)
        AlertDialog.Builder(this).setTitle("افزودن نانوایی").setView(lay).setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ -> toast(db.addBakery(name.text.toString(), quota.text.toString().toIntOrNull() ?: 0, days.text.toString().toIntOrNull() ?: 13) ?: "اضافه شد."); showMain() }.show()
    }
    private fun editQuotaDlg() {
        val bakeries = db.bakeries()
        if (bakeries.isEmpty()) { toast("نانوایی نیست"); return }
        AlertDialog.Builder(this).setTitle("انتخاب نانوایی").setItems(bakeries.map { it.name }.toTypedArray()) { _, which ->
            val b = bakeries[which]
            val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
            val quota = EditText(this).apply { setText(b.monthlyQuota.toString()); hint = "سهمیه ماهانه"; inputType = InputType.TYPE_CLASS_NUMBER }
            val days = EditText(this).apply { setText(b.workingDays.toString()); hint = "روز کاری"; inputType = InputType.TYPE_CLASS_NUMBER }
            val comp = EditText(this).apply { setText(b.compensatory.toString()); hint = "جبرانی/ترمیمی"; inputType = InputType.TYPE_CLASS_NUMBER }
            lay.addView(quota); lay.addView(days); lay.addView(comp)
            AlertDialog.Builder(this).setTitle("سهمیه — ${b.name}").setView(lay).setNegativeButton("انصراف", null)
                .setPositiveButton("ذخیره") { _, _ ->
                    toast(db.updateQuota(b.id, quota.text.toString().toIntOrNull() ?: b.monthlyQuota, days.text.toString().toIntOrNull() ?: b.workingDays, comp.text.toString().toIntOrNull() ?: 0) ?: "ذخیره شد — در همه پنل‌ها اعمال می‌شود.")
                    showMain()
                }.show()
        }.setNegativeButton("انصراف", null).show()
    }
    private fun leaveDlg() {
        val bakeries = db.bakeries()
        if (bakeries.isEmpty()) return
        AlertDialog.Builder(this).setTitle("مرخصی/تعطیل امروز").setItems(bakeries.map { it.name }.toTypedArray()) { _, i ->
            val b = bakeries[i]; val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()); val on = db.isLeave(b.id, day)
            AlertDialog.Builder(this).setTitle(b.name)
                .setMessage(if (on) "امروز مرخصی است. برداشتن؟" else "ثبت مرخصی امروز؟ (سهمیه بین روزهای دیگر تقسیم می‌شود)")
                .setPositiveButton(if (on) "برداشتن" else "ثبت مرخصی") { _, _ -> db.setLeave(b.id, day, !on); toast("ثبت شد"); showMain() }
                .setNegativeButton("انصراف", null).show()
        }.setNegativeButton("بستن", null).show()
    }
    private fun showReport() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        scroll.addView(box)
        db.bakeries().forEach { b ->
            val q = db.computeDayQuota(b)
            box.addView(TextView(this).apply {
                text = "— ${b.name} —\nپایه: ${q.baseDaily} | شناور امروز: ${q.fairShare}\nمجاز صحیح: ${q.allowedWhole} | بانک کسری: ${q.remainderBank}\nکارخانه: ${db.countAll(b.id, ScanMode.FACTORY_OUT.code)} | ورود: ${db.countAll(b.id, ScanMode.WAREHOUSE_IN.code)} | خروج: ${db.countAll(b.id, ScanMode.DAILY_OUT.code)}\nموجودی: ${db.discrepancy(b.id)}"
                textSize = 13f; setPadding(0, dp(8), 0, dp(4))
            })
            db.recentScans(b.id, 10).forEach { r -> box.addView(TextView(this).apply { text = "  ${r[1]} | ${r[0].take(14)} | ${r[2]}"; textSize = 11f }) }
        }
        AlertDialog.Builder(this).setTitle("گزارش سهمیه").setView(scroll).setPositiveButton("بستن", null).show()
    }
    private fun startScan() {
        val user = currentUser ?: return
        val bakeries = db.bakeries()
        if (bakeries.isEmpty()) { toast("نانوایی نیست"); return }
        val bakeryId = when {
            user.role == Role.BAKER && user.bakeryId != null -> user.bakeryId
            bakeries.size == 1 -> bakeries[0].id
            else -> null
        }
        if (bakeryId != null) launchScan(bakeryId)
        else AlertDialog.Builder(this).setTitle("انتخاب نانوایی").setItems(bakeries.map { it.name }.toTypedArray()) { _, i -> launchScan(bakeries[i].id) }.setNegativeButton("انصراف", null).show()
    }
    private fun launchScan(bakeryId: Int) {
        pendingBakeryId = bakeryId
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE, IntentIntegrator.CODE_128, IntentIntegrator.CODE_39)
        integrator.setPrompt(currentMode.title)
        integrator.setCameraId(0); integrator.setBeepEnabled(false); integrator.setOrientationLocked(true)
        integrator.setCaptureActivity(ScannerCaptureActivity::class.java)
        integrator.initiateScan()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                val user = currentUser ?: return
                val (ok, msg) = db.recordScan(pendingBakeryId, result.contents, currentMode, user.id)
                if (ok) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150) else tone?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                toast(msg); showMain()
            }
        } else super.onActivityResult(requestCode, resultCode, data)
    }
    override fun onDestroy() { tone?.release(); tone = null; super.onDestroy() }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
