# 别错过（qiuzhao-alarm）

> 原名「秋招闹钟」。改名原因：①原名太直白，桌面上一眼就暴露在做什么；
> ②它现在不只管秋招，还管每日打卡（如多邻国），叫"秋招闹钟"已不准确。

一个只做一件事的 Android App：**把秋招监控的 ICS 截止日变成"静音/震动模式下也会响"的闹钟**。

做它的原因：现成方案都有缺口——
- Outlook / Google 的 ICS **订阅**日历是只读的，**不触发提醒**；
- KashCal 这类 App 的提醒走**普通通知音量流**，静音和勿扰会掐掉（已全库检索确认它没有用过 `USAGE_ALARM`）；
- 系统时钟的闹钟能做到"静音也响"，但没法和 ICS 联动。

本 App 补的正是这个缺口。

## 它做什么

1. 拉取 ICS（默认 jsDelivr 那个地址，可在界面改）；
2. 解析 `VEVENT` + `VALARM`，算出每个提醒的触发时刻；
3. **在界面上按时间列出「即将提醒」**（最近 15 条，左边是提醒时间）——所以它自己就能"看见"截止日，不需要再挂一个日历 App；
4. **「我的每日提醒」**：与秋招无关的日常打卡（如多邻国），可自己加时间与内容、可删除，走同一套闹钟音量流。存在本机，不进那份公开的 ICS；
5. 用 `AlarmManager.setAlarmClock()` 排成系统闹钟（Doze 下也能准时唤醒）；
6. 到点：**全屏弹出 + 走闹钟音量流循环响铃 + 震动**，直到点「关闭」；没关掉则每 60 秒再响一次，最多 5 次；
7. 每天 **20:05** 自动同步一次（等 19:30 的监控任务跑完并推送之后）；
8. 开机 / 应用更新后自动恢复全部排程。

> 首次运行会预置一条「多邻国打卡 21:00」，删掉后不会再重复预置。
> 自定义提醒按 10 天滚动排程，每天 20:05 的自动同步会补足。

## 与 KashCal 的关系（为什么不合并）

最初尝试用现成的 [KashCal](https://github.com/KashCal/KashCal)（ICS 订阅 + 提醒都齐全），但**它的提醒走普通通知音量流，静音/震动模式下会被系统掐掉**——全库检索确认它从未用过 `USAGE_ALARM`。这正是本项目存在的唯一理由。

**没有去改 KashCal 源码**，原因：它是 Kotlin + Gradle + AGP 工程，而本机只有 JDK 26（AGP 不支持），要合并得先装 JDK 17/21 + Gradle + Kotlin 工具链并下载数百 MB 依赖；且重签名后必须卸载官方版、失去后续更新。相比之下本项目零依赖、24 KB，改一行重编一条命令。

因此**两者选一即可，本项目已含事件列表，可直接卸载 KashCal**。

## 关键技术点（决定"静音也响"的三行）

| 位置 | 作用 |
|---|---|
| `NotificationChannel.setSound(uri, AudioAttributes USAGE_ALARM)` | 通知音走**闹钟音量流** → 静音/震动模式不掐 |
| `NotificationChannel.setBypassDnd(true)` | 勿扰模式下也放行（需授予勿扰访问权限） |
| `Notification.setFullScreenIntent(...)` | 锁屏时直接弹出全屏闹钟页 |
| `AlarmManager.setAlarmClock(...)` | 精确触发，不受 Doze 影响 |

## 目录

```
AndroidManifest.xml          权限与组件声明
.gitignore                   排除签名密钥、构建产物、本机一次性脚本
build.sh                     手工打包：aapt2 compile/link → javac → d8 → 塞 dex → zipalign → apksigner
run_test.sh                  离线验证解析逻辑（用真实 ICS 在普通 JVM 上跑，不需要设备）
verify.sh                    真机验证：构建 → 安装 → 三个页签截图 → 崩溃检查
tools/make_icon.py           生成自适应图标（前景/背景两层）+ 预览图
tools/make_nav_icons.py      生成底部导航图标（清单/加号/人像，白色字形）
res/mipmap-*/                各密度图标
  ic_launcher_foreground.png   白色闹钟，透明底（前景层）
  ic_launcher_background.png   蓝色渐变（背景层，全出血）
  ic_launcher.png              传统图标（圆形裁切，兜底）
res/mipmap-anydpi-v26/
  ic_launcher.xml              自适应图标声明（背景 + 前景）
res/drawable-*/              底部导航图标（5 套密度）
src/com/qiuzhao/alarm/
  MainActivity.java          三页签界面：事件 / 添加 / 我的
  Sched.java                 核心：ICS 拉取、解析、排程、通知、自定义每日提醒
  Event.java                 事件模型（JSON 持久化）
  AlarmReceiver.java         闹钟触发 / 关闭
  AlarmActivity.java         全屏闹钟页（循环响铃 + 震动）
  SyncReceiver.java          每日自动同步
  BootReceiver.java          开机/更新后恢复排程
test/com/qiuzhao/alarm/
  ParseTest.java             解析逻辑的离线单测（配合 run_test.sh）
```

> 本机还有一批一次性排查脚本（`shot_tabs.sh`、`inset_probe.sh` 等），
> 里面写死了本机 adb 路径与屏幕分辨率，已在 `.gitignore` 中排除，不进仓库。

### 首次克隆后怎么跑

```bash
python tools/make_icon.py        # 生成图标资源（若 res/ 里没有）
python tools/make_nav_icons.py   # 生成导航图标
bash build.sh                    # 产出 别错过.apk（签名密钥会自动生成）
bash run_test.sh                 # 验证 ICS 解析（不需要手机）
bash verify.sh                   # 装到手机并截图（需要 adb 与已授权的设备）
```

⚠️ `verify.sh` 与 `run_test.sh` 里的 `ADB` 路径是按本机写的（`D:\python--demo\...\adb.exe`），
换机器需要改这一行，或把 adb 加进 PATH 后改成 `ADB=adb`。

## 图标

设计：蓝色渐变底 + 白色闹钟（表盘 + 铃铛 + 指针），时针用暖色做视觉焦点。
自适应图标规范要求画布 108×108dp、可见内容限制在居中 72×72dp 安全区内，因此前景只画在中心。

```bash
python tools/make_icon.py    # 重新生成所有密度 + icon_preview.png
```

`icon_preview.png` 是按真实合成方式（背景全出血 + 前景居中 66%）渲染的预览，
不必装机就能看效果；`icon_preview_48px.png` 用来检查桌面小尺寸下的可辨识度。

## 重新构建

```bash
bash build.sh        # 产出 秋招闹钟.apk
```

不用 Gradle / AGP，因为本机只有 JDK 26（AGP 不支持），改用 build-tools 直接手工打包。
`javac --release 8` + `d8 --min-api 26`，零第三方依赖（连 AndroidX 都没用）。

## 离线验证

```bash
bash run_test.sh
```

用真实的 `秋招截止提醒.ics` 在普通 JVM 上跑 `Sched` 的纯函数（解析部分不碰 `android.*`）。
上次结果：36 个事件全部解析出 VALARM、8 种 ISO8601 时长格式全对、工作日重复事件正确展开、未来共 74 个闹钟。

## 真机验证结果（vivo S17t / Android 15 / SDK 35）

已在设备上逐环验证通过：

| 环节 | 证据 |
|---|---|
| 装机运行 | `adb install` Success，MainActivity 无崩溃 |
| 设备端拉取解析 | 界面显示"同步成功：**36 个事件，排了 74 个闹钟**"，与离线验证数字一致 |
| 闹钟真的注册进系统 | `dumpsys alarm` 内 **161 个** `*walarm*:com.qiuzhao.alarm.FIRE`（`walarm` 前缀＝`setAlarmClock()`） |
| 每日同步生效 | 系统里有 `*walarm*:com.qiuzhao.alarm.SYNC`，时间＝次日 20:05 |
| 重复事件展开 | 系统里有 9/28 08:55 等条目（工作日重复事件提前 5 分钟） |
| 渠道走闹钟流 | `dumpsys notification`：`usage=USAGE_ALARM`、`mSound=.../alarm_alert`、`mImportance=4`、`mVibrationEnabled=true` |
| 到点精确触发 | logcat：排程 22:32:20.868 → 触发 22:32:30.870（**正好 10 秒**）→ 通知已发出 |
| **静音/震动下会响** | **震动模式**下 `dumpsys audio`：`STREAM_NOTIFICATION Muted: true`（普通通知被静音）而 **`STREAM_ALARM Muted: false`**（闹钟流未被静音） |

验证过程中修掉一个真 bug：测试提醒固定用 code `999001`，而"已关闭"状态按 code 记录，
一旦关过一次就再也不响（只有同步才清）。现已加 `clearDismissed()`，测试提醒每次都必定触发。

**唯一无法由我验证的**：实际听感（我这边听不到手机）。需人工确认静音/震动模式下是否真的出声。

## 已知局限

- ICS 里只支持展开 `FREQ=WEEKLY` + `BYDAY` 的重复规则（本项目够用），其它 RRULE 类型按单次事件处理。
- 全屏闹钟页只在**锁屏/息屏**时拉起；屏幕亮着且 App 在前台时，`setFullScreenIntent` 按 Android 规范降级为 heads-up 横幅（系统行为，非 bug）。
- 勿扰（DND）放行需要手动授予「勿扰访问权限」，否则 `mBypassDnd` 保持 false。**静音/震动模式不依赖此项**。
- 国行 ROM 仍需手动加白名单（自启动 / 电池不优化 / 通知响铃震动），否则照样会被杀。
- keystore 是本地自签（`keystore.p12`，口令 android），仅供自己 sideload。
