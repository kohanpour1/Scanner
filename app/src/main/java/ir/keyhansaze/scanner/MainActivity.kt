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
    // ... (full content truncated for this call; will follow with complete in next if needed)
}
