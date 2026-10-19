package com.discipline.control.core

import org.json.JSONArray
import org.json.JSONObject

/** 桌面上的一个条目：要么是单个应用，要么是分组（文件夹） */
sealed class HomeItem {
    data class AppItem(val pkg: String) : HomeItem()
    data class FolderItem(val folder: HomeFolder) : HomeItem()
}

data class HomeFolder(val id: String, val name: String, val pkgs: List<String>)

/**
 * 桌面布局：条目顺序 + 壁纸路径。应用是否显示仍由管控规则决定（隐藏的应用不会出现在桌面/分组里）。
 */
data class HomeLayout(
    val items: List<HomeItem> = emptyList(),
    /** 自定义壁纸的本地文件绝对路径，空串 = 用默认背景 */
    val wallpaper: String = "",
    /** 底部 Dock 栏固定的应用（最多 5 个），Dock 里的应用不再出现在上面的网格里 */
    val dock: List<String> = emptyList()
) {

    fun folderOf(pkg: String): HomeFolder? = items.filterIsInstance<HomeItem.FolderItem>()
        .firstOrNull { it.folder.pkgs.contains(pkg) }?.folder

    fun folder(folderId: String): HomeFolder? = items.filterIsInstance<HomeItem.FolderItem>()
        .firstOrNull { it.folder.id == folderId }?.folder

    fun toJson(): JSONObject {
        val arr = JSONArray()
        items.forEach { item ->
            when (item) {
                is HomeItem.AppItem -> arr.put(JSONObject().put("t", "app").put("pkg", item.pkg))
                is HomeItem.FolderItem -> {
                    val pkgs = JSONArray()
                    item.folder.pkgs.forEach { pkgs.put(it) }
                    arr.put(
                        JSONObject()
                            .put("t", "folder")
                            .put("id", item.folder.id)
                            .put("name", item.folder.name)
                            .put("pkgs", pkgs)
                    )
                }
            }
        }
        val dockArr = JSONArray()
        dock.forEach { dockArr.put(it) }
        return JSONObject().put("items", arr).put("wallpaper", wallpaper).put("dock", dockArr)
    }

    companion object {
        fun fromJson(o: JSONObject): HomeLayout {
            val items = ArrayList<HomeItem>()
            val arr = o.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    when (it.optString("t")) {
                        "app" -> {
                            val pkg = it.optString("pkg")
                            if (pkg.isNotEmpty()) items.add(HomeItem.AppItem(pkg))
                        }

                        "folder" -> {
                            val pkgs = ArrayList<String>()
                            val pa = it.optJSONArray("pkgs")
                            if (pa != null) for (k in 0 until pa.length()) {
                                val p = pa.optString(k)
                                if (p.isNotEmpty()) pkgs.add(p)
                            }
                            items.add(
                                HomeItem.FolderItem(
                                    HomeFolder(
                                        id = it.optString("id", "f_" + i),
                                        name = it.optString("name", "分组"),
                                        pkgs = pkgs
                                    )
                                )
                            )
                        }
                    }
                }
            }
            val dock = ArrayList<String>()
            val dockArr = o.optJSONArray("dock")
            if (dockArr != null) for (i in 0 until dockArr.length()) {
                val pkg = dockArr.optString(i)
                if (pkg.isNotEmpty()) dock.add(pkg)
            }
            return HomeLayout(items = items, wallpaper = o.optString("wallpaper", ""), dock = dock)
        }
    }
}

/** 桌面最终渲染出来的条目 */
sealed class LauncherEntry {
    abstract val key: String

    data class AppEntry(val pkg: String, val label: String) : LauncherEntry() {
        override val key: String get() = "a:$pkg"
    }

    data class FolderEntry(val id: String, val name: String, val apps: List<AppEntry>) : LauncherEntry() {
        override val key: String get() = "f:$id"
    }
}

/**
 * 布局合成逻辑（纯函数，可单测）：
 * 保存的布局 + 当前安装列表 + 当前可见集合 → 桌面条目。
 * 规则：隐藏/已卸载的剔除；空分组消失；没进过布局的新应用按名称追加末尾。
 */
object HomeLayoutEngine {

    /** installed / visible 都用 pkg 集合表达，label 单独映射，避免把 Android 类型带进纯逻辑 */
    fun build(
        layout: HomeLayout,
        labels: Map<String, String>,
        visible: Set<String>
    ): List<LauncherEntry> {
        val placed = HashSet<String>()
        val dockSet = layout.dock.toSet()
        val out = ArrayList<LauncherEntry>()
        val labelOf = { pkg: String -> labels[pkg] ?: pkg }
        val showable = { pkg: String -> visible.contains(pkg) && labels.containsKey(pkg) }

        for (item in layout.items) {
            when (item) {
                is HomeItem.AppItem -> {
                    if (!dockSet.contains(item.pkg) && showable(item.pkg) && placed.add(item.pkg)) {
                        out.add(LauncherEntry.AppEntry(item.pkg, labelOf(item.pkg)))
                    }
                }

                is HomeItem.FolderItem -> {
                    val apps = item.folder.pkgs
                        .filter { showable(it) && placed.add(it) }
                        .map { LauncherEntry.AppEntry(it, labelOf(it)) }
                    if (apps.isNotEmpty()) {
                        out.add(LauncherEntry.FolderEntry(item.folder.id, item.folder.name, apps))
                    }
                }
            }
        }
        // 新装的应用 / 新加入管控又解除的应用：按名称补到末尾
        labels.keys.filter { visible.contains(it) && !placed.contains(it) && !dockSet.contains(it) }
            .sortedBy { labelOf(it).lowercase() }
            .forEach { out.add(LauncherEntry.AppEntry(it, labelOf(it))) }
        return out
    }

    /** 把布局补齐成"当前所有可见应用都有位置"，再做增删改，避免顺序丢失 */
    fun materialize(layout: HomeLayout, labels: Map<String, String>, visible: Set<String>): HomeLayout {
        val items = build(layout, labels, visible).map { entry ->
            when (entry) {
                is LauncherEntry.AppEntry -> HomeItem.AppItem(entry.pkg)
                is LauncherEntry.FolderEntry -> HomeItem.FolderItem(
                    HomeFolder(entry.id, entry.name, entry.apps.map { it.pkg })
                )
            }
        }
        return layout.copy(items = items)
    }

    /**
     * 新建分组并把 [pkgs] 放进去。
     * 文件夹出现在第一个被移入应用原来的位置（安卓桌面的习惯），而不是被丢到列表末尾。
     */
    fun createFolder(layout: HomeLayout, id: String, name: String, pkgs: List<String>): HomeLayout {
        val pkgSet = pkgs.toSet()
        val out = ArrayList<HomeItem>()
        var placed = false
        layout.items.forEach { item ->
            when (item) {
                is HomeItem.AppItem -> {
                    if (pkgSet.contains(item.pkg)) {
                        if (!placed) {
                            out.add(HomeItem.FolderItem(HomeFolder(id, name, pkgs)))
                            placed = true
                        }
                    } else {
                        out.add(item)
                    }
                }

                is HomeItem.FolderItem -> {
                    val left = item.folder.pkgs.filter { !pkgSet.contains(it) }
                    if (left.isNotEmpty()) out.add(HomeItem.FolderItem(item.folder.copy(pkgs = left)))
                }
            }
        }
        if (!placed) out.add(HomeItem.FolderItem(HomeFolder(id, name, pkgs)))
        return layout.copy(items = out)
    }

    /** 把应用移到已有分组里 */
    fun addToFolder(layout: HomeLayout, folderId: String, pkg: String): HomeLayout {
        val items = layout.items.map { item ->
            when (item) {
                is HomeItem.AppItem -> if (item.pkg == pkg) null else item
                is HomeItem.FolderItem ->
                    if (item.folder.id == folderId) {
                        HomeItem.FolderItem(item.folder.copy(pkgs = item.folder.pkgs + pkg))
                    } else if (item.folder.pkgs.contains(pkg)) {
                        HomeItem.FolderItem(item.folder.copy(pkgs = item.folder.pkgs - pkg))
                    } else {
                        item
                    }
            }
        }.filterNotNull()
        return layout.copy(items = items)
    }

    /** 从分组里移出，回到桌面（追加到末尾） */
    fun removeFromFolder(layout: HomeLayout, pkg: String): HomeLayout {
        var moved = false
        val items = layout.items.mapNotNull { item ->
            when (item) {
                is HomeItem.AppItem -> item
                is HomeItem.FolderItem ->
                    if (item.folder.pkgs.contains(pkg)) {
                        moved = true
                        val left = item.folder.pkgs - pkg
                        if (left.isEmpty()) null else HomeItem.FolderItem(item.folder.copy(pkgs = left))
                    } else {
                        item
                    }
            }
        }.toMutableList()
        if (moved) items.add(HomeItem.AppItem(pkg))
        return layout.copy(items = items)
    }

    /** 解散分组：组内应用全部回到桌面 */
    fun deleteFolder(layout: HomeLayout, folderId: String): HomeLayout {
        val out = ArrayList<HomeItem>()
        layout.items.forEach { item ->
            if (item is HomeItem.FolderItem && item.folder.id == folderId) {
                item.folder.pkgs.forEach { out.add(HomeItem.AppItem(it)) }
            } else {
                out.add(item)
            }
        }
        return layout.copy(items = out)
    }

    fun renameFolder(layout: HomeLayout, folderId: String, name: String): HomeLayout {
        val items = layout.items.map { item ->
            if (item is HomeItem.FolderItem && item.folder.id == folderId) {
                HomeItem.FolderItem(item.folder.copy(name = name))
            } else {
                item
            }
        }
        return layout.copy(items = items)
    }

    /** 布局条目的稳定 key（拖拽/排序用）：应用 a:pkg，文件夹 f:id */
    fun keyOf(item: HomeItem): String = when (item) {
        is HomeItem.AppItem -> "a:" + item.pkg
        is HomeItem.FolderItem -> "f:" + item.folder.id
    }

    /** 拖拽排序：把 [fromKey] 移到 [toKey] 的位置（被隐藏/未显示的条目保持原位） */
    fun moveItem(layout: HomeLayout, fromKey: String, toKey: String): HomeLayout {
        val list = layout.items.toMutableList()
        val from = list.indexOfFirst { keyOf(it) == fromKey }
        val to = list.indexOfFirst { keyOf(it) == toKey }
        if (from < 0 || to < 0 || from == to) return layout
        val item = list.removeAt(from)
        list.add(to, item)
        return layout.copy(items = list)
    }

    /** 拖拽结束后的展示顺序写回布局（同样只重排"显示的条目"，隐藏的留在原位） */
    fun applyDisplayOrder(layout: HomeLayout, orderedKeys: List<String>): HomeLayout {
        val byKey = HashMap<String, HomeItem>()
        layout.items.forEach { byKey[keyOf(it)] = it }
        val displayed = orderedKeys.mapNotNull { byKey[it] }
        if (displayed.isEmpty()) return layout
        val displayedKeys = displayed.map { keyOf(it) }.toSet()
        val out = ArrayList<HomeItem>(layout.items.size)
        var di = 0
        for (item in layout.items) {
            if (displayedKeys.contains(keyOf(item))) {
                if (di < displayed.size) out.add(displayed[di++])
            } else {
                out.add(item)
            }
        }
        while (di < displayed.size) out.add(displayed[di++])
        return layout.copy(items = out)
    }

    /**
     * 拖拽合并：把 [draggedPkg] 拖到 [targetKey] 上松手。
     * - 目标是应用：在目标位置新建文件夹（目标应用 + 拖拽应用）
     * - 目标是文件夹：直接把拖拽应用加进去
     */
    fun mergeIntoFolder(
        layout: HomeLayout,
        draggedPkg: String,
        targetKey: String,
        newFolderId: String,
        newFolderName: String
    ): HomeLayout {
        val stripped = ArrayList<HomeItem>()
        layout.items.forEach { item ->
            when (item) {
                is HomeItem.AppItem -> if (item.pkg != draggedPkg) stripped.add(item)
                is HomeItem.FolderItem -> {
                    val left = item.folder.pkgs.filter { it != draggedPkg }
                    if (left.isNotEmpty()) stripped.add(HomeItem.FolderItem(item.folder.copy(pkgs = left)))
                }
            }
        }
        val targetIndex = stripped.indexOfFirst { keyOf(it) == targetKey }
        if (targetIndex < 0) return layout
        val target = stripped[targetIndex]
        return if (target is HomeItem.FolderItem) {
            stripped[targetIndex] =
                HomeItem.FolderItem(target.folder.copy(pkgs = target.folder.pkgs + draggedPkg))
            layout.copy(items = stripped)
        } else {
            val targetPkg = (target as HomeItem.AppItem).pkg
            stripped[targetIndex] = HomeItem.FolderItem(
                HomeFolder(newFolderId, newFolderName, listOf(targetPkg, draggedPkg))
            )
            layout.copy(items = stripped)
        }
    }
    // ---------------- Dock 栏（手机桌面底部固定应用） ----------------

    /** Dock 最多几个 */
    fun dockMax(): Int = 5

    fun isInDock(layout: HomeLayout, pkg: String): Boolean = layout.dock.contains(pkg)

    /** 拖到 Dock：从桌面/文件夹移出并加到 Dock 末尾；Dock 已满或已在其中则原样返回 */
    fun moveToDock(layout: HomeLayout, pkg: String): HomeLayout {
        if (layout.dock.contains(pkg)) return layout
        if (layout.dock.size >= dockMax()) return layout
        val stripped = removePkgs(layout.items, setOf(pkg))
        return layout.copy(items = stripped, dock = layout.dock + pkg)
    }

    /** 从 Dock 移除：回到桌面，追加到末尾 */
    fun removeFromDock(layout: HomeLayout, pkg: String): HomeLayout {
        if (!layout.dock.contains(pkg)) return layout
        return layout.copy(items = layout.items + HomeItem.AppItem(pkg), dock = layout.dock - pkg)
    }

    /** Dock 为空时播种几个常用应用（只挑已安装的），符合手机桌面的默认观感 */
    fun seedDockIfEmpty(layout: HomeLayout, installedCandidates: List<String>): HomeLayout {
        if (layout.dock.isNotEmpty()) return layout
        val picked = installedCandidates.distinct().take(dockMax())
        if (picked.isEmpty()) return layout
        val stripped = removePkgs(layout.items, picked.toSet())
        return layout.copy(items = stripped, dock = picked)
    }
    private fun removePkgs(items: List<HomeItem>, pkgs: Set<String>): List<HomeItem> {
        return items.mapNotNull { item ->
            when (item) {
                is HomeItem.AppItem -> if (pkgs.contains(item.pkg)) null else item
                is HomeItem.FolderItem -> {
                    val left = item.folder.pkgs.filter { !pkgs.contains(it) }
                    if (left.isEmpty()) null else HomeItem.FolderItem(item.folder.copy(pkgs = left))
                }
            }
        }
    }
}
