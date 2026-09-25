# qiuzhao-calendar

这个仓库有两个用途。

## 1. 秋招截止日订阅源（ICS）

`qiuzhao-deadlines.ics` 是给手机日历订阅用的文件（已去掉简历文件名等个人信息）：

```
https://raw.githubusercontent.com/zgl66-z/qiuzhao-calendar/main/qiuzhao-deadlines.ics
```

内容由本机的秋招监控任务每天 19:30 检索并推送更新，**只追加、不删历史行**。

## 2. 配套的安卓 App（`app/`）

`app/` 下是「**别错过**」——把上面的 ICS 截止日排成系统闹钟的安卓应用。

它只解决一个问题：**通知走闹钟音量流（`AudioAttributes.USAGE_ALARM`），
所以静音 / 震动模式下也会响。** 这一点是 Outlook / Google 的只读订阅日历
（不触发提醒）、以及 KashCal 这类 App（提醒走普通通知流，静音即被掐）都做不到的。

- 三页签界面：事件（按时序合并展示截止日与每日打卡）/ 添加（管理每日打卡）/
  我的（权限与状态）
- 解析 ICS 的 `VEVENT` + `VALARM`，用 `AlarmManager.setAlarmClock()` 精确排程；
  到点全屏弹出 + 循环响铃 + 震动，未关掉则每分钟再响一次
- 每天 20:05 自动同步 ICS；开机 / 应用更新后自动恢复排程
- 零第三方依赖，手工打包（`aapt2` + `javac` + `d8` + `zipalign` + `apksigner`），
  不使用 Gradle / AGP

构建方法、设计取舍与验证过程见 [`app/README.md`](app/README.md)。
