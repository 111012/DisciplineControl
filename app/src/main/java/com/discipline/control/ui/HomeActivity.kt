package com.discipline.control.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.discipline.control.R
import com.discipline.control.core.AppEntry
import com.discipline.control.core.AppRule
import com.discipline.control.core.Apps
import com.discipline.control.core.DayUsage
import com.discipline.control.core.Engine
import com.discipline.control.core.HomeItem
import com.discipline.control.core.HomeLayout
import com.discipline.control.core.HomeLayoutEngine
import com.discipline.control.core.LauncherEntry
import com.discipline.control.core.Notifier
import com.discipline.control.core.Overlay
import com.discipline.control.core.Pin
import com.discipline.control.core.RemindStyle
import com.discipline.control.core.Store
import com.discipline.control.core.dp
import com.discipline.control.service.GuardAccessibilityService
import com.discipline.control.service.GuardService
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class HomeActivity : AppCompatActivity() {

    private lateinit var grid: GridView
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvFooter: TextView
    private lateinit var chipBox: LinearLayout
    private lateinit var ivWallpaper: ImageView
    private lateinit var vScrim: View
    private lateinit var adapter: HomeGridAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var lastMinute = -1
    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, android.graphics.drawable.Drawable?>()
    private var wallpaperBitmap: Bitmap? = null
    private var loadedWallpaperPath: String = ""

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            val minute = LocalDateTime.now().minute
            if (minute != lastMinute) {
                lastMinute = minute
                refresh()
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        grid = findViewById(R.id.grid)
        tvClock = findViewById(R.id.tvClock)
        tvDate = findViewById(R.id.tvDate)
        tvStatus = findViewById(R.id.tvStatus)
        tvFooter = findViewById(R.id.tvFooter)
        chipBox = findViewById(R.id.chipBox)
        ivWallpaper = findViewById(R.id.ivWallpaper)
        vScrim = findViewById(R.id.vScrim)
        val btnSettings: ImageButton = findViewById(R.id.btnSettings)

        adapter = HomeGridAdapter(this)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            when (val entry = adapter.getItem(position)) {
                is LauncherEntry.AppEntry -> launchApp(entry.pkg)
                is LauncherEntry.FolderEntry -> openFolder(entry.id)
            }
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            when (val entry = adapter.getItem(position)) {
                is LauncherEntry.AppEntry -> showAppMenu(entry.pkg)
                is LauncherEntry.FolderEntry -> showFolderMenu(entry.id, entry.name)
            }
            true
        }

        btnSettings.setOnClickListener { Pin.gate(this) { openSettings() } }
        btnSettings.setOnLongClickListener {
            openSystemSettings()
            true
        }

        requestNotifPermissionIfNeeded()
        updateClock()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (!GuardService.isRunning()) GuardService.startSafely(this)
        applyWallpaper()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun updateClock() {
        val now = LocalDateTime.now()
        tvClock.text = now.format(clockFmt)
        tvDate.text = "${now.monthValue} 月 ${now.dayOfMonth} 日 ${dayNames[now.dayOfWeek.value - 1]}"
    }

    // ---------------- 桌面刷新 ----------------

    private fun refresh() {
        val now = LocalDateTime.now()
        val rules = Store.rules()
        val day = Store.day()
        val all = Apps.launchable(this)

        labels.clear()
        icons.clear()
        all.forEach {
            labels[it.pkg] = it.label
            icons[it.pkg] = it.icon
        }

        val visible = HashSet<String>(all.size)
        for (e in all) {
            val rule = rules.firstOrNull { it.pkg == e.pkg && it.enabled }
            if (rule == null || Engine.visibleInLauncher(rule, day.peek(e.pkg), now)) visible.add(e.pkg)
        }

        val entries = HomeLayoutEngine.build(Store.layout(), labels, visible)
        adapter.setData(entries, icons)
        rebuildStatus(rules, day, now)
        rebuildChips()
        rebuildFooter()
    }

    private fun rebuildStatus(rules: List<AppRule>, day: DayUsage, now: LocalDateTime) {
        val enabledRules = rules.filter { it.enabled }
        if (enabledRules.isEmpty()) {
            tvStatus.text = "还没有管控任何应用。点右上角 → 添加管控应用"
            return
        }
        val sb = StringBuilder()
        enabledRules.forEach { r ->
            if (sb.isNotEmpty()) sb.append("\n")
            val u = day.peek(r.pkg)
            val state = when {
                u?.finished == true -> "今日已用完，明天再来"
                r.dailyOpenLimit > 0 && (u?.opens ?: 0) >= r.dailyOpenLimit -> "今日次数已用完"
                !r.inWindow(now) -> "已隐身 · ${Engine.nextOpenText(r, now)} 开放"
                r.dailyOpenLimit > 0 -> "开放中 · 今日已用 ${u?.opens ?: 0}/${r.dailyOpenLimit} 次"
                else -> "开放中 · 次数不限"
            }
            sb.append(r.label).append("：").append(state)
        }
        tvStatus.text = sb.toString()
    }

    private fun rebuildChips() {
        chipBox.removeAllViews()
        val needDefault = !isDefaultLauncher()
        val accEnabled = GuardAccessibilityService.isEnabled(this)
        if (needDefault) addChip("① 设为默认桌面", true) { openHomeSettings() }
        if (!accEnabled) addChip("② 开启无障碍拦截", true) { openAccessibilitySettings() }
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost()) {
            addChip("③ 允许通知", true) { requestNotifPermissionIfNeeded() }
        }
        if (Store.config().remindStyle == RemindStyle.OVERLAY && !Overlay.canDraw(this)) {
            addChip("悬浮窗权限", true) { openOverlaySettings() }
        }
        val lw = GuardService.lastWarn
        if (lw != null) {
            val left = (lw.remainMin - (System.currentTimeMillis() - lw.at) / 60_000L).coerceAtLeast(0L)
            if (left > 0) addChip("⏳ ${lw.label} 还剩 $left 分钟", true) {}
        }
        val running = GuardService.isRunning()
        if (!accEnabled || !running) {
            addChip("⚠ 守护未运行：点此修复", true) {
                startActivity(Intent(this, RepairActivity::class.java))
            }
        }
        if (!needDefault && accEnabled && running && Store.rules().isEmpty()) {
            addChip("＋ 添加管控应用", false) { openSettings() }
        }
        if (!needDefault && accEnabled && running && Store.rules().isNotEmpty()) {
            addChip("守护运行中", false) {}
        }
    }

    private fun rebuildFooter() {
        val lb = GuardService.lastBlock
        if (lb != null && System.currentTimeMillis() - lb.at < 90_000L) {
            tvFooter.text = "${lb.label} 已被拦下：${lb.text}"
            tvFooter.setTextColor(ContextCompat.getColor(this, R.color.warn))
        } else {
            tvFooter.text = "长按图标可分组 · 长按右上角进系统设置"
            tvFooter.setTextColor(ContextCompat.getColor(this, R.color.sub))
        }
    }

    private fun addChip(text: String, warn: Boolean, onClick: () -> Unit) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(ContextCompat.getColor(this, if (warn) R.color.warn else R.color.accent))
        tv.background = ContextCompat.getDrawable(this, if (warn) R.drawable.bg_chip_warn else R.drawable.bg_chip)
        val h = dp(12)
        val v = dp(6)
        tv.setPadding(h, v, h, v)
        tv.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.rightMargin = dp(8)
        tv.layoutParams = lp
        tv.setOnClickListener { onClick() }
        chipBox.addView(tv)
    }

    // ---------------- 壁纸 ----------------

    private fun applyWallpaper() {
        val path = Store.layout().wallpaper
        if (path.isEmpty() || !File(path).exists()) {
            if (loadedWallpaperPath.isNotEmpty()) {
                wallpaperBitmap = null
                loadedWallpaperPath = ""
            }
            ivWallpaper.visibility = View.GONE
            vScrim.visibility = View.GONE
            return
        }
        if (path != loadedWallpaperPath || wallpaperBitmap == null) {
            wallpaperBitmap = decodeWallpaper(path)
            loadedWallpaperPath = path
        }
        val bmp = wallpaperBitmap
        if (bmp == null) {
            ivWallpaper.visibility = View.GONE
            vScrim.visibility = View.GONE
            return
        }
        ivWallpaper.setImageBitmap(bmp)
        ivWallpaper.visibility = View.VISIBLE
        vScrim.visibility = View.VISIBLE
    }

    /**
     * 壁纸按"液态玻璃"处理：降采样 + 缩到极小再放大（任何 API 都能用的磨砂模糊），
     * 输出约 1000px 宽，既省内存（原图 1800x2880 ARGB 要 20MB+）又有磨砂质感。
     */
    private fun decodeWallpaper(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
        val tinyW = (src.width / 12).coerceAtLeast(16)
        val tinyH = (src.height / 12).coerceAtLeast(16)
        val tiny = Bitmap.createScaledBitmap(src, tinyW, tinyH, true)
        val outW = src.width.coerceAtMost(1000)
        val outH = (src.height * outW / src.width).coerceAtLeast(1)
        val blurred = Bitmap.createScaledBitmap(tiny, outW, outH, true)
        if (tiny !== src) tiny.recycle()
        src.recycle()
        blurred
    }.getOrNull()

    // ---------------- 长按菜单 ----------------

    private fun showAppMenu(pkg: String) {
        val layout = Store.layout()
        val inFolder = layout.folderOf(pkg)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add("移动到分组…" to { moveToFolder(pkg) })
        if (inFolder == null) {
            actions.add("新建分组并移入" to { createFolderWith(pkg) })
        } else {
            actions.add("移出分组（${inFolder.name}）" to {
                Store.saveLayout(HomeLayoutEngine.removeFromFolder(Store.layout(), pkg))
                refresh()
                Toast.makeText(this, "已移出分组", Toast.LENGTH_SHORT).show()
            })
        }
        actions.add("应用信息" to { showAppInfo(pkg) })
        AlertDialog.Builder(this)
            .setTitle(labels[pkg] ?: pkg)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFolderMenu(folderId: String, name: String) {
        val actions = listOf<Pair<String, () -> Unit>>(
            "打开" to { openFolder(folderId) },
            "重命名" to { promptRenameFolder(folderId, name) },
            "解散分组" to { confirmDeleteFolder(folderId, name) }
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 编辑布局前先把"当前所有已安装应用"固化成布局，避免隐藏/新装应用导致顺序错乱 */
    private fun editableLayout(): HomeLayout =
        HomeLayoutEngine.materialize(Store.layout(), labels, labels.keys)

    private fun moveToFolder(pkg: String) {
        val folders = Store.layout().items.filterIsInstance<HomeItem.FolderItem>().map { it.folder }
        if (folders.isEmpty()) {
            createFolderWith(pkg)
            return
        }
        val names = folders.map { it.name }.toMutableList()
        names.add("＋ 新建分组…")
        AlertDialog.Builder(this)
            .setTitle("移动到分组")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == folders.size) {
                    createFolderWith(pkg)
                } else {
                    val layout = HomeLayoutEngine.addToFolder(editableLayout(), folders[which].id, pkg)
                    Store.saveLayout(layout)
                    refresh()
                    Toast.makeText(this, "已移入「${folders[which].name}」", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createFolderWith(pkg: String) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "比如：游戏"
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("新建分组")
            .setView(box)
            .setPositiveButton("创建") { _, _ ->
                val name = et.text.toString().trim().ifEmpty { "分组" }
                val id = "f_" + UUID.randomUUID().toString().take(6)
                val layout = HomeLayoutEngine.createFolder(editableLayout(), id, name, listOf(pkg))
                Store.saveLayout(layout)
                refresh()
                Toast.makeText(this, "已创建分组「$name」", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptRenameFolder(folderId: String, current: String) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(current)
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名分组")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    Store.saveLayout(HomeLayoutEngine.renameFolder(Store.layout(), folderId, name))
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteFolder(folderId: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("解散「$name」？")
            .setMessage("组内应用会回到桌面，管控规则不受影响。")
            .setPositiveButton("解散") { _, _ ->
                Store.saveLayout(HomeLayoutEngine.deleteFolder(Store.layout(), folderId))
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 动作 ----------------

    private fun openFolder(folderId: String) {
        startActivity(Intent(this, FolderActivity::class.java).putExtra("folderId", folderId))
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(this, "这个应用没有可启动的入口", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "启动失败：${it.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun showAppInfo(pkg: String) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("authed", true))
    }

    private fun openSystemSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun openHomeSettings() {
        val ok = runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isSuccess
        if (!ok) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            Toast.makeText(this, "请在「默认应用 / 桌面」里选择「自律控制」", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "请在列表里选择「自律控制」并设为默认", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        Toast.makeText(this, "找到「自律控制 · 应用守护」并开启", Toast.LENGTH_LONG).show()
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                101
            )
        }
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = runCatching { packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrNull() ?: return false
        return res.activityInfo?.packageName == packageName
    }
}
