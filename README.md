# 自律控制（Discipline Control）

给小米平板用的**自律 / 家长控制** App（小米平板 5/6 均可，实测 Pad 6）：把指定应用（比如王者荣耀）在桌面隐藏起来，只在你设定的时间段内可见；每天有打开次数上限；单次使用到点先提醒、再强制退出。**所有参数都在 App 里改，没有写死。**

- 纯本地运行：不联网、不上传任何数据、不申请存储权限
- 方案：**自建桌面（Launcher）+ 无障碍前台监听 + 前台计时服务**
- 包名 `com.discipline.control`，`minSdk 26` / `targetSdk 33`，Kotlin + AGP 7.4.2 + Gradle 7.6.4

> **当前状态**：v0.1.8 已构建并在小米平板 6 上完成真机验证（含 v0.1.8 代码审查修复的回归），**29 个** JVM 单测全绿。
> 安装包：`dist/DisciplineControl-v0.1.8-debug.apk`（versionCode 9，约 3.0 MB，固定签名；`dist/` 里只有这一个）
> 首次使用要设 PIN（测试期设的是 `1234`，自己用请改掉：设置 → 修改 PIN）
> 安装步骤：[docs/平板安装与使用说明.md](docs/平板安装与使用说明.md)
> 逐项验收：[docs/验收测试清单.md](docs/验收测试清单.md)
> 真机记录：[docs/真机验证记录.md](docs/真机验证记录.md)
> 更新日志：[CHANGELOG.md](CHANGELOG.md)

---

## 一、功能清单（v0.1）

| 能力 | 说明 |
|---|---|
| 桌面图标隐身 | 本 App 就是桌面：只列出"当前允许显示"的应用；不在时段内 / 次数用完 / 今日已用完 → 图标直接从桌面消失 |
| 入口拦截 | 从最近任务、通知、其他 App 拉起时，无障碍服务检测到前台包名立即送回桌面，并弹通知说明原因 |
| 开放时段 | 每个应用独立配置，支持一天多个时段、按星期区分、可跨零点 |
| 每日次数 | 每日最多 N 次，第 N+1 次直接隐身（0 = 不限） |
| 单次时长 | 使用满 X 分钟后弹提醒 + 震动，再过 Y 分钟强制退出并当日隐身 |
| 到点提醒 | 三档可选：仅通知+震动（默认）／ 通知+游戏内悬浮横幅 ／ 到点先退回桌面（最强烈且不覆盖画面） |
| 计数规则 | 离开超过 Z 分钟再回来才算"新的一次打开"（防止切个后台就消耗一次） |
| 时段结束 | 可配置到点是否立即踢出 |
| 待生效修改 | 涉及**放宽**的修改（调大次数/时长、扩时段、停用管控、移除管控）会延迟 N 小时生效，收紧立即生效 —— 防止一时冲动改掉限制 |
| PIN 保护 | 进设置页要 PIN；PIN 只存加盐 SHA-256 |
| 统计 | 最近 7 天每个被管控应用的打开次数与使用时长 |
| 保活 | 前台服务 + START_STICKY + 15 分钟 AlarmManager 自拉起 + 开机自启 |
| 失效告警 | 无障碍被系统关掉（含被"强行停止"）→ 15 秒内发「拦截已失效」通知，常驻通知与桌面变 ⚠；点通知进一键修复页（无需 PIN） |

### 默认规则（添加应用时自动套用，之后可改）

| 参数 | 默认值 |
|---|---|
| 开放时段 | 每天 20:00–23:00 |
| 每日次数 | 3 次 |
| 单次时长 | 60 分钟 |
| 提醒后宽限 | 20 分钟（即最长 80 分钟） |
| 计数间隔 | 离开 5 分钟算新的一次 |
| 时段结束 | 立即退出 |
| 跨天重置 | 0 点 |
| 放宽延迟 | 24 小时 |
| 游戏内悬浮提醒 | 关闭（默认用"仅通知 + 震动"，不覆盖游戏画面） |

---

## 二、代码结构

```
app/src/main/java/com/discipline/control/
├── ControlApp.kt                  Application：初始化 + 拉起守护服务
├── core/
│   ├── Models.kt                  规则/时段/用量数据模型 + Engine 判定（可见性、拦截原因、下次开放、放宽判断）
│   ├── Store.kt                   SharedPreferences JSON 存储（规则 / 配置 / 当日用量 / 待生效修改）
│   ├── Apps.kt                    已安装应用列表（带 30 秒缓存 + 图标缓存）
│   ├── Pin.kt                     PIN 设置 / 校验 / 门禁
│   ├── Notifier.kt                通知渠道、常驻通知、提醒通知
│   ├── Overlay.kt                 可选游戏内悬浮横幅
│   └── Utils.kt                   dp、时间格式化
├── service/
│   ├── GuardService.kt            前台守护服务：会话计时、到点提醒、超限强制退出、拦截
│   ├── GuardAccessibilityService.kt  前台包名监听 + performGlobalAction(HOME)
│   └── BootReceiver.kt            开机 / 更新后拉起服务
└── ui/
    ├── HomeActivity.kt            桌面（HOME + LAUNCHER）
    ├── SettingsActivity.kt        设置：权限引导 / 管控列表 / 全局参数 / 待生效 / PIN
    ├── RepairActivity.kt          「拦截已失效」一键修复页（无需 PIN：无障碍 / 默认桌面 / 通知）
    ├── AppRuleEditActivity.kt     单个应用规则编辑（时段、次数、时长、宽限、计数间隔）
    ├── AppPickerActivity.kt       从已安装应用里挑要管控的
    ├── StatsActivity.kt           最近 7 天统计
    ├── AppGridAdapter.kt / Ui.kt  桌面网格适配器、通用 UI 构造工具
```

### 关键判定逻辑

- 桌面可见：`enabled && !finished && inWindow(now) && opens < dailyOpenLimit`
- 启动拦截（无障碍收到前台事件）：
  1. 今日已 `finished` → `DAY_FINISHED`
  2. 不在时段 → `NOT_IN_WINDOW`
  3. 非"续接会话"且次数已满 → `NO_OPENS_LEFT`
  4. 否则开始/续接会话；`now - lastExitAt <= countGapMin` 视为续接，不消耗次数
- 计时：`elapsed = 已累计 + (now - resumedAt)`；`elapsed >= limit` 首次提醒；`elapsed >= limit + grace` 强制退出 + `finished = true`
- 强制退出 = `performGlobalAction(GLOBAL_ACTION_HOME)`（不 force-stop 其他 App，无需 root）

---

## 三、构建

工具链已装在项目内（`.toolchain/`，已被 .gitignore 忽略）：

```
JDK   : D:\4.program\JavaDevelopment\Java\JDK11
SDK   : .toolchain\android-sdk        (platform-tools 37, platforms;android-33, build-tools;33.0.2)
Gradle: .toolchain\gradle-7.6.4
```

```powershell
$env:JAVA_HOME        = 'D:\4.program\JavaDevelopment\Java\JDK11'
$env:GRADLE_USER_HOME = 'D:\DSH_WorkSpace\DisciplineControl\.toolchain\gradle-home'
$env:ANDROID_HOME     = 'D:\DSH_WorkSpace\DisciplineControl\.toolchain\android-sdk'
cd D:\DSH_WorkSpace\DisciplineControl
.\.toolchain\gradle-7.6.4\bin\gradle.bat :app:assembleDebug --no-daemon
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（同时拷到 `dist/`）

单元测试（纯 JVM，不需要真机，覆盖时段边界、次数/时长判定、跨零点、放宽判断、会话续接、跨天记账、JSON 往返等 29 个用例：`EngineTest` 23 + `ModelJsonTest` 6，v0.1.8 全绿）：

```powershell
.\.toolchain\gradle-7.6.4\bin\gradle.bat :app:testDebugUnitTest --no-daemon
```

> JUnit 4 的两个 jar 直接放在 `app/libs/`（`junit-4.13.2.jar`、`hamcrest-core-1.3.jar`），不依赖联网解析，避免镜像抽风。

签名：`keystore/discipline.jks`（别名 `discipline`，密码 `***REMOVED***`）。**固定签名**，后续版本可覆盖安装，应用数据不会丢；但 MIUI 在覆盖安装后会把默认桌面重置回小米桌面，升级后必须重设一次（见第七节）。

依赖全部走阿里云 Maven 镜像（见 `settings.gradle.kts`）。

---

## 四、安装与授权（重要，缺一不可）

见 [docs/平板安装与使用说明.md](docs/平板安装与使用说明.md)。核心 4 步：设为默认桌面 → 开无障碍 → 允许通知 → 关电池优化 + MIUI 允许自启动。

---

## 五、已知限制

1. **不是系统级隐藏**：本方案不申请设备管理员（Device Owner），因此从"最近任务"点开受限应用时，画面会闪现一下再被弹回桌面。彻底隐藏需要一次性 ADB 配置设备管理员，且卸载只能靠恢复出厂 —— 不在 v0.1 范围内。
2. **无障碍可被手动关闭**：这是 Android 的限制，任何非 root 方案都绕不过。App 能做的是把入口藏起来 + 加 PIN，提高成本而不是绝对锁死；v0.1.7 起服务被系统关掉时会主动发「拦截已失效」通知，可一键跳修复页。
3. **MIUI 会杀后台**：必须手动允许自启动、省电无限制、锁定后台，否则计时可能中断（中断后靠前台服务重启 + 15 分钟自拉起恢复）。
4. **游戏内悬浮提醒默认关闭**：腾讯 ACE 可能把 `TYPE_APPLICATION_OVERLAY` 判定为辅助工具，所以默认只用通知 + 震动。需要"砸脸"提醒可在设置里打开，风险自担。
5. 计时以"前台应用"为准，无法精确到"进入对局"；只要王者在前台就开始计时。
6. **覆盖安装会重置默认桌面**：MIUI 在 `adb install -r` 后把主屏幕应用换回小米桌面，图标隐身随之中断。升级后重跑 `scripts/adb-deploy.ps1` 或重新设一次默认桌面（见第七节）。
7. **用系统「强行停止」/`am force-stop` 会把无障碍服务一起关掉**：这是 Android 行为，本应用绕不过（实测复现：force-stop 后 `enabled_accessibility_services` 被清空，受限应用不再被拦）。恢复方式：重新打开一次无障碍，或点「拦截已失效」通知进修复页一键修复。桌面和常驻通知现在会显示「守护未运行 / ⚠ 无障碍已关闭」。

### 已修复问题（v0.1.8 代码审查）

| # | 问题（严重度） | 原因 | 修复 |
|---|---|---|---|
| 1 | 1.5 秒去重把「回桌面」一起吞掉 —— 被弹回后 1.5 秒内再点一次可绕过管控且不再计时（**P0**） | 去重判断同时跳过了 `goHome()` | 去重只用于通知，`goHome()` 无条件执行，并清 `lastReconciledPkg` |
| 2 | 关屏再亮屏后计时永久停摆 | `pauseSession` 没清前台去重状态，同包名被判「没变化」 | pause 时清空去重状态，亮屏后 `session start resuming=true` |
| 3 | 跨天把昨天时长记到今天、压死新一天 | Session 用当天日期落盘，缓存被旧数据污染 | Session 记录逻辑日期，跨天结算回原日期；`saveDay` 改同步 `commit()` |
| 4 | 跨零点时段只认开始日（只选周一 22:00–02:00 时，周二 00:30 被判不在时段） | 时段归属按「当前星期」算 | 按归属开始日判定，周二 00:30 属于周一那段 |
| 5 | 待生效修改会把等待期内的收紧回滚掉 | 生效时直接覆盖整条规则 | 登记时存 base 快照，规则被改过则整条作废 |
| 6 | 改「每天几点重置次数」能清空当天额度 | 该修改走了立即生效 | 改为走放宽延迟生效 |
| 7 | 其它：空/非数字输入被静默当 0（=不限）、规则 JSON 损坏无法恢复、`goHome` 偶发失败、`windows-changed` 事件风暴、桌面误判守护状态、PIN 设置取消后停留在空页面 | — | 空输入拒绝保存；JSON 损坏回退 `rules_bak`；`goHome` 重试；探测 300ms 节流；`GuardService.isRunning()`；取消即关页面 |

---

## 六、真机调试（adb）

工具链里自带 adb，脚本都在 `scripts/`。平板需开「USB 调试 + USB 安装」，插线选「传输文件」。

```powershell
pwsh -File scripts/adb-deploy.ps1          # 装包 + 授通知 + 设默认桌面 + 开无障碍 + 忽略电池优化 + 回显验证
pwsh -File scripts/adb-logs.ps1            # 实时看 DC/Guard、DC/A11y、DC/Home 判定日志
pwsh -File scripts/adb-shot.ps1            # 截图到 screenshots/
pwsh -File scripts/adb-smoke.ps1 -Mode block   # 时段外拉起王者，验证 2 秒内被弹回
pwsh -File scripts/adb-smoke.ps1 -Mode warn    # 2 分钟提醒 + 1 分钟宽限，跑完整提醒→强制退出
pwsh -File scripts/adb-smoke.ps1 -Mode count   # 连续打开 4 次验证次数限制
pwsh -File scripts/adb-smoke.ps1 -Mode status  # 打印当前规则 / 今日用量 / 待生效修改
```

**仅 debug 构建**提供的调试广播（release 里 `DebugReceiver` 直接 return），方便不点 UI 就改规则：

```powershell
adb shell am broadcast -a com.discipline.control.DEBUG -e cmd reset-day
adb shell am broadcast -a com.discipline.control.DEBUG -e cmd rule --es pkg com.tencent.tmgp.sgame `
    --ei startHour 20 --ei endHour 23 --ei opens 3 --ei sessionMin 60 --ei graceMin 20 --ei gapMin 5
# 分钟级时段边界：startMin/endMin 优先于 startHour/endHour
adb shell am broadcast -a com.discipline.control.DEBUG -e cmd rule --es pkg com.tencent.tmgp.sgame `
    --ei startMin 1330 --ei endMin 1400 --ei opens 3
# 直接设今日用量，省得为测次数真去开 3 次（opens / sessionMin / finished / warned）
adb shell am broadcast -a com.discipline.control.DEBUG -e cmd set-usage --es pkg com.tencent.tmgp.sgame `
    --ei opens 3 --ei sessionMin 59 --ez finished false
# 改全局配置（remindStyle 0=通知 / 1=悬浮横幅 / 2=先回桌面；relaxDelayHours；resetHour）
adb shell am broadcast -a com.discipline.control.DEBUG -e cmd config --ei remindStyle 2 --ei relaxDelayHours 0
# 造一条 20 秒后生效的「放宽」修改，验证待生效链路
adb shell am broadcast -a com.discipline.control.DEBUG -e cmd pending-demo --es pkg com.tencent.tmgp.sgame
adb shell am broadcast -a com.discipline.control.DEBUG -e cmd status
adb shell am broadcast -a com.discipline.control.DEBUG -e cmd remove --es pkg com.tencent.tmgp.sgame
adb shell am broadcast -a com.discipline.control.DEBUG -e cmd clear
```

> `pending-demo` 会把该应用每日次数 +5 并设为 20 秒后生效，用来验证「待生效 → 到点自动应用 → 通知」整条链路。

> 调试入口只在 debug 包里生效（`BuildConfig.DEBUG` 判断），正式发布包会关掉。

## 七、真机验证记录

2026-10-18 在**小米平板 6**上完成两轮真机验证：v0.1.6 基线 + v0.1.8 代码审查修复的回归，核心链路全部通过。完整时间线、日志和遗留项见 [docs/真机验证记录.md](docs/真机验证记录.md)。

| 项 | 值 |
|---|---|
| 机型 | 小米平板 6（Xiaomi Pad 6），型号 23043RP34C |
| 系统 | Android 13 / MIUI V140 |
| 屏幕 | 1800×2880（横屏 2880×1800），密度 400 |
| 安装包 | `dist/DisciplineControl-v0.1.8-debug.apk`（versionCode 9） |

> 早期文档按「小米平板 5」编写，实际验证机是 Pad 6；Pad 5 未实测。

### 已验证

| 项 | 结果 |
|---|---|
| 安装 / 授权 | ✅ `adb install -r -g`；默认桌面、无障碍、电池白名单、通知权限均自动完成 |
| 默认桌面 | ✅ `cmd package set-home-activity com.discipline.control/.ui.HomeActivity` |
| 桌面图标隐身 | ✅ 时段外受控应用消失，状态行「已隐身 · 明天 03:00 开放」 |
| 启动拦截 | ✅ 拉起王者 0.4 秒内弹回桌面，`reason=NOT_IN_WINDOW` |
| 计时链路 | ✅ 单次 1 分钟 + 宽限 1 分钟：第 61 秒 warn，第 121 秒 `finish reason=DAY_FINISHED` 并回桌面 |
| 次数限制 | ✅ `opens=3` 时第 4 次被拦，`reason=NO_OPENS_LEFT` |
| 时段结束 | ✅ `window end -> end session`，只结束本次会话（`finished=false`、`opens=1`），不锁死整天 |
| 到点提醒第三档 | ✅ 60 秒 warn 后立即回桌面，不覆盖游戏画面 |
| 设置 / PIN / 规则页 / 待生效 | ✅ 均实际点过；停用管控显示「10-19 23:35 生效」+ 取消按钮 |
| 拦截去重（v0.1.8 P0 回归） | ✅ 连续 3 次快速点开王者（间隔 1.2 秒）每次都被弹回，日志 `deny=NOT_IN_WINDOW` 后 `topResumedActivity` 均为自律控制桌面 |
| 关屏 / 亮屏计时 | ✅ `session pause` → 亮屏 `session start resuming=true`，`sessionMs` 6739 → 8159 未重置 |
| 待生效修改自动生效 | ✅ 20 秒版：30 秒内 `applyDuePending` 自动应用，`dailyOpenLimit 1→6`、pending 清空 |
| 无障碍被关告警 | ✅ 15 秒内出现「拦截已失效」通知，点通知进 RepairActivity（✅ 无障碍 / 默认桌面 / 通知） |

### 真机暴露的两个坑（已修）

| 问题 | 现象 | 处理 |
|---|---|---|
| MIUI 插入 `com.miui.securitycenter` 窗口 | 拉起游戏时会话被暂停；游戏回到前台不再发窗口事件，计时卡死 | 改为「无障碍事件 + 每 2 秒主动查询 `rootInActiveWindow` 包名」双通道，补 `typeWindowsChanged`，无障碍配置允许取窗口内容 |
| 覆盖安装重置默认桌面 | `adb install -r` 后主屏幕应用回到小米桌面，图标隐身失效 | 升级后重跑 `scripts/adb-deploy.ps1` 或重新 `set-home-activity`，并用 `resolve-activity` 复查 |

### 如何复跑

```powershell
Set-ExecutionPolicy -Scope Process Bypass   # 仅 Windows PowerShell 5.1 需要；pwsh 7 可跳过
pwsh -File scripts/adb-deploy.ps1           # 装包 + 授权 + 默认桌面 + 无障碍 + 回显验证
pwsh -File scripts/adb-smoke.ps1 -Mode block
pwsh -File scripts/adb-smoke.ps1 -Mode warn
pwsh -File scripts/adb-smoke.ps1 -Mode count
pwsh -File scripts/adb-smoke.ps1 -Mode status
```

> `warn` 模式脚本默认 2 分钟 + 宽限 1 分钟（约 3.5 分钟）；本次实测用 `rule --ei sessionMin 1 --ei graceMin 1` 缩短到 1+1。

## 八、后续可做（v0.2 候选）

- 设备管理员加固（真正系统级隐藏 + 防卸载），做成可选开关
- 桌面图标排序 / 分组 / 隐藏的系统应用入口
- 按应用分类批量管控（游戏类 / 短视频类一键加规则）
- 规则模板（"考试周"模式）与配置导出/导入
- 家长远程模式 / 多设备同步（需要联网，默认不做）
