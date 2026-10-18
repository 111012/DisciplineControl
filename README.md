# 自律控制（Discipline Control）

给小米平板 5 用的**自律 / 家长控制** App：把指定应用（比如王者荣耀）在桌面隐藏起来，只在你设定的时间段内可见；每天有打开次数上限；单次使用到点先提醒、再强制退出。**所有参数都在 App 里改，没有写死。**

- 纯本地运行：不联网、不上传任何数据、不申请存储权限
- 方案：**自建桌面（Launcher）+ 无障碍前台监听 + 前台计时服务**
- 包名 `com.discipline.control`，`minSdk 26` / `targetSdk 33`，Kotlin + AGP 7.4.2 + Gradle 7.6.4

> **当前状态**：v0.1.0 已构建通过，17 个 JVM 单测全绿。
> 安装包：`dist/DisciplineControl-v0.1.0-debug.apk`（2.9 MB，固定签名）
> 安装步骤：[docs/平板安装与使用说明.md](docs/平板安装与使用说明.md)
> 逐项验收：[docs/验收测试清单.md](docs/验收测试清单.md)

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

单元测试（纯 JVM，不需要真机，覆盖时段边界、次数/时长判定、跨零点、放宽判断等 17 个用例）：

```powershell
.\.toolchain\gradle-7.6.4\bin\gradle.bat :app:testDebugUnitTest --no-daemon
```

> JUnit 4 的两个 jar 直接放在 `app/libs/`（`junit-4.13.2.jar`、`hamcrest-core-1.3.jar`），不依赖联网解析，避免镜像抽风。

签名：`keystore/discipline.jks`（别名 `discipline`，密码 `***REMOVED***`）。**固定签名**，后续版本可覆盖安装，不会丢数据。

依赖全部走阿里云 Maven 镜像（见 `settings.gradle.kts`）。

---

## 四、安装与授权（重要，缺一不可）

见 [docs/平板安装与使用说明.md](docs/平板安装与使用说明.md)。核心 4 步：设为默认桌面 → 开无障碍 → 允许通知 → 关电池优化 + MIUI 允许自启动。

---

## 五、已知限制

1. **不是系统级隐藏**：本方案不申请设备管理员（Device Owner），因此从"最近任务"点开受限应用时，画面会闪现一下再被弹回桌面。彻底隐藏需要一次性 ADB 配置设备管理员，且卸载只能靠恢复出厂 —— 不在 v0.1 范围内。
2. **无障碍可被手动关闭**：这是 Android 的限制，任何非 root 方案都绕不过。App 能做的是把入口藏起来 + 加 PIN，提高成本而不是绝对锁死。
3. **MIUI 会杀后台**：必须手动允许自启动、省电无限制、锁定后台，否则计时可能中断（中断后靠前台服务重启 + 15 分钟自拉起恢复）。
4. **游戏内悬浮提醒默认关闭**：腾讯 ACE 可能把 `TYPE_APPLICATION_OVERLAY` 判定为辅助工具，所以默认只用通知 + 震动。需要"砸脸"提醒可在设置里打开，风险自担。
5. 计时以"前台应用"为准，无法精确到"进入对局"；只要王者在前台就开始计时。

---

## 六、后续可做（v0.2 候选）

- 设备管理员加固（真正系统级隐藏 + 防卸载），做成可选开关
- 桌面图标排序 / 分组 / 隐藏的系统应用入口
- 按应用分类批量管控（游戏类 / 短视频类一键加规则）
- 规则模板（"考试周"模式）与配置导出/导入
- 家长远程模式 / 多设备同步（需要联网，默认不做）
