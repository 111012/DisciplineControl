# 更新日志

版本号对应 `app/build.gradle.kts` 的 `versionName` / `versionCode`。

> v0.1.0–v0.1.3 有 git 提交，条目来自 `git log` 与提交 diff。
> v0.1.4–v0.1.6 **未单独提交**，条目按工作区改动时间与 v0.1.6 构建产物整理，版本归属无法从 commit 逐行核对。

---

## v0.1.6 — 2026-10-18（versionCode 7）

### 新增

- debug 广播 `config`：修改 `remindStyle`（0 通知 / 1 悬浮横幅 / 2 先回桌面）、`relaxDelayHours`、`resetHour`。
- debug 广播 `set-usage`：直接设置今日 `opens` / `sessionMin` / `finished` / `warned`，不必真开 3 次游戏。
- debug 广播 `pending-demo`：造一条 20 秒后生效的「放宽」修改，验证待生效链路。
- `rule` 广播支持 `startMin` / `endMin`，可测分钟级时段边界。

### 修复

- 计数间隔设为 0 时，至少保留 **10 秒**回归缓冲：MIUI 游戏加速等系统悬浮窗短暂抢焦点再回来，不再被算成新的一次打开（新增单测 `systemOverlayBlipNeverCountsAsNewOpen`）。

### 构建

- 版本号 `0.1.6` / `versionCode 7`。
- 产物：`dist/DisciplineControl-v0.1.6-debug.apk`。
- 单测 28 个全绿（`EngineTest` 22 + `ModelJsonTest` 6）。
- 完成小米平板 6（Android 13 / MIUI V140）真机验证，详见 [docs/真机验证记录.md](docs/真机验证记录.md)。

---

## v0.1.5 — 2026-10-18（versionCode 6，未提交）

### 修复

- **MIUI 窗口抢焦点导致计时卡死**：拉起游戏时 MIUI 插入 `com.miui.securitycenter` 窗口，会话被暂停后游戏回前台不再发窗口事件。改为双通道：
  - 无障碍事件增加 `TYPE_WINDOWS_CHANGED`；
  - `GuardService` 每 2 秒主动查询 `rootInActiveWindow` 包名兜底；
  - `lastReconciledPkg` 去重，避免同一前台包名重复处理。
- 无障碍配置改为 `typeWindowStateChanged|typeWindowsChanged`、`flagRetrieveInteractiveWindows`、`canRetrieveWindowContent=true`，支持主动查询焦点窗口。

### 验证

- 真机修复后会话不再卡在暂停态，1 分钟 + 宽限 1 分钟链路正常走到 warn / finish。

---

## v0.1.4 — 2026-10-18（versionCode 5，未提交）

### 修复

- `scripts/*.ps1` 改为 **UTF-8 BOM**，兼容 Windows PowerShell 5.1（无 BOM 时中文与脚本解析异常）。
- `scripts/adb-deploy.ps1` 变量 `$home` 改名 `$homeComp`：PowerShell 变量名不区分大小写，`$home` 与只读自动变量 `$HOME` 冲突。
- 真机首次 adb 部署跑通：安装、默认桌面、无障碍、电池白名单、通知权限。

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
