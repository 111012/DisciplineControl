package com.discipline.control.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面布局合成逻辑：分组、壁纸、隐藏应用剔除、新应用追加、JSON 往返。
 */
class HomeLayoutTest {

    private val labels = mapOf(
        "com.a" to "Alpha",
        "com.b" to "Beta",
        "com.c" to "Charlie",
        "com.d" to "Delta"
    )
    private val allVisible = labels.keys

    private fun app(pkg: String) = LauncherEntry.AppEntry(pkg, labels.getValue(pkg))

    @Test
    fun emptyLayoutAppendsEverythingSorted() {
        val out = HomeLayoutEngine.build(HomeLayout(), labels, allVisible)
        assertEquals(listOf("com.a", "com.b", "com.c", "com.d"), out.map { (it as LauncherEntry.AppEntry).pkg })
    }

    @Test
    fun layoutOrderIsRespectedAndNewAppsAppended() {
        val layout = HomeLayout(
            items = listOf(HomeItem.AppItem("com.d"), HomeItem.AppItem("com.a"))
        )
        val out = HomeLayoutEngine.build(layout, labels, allVisible)
        assertEquals(
            listOf("com.d", "com.a", "com.b", "com.c"),
            out.map { (it as LauncherEntry.AppEntry).pkg }
        )
    }

    @Test
    fun folderRendersWithItsApps() {
        val layout = HomeLayout(
            items = listOf(
                HomeItem.FolderItem(HomeFolder("f1", "游戏", listOf("com.a", "com.b"))),
                HomeItem.AppItem("com.c")
            )
        )
        val out = HomeLayoutEngine.build(layout, labels, allVisible)
        // 分组 + 桌面上的 com.c + 没进过布局的新应用 com.d
        assertEquals(3, out.size)
        val folder = out[0] as LauncherEntry.FolderEntry
        assertEquals("游戏", folder.name)
        assertEquals(listOf("com.a", "com.b"), folder.apps.map { it.pkg })
        assertEquals("com.c", (out[1] as LauncherEntry.AppEntry).pkg)
        assertEquals("com.d", (out[2] as LauncherEntry.AppEntry).pkg)
        // 组内应用不会在桌面重复出现
        assertTrue(out.drop(1).none { it is LauncherEntry.AppEntry && it.pkg == "com.a" })
    }

    @Test
    fun hiddenAppsAreRemovedFromFoldersAndEmptyFolderDisappears() {
        val layout = HomeLayout(
            items = listOf(HomeItem.FolderItem(HomeFolder("f1", "游戏", listOf("com.a", "com.b"))))
        )
        // com.b 被规则隐藏
        val out = HomeLayoutEngine.build(layout, labels, setOf("com.a", "com.c", "com.d"))
        val folder = out[0] as LauncherEntry.FolderEntry
        assertEquals(listOf("com.a"), folder.apps.map { it.pkg })

        // 两个都被隐藏 → 分组整体消失
        val out2 = HomeLayoutEngine.build(layout, labels, setOf("com.c", "com.d"))
        assertTrue(out2.none { it is LauncherEntry.FolderEntry })
    }

    @Test
    fun uninstalledAppsAreDropped() {
        val layout = HomeLayout(
            items = listOf(HomeItem.AppItem("com.gone"), HomeItem.AppItem("com.a"))
        )
        val out = HomeLayoutEngine.build(layout, labels, allVisible)
        assertEquals(listOf("com.a", "com.b", "com.c", "com.d"), out.map { (it as LauncherEntry.AppEntry).pkg })
    }

    @Test
    fun createFolderMovesAppsIn() {
        val base = HomeLayout(items = listOf(HomeItem.AppItem("com.a"), HomeItem.AppItem("com.b")))
        val withFolder = HomeLayoutEngine.createFolder(base, "f1", "工具", listOf("com.a"))
        // 文件夹应该出现在 com.a 原来的位置（第一个）
        assertTrue((withFolder.items[0] as HomeItem.FolderItem).folder.name == "工具")
        val out = HomeLayoutEngine.build(withFolder, labels, allVisible)
        val folder = out.first { it is LauncherEntry.FolderEntry } as LauncherEntry.FolderEntry
        assertEquals(listOf("com.a"), folder.apps.map { it.pkg })
        assertTrue(out.none { it is LauncherEntry.AppEntry && it.pkg == "com.a" })
    }

    @Test
    fun addAndRemoveFromFolder() {
        var layout = HomeLayout(
            items = listOf(
                HomeItem.FolderItem(HomeFolder("f1", "游戏", listOf("com.a"))),
                HomeItem.AppItem("com.b")
            )
        )
        layout = HomeLayoutEngine.addToFolder(layout, "f1", "com.b")
        assertEquals(listOf("com.a", "com.b"), layout.folder("f1")!!.pkgs)
        assertTrue(layout.items.none { it is HomeItem.AppItem && it.pkg == "com.b" })

        layout = HomeLayoutEngine.removeFromFolder(layout, "com.a")
        assertEquals(listOf("com.b"), layout.folder("f1")!!.pkgs)
        assertTrue(layout.items.any { it is HomeItem.AppItem && it.pkg == "com.a" })

        // 移出最后一个 → 分组消失
        layout = HomeLayoutEngine.removeFromFolder(layout, "com.b")
        assertNull(layout.folder("f1"))
    }

    @Test
    fun deleteFolderReturnsAppsToDesktop() {
        val layout = HomeLayout(
            items = listOf(HomeItem.FolderItem(HomeFolder("f1", "游戏", listOf("com.a", "com.b"))))
        )
        val after = HomeLayoutEngine.deleteFolder(layout, "f1")
        assertNull(after.folder("f1"))
        assertEquals(2, after.items.count { it is HomeItem.AppItem })
    }

    @Test
    fun renameFolder() {
        val layout = HomeLayout(items = listOf(HomeItem.FolderItem(HomeFolder("f1", "旧名", listOf("com.a")))))
        assertEquals("新名", HomeLayoutEngine.renameFolder(layout, "f1", "新名").folder("f1")!!.name)
    }

    @Test
    fun materializeKeepsExistingOrder() {
        val layout = HomeLayout(items = listOf(HomeItem.AppItem("com.c")))
        val m = HomeLayoutEngine.materialize(layout, labels, allVisible)
        val rebuilt = HomeLayoutEngine.build(m, labels, allVisible)
        assertEquals(
            listOf("com.c", "com.a", "com.b", "com.d"),
            rebuilt.map { (it as LauncherEntry.AppEntry).pkg }
        )
    }

    // ---------------- 拖拽排序 / 拖拽建文件夹 ----------------

    @Test
    fun moveItemReordersDesktop() {
        var layout = HomeLayout(
            items = listOf(HomeItem.AppItem("com.a"), HomeItem.AppItem("com.b"), HomeItem.AppItem("com.c"))
        )
        layout = HomeLayoutEngine.moveItem(layout, "a:com.a", "a:com.c")
        assertEquals(
            listOf("com.b", "com.c", "com.a"),
            layout.items.map { (it as HomeItem.AppItem).pkg }
        )
        // 未知 key 不动
        assertEquals(layout, HomeLayoutEngine.moveItem(layout, "a:nope", "a:com.b"))
    }

    @Test
    fun applyDisplayOrderKeepsHiddenSlots() {
        val hidden = HomeItem.AppItem("com.hidden")
        val layout = HomeLayout(
            items = listOf(HomeItem.AppItem("com.a"), hidden, HomeItem.AppItem("com.b"))
        )
        // 显示的只有 com.b、com.a（com.hidden 被规则隐藏），拖拽后顺序变成 b,a
        val after = HomeLayoutEngine.applyDisplayOrder(layout, listOf("a:com.b", "a:com.a"))
        assertEquals(
            listOf("a:com.b", "a:com.hidden", "a:com.a"),
            after.items.map { HomeLayoutEngine.keyOf(it) }
        )
    }

    @Test
    fun dragAppOntoAppCreatesFolderAtTargetSlot() {
        val layout = HomeLayout(
            items = listOf(HomeItem.AppItem("com.a"), HomeItem.AppItem("com.b"), HomeItem.AppItem("com.c"))
        )
        // 把 com.a 拖到 com.b 上松手
        val merged = HomeLayoutEngine.mergeIntoFolder(layout, "com.a", "a:com.b", "f_new", "文件夹")
        assertEquals(2, merged.items.size)
        // 拖拽项被摘除后，目标应用前移一位，文件夹落在目标位置
        val folder = merged.items[0] as HomeItem.FolderItem
        assertEquals("f_new", folder.folder.id)
        assertEquals("文件夹", folder.folder.name)
        assertEquals(listOf("com.b", "com.a"), folder.folder.pkgs)
        // 被拖的应用不会在桌面重复出现
        assertTrue(merged.items.none { it is HomeItem.AppItem && it.pkg == "com.a" })
    }

    @Test
    fun dragAppOntoFolderAppends() {
        val layout = HomeLayout(
            items = listOf(
                HomeItem.FolderItem(HomeFolder("f1", "游戏", listOf("com.a"))),
                HomeItem.AppItem("com.b")
            )
        )
        val merged = HomeLayoutEngine.mergeIntoFolder(layout, "com.b", "f:f1", "f_new", "文件夹")
        assertEquals(1, merged.items.size)
        assertEquals(listOf("com.a", "com.b"), (merged.items[0] as HomeItem.FolderItem).folder.pkgs)
    }

    @Test
    fun dragAppFromFolderOutToAnotherApp() {
        val layout = HomeLayout(
            items = listOf(
                HomeItem.FolderItem(HomeFolder("f1", "旧组", listOf("com.a", "com.h"))),
                HomeItem.AppItem("com.b")
            )
        )
        // 把已经在文件夹里的 com.h 拖到 com.b 上 → 新文件夹里只有 b 和 h，旧文件夹只剩 a
        val merged = HomeLayoutEngine.mergeIntoFolder(layout, "com.h", "a:com.b", "f_new", "文件夹")
        val oldFolder = merged.items.filterIsInstance<HomeItem.FolderItem>().first { it.folder.id == "f1" }
        assertEquals(listOf("com.a"), oldFolder.folder.pkgs)
        val newFolder = merged.items.filterIsInstance<HomeItem.FolderItem>().first { it.folder.id == "f_new" }
        assertEquals(listOf("com.b", "com.h"), newFolder.folder.pkgs)
    }
    @Test
    fun layoutJsonRoundTrip() {
        val layout = HomeLayout(
            items = listOf(
                HomeItem.AppItem("com.a"),
                HomeItem.FolderItem(HomeFolder("f1", "游戏", listOf("com.b", "com.c")))
            ),
            wallpaper = "/data/user/0/com.discipline.control/files/wallpaper.img"
        )
        val back = HomeLayout.fromJson(JSONObject(layout.toJson().toString()))
        assertEquals(layout, back)
    }
}
