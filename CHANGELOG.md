# 更新日志

版本号对应 `app/build.gradle.kts` 的 `versionName` / `versionCode`。

> v0.1.0–v0.2.0 **均已提交**。v0.1.4–v0.1.8 同属提交 `323c556`「v0.1.4-v0.1.8: 真机修复轮（含代码审查发现的 P0/P1）」，v0.2.0 为后续提交。
> 当前版本 **v0.5.0 / versionCode 14**，产物 `dist/DisciplineControl-v0.5.0-release.apk`（日常用）/ `dist/DisciplineControl-v0.5.0-debug.apk`（调试用）。

---

## v0.5.0 — 2026-10-19（versionCode 14）

按手机桌面习惯重做交互手感：Dock 栏 + 动效 + 系统式菜单。

### 新增

- **底部 Dock 栏**：毛玻璃胶囊，固定最多 5 个常用应用（全新安装自动放入微信/QQ/浏览器/文件管理）；Dock 里的应用不再重复出现在网格；把网格图标**拖到 Dock 上松手即加入**（拖拽时 Dock 会放大提示）；长按 Dock 图标 → 「从 Dock 移除」；Dock 应用同样受管控规则约束（被隐身时不显示）
- **手感动画**：图标按下微缩 0.94 / 抬手回弹；长按拖动时抬起放大 1.12 + 抬高阴影 + 轻微透明 + **震动反馈**；新建文件夹带 Overshoot 弹性"弹"出来；进入/退出分组用缩放+淡入淡出过渡（`overridePendingTransition`）
- **系统式 PopupMenu**：长按图标/文件夹改为贴着图标弹出的原生菜单（不再用占满屏的大对话框）
- **去掉教程式常驻文字**：底部"长按图标拖到…"提示删除；拦截提示改为状态胶囊里的一条 chip
- 布局数据新增 `dock` 字段（JSON 持久化），`HomeLayoutEngine` 新增 `moveToDock / removeFromDock / seedDockIfEmpty` 纯函数 + 5 个单测

### 验证

- 真机（Pad 6 / Android 13）：Dock 渲染与默认播种 ✅、长按 Dock 图标 PopupMenu ✅、从 Dock 移除后回到网格 ✅、拖拽加入 Dock ✅、拖拽合并建文件夹回归 ✅（布局里生成 `folder [小米视频, 天气]`）
- 单测 **52 个**全绿（EngineTest 25 + HomeLayoutTest 21 + ModelJsonTest 6）；versionCode 14

---
## v0.4.0 — 2026-10-19（versionCode 13）

桌面交互对齐 iOS/安卓桌面：拖拽建文件夹 + 壁纸原图分辨率。

### 新增

- **长按拖拽建文件夹**：长按应用图标进入拖拽 → 拖到另一个应用图标上悬停 320ms（目标高亮 + 放大 1.08）→ 松手自动创建文件夹并把两个应用收纳进去，文件夹出现在目标位置；拖到已有文件夹上松手 = 加入该文件夹；长按不移动直接松手 = 原来的一键菜单；拖拽过程中可排序
- **拖拽排序**：快速划过图标（不悬停）→ 走 ItemTouchHelper 的 `onMove` 交换，松手后顺序写回布局（`HomeLayoutEngine.applyDisplayOrder`，被规则隐藏的条目位置不变）
- **壁纸改为原图分辨率**：`ImageDecoder`（API 28+）按"覆盖屏幕"尺寸高质量缩放，一次解码、不再"解码小图再放大"，屏幕上是原图清晰度（上限 8MP ≈32MB 内存）；去掉整屏白色遮罩，文字靠玻璃卡片 + 柔光阴影保证可读
- 玻璃头部提高不透明度、底部提示改成玻璃胶囊，高亮壁纸上文字也清楚

### 技术选型（先调研再动手）

- 参考 AOSP Launcher3 的拖拽架构：[DragController](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/src/com/android/launcher3/dragndrop/DragController.java)、[Workspace](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/src/com/android/launcher3/Workspace.java)（`DRAG_MODE_CREATE_FOLDER / ADD_TO_FOLDER / REORDER` 三态 + `getFolderCreationRadius` 命中判定）、[FolderIcon](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/src/com/android/launcher3/folder/FolderIcon.java)
- 官方 [ItemTouchHelper](https://developer.android.google.cn/reference/androidx/recyclerview/widget/ItemTouchHelper) 提供长按起拖、回弹动画、边缘自动滚动；"合并"语义需自写 Callback
- 现成库调研结论：没有能直接用且维护良好的"拖拽合并建文件夹"库；[DSAppTeam/RecyclerViewDrag](https://github.com/DSAppTeam/RecyclerViewDrag)（49★，2023 停更）可参考思路，`android-advancedrecyclerview` 等只做排序不含合并 → 自写，约 400 行

### 实现

- 桌面网格 `GridView` → `RecyclerView` + `GridLayoutManager`（新增 `androidx.recyclerview:recyclerview:1.3.0` 依赖）
- `HomeGridAdapter` 改为 `RecyclerView.Adapter`，支持合并目标高亮
- 命中检测用**拖拽视觉中心到格子中心的距离**（半径 0.45 格宽），不用 `findChildViewUnder`（其按未平移坐标命中，拖起来后不可靠）
- `HomeLayoutEngine` 新增纯函数：`keyOf` / `moveItem` / `applyDisplayOrder` / `mergeIntoFolder`，并加 5 个单测（含"从文件夹里拖出来再合并"）
## v0.3.0 — 2026-10-19（versionCode 12）

桌面外观重做：液态玻璃 + 分组 + 壁纸。

### 新增

- **液态玻璃视觉**：磨砂玻璃卡片（半透明白渐变 + 1dp 高光描边 + 24dp 圆角）、玻璃胶囊、玻璃文件夹格子；页面底色改为多色光斑渐变（蓝/粉/绿/靛），卡片才有东西可"透"；图标文字加白色柔光阴影
- **自定义壁纸**：设置 → 桌面外观 → 壁纸（系统相册 ACTION_PICK，MIUI 弹应用选择时选「相册」，可勾"下次默认"）；图片拷到应用私有目录，自动做磨砂模糊（降采样 + 小图放大，任何 Android 版本可用，输出 ≤1000px 省内存），并叠柔和渐变遮罩保证可读
- **桌面分组**：长按应用 →「移动到分组…」/「新建分组并移入」；分组显示为玻璃文件夹格子，**原位替换**被移入图标（不再丢到列表末尾）；点开进入分组页，点图标启动、长按移出；移空自动消失
- **分组管理页**：设置 → 桌面外观 → 分组管理，可重命名 / 解散（组内应用回桌面）

### 实现要点

- 新增 `core/HomeLayout.kt`：布局模型（条目顺序 + 壁纸路径 + 分组）+ `HomeLayoutEngine` 纯函数（合成渲染条目、隐藏/卸载剔除、空分组消失、新应用追加、原位创建分组、移入/移出/解散/重命名），全部可单测
- 新增 `HomeLayoutTest` 11 个用例（含文件夹原位、隐藏剔除、JSON 往返等），单测合计 **42 个**
- 新增 `HomeGridAdapter`（应用/文件夹双类型）、`FolderActivity`、`GroupManageActivity`
- `Store` 增加 `layout()` / `saveLayout()`（JSON 持久化）

### 验证

- 真机（Pad 6 / Android 13）逐项点测：玻璃界面渲染、长按分组（原位出现）、分组页进入、分组管理解散、相册选壁纸 → 磨砂效果生效（`files/wallpaper.img` 321KB）、恢复默认背景
- release 包覆盖安装（versionCode 12），默认桌面/无障碍/守护服务正常

---

## v0.2.1 — 2026-10-19（versionCode 11）

性能与交付工具。

### 变更

- `Engine.nextOpenText` 由"逐分钟扫 14 天（最多 2 万次判断）"改为"只算各时段下一个开始时刻"（O(时段数×14)）；桌面每分钟在主线程调用它，收益在多规则时明显
- 新增 `scripts/adb-verify.ps1`：一键真机验收（装 debug → 时段外拦截/次数上限/计时提醒/待生效生效/关屏亮屏续接 → PASS/FAIL 汇总 → 清理并装回 release）
- 新增 `DELIVERY.md`：交付说明（装哪个包、五分钟上手、参数表、验证结果、已知限制、维护命令）
- 单测 **31 个**（EngineTest 25 + ModelJsonTest 6），新增"下次开放时间"的星期/跨零点/时段内用例

---

## v0.2.0 — 2026-10-19（versionCode 10）

首个 release 包 + 跨天逻辑抽纯函数。

### 新增 / 修复

- 新增 `Engine.logicalDate(now, resetHour)` 纯函数（非法重置小时夹到 0–23），`Store.logicalDate` 改为调用它；补单测覆盖 0 点 / 4 点重置边界，跨天行为不再依赖真机等 0 点
- 真机验证「跨 0 点重置」：23:49 把当天次数消耗满 → 00:01 检查 `day={"date":"2026-10-19","apps":{}}`，次数清零、王者重新可见，规则本身保留
- 正式产出 **release 包**（2.3 MB）：`android:debuggable` 关闭、无 `DebugReceiver`；与 debug 包同签名，可互相覆盖安装
- `app/src/release/AndroidManifest.xml` 用 `tools:node="remove"` 在 release 清单里移除调试广播组件（源码另有 `BuildConfig.DEBUG` 兜底）

### 构建 / 验证

- `:app:assembleRelease` 通过；`apksigner verify` 证书与 debug 一致；`aapt dump badging` 无 debuggable 属性
- 单测 **30 个**全绿（EngineTest 24 + ModelJsonTest 6）
- release 包已覆盖安装到平板（versionName 0.2.0），默认桌面 / 无障碍 / 前台守护服务均正常，调试广播在 release 下无响应（符合预期）

---

## v0.1.8 — 2026-10-18（versionCode 9）

代码审查（P0/P1）修复轮，7 组问题：

### 修复

- **P0：拦截去重把「回桌面」一起吞掉**。1.5 秒去重逻辑把 `goHome()` 也跳过：被弹回桌面后 1.5 秒内再点一次可绕过管控，而且会话不再计时。改为**去重只用于通知**，`goHome()` 无条件执行并清 `lastReconciledPkg`。
- **关屏再亮屏后计时永久停摆**。`pauseSession` 没清前台去重状态，亮屏后同一包名被判为「没变化」而跳过。已在 pause 时清空去重状态。
- **跨天把昨天时长记到今天并压死新一天**。`Session` 改为记录逻辑日期，跨天结算写回原日期；`saveDay` 改同步 `commit()`，不再用旧数据污染当天缓存。
- **跨零点时段只认开始日**。只配「周一 22:00–02:00」时，周二 00:30 会被判成不在时段；改为按归属开始日判定（周二 00:30 属于周一那段）。
- **待生效修改会回滚期间的收紧**。登记待生效修改时记录 base 快照，等待期内规则被再次修改则整条作废，不会等 N 小时后把后来的收紧覆盖回去。
- **改「每天几点重置次数」可清空当天额度**。改为走放宽延迟生效，不能再靠改重置时间点立刻把当天次数清零。
- **其它修复**：空/非数字输入不再静默当 0（0 = 不限）；规则 JSON 损坏回退 `rules_bak`；`goHome` 失败重试；`windows-changed` 探测 300ms 节流；桌面用 `GuardService.isRunning()` 判断守护是否真在跑；PIN 设置取消后关页面；overlay 在服务销毁时移除。

### 构建

- 版本号 `0.1.8` / `versionCode 9`。
- 产物：`dist/DisciplineControl-v0.1.8-debug.apk`（`dist/` 里唯一的包）。
- 单测 **29 个**全绿（`EngineTest` 23 + `ModelJsonTest` 6）。

### 验证

- 真机回归：P0 连续快速点开 3 次全部弹回、关屏/亮屏 `sessionMs` 不重置、待生效修改 30 秒内自动生效、无障碍被关 15 秒内告警并可从通知进 RepairActivity。详见 [docs/真机验证记录.md](docs/真机验证记录.md)。

---

## v0.1.7 — 2026-10-18（versionCode 8）

### 新增

- 无障碍被系统关掉时**主动告警**：检测到服务没了就发「拦截已失效 / 无障碍服务被系统关掉了，点这里一键修复」通知，常驻通知同步改成 ⚠ 文案。
- 新增 `ui/RepairActivity` 一键修复页：**不需要 PIN**，只提供三个入口——开启无障碍 / 设为默认桌面 / 通知权限；进页面先列三项 ✅/❌ 当前状态。
- 通知支持自定义 Intent（点「拦截已失效」直达 RepairActivity）。

### 验证

- 真机：手动关掉无障碍后 15 秒内弹通知，点通知能打开修复页。

---

## v0.1.6 — 2026-10-18（versionCode 7）

### 文档

- README 补真机验证记录与「真机暴露的两个坑」。
- 新建 `docs/真机验证记录.md`，`docs/验收测试清单.md` 逐项打勾，本次 CHANGELOG 补齐。

### 说明

- 该版提交内容以文档与验证记录为主（代码仅版本号变化）。

---

## v0.1.5 — 2026-10-18（versionCode 6）

### 新增

- debug 广播扩展：`config`（`remindStyle` / `relaxDelayHours` / `resetHour`）、`set-usage`（直接写今日 `opens` / `sessionMin` / `finished` / `warned`）。
- `rule` 广播支持 `startMin` / `endMin`，可测分钟级时段边界；新增 `pending-demo`（造一条 20 秒后生效的放宽修改）。

### 修复

- **会话回归缓冲**：计数间隔设 0 时也保留 **10 秒最小缓冲**，系统悬浮窗（如 MIUI 游戏加速 `com.miui.securitycenter`）闪一下不再算成新一次打开。新增单测 `systemOverlayBlipNeverCountsAsNewOpen`。

---

## v0.1.4 — 2026-10-18（versionCode 5）

### 修复

- **前台监听双通道**，修复「MIUI 游戏加速 `com.miui.securitycenter` 抢焦点后、游戏回前台不发窗口事件 → 计时卡死」：
  - 无障碍事件增加 `TYPE_WINDOWS_CHANGED`；
  - `GuardService` 每 2 秒主动查询 `rootInActiveWindow` 包名兜底；
  - 无障碍配置改为 `typeWindowStateChanged|typeWindowsChanged` + `flagRetrieveInteractiveWindows` + `canRetrieveWindowContent=true`；
  - `lastReconciledPkg` 去重，同一前台包名不重复处理。
- `scripts/*.ps1` 改为 UTF-8 BOM（兼容 Windows PowerShell 5.1）；`adb-deploy.ps1` 变量 `$home` 改名 `$homeComp`（与只读自动变量 `$HOME` 冲突）。

### 验证

- 真机：会话不再卡在暂停态，1 分钟 + 宽限 1 分钟正常走到 warn / finish。

---

## v0.1.3 — 2026-10-18（versionCode 4）

### 修复

- 时段结束只结束**本次会话**，不再把当日判定为用完（修复多时段配置下第二段进不去）。`finished=false`，次数不额外消耗。
- `SystemUI` / 输入法 / 权限弹窗等系统临时窗口不算离开游戏，计时不中断。

### 新增

- `DebugReceiver`（仅 debug 构建）：adb 广播 `status` / `reset-day` / `rule` / `remove` / `clear` / `start-service`。
- `ModelJsonTest`：规则、配置（含 `warnOverlay → remindStyle` 迁移）、用量 JSON 往返共 6 例。
- `scripts/adb-smoke.ps1`：`block` / `warn` / `count` / `status` / `reset` 五种真机验证模式。

### 测试

- 27 个 JVM 单测。

---

## v0.1.2 — 2026-10-18（versionCode 3）

### 新增

- 关键路径日志 tag：`DC/Guard`（判定 / 会话 / 提醒 / 强制退出）、`DC/A11y`（前台包名）、`DC/Home`、`DC/App`。
- `scripts/adb-deploy.ps1`：装包 + 授通知 + 设默认桌面 + 开无障碍 + 忽略电池优化，并回显验证。
- `scripts/adb-logs.ps1` / `scripts/adb-shot.ps1`：实时日志与截图。

### 修复

- adb server 临时目录改到 `.toolchain/tmp`，避免 `%TEMP%` 写入被拒导致 daemon 起不来。

---

## v0.1.1 — 2026-10-18（versionCode 2）

### 新增

- 到点提醒改为三档：仅通知 + 震动（默认）/ 通知 + 游戏内悬浮横幅 / 到点先退回桌面。
- 桌面显示倒计时 chip「王者荣耀 还剩 X 分钟」。
- 移除管控也走「放宽」延迟。

### 修复

- 已提醒（`warned`）后再次进入算续接会话，不会把计时清零重来。
- 新增 `Engine.isResumingSession` 纯函数 + 3 个单测。

### 测试

- 20 个 JVM 单测。

---

## v0.1.0 — 2026-10-18（versionCode 1）

### 新增

- 自建桌面（HOME + LAUNCHER）：只列出当前允许显示的应用，实现图标隐身。
- 无障碍前台监听 + `performGlobalAction(HOME)` 拦截受限应用。
- 每个应用独立规则：开放时段（多时段 / 按星期 / 可跨零点）、每日次数、单次时长、提醒后宽限、计数间隔、时段结束是否立即退出。
- 待生效修改：放宽类修改延迟 N 小时生效，收紧立即生效。
- PIN 保护（加盐 SHA-256）。
- 最近 7 天使用统计。
- 保活：前台服务 + `START_STICKY` + 15 分钟 AlarmManager 自拉起 + 开机自启。
- 固定签名 `keystore/discipline.jks`，支持覆盖安装。

### 测试

- 17 个 JVM 单测（时段边界、次数 / 时长判定、跨零点、放宽判断）。
