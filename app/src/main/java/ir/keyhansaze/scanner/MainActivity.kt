package ir.keyhansaze.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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

enum class Role(val code: String, val title: String) {
    ADMIN("ADMIN", "مدیر سامانه"),
    FACTORY("FACTORY", "کارخانه"),
    BAKER("BAKER", "نانوا")
}

enum class ScanMode(val code: String, val title: String, val color: Int) {
    FACTORY_OUT("FACTORY_OUT", "خروج از کارخانه", 0xFFC62828.toInt()),
    WAREHOUSE_IN("WAREHOUSE_IN", "ورود به انبار", 0xFF2E7D32.toInt()),
    DAILY_OUT("DAILY_OUT", "خروج روزانه", 0xFF1565C0.toInt())
}

data class User(
    val id: Int,
    val username: String,
    val password: String,
    val role: Role,
    val active: Boolean,
    val bakeryId: Int?
)

data class Bakery(
    val id: Int,
    val name: String,
    val monthlyQuota: Int,
    val workingDays: Int,
    val compensatory: Int
)

class ScannerDb(context: Context) : SQLiteOpenHelper(context, "scanner_quota_v3.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE users(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE,
            password TEXT NOT NULL,
            role TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 1,
            bakery_id INTEGER
        )""")
        db.execSQL("""CREATE TABLE bakeries(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            monthly_quota INTEGER NOT NULL DEFAULT 30,
            working_days INTEGER NOT NULL DEFAULT 13,
            compensatory INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE scans(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            qr_code TEXT NOT NULL,
            bakery_id INTEGER NOT NULL,
            scan_type TEXT NOT NULL,
            scanned_by INTEGER,
            scanned_at TEXT NOT NULL,
            synced INTEGER NOT NULL DEFAULT 0,
            UNIQUE(qr_code, scan_type)
        )""")
        db.execSQL("CREATE INDEX idx_scans_qr ON scans(qr_code)")
        db.execSQL("INSERT INTO bakeries(name,monthly_quota,working_days,compensatory) VALUES('نانوایی پیش‌فرض',30,13,0)")
        db.execSQL("INSERT INTO users(username,password,role,active,bakery_id) VALUES('admin','admin','ADMIN',1,NULL)")
        db.execSQL("INSERT INTO users(username,password,role,active,bakery_id) VALUES('factory','factory','FACTORY',1,NULL)")
        db.execSQL("INSERT INTO users(username,password,role,active,bakery_id) VALUES('baker','baker','BAKER',1,1)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS scans")
        db.execSQL("DROP TABLE IF EXISTS users")
        db.execSQL("DROP TABLE IF EXISTS bakeries")
        onCreate(db)
    }

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun login(username: String, password: String): User? {
        readableDatabase.rawQuery(
            "SELECT id,username,password,role,active,bakery_id FROM users WHERE username=? AND password=?",
            arrayOf(username.trim(), password)
        ).use { c ->
            if (!c.moveToFirst()) return null
            if (c.getInt(4) == 0) return null
            val role = when (c.getString(3)) {
                "ADMIN" -> Role.ADMIN
                "FACTORY" -> Role.FACTORY
                else -> Role.BAKER
            }
            val bakeryId = if (c.isNull(5)) null else c.getInt(5)
            return User(c.getInt(0), c.getString(1), c.getString(2), role, true, bakeryId)
        }
    }

    fun allUsers(): List<User> {
        val list = mutableListOf<User>()
        readableDatabase.rawQuery("SELECT id,username,password,role,active,bakery_id FROM users ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val role = when (c.getString(3)) {
                    "ADMIN" -> Role.ADMIN
                    "FACTORY" -> Role.FACTORY
                    else -> Role.BAKER
                }
                list.add(User(c.getInt(0), c.getString(1), c.getString(2), role, c.getInt(4)==1, if (c.isNull(5)) null else c.getInt(5)))
            }
        }
        return list
    }

    fun addUser(username: String, password: String, role: Role, bakeryId: Int? = null): String? {
        val u = username.trim()
        if (u.isEmpty() || password.isEmpty()) return "نام کاربری و رمز الزامی است."
        return try {
            val v = ContentValues().apply {
                put("username", u)
                put("password", password)
                put("role", role.code)
                put("active", 1)
                if (bakeryId != null) put("bakery_id", bakeryId) else putNull("bakery_id")
            }
            writableDatabase.insertOrThrow("users", null, v)
            null
        } catch (_: Exception) { "این نام کاربری قبلاً وجود دارد." }
    }

    fun updateUserPassword(userId: Int, newPass: String): String? {
        if (newPass.isEmpty()) return "رمز خالی مجاز نیست."
        writableDatabase.execSQL("UPDATE users SET password=? WHERE id=?", arrayOf(newPass, userId))
        return null
    }

    fun updateUsername(userId: Int, newName: String): String? {
        val n = newName.trim()
        if (n.isEmpty()) return "نام خالی مجاز نیست."
        return try {
            writableDatabase.execSQL("UPDATE users SET username=? WHERE id=?", arrayOf(n, userId))
            null
        } catch (_: Exception) { "نام تکراری است." }
    }

    fun setUserActive(userId: Int, active: Boolean) {
        writableDatabase.execSQL("UPDATE users SET active=? WHERE id=?", arrayOf(if (active) 1 else 0, userId))
    }

    fun deleteUser(userId: Int): String? {
        val u = allUsers().find { it.id == userId } ?: return "کاربر یافت نشد."
        if (u.role == Role.ADMIN) return "حذف مدیر سامانه مجاز نیست."
        writableDatabase.delete("users", "id=?", arrayOf(userId.toString()))
        return null
    }

    fun resetPassword(userId: Int, newPass: String = "1234"): String? {
        writableDatabase.execSQL("UPDATE users SET password=? WHERE id=?", arrayOf(newPass, userId))
        return null
    }

    fun bakeries(): List<Bakery> {
        val list = mutableListOf<Bakery>()
        readableDatabase.rawQuery("SELECT id,name,monthly_quota,working_days,compensatory FROM bakeries ORDER BY id", null).use { c ->
            while (c.moveToNext()) list.add(Bakery(c.getInt(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4)))
        }
        return list
    }

    fun addBakery(name: String, quota: Int, days: Int = 13): String? {
        val n = name.trim()
        if (n.isEmpty()) return "نام الزامی است."
        return try {
            val v = ContentValues().apply {
                put("name", n); put("monthly_quota", quota); put("working_days", days.coerceIn(1, 31)); put("compensatory", 0)
            }
            writableDatabase.insertOrThrow("bakeries", null, v)
            null
        } catch (_: Exception) { "نام تکراری است." }
    }

    fun updateQuota(bakeryId: Int, monthly: Int, days: Int, comp: Int): String? {
        if (monthly < 0 || days < 1) return "مقادیر نامعتبر."
        writableDatabase.execSQL(
            "UPDATE bakeries SET monthly_quota=?, working_days=?, compensatory=? WHERE id=?",
            arrayOf(monthly, days, comp, bakeryId)
        )
        return null
    }

    fun dailyLimit(b: Bakery): Double {
        val total = (b.monthlyQuota + b.compensatory).toDouble()
        return if (b.workingDays > 0) total / b.workingDays else 0.0
    }

    fun countToday(bakeryId: Int, type: String): Int {
        val day = today()
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM scans WHERE bakery_id=? AND scan_type=? AND scanned_at LIKE ?",
            arrayOf(bakeryId.toString(), type, "$day%")
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun countAll(bakeryId: Int, type: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM scans WHERE bakery_id=? AND scan_type=?",
            arrayOf(bakeryId.toString(), type)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun recordScan(bakeryId: Int, qr: String, mode: ScanMode, userId: Int): Pair<Boolean, String> {
        val code = qr.trim()
        if (code.isEmpty()) return false to "کد QR خالی است."

        val existing = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT scan_type FROM scans WHERE qr_code=?", arrayOf(code)).use { c ->
            while (c.moveToNext()) existing.add(c.getString(0))
        }

        when (mode) {
            ScanMode.FACTORY_OUT -> {
                if (existing.contains(ScanMode.FACTORY_OUT.code))
                    return false to "این کیسه قبلاً خروج از کارخانه ثبت شده."
            }
            ScanMode.WAREHOUSE_IN -> {
                if (!existing.contains(ScanMode.FACTORY_OUT.code))
                    return false to "ابتدا خروج از کارخانه باید ثبت شود."
                if (existing.contains(ScanMode.WAREHOUSE_IN.code))
                    return false to "این کیسه قبلاً ورود به انبار ثبت شده."
            }
            ScanMode.DAILY_OUT -> {
                if (!existing.contains(ScanMode.WAREHOUSE_IN.code))
                    return false to "ابتدا ورود به انبار باید ثبت شود."
                if (existing.contains(ScanMode.DAILY_OUT.code))
                    return false to "این کیسه قبلاً خروج روزانه ثبت شده."
                val bakery = bakeries().find { it.id == bakeryId } ?: return false to "نانوایی یافت نشد."
                val limit = dailyLimit(bakery)
                val todayOut = countToday(bakeryId, ScanMode.DAILY_OUT.code)
                if (todayOut + 1 > limit + 0.01)
                    return false to "فراتر از سهمیه روزانه (${\"%.1f\".format(limit)}). نیاز به تأیید مدیر."
            }
        }

        return try {
            val v = ContentValues().apply {
                put("qr_code", code)
                put("bakery_id", bakeryId)
                put("scan_type", mode.code)
                put("scanned_by", userId)
                put("scanned_at", now())
                put("synced", 0)
            }
            writableDatabase.insertOrThrow("scans", null, v)
            true to "${mode.title} ثبت شد."
        } catch (e: Exception) {
            false to "خطا: ${e.message}"
        }
    }

    fun discrepancy(bakeryId: Int): Int =
        countAll(bakeryId, ScanMode.WAREHOUSE_IN.code) - countAll(bakeryId, ScanMode.DAILY_OUT.code)

    fun recentScans(bakeryId: Int, limit: Int = 40): List<Array<String>> {
        val rows = mutableListOf<Array<String>>()
        readableDatabase.rawQuery(
            "SELECT qr_code, scan_type, scanned_at, synced FROM scans WHERE bakery_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(bakeryId.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val typeFa = when (c.getString(1)) {
                    ScanMode.FACTORY_OUT.code -> "خروج کارخانه"
                    ScanMode.WAREHOUSE_IN.code -> "ورود انبار"
                    ScanMode.DAILY_OUT.code -> "خروج روزانه"
                    else -> c.getString(1)
                }
                rows.add(arrayOf(c.getString(0) ?: "", typeFa, c.getString(2) ?: "", if (c.getInt(3)==1) "همگام" else "محلی"))
            }
        }
        return rows
    }

    fun unsyncedCount() = readableDatabase.rawQuery("SELECT COUNT(*) FROM scans WHERE synced=0", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }
}

class MainActivity : Activity() {

    private lateinit var db: ScannerDb
    private lateinit var prefs: SharedPreferences
    private var currentUser: User? = null
    private var currentMode: ScanMode = ScanMode.WAREHOUSE_IN
    private var toneGenerator: ToneGenerator? = null
    private var rootContainer: FrameLayout? = null
    private var pendingBakeryId: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ScannerDb(this)
        prefs = getSharedPreferences("scanner_session", MODE_PRIVATE)
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        rootContainer = FrameLayout(this)
        setContentView(rootContainer)

        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        if (savedUser != null && savedPass != null) {
            val u = db.login(savedUser, savedPass)
            if (u != null) {
                currentUser = u
                showMainPanel()
                return
            }
        }
        showLogin()
    }

    private fun showLogin() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF5F7F4.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        TextView(this).apply {
            text = "ورود به سامانه اسکنر"
            textSize = 22f
            setTextColor(0xFF1B4332.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
            box.addView(this)
        }
        val userEt = EditText(this).apply {
            hint = "نام کاربری"
            setSingleLine(true)
            setPadding(16, 20, 16, 20)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val passEt = EditText(this).apply {
            hint = "رمز عبور"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(16, 20, 16, 20)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        box.addView(userEt, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
        box.addView(passEt, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 })

        Button(this).apply {
            text = "ورود"
            textSize = 18f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00695C.toInt()))
            setOnClickListener {
                val u = db.login(userEt.text.toString(), passEt.text.toString())
                if (u == null) {
                    Toast.makeText(this@MainActivity, "نام کاربری یا رمز اشتباه است / حساب غیرفعال", Toast.LENGTH_LONG).show()
                } else {
                    currentUser = u
                    prefs.edit().putString("username", u.username).putString("password", passEt.text.toString()).apply()
                    showMainPanel()
                }
            }
            box.addView(this, LinearLayout.LayoutParams(-1, 72))
        }

        TextView(this).apply {
            text = "admin/admin  •  factory/factory  •  baker/baker"
            textSize = 12f
            setTextColor(0xFF78909C.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
            box.addView(this)
        }

        rootContainer?.removeAllViews()
        rootContainer?.addView(box, ViewGroup.LayoutParams(-1, -1))
    }

    private fun showMainPanel() {
        val user = currentUser ?: return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF5F7F4.toInt())
            setPadding(10, 8, 10, 8)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xFF1B4332.toInt())
        }
        TextView(this).apply {
            text = "${user.role.title} — ${user.username}"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            header.addView(this)
        }
        Button(this).apply {
            text = "خروج"
            textSize = 13f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFC62828.toInt()))
            setOnClickListener {
                prefs.edit().clear().apply()
                currentUser = null
                showLogin()
            }
            header.addView(this, LinearLayout.LayoutParams(-2, 56))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })

        when (user.role) {
            Role.ADMIN -> buildAdminPanel(root)
            Role.FACTORY -> buildFactoryPanel(root)
            Role.BAKER -> buildBakerPanel(root)
        }

        rootContainer?.removeAllViews()
        rootContainer?.addView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(-1, -2))
        }, ViewGroup.LayoutParams(-1, -1))
    }

    private fun buildAdminPanel(root: LinearLayout) {
        sectionTitle(root, "مدیریت کاربران")
        addBtn(root, "＋ افزودن کاربر", 0xFF2E7D32.toInt()) { addUserDialog() }
        addBtn(root, "▤ لیست کاربران / فعال‌سازی / ریست", 0xFF1565C0.toInt()) { showUsersList() }
        addBtn(root, "🔑 تغییر رمز خودم", 0xFF5D4037.toInt()) { changeOwnPassword() }

        sectionTitle(root, "مدیریت نانوایی و سهمیه")
        addBtn(root, "＋ افزودن نانوایی", 0xFF455A64.toInt()) { addBakeryDialog() }
        addBtn(root, "⚙ تنظیم سهمیه / روز کاری / جبرانی", 0xFF6D4C41.toInt()) { editQuotaDialog() }
        addBtn(root, "▤ گزارش کامل اسکن‌ها", 0xFF37474F.toInt()) { showFullReport() }

        sectionTitle(root, "اسکن (دسترسی کامل)")
        addModeButtons(root, listOf(ScanMode.FACTORY_OUT, ScanMode.WAREHOUSE_IN, ScanMode.DAILY_OUT))
        addBtn(root, "▦ اسکن QR", 0xFF00695C.toInt(), 100) { startScan() }
        showQuotaSummary(root)
    }

    private fun buildFactoryPanel(root: LinearLayout) {
        sectionTitle(root, "خروج از کارخانه")
        TextView(this).apply {
            text = "فقط می‌توانید کیسه‌ها را برای تحویل ثبت کنید.\nسهمیه توسط مدیر سامانه تعریف شده و به‌صورت خودکار اعمال می‌شود."
            textSize = 13f
            setTextColor(0xFF455A64.toInt())
            setPadding(8, 4, 8, 12)
            root.addView(this)
        }
        currentMode = ScanMode.FACTORY_OUT
        addModeButtons(root, listOf(ScanMode.FACTORY_OUT))
        addBtn(root, "▦ اسکن خروج کارخانه", 0xFFC62828.toInt(), 120) { startScan() }
        addBtn(root, "🔑 تغییر رمز خودم", 0xFF5D4037.toInt()) { changeOwnPassword() }
        showQuotaSummary(root)
    }

    private fun buildBakerPanel(root: LinearLayout) {
        sectionTitle(root, "ورود انبار و خروج روزانه")
        TextView(this).apply {
            text = "سهمیه و روز کاری توسط مدیر تنظیم شده و اینجا نمایش داده می‌شود."
            textSize = 13f
            setTextColor(0xFF455A64.toInt())
            setPadding(8, 4, 8, 12)
            root.addView(this)
        }
        currentMode = ScanMode.WAREHOUSE_IN
        addModeButtons(root, listOf(ScanMode.WAREHOUSE_IN, ScanMode.DAILY_OUT))
        addBtn(root, "▦ اسکن QR", 0xFF00695C.toInt(), 120) { startScan() }
        addBtn(root, "▤ گزارش نانوایی من", 0xFF37474F.toInt()) { showBakerReport() }
        addBtn(root, "🔑 تغییر رمز خودم", 0xFF5D4037.toInt()) { changeOwnPassword() }
        showQuotaSummary(root)
    }

    private fun sectionTitle(parent: LinearLayout, title: String) {
        TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFF1B4332.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(4, 16, 4, 8)
            parent.addView(this)
        }
    }

    private fun addBtn(parent: LinearLayout, label: String, color: Int, height: Int = 64, action: () -> Unit) {
        Button(this).apply {
            text = label
            textSize = if (height >= 100) 18f else 15f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(-1, height).apply { bottomMargin = 8 })
        }
    }

    private fun addModeButtons(parent: LinearLayout, modes: List<ScanMode>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
        }
        modes.forEach { mode ->
            Button(this).apply {
                text = mode.title
                textSize = 12f
                isAllCaps = false
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundTintList(android.content.res.ColorStateList.valueOf(mode.color))
                setOnClickListener {
                    currentMode = mode
                    Toast.makeText(this@MainActivity, "حالت: ${mode.title}", Toast.LENGTH_SHORT).show()
                }
                row.addView(this, LinearLayout.LayoutParams(0, 80, 1f).apply { marginEnd = 4 })
            }
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
    }

    private fun showQuotaSummary(parent: LinearLayout) {
        val bakeries = db.bakeries()
        val sb = StringBuilder()
        bakeries.forEach { b ->
            val limit = db.dailyLimit(b)
            val today = db.countToday(b.id, ScanMode.DAILY_OUT.code)
            val stock = db.discrepancy(b.id)
            sb.append("• ${b.name}: سهمیه ${b.monthlyQuota}+${b.compensatory} | روز ${b.workingDays} | حد روزانه ${\"%.1f\".format(limit)}\n")
            sb.append("  امروز خروج: $today | موجودی تقریبی: $stock\n")
        }
        TextView(this).apply {
            text = sb.toString().ifEmpty { "نانوایی تعریف نشده." }
            textSize = 12f
            setTextColor(0xFF37474F.toInt())
            setBackgroundColor(0xFFE8F5E9.toInt())
            setPadding(10, 10, 10, 10)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        }
    }

    private fun addUserDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 8) }
        val name = EditText(this).apply { hint = "نام کاربری"; setSingleLine(true) }
        val pass = EditText(this).apply { hint = "رمز عبور"; setSingleLine(true) }
        val roleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item,
                listOf("کارخانه (FACTORY)", "نانوا (BAKER)")).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val bakerySpinner = Spinner(this)
        val bakeries = db.bakeries()
        bakerySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bakeries.map { it.name }.ifEmpty { listOf("—") })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        layout.addView(name); layout.addView(pass); layout.addView(roleSpinner); layout.addView(bakerySpinner)
        AlertDialog.Builder(this).setTitle("افزودن کاربر")
            .setView(layout)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val role = if (roleSpinner.selectedItemPosition == 0) Role.FACTORY else Role.BAKER
                val bakeryId = if (role == Role.BAKER && bakeries.isNotEmpty()) bakeries[bakerySpinner.selectedItemPosition].id else null
                val err = db.addUser(name.text.toString(), pass.text.toString(), role, bakeryId)
                Toast.makeText(this, err ?: "کاربر اضافه شد.", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun showUsersList() {
        val users = db.allUsers()
        val names = users.map { u ->
            val status = if (u.active) "فعال" else "غیرفعال"
            "${u.username} (${u.role.title}) — $status"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("کاربران")
            .setItems(names) { _, which ->
                val u = users[which]
                val opts = arrayOf("ریست رمز به 1234", if (u.active) "غیرفعال کردن" else "فعال کردن", "حذف کاربر", "تغییر نام کاربری")
                AlertDialog.Builder(this).setTitle(u.username)
                    .setItems(opts) { _, i ->
                        when (i) {
                            0 -> { db.resetPassword(u.id); Toast.makeText(this, "رمز به 1234 تغییر کرد.", Toast.LENGTH_SHORT).show() }
                            1 -> { db.setUserActive(u.id, !u.active); Toast.makeText(this, "وضعیت تغییر کرد.", Toast.LENGTH_SHORT).show() }
                            2 -> {
                                val err = db.deleteUser(u.id)
                                Toast.makeText(this, err ?: "حذف شد.", Toast.LENGTH_SHORT).show()
                            }
                            3 -> {
                                val et = EditText(this).apply { setText(u.username); setSingleLine(true) }
                                AlertDialog.Builder(this).setTitle("نام جدید").setView(et)
                                    .setPositiveButton("ذخیره") { _, _ ->
                                        val e = db.updateUsername(u.id, et.text.toString())
                                        Toast.makeText(this, e ?: "تغییر یافت.", Toast.LENGTH_SHORT).show()
                                    }.setNegativeButton("انصراف", null).show()
                            }
                        }
                    }.setNegativeButton("بستن", null).show()
            }.setNegativeButton("بستن", null).show()
    }

    private fun changeOwnPassword() {
        val user = currentUser ?: return
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 8) }
        val newPass = EditText(this).apply {
            hint = "رمز جدید"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        layout.addView(newPass)
        AlertDialog.Builder(this).setTitle("تغییر رمز — ${user.username}")
            .setView(layout)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val err = db.updateUserPassword(user.id, newPass.text.toString())
                if (err == null) {
                    prefs.edit().putString("password", newPass.text.toString()).apply()
                    Toast.makeText(this, "رمز تغییر کرد.", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun addBakeryDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 8) }
        val name = EditText(this).apply { hint = "نام نانوایی"; setSingleLine(true) }
        val quota = EditText(this).apply { hint = "سهمیه ماهانه"; inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true) }
        val days = EditText(this).apply { hint = "روز کاری"; setText("13"); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true) }
        layout.addView(name); layout.addView(quota); layout.addView(days)
        AlertDialog.Builder(this).setTitle("افزودن نانوایی")
            .setView(layout)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val err = db.addBakery(name.text.toString(), quota.text.toString().toIntOrNull() ?: 0, days.text.toString().toIntOrNull() ?: 13)
                Toast.makeText(this, err ?: "نانوایی اضافه شد.", Toast.LENGTH_LONG).show()
                showMainPanel()
            }.show()
    }

    private fun editQuotaDialog() {
        val bakeries = db.bakeries()
        if (bakeries.isEmpty()) {
            Toast.makeText(this, "نانوایی وجود ندارد.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = bakeries.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("انتخاب نانوایی")
            .setItems(names) { _, which ->
                val b = bakeries[which]
                val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 8) }
                val quota = EditText(this).apply { setText(b.monthlyQuota.toString()); hint = "سهمیه ماهانه"; inputType = InputType.TYPE_CLASS_NUMBER }
                val days = EditText(this).apply { setText(b.workingDays.toString()); hint = "روز کاری"; inputType = InputType.TYPE_CLASS_NUMBER }
                val comp = EditText(this).apply { setText(b.compensatory.toString()); hint = "سهمیه جبرانی/ترمیمی"; inputType = InputType.TYPE_CLASS_NUMBER }
                layout.addView(quota); layout.addView(days); layout.addView(comp)
                AlertDialog.Builder(this).setTitle("تنظیم سهمیه — ${b.name}")
                    .setView(layout)
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("ذخیره") { _, _ ->
                        val err = db.updateQuota(b.id,
                            quota.text.toString().toIntOrNull() ?: b.monthlyQuota,
                            days.text.toString().toIntOrNull() ?: b.workingDays,
                            comp.text.toString().toIntOrNull() ?: 0)
                        Toast.makeText(this, err ?: "ذخیره شد و در همه پنل‌ها اعمال می‌شود.", Toast.LENGTH_LONG).show()
                        showMainPanel()
                    }.show()
            }.setNegativeButton("انصراف", null).show()
    }

    private fun showFullReport() {
        val bakeries = db.bakeries()
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12); layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL }
        scroll.addView(box)
        bakeries.forEach { b ->
            box.addView(TextView(this).apply {
                text = "— ${b.name} —\nحد روزانه: ${\"%.2f\".format(db.dailyLimit(b))}\nخروج کارخانه: ${db.countAll(b.id, ScanMode.FACTORY_OUT.code)} | ورود: ${db.countAll(b.id, ScanMode.WAREHOUSE_IN.code)} | خروج روزانه: ${db.countAll(b.id, ScanMode.DAILY_OUT.code)}\nموجودی: ${db.discrepancy(b.id)}"
                textSize = 13f
                setPadding(0, 8, 0, 8)
            })
            db.recentScans(b.id, 15).forEach { r ->
                box.addView(TextView(this).apply { text = "  ${r[1]} | ${r[0].take(16)} | ${r[2]}"; textSize = 12f })
            }
        }
        AlertDialog.Builder(this).setTitle("گزارش کامل").setView(scroll).setPositiveButton("بستن", null).show()
    }

    private fun showBakerReport() {
        val user = currentUser ?: return
        val bakeryId = user.bakeryId ?: db.bakeries().firstOrNull()?.id ?: return
        val b = db.bakeries().find { it.id == bakeryId } ?: return
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12) }
        scroll.addView(box)
        box.addView(TextView(this).apply {
            text = "${b.name}\nحد روزانه: ${\"%.2f\".format(db.dailyLimit(b))}\nامروز خروج: ${db.countToday(b.id, ScanMode.DAILY_OUT.code)}\nموجودی: ${db.discrepancy(b.id)}"
            textSize = 14f
        })
        db.recentScans(b.id, 25).forEach { r ->
            box.addView(TextView(this).apply { text = "${r[1]} | ${r[0]} | ${r[2]}"; textSize = 12f })
        }
        AlertDialog.Builder(this).setTitle("گزارش").setView(scroll).setPositiveButton("بستن", null).show()
    }

    private fun startScan() {
        val user = currentUser ?: return
        val bakeries = db.bakeries()
        if (bakeries.isEmpty()) {
            Toast.makeText(this, "نانوایی تعریف نشده.", Toast.LENGTH_SHORT).show()
            return
        }
        val bakeryId = when {
            user.role == Role.BAKER && user.bakeryId != null -> user.bakeryId
            bakeries.size == 1 -> bakeries[0].id
            else -> null
        }
        if (bakeryId != null) {
            launchScanner(bakeryId)
        } else {
            AlertDialog.Builder(this).setTitle("انتخاب نانوایی")
                .setItems(bakeries.map { it.name }.toTypedArray()) { _, i ->
                    launchScanner(bakeries[i].id)
                }.setNegativeButton("انصراف", null).show()
        }
    }

    private fun launchScanner(bakeryId: Int) {
        pendingBakeryId = bakeryId
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE, IntentIntegrator.CODE_128, IntentIntegrator.CODE_39)
        integrator.setPrompt("${currentMode.title} — کیسه را اسکن کنید")
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
                if (ok) toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                else toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                showMainPanel()
            }
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }
}
