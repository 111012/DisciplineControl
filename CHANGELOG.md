# 更新日志

版本号对应 `app/build.gradle.kts` 的 `versionName` / `versionCode`。

> v0.1.0–v0.1.8 **均已提交**。v0.1.4–v0.1.8 同属一个提交 `323c556`「v0.1.4-v0.1.8: 真机修复轮（含代码审查发现的 P0/P1）」，条目来自该提交说明与 `git show --stat`。
> 当前版本 **v0.1.8 / versionCode 9**，产物 `dist/DisciplineControl-v0.1.8-debug.apk`（`dist/` 里只有这一个）。

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
