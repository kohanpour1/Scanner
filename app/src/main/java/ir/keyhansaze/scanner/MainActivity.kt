package ir.keyhansaze.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator
import java.io.OutputStreamWriter
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
data class DayQuota(
    val baseDaily: Double, val remainingBags: Double, val remainingDays: Int,
    val fairShare: Double, val allowedWhole: Int, val remainderBank: Double,
    val usedToday: Int, val usedPeriod: Int
)

class ScannerDb(ctx: Context) : SQLiteOpenHelper(ctx, "scanner_quota_v5.db", null, 5) {
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
        db.execSQL("DROP TABLE IF EXISTS scans"); db.execSQL("DROP TABLE IF EXISTS users"); db.execSQL("DROP TABLE IF EXISTS bakeries")
        onCreate(db)
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
            val v = ContentValues().apply {
                put("username", username.trim()); put("password", password); put("role", role.code); put("active", 1)
                if (bakeryId != null) put("bakery_id", bakeryId) else putNull("bakery_id")
            }
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
            val v = ContentValues().apply {
                put("name", name.trim()); put("monthly_quota", quota); put("working_days", days.coerceIn(1, 31))
                put("compensatory", 0); put("period_start", today())
            }
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
        val daysWithOut = readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT substr(scanned_at,1,10)) FROM scans WHERE bakery_id=? AND scan_type=?",
            arrayOf(b.id.toString(), ScanMode.DAILY_OUT.code)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val leaveCount = readableDatabase.rawQuery("SELECT COUNT(*) FROM leave_days WHERE bakery_id=?", arrayOf(b.id.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val effectiveWorking = max(1, b.workingDays - leaveCount)
        val remainingDays = max(1, effectiveWorking - daysWithOut + if (usedToday > 0) 1 else 0)
        val fairShare = remainingBags / remainingDays
        val bank = getRemainderBank(b.id)
        val raw = fairShare + bank
        var allowed = floor(raw + 1e-9).toInt()
        val floorFair = floor(fairShare + 1e-9).toInt()
        val frac = raw - floor(raw)
        if (frac < 0.95 && allowed > floorFair && bank < 0.95) allowed = floorFair
        allowed = max(0, min(allowed, floor(remainingBags + 1e-9).toInt()))
        return DayQuota(base, remainingBags, remainingDays, fairShare, allowed, bank, usedToday, usedPeriod)
    }

    fun recordScan(bakeryId: Int, qr: String, mode: ScanMode, userId: Int): Pair<Boolean, String> {
        val code = qr.trim()
        if (code.isEmpty()) return false to "کد خالی است."
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
                    val fs = String.format("%.2f", q.fairShare)
                    val bk = String.format("%.2f", q.remainderBank)
                    return false to "سقف امروز ${q.allowedWhole} کیسه (شناور $fs + بانک $bk). از خروج بیشتر جلوگیری شد."
                }
            }
        }
        var preQ: DayQuota? = null
        if (mode == ScanMode.DAILY_OUT) {
            val bakery = bakeries().find { it.id == bakeryId }
            if (bakery != null) preQ = computeDayQuota(bakery)
        }
        return try {
            val v = ContentValues().apply {
                put("qr_code", code); put("bakery_id", bakeryId); put("scan_type", mode.code)
                put("scanned_by", userId); put("scanned_at", now()); put("synced", 0)
            }
            writableDatabase.insertOrThrow("scans", null, v)
            if (mode == ScanMode.DAILY_OUT && preQ != null) {
                var newBank = preQ.remainderBank + preQ.fairShare - 1.0
                if (newBank > 2.5) newBank = 2.5
                if (newBank < -1.0) newBank = -1.0
                setRemainderBank(bakeryId, newBank)
            }
            true to "${mode.title} ثبت شد."
        } catch (e: Exception) { false to "خطا: ${e.message}" }
    }

    fun discrepancy(bakeryId: Int) = countAll(bakeryId, ScanMode.WAREHOUSE_IN.code) - countAll(bakeryId, ScanMode.DAILY_OUT.code)

    fun recentScans(bakeryId: Int?, limit: Int = 50): List<Array<String>> {
        val rows = mutableListOf<Array<String>>()
        val sql = if (bakeryId != null)
            "SELECT qr_code, scan_type, scanned_at, bakery_id FROM scans WHERE bakery_id=? ORDER BY id DESC LIMIT ?"
        else
            "SELECT qr_code, scan_type, scanned_at, bakery_id FROM scans ORDER BY id DESC LIMIT ?"
        val args = if (bakeryId != null) arrayOf(bakeryId.toString(), limit.toString()) else arrayOf(limit.toString())
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val typeFa = when (c.getString(1)) {
                    ScanMode.FACTORY_OUT.code -> "خروج کارخانه"
                    ScanMode.WAREHOUSE_IN.code -> "ورود انبار"
                    ScanMode.DAILY_OUT.code -> "خروج روزانه"
                    else -> c.getString(1)
                }
                rows.add(arrayOf(c.getString(0) ?: "", typeFa, c.getString(2) ?: "", c.getInt(3).toString()))
            }
        }
        return rows
    }

    fun allScansForExport(bakeryId: Int?): List<Array<String>> {
        val rows = mutableListOf<Array<String>>()
        val sql = if (bakeryId != null)
            "SELECT qr_code, scan_type, scanned_at, bakery_id, scanned_by FROM scans WHERE bakery_id=? ORDER BY id"
        else
            "SELECT qr_code, scan_type, scanned_at, bakery_id, scanned_by FROM scans ORDER BY id"
        val args = if (bakeryId != null) arrayOf(bakeryId.toString()) else null
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                rows.add(arrayOf(
                    c.getString(0) ?: "",
                    c.getString(1) ?: "",
                    c.getString(2) ?: "",
                    c.getInt(3).toString(),
                    c.getInt(4).toString()
                ))
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
    private fun cardBg(color: Int, radius: Float = 16f) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * resources.displayMetrics.density
    }
    private fun bagsToKg(bags: Number) = bags.toDouble() * 40.0
    private fun fmt(d: Double) = String.format(Locale.US, "%.2f", d)
    private fun fmt1(d: Double) = String.format(Locale.US, "%.1f", d)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ScannerDb(this)
        prefs = getSharedPreferences("scanner_session", MODE_PRIVATE)
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        rootBox = FrameLayout(this)
        setContentView(rootBox)
        val su = prefs.getString("username", null)
        val sp = prefs.getString("password", null)
        if (su != null && sp != null) {
            val u = db.login(su, sp)
            if (u != null) { currentUser = u; showMain(); return }
        }
        showLogin()
    }

    private fun showLogin() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(28))
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFF0F172A.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        try {
            box.addView(ImageView(this).apply {
                setImageResource(R.drawable.scanner_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { bottomMargin = dp(12); gravity = Gravity.CENTER })
        } catch (_: Exception) {}
        box.addView(TextView(this).apply {
            text = "سامانه سهمیه آرد"; textSize = 24f; setTextColor(0xFFF8FAFC.toInt())
            gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "ورود امن به پنل نقش‌محور"; textSize = 13f; setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(24))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg(0xFF1E293B.toInt(), 20f)
            setPadding(dp(20), dp(22), dp(20), dp(22))
        }
        val userEt = EditText(this).apply {
            hint = "نام کاربری"; setSingleLine(true); setTextColor(0xFFF1F5F9.toInt())
            setHintTextColor(0xFF64748B.toInt()); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBg(0xFF0F172A.toInt(), 12f)
        }
        val passEt = EditText(this).apply {
            hint = "رمز عبور"; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFFF1F5F9.toInt()); setHintTextColor(0xFF64748B.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14)); background = cardBg(0xFF0F172A.toInt(), 12f)
        }
        card.addView(userEt, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        card.addView(passEt, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        card.addView(Button(this).apply {
            text = "ورود به سامانه"; textSize = 17f; isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt()); background = cardBg(0xFF0D9488.toInt(), 14f)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                val u = db.login(userEt.text.toString(), passEt.text.toString())
                if (u == null) toast("نام کاربری یا رمز اشتباه / حساب غیرفعال")
                else {
                    currentUser = u
                    prefs.edit().putString("username", u.username).putString("password", passEt.text.toString()).apply()
                    showMain()
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        box.addView(card, LinearLayout.LayoutParams(-1, -2))
        box.addView(TextView(this).apply {
            text = "admin / factory / baker"; textSize = 11f; setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0)
        })
        rootBox?.removeAllViews()
        rootBox?.addView(ScrollView(this).apply { addView(box) }, ViewGroup.LayoutParams(-1, -1))
    }

    private fun showMain() {
        val user = currentUser ?: run { showLogin(); return }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF1F5F9.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        header.addView(TextView(this).apply {
            text = "${user.role.title} — ${user.username}"; textSize = 16f
            setTextColor(0xFF0F172A.toInt()); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(Button(this).apply {
            text = "خروج"; textSize = 13f; isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt()); background = cardBg(0xFFDC2626.toInt(), 10f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { logout() }
        })
        root.addView(header)

        val hostRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hostVal = prefs.getString("server_host", "") ?: ""
        hostRow.addView(TextView(this).apply {
            text = if (hostVal.isEmpty()) "سرور: آفلاین (تنظیم نشده)" else "سرور: $hostVal"
            textSize = 12f; setTextColor(0xFF64748B.toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        hostRow.addView(Button(this).apply {
            text = "هاست"; textSize = 12f; isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt()); background = cardBg(0xFF475569.toInt(), 8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { setHostDialog() }
        })
        root.addView(hostRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        when (user.role) {
            Role.ADMIN -> {
                section(root, "مدیریت کاربران")
                btn(root, "افزودن کاربر", 0xFF059669.toInt()) { addUserDialog() }
                btn(root, "لیست کاربران / فعال / ریست", 0xFF2563EB.toInt()) { usersDialog() }
                btn(root, "تغییر رمز خودم", 0xFF78716C.toInt()) { changePassDialog() }
                section(root, "نانوایی و سهمیه")
                btn(root, "افزودن نانوایی", 0xFF475569.toInt()) { addBakeryDialog() }
                btn(root, "تنظیم سهمیه / روز کاری / جبرانی", 0xFFB45309.toInt()) { editQuotaDialog() }
                btn(root, "ثبت مرخصی امروز", 0xFF7C3AED.toInt()) { leaveDialog() }
                section(root, "اسکن همه مراحل")
                addModes(root, listOf(ScanMode.FACTORY_OUT, ScanMode.WAREHOUSE_IN, ScanMode.DAILY_OUT))
                btn(root, "اسکن بارکد / QR", 0xFF0D9488.toInt(), 96) { startScan() }
                section(root, "گزارش و خروجی")
                btn(root, "گزارش کامل همه نقش‌ها", 0xFF334155.toInt()) { showFullReport() }
                btn(root, "خروجی Excel (CSV)", 0xFF15803D.toInt()) { exportCsv(null) }
                btn(root, "خروجی PDF", 0xFFB91C1C.toInt()) { exportPdf(null) }
            }
            Role.FACTORY -> {
                section(root, "خروج از کارخانه")
                addModes(root, listOf(ScanMode.FACTORY_OUT))
                currentMode = ScanMode.FACTORY_OUT
                btn(root, "اسکن خروج کارخانه", 0xFFDC2626.toInt(), 100) { startScan() }
                section(root, "گزارش")
                btn(root, "گزارش اسکن‌های کارخانه", 0xFF334155.toInt()) { showRoleReport() }
                btn(root, "خروجی Excel", 0xFF15803D.toInt()) { exportCsv(null) }
                btn(root, "خروجی PDF", 0xFFB91C1C.toInt()) { exportPdf(null) }
                btn(root, "تغییر رمز", 0xFF78716C.toInt()) { changePassDialog() }
            }
            Role.BAKER -> {
                section(root, "ورود انبار و خروج روزانه")
                addModes(root, listOf(ScanMode.WAREHOUSE_IN, ScanMode.DAILY_OUT))
                currentMode = ScanMode.WAREHOUSE_IN
                btn(root, "اسکن بارکد / QR", 0xFF0D9488.toInt(), 100) { startScan() }
                section(root, "گزارش و سهمیه")
                btn(root, "گزارش و وضعیت سهمیه", 0xFF334155.toInt()) { showBakerReport() }
                btn(root, "خروجی Excel", 0xFF15803D.toInt()) { exportCsv(user.bakeryId) }
                btn(root, "خروجی PDF", 0xFFB91C1C.toInt()) { exportPdf(user.bakeryId) }
                btn(root, "تغییر رمز", 0xFF78716C.toInt()) { changePassDialog() }
            }
        }
        showQuotaCards(root)
        rootBox?.removeAllViews()
        rootBox?.addView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)) }, ViewGroup.LayoutParams(-1, -1))
    }

    private fun section(p: LinearLayout, t: String) {
        p.addView(TextView(this).apply {
            text = t; textSize = 13f; setTextColor(0xFF64748B.toInt()); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(14), dp(4), dp(6))
        })
    }
    private fun btn(p: LinearLayout, label: String, color: Int, h: Int = 52, action: () -> Unit) {
        p.addView(Button(this).apply {
            text = label; textSize = if (h >= 90) 16f else 14f; isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt()); background = cardBg(color, 12f)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, dp(h)).apply { bottomMargin = dp(8) })
    }
    private fun addModes(p: LinearLayout, modes: List<ScanMode>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes.forEach { m ->
            row.addView(Button(this).apply {
                text = m.title; textSize = 12f; isAllCaps = false
                setTextColor(0xFFFFFFFF.toInt()); background = cardBg(m.color, 10f)
                setOnClickListener {
                    currentMode = m
                    toast("حالت: ${m.title}")
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        }
        p.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun showQuotaCards(p: LinearLayout) {
        val user = currentUser ?: return
        val list = if (user.role == Role.BAKER && user.bakeryId != null)
            db.bakeries().filter { it.id == user.bakeryId }
        else db.bakeries()
        list.forEach { b ->
            val q = db.computeDayQuota(b)
            val totalBags = b.monthlyQuota + b.compensatory
            val totalKg = bagsToKg(totalBags)
            val remKg = bagsToKg(q.remainingBags)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = cardBg(0xFFFFFFFF.toInt(), 14f)
                setPadding(dp(14), dp(12), dp(14), dp(12)); elevation = 4f
            }
            card.addView(TextView(this).apply {
                text = b.name; textSize = 15f; setTextColor(0xFF0F172A.toInt()); typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = "سهمیه کل: $totalBags کیسه (${fmt1(totalKg)} کیلوگرم)\n" +
                        "باقی‌مانده دوره: ${fmt1(q.remainingBags)} کیسه (${fmt1(remKg)} kg)\n" +
                        "حد روزانه (کیسه): ${q.allowedWhole}  |  شناور: ${fmt(q.fairShare)}\n" +
                        "مصرف امروز: ${q.usedToday}  |  بانک کسری: ${fmt(q.remainderBank)}\n" +
                        "کارخانه: ${db.countAll(b.id, ScanMode.FACTORY_OUT.code)} | ورود: ${db.countAll(b.id, ScanMode.WAREHOUSE_IN.code)} | خروج: ${db.countAll(b.id, ScanMode.DAILY_OUT.code)}\n" +
                        "موجودی تقریبی: ${db.discrepancy(b.id)} کیسه"
                textSize = 12.5f; setTextColor(0xFF334155.toInt()); setPadding(0, dp(6), 0, 0)
            })
            p.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun logout() {
        currentUser = null
        prefs.edit().remove("username").remove("password").apply()
        showLogin()
        toast("از سامانه خارج شدید")
    }

    private fun setHostDialog() {
        val et = EditText(this).apply {
            hint = "مثال: http://vm-270230:8080 یا IP"
            setText(prefs.getString("server_host", "") ?: "")
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("آدرس هاست / سرور (آنلاین)")
            .setMessage("با تنظیم هاست، در نسخه‌های بعدی همگام‌سازی بین گوشی‌ها فعال می‌شود. الان آفلاین کار می‌کند.")
            .setView(et)
            .setPositiveButton("ذخیره") { _, _ ->
                val h = et.text.toString().trim()
                prefs.edit().putString("server_host", h).apply()
                toast(if (h.isEmpty()) "حالت آفلاین" else "هاست ذخیره شد: $h")
                showMain()
            }
            .setNegativeButton("انصراف", null).show()
    }

    private fun addUserDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val u = EditText(this).apply { hint = "نام کاربری"; setSingleLine(true) }
        val p = EditText(this).apply { hint = "رمز"; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val roleSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("مدیر", "کارخانه", "نانوا"))
        }
        box.addView(u); box.addView(p); box.addView(roleSp)
        AlertDialog.Builder(this).setTitle("افزودن کاربر").setView(box)
            .setPositiveButton("ثبت") { _, _ ->
                val role = when (roleSp.selectedItemPosition) { 0 -> Role.ADMIN; 1 -> Role.FACTORY; else -> Role.BAKER }
                val bid = if (role == Role.BAKER) db.bakeries().firstOrNull()?.id else null
                val err = db.addUser(u.text.toString(), p.text.toString(), role, bid)
                toast(err ?: "کاربر اضافه شد")
                showMain()
            }.setNegativeButton("انصراف", null).show()
    }

    private fun usersDialog() {
        val users = db.allUsers()
        val items = users.map { "${it.username} (${it.role.title}) — ${if (it.active) "فعال" else "غیرفعال"}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("کاربران")
            .setItems(items) { _, which ->
                val u = users[which]
                val acts = arrayOf("فعال/غیرفعال", "ریست رمز به 1234", "حذف")
                AlertDialog.Builder(this).setTitle(u.username).setItems(acts) { _, a ->
                    when (a) {
                        0 -> { db.setUserActive(u.id, !u.active); toast("وضعیت تغییر کرد"); showMain() }
                        1 -> { db.resetPassword(u.id); toast("رمز به 1234 تغییر کرد") }
                        2 -> {
                            val err = db.deleteUser(u.id)
                            toast(err ?: "حذف شد"); showMain()
                        }
                    }
                }.show()
            }.setNegativeButton("بستن", null).show()
    }

    private fun changePassDialog() {
        val user = currentUser ?: return
        val et = EditText(this).apply { hint = "رمز جدید"; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("تغییر رمز").setView(et)
            .setPositiveButton("ذخیره") { _, _ ->
                val err = db.updateUserPassword(user.id, et.text.toString())
                if (err == null) prefs.edit().putString("password", et.text.toString()).apply()
                toast(err ?: "رمز تغییر کرد")
            }.setNegativeButton("انصراف", null).show()
    }

    private fun addBakeryDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val name = EditText(this).apply { hint = "نام نانوایی"; setSingleLine(true) }
        val quota = EditText(this).apply { hint = "سهمیه ماهانه (کیسه)"; setSingleLine(true); inputType = InputType.TYPE_CLASS_NUMBER; setText("30") }
        val days = EditText(this).apply { hint = "روز کاری"; setSingleLine(true); inputType = InputType.TYPE_CLASS_NUMBER; setText("13") }
        box.addView(name); box.addView(quota); box.addView(days)
        AlertDialog.Builder(this).setTitle("افزودن نانوایی").setView(box)
            .setPositiveButton("ثبت") { _, _ ->
                val err = db.addBakery(name.text.toString(), quota.text.toString().toIntOrNull() ?: 30, days.text.toString().toIntOrNull() ?: 13)
                toast(err ?: "نانوایی اضافه شد"); showMain()
            }.setNegativeButton("انصراف", null).show()
    }

    private fun editQuotaDialog() {
        val bakeries = db.bakeries()
        if (bakeries.isEmpty()) { toast("نانوایی نیست"); return }
        AlertDialog.Builder(this).setTitle("انتخاب نانوایی")
            .setItems(bakeries.map { it.name }.toTypedArray()) { _, i ->
                val b = bakeries[i]
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
                val m = EditText(this).apply { setText(b.monthlyQuota.toString()); hint = "سهمیه ماهانه (کیسه)"; inputType = InputType.TYPE_CLASS_NUMBER }
                val d = EditText(this).apply { setText(b.workingDays.toString()); hint = "روز کاری"; inputType = InputType.TYPE_CLASS_NUMBER }
                val c = EditText(this).apply { setText(b.compensatory.toString()); hint = "جبرانی (کیسه)"; inputType = InputType.TYPE_CLASS_NUMBER }
                box.addView(m); box.addView(d); box.addView(c)
                AlertDialog.Builder(this).setTitle("سهمیه ${b.name}").setView(box)
                    .setPositiveButton("ذخیره") { _, _ ->
                        val err = db.updateQuota(b.id, m.text.toString().toIntOrNull() ?: b.monthlyQuota,
                            d.text.toString().toIntOrNull() ?: b.workingDays, c.text.toString().toIntOrNull() ?: 0)
                        toast(err ?: "ذخیره شد — در همه پنل‌ها اعمال می‌شود"); showMain()
                    }.setNegativeButton("انصراف", null).show()
            }.show()
    }

    private fun leaveDialog() {
        val bakeries = db.bakeries()
        if (bakeries.isEmpty()) { toast("نانوایی نیست"); return }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        AlertDialog.Builder(this).setTitle("مرخصی امروز ($today)")
            .setItems(bakeries.map { it.name }.toTypedArray()) { _, i ->
                val b = bakeries[i]
                val on = db.isLeave(b.id, today)
                AlertDialog.Builder(this)
                    .setMessage(if (on) "مرخصی امروز برای ${b.name} ثبت است. حذف شود؟" else "ثبت مرخصی امروز برای ${b.name}؟")
                    .setPositiveButton(if (on) "حذف" else "ثبت") { _, _ ->
                        db.setLeave(b.id, today, !on); toast("انجام شد"); showMain()
                    }.setNegativeButton("انصراف", null).show()
            }.show()
    }

    private fun showFullReport() {
        val sb = StringBuilder()
        db.bakeries().forEach { b ->
            val q = db.computeDayQuota(b)
            val total = b.monthlyQuota + b.compensatory
            sb.append("—— ${b.name} ——\n")
            sb.append("سهمیه: $total کیسه (${fmt1(bagsToKg(total))} kg)\n")
            sb.append("باقی: ${fmt1(q.remainingBags)} کیسه | مجاز امروز: ${q.allowedWhole} کیسه\n")
            sb.append("کارخانه: ${db.countAll(b.id, ScanMode.FACTORY_OUT.code)} | ورود: ${db.countAll(b.id, ScanMode.WAREHOUSE_IN.code)} | خروج: ${db.countAll(b.id, ScanMode.DAILY_OUT.code)}\n")
            sb.append("موجودی: ${db.discrepancy(b.id)}\n\n")
        }
        sb.append("آخرین اسکن‌ها:\n")
        db.recentScans(null, 30).forEach { r -> sb.append("${r[2]} | ${r[1]} | ${r[0]}\n") }
        AlertDialog.Builder(this).setTitle("گزارش کامل مدیر").setMessage(sb.toString())
            .setPositiveButton("بستن", null).show()
    }

    private fun showBakerReport() {
        val user = currentUser ?: return
        val bid = user.bakeryId ?: db.bakeries().firstOrNull()?.id ?: return
        val b = db.bakeries().find { it.id == bid } ?: return
        val q = db.computeDayQuota(b)
        val total = b.monthlyQuota + b.compensatory
        val msg = "نانوایی: ${b.name}\n" +
                "سهمیه کل: $total کیسه (${fmt1(bagsToKg(total))} kg)\n" +
                "باقی‌مانده: ${fmt1(q.remainingBags)} کیسه (${fmt1(bagsToKg(q.remainingBags))} kg)\n" +
                "حد روزانه: ${q.allowedWhole} کیسه (شناور ${fmt(q.fairShare)})\n" +
                "مصرف امروز: ${q.usedToday} | بانک کسری: ${fmt(q.remainderBank)}\n" +
                "موجودی: ${db.discrepancy(bid)} کیسه"
        AlertDialog.Builder(this).setTitle("وضعیت سهمیه").setMessage(msg)
            .setPositiveButton("بستن", null).show()
    }

    private fun showRoleReport() {
        val sb = StringBuilder("آخرین اسکن‌های ثبت‌شده:\n\n")
        db.recentScans(null, 40).forEach { r -> sb.append("${r[2]}\n${r[1]} — ${r[0]}\n\n") }
        AlertDialog.Builder(this).setTitle("گزارش").setMessage(sb.toString())
            .setPositiveButton("بستن", null).show()
    }

    private fun exportCsv(bakeryId: Int?) {
        try {
            val rows = db.allScansForExport(bakeryId)
            val name = "quota_export_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.csv"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: throw Exception("نمی‌توان فایل ساخت")
            contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                    w.write("\uFEFFqr_code,scan_type,scanned_at,bakery_id,scanned_by\n")
                    rows.forEach { r -> w.write("${r[0]},${r[1]},${r[2]},${r[3]},${r[4]}\n") }
                }
            }
            toast("Excel/CSV در Downloads ذخیره شد: $name")
        } catch (e: Exception) {
            toast("خطا در خروجی: ${e.message}")
        }
    }

    private fun exportPdf(bakeryId: Int?) {
        try {
            val rows = db.allScansForExport(bakeryId)
            val doc = PdfDocument()
            val paint = Paint().apply { textSize = 10f }
            val titlePaint = Paint().apply { textSize = 14f; isFakeBoldText = true }
            var pageNum = 1
            var y = 40f
            var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNum).create())
            var canvas = page.canvas
            canvas.drawText("گزارش سهمیه آرد", 40f, y, titlePaint); y += 24f
            canvas.drawText(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()), 40f, y, paint); y += 20f
            rows.forEach { r ->
                if (y > 800) {
                    doc.finishPage(page)
                    pageNum++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNum).create())
                    canvas = page.canvas; y = 40f
                }
                canvas.drawText("${r[2]} | ${r[1]} | ${r[0]}", 40f, y, paint)
                y += 14f
            }
            doc.finishPage(page)
            val name = "quota_report_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.pdf"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: throw Exception("نمی‌توان PDF ساخت")
            contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            doc.close()
            toast("PDF در Downloads ذخیره شد: $name")
        } catch (e: Exception) {
            toast("خطا در PDF: ${e.message}")
        }
    }

    private fun startScan() {
        val user = currentUser ?: return
        val bakeries = db.bakeries()
        if (bakeries.isEmpty()) { toast("نانوایی تعریف نشده"); return }
        val bakeryId = when {
            user.role == Role.BAKER && user.bakeryId != null -> user.bakeryId
            bakeries.size == 1 -> bakeries[0].id
            else -> null
        }
        if (bakeryId != null) launchScan(bakeryId)
        else AlertDialog.Builder(this).setTitle("انتخاب نانوایی")
            .setItems(bakeries.map { it.name }.toTypedArray()) { _, i -> launchScan(bakeries[i].id) }
            .setNegativeButton("انصراف", null).show()
    }

    private fun launchScan(bakeryId: Int) {
        pendingBakeryId = bakeryId
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(
            IntentIntegrator.QR_CODE,
            IntentIntegrator.CODE_128,
            IntentIntegrator.CODE_39,
            IntentIntegrator.CODE_93,
            IntentIntegrator.EAN_13,
            IntentIntegrator.EAN_8,
            IntentIntegrator.UPC_A,
            IntentIntegrator.UPC_E,
            IntentIntegrator.ITF,
            IntentIntegrator.CODABAR,
            IntentIntegrator.DATA_MATRIX,
            IntentIntegrator.PDF_417,
            IntentIntegrator.AZTEC
        )
        integrator.setPrompt("${currentMode.title} — بارکد یا QR را اسکن کنید")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(true)
        integrator.setCaptureActivity(ScannerCaptureActivity::class.java)
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                val user = currentUser ?: return
                val (ok, msg) = db.recordScan(pendingBakeryId, result.contents, currentMode, user.id)
                if (ok) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                else tone?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                toast(msg)
                showMain()
            }
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        tone?.release(); tone = null; super.onDestroy()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
