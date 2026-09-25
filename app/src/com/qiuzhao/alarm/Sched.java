package com.qiuzhao.alarm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 核心：拉取 ICS、解析事件与 VALARM、用系统闹钟排程、发「闹钟音量流」通知。 */
public final class Sched {

    public static final String PREFS = "qzalarm";
    public static final String KEY_EVENTS = "events";
    public static final String KEY_CODES = "codes";
    public static final String KEY_URL = "ics_url";
    public static final String KEY_LAST = "last_status";
    public static final String KEY_DISMISSED = "dismissed";
    public static final String KEY_REPEAT = "repeat_of";

    public static final String DEFAULT_URL =
            "https://cdn.jsdelivr.net/gh/zgl66-z/qiuzhao-calendar@main/qiuzhao-deadlines.ics";

    public static final String CH_ID = "deadline_alarm";

    /** 每日自动同步的闹钟 code（放在 20:05，等监控任务 19:30 跑完并推送之后再拉）。 */
    public static final int SYNC_CODE = 880011;
    private static final int SYNC_HOUR = 20;
    private static final int SYNC_MINUTE = 5;

    /** 每次同步后，每个触发点最多往后排几次（覆盖重复事件的未来若干次）。 */
    private static final int MAX_OCCURRENCES = 10;
    /** 闹钟响后没被关掉，隔多久再响一次（毫秒），以及最多再响几次。 */
    private static final long RENOTIFY_GAP = 60_000L;
    private static final int RENOTIFY_MAX = 5;

    private Sched() {}

    // ---------------------------------------------------------------- 网络

    public static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "QiuzhaoAlarm/1.0");
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- 解析

    /** RFC5545 折行还原：CRLF + 单个空格/制表符是续行。 */
    static String unfold(String raw) {
        return raw.replace("\r\n ", "").replace("\r\n\t", "")
                  .replace("\n ", "").replace("\n\t", "");
    }

    /** ISO8601 时长 → 毫秒（带符号）。-P6D → -518400000，-PT5M → -300000。 */
    static Long parseDuration(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        boolean neg = s.startsWith("-");
        if (s.startsWith("-") || s.startsWith("+")) s = s.substring(1);
        if (!s.startsWith("P")) return null;
        s = s.substring(1);
        Matcher m = Pattern.compile(
                "^(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")
                .matcher(s);
        if (!m.matches()) return null;
        long ms = 0L;
        ms += num(m.group(1)) * 7L * 86400_000L;
        ms += num(m.group(2)) * 86400_000L;
        ms += num(m.group(3)) * 3600_000L;
        ms += num(m.group(4)) * 60_000L;
        ms += num(m.group(5)) * 1000L;
        return Long.valueOf(neg ? -ms : ms);
    }

    private static long num(String g) {
        return (g == null || g.isEmpty()) ? 0L : Long.parseLong(g);
    }

    /** 解析 DTSTART / DTEND 的值。日期只有 8 位时按当天 09:00 处理。 */
    static Long parseStamp(String v) {
        if (v == null) return null;
        v = v.trim();
        boolean utc = v.endsWith("Z");
        if (utc) v = v.substring(0, v.length() - 1);
        try {
            SimpleDateFormat f;
            if (v.length() == 8) {
                f = new SimpleDateFormat("yyyyMMdd", Locale.US);
                f.setTimeZone(TimeZone.getDefault());
                Long base = Long.valueOf(f.parse(v).getTime());
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(base.longValue());
                c.set(Calendar.HOUR_OF_DAY, 9);
                c.set(Calendar.MINUTE, 0);
                c.set(Calendar.SECOND, 0);
                return Long.valueOf(c.getTimeInMillis());
            }
            if (v.length() >= 15) {
                f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
                f.setTimeZone(utc ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
                return Long.valueOf(f.parse(v.substring(0, 15)).getTime());
            }
        } catch (Exception ignore) {
            return null;
        }
        return null;
    }

    static String valueOf(String line) {
        int i = line.indexOf(':');
        return i < 0 ? "" : line.substring(i + 1).trim();
    }

    static List<Event> parseIcs(String raw) {
        List<Event> out = new ArrayList<Event>();
        String[] lines = unfold(raw).split("\n");
        Event cur = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.equals("BEGIN:VEVENT")) {
                cur = new Event();
                continue;
            }
            if (t.equals("END:VEVENT")) {
                if (cur != null && cur.start > 0 && !cur.title.isEmpty()) out.add(cur);
                cur = null;
                continue;
            }
            if (cur == null) continue;
            if (t.startsWith("UID")) {
                cur.uid = valueOf(t);
            } else if (t.startsWith("SUMMARY")) {
                cur.title = valueOf(t);
            } else if (t.startsWith("DESCRIPTION")) {
                if (cur.desc.isEmpty()) cur.desc = valueOf(t);
            } else if (t.startsWith("DTSTART")) {
                Long ms = parseStamp(valueOf(t));
                if (ms != null) cur.start = ms.longValue();
            } else if (t.startsWith("RRULE")) {
                cur.rrule = valueOf(t);
            } else if (t.startsWith("TRIGGER")) {
                Long d = parseDuration(valueOf(t));
                if (d != null) cur.offsets.add(d);
            }
        }
        for (Event e : out) {
            if (e.uid.isEmpty()) e.uid = Integer.toHexString((e.title + e.start).hashCode());
            if (e.offsets.isEmpty()) e.offsets.add(Long.valueOf(-86400_000L));
        }
        return out;
    }

    /** 由 RRULE 展开出未来若干次发生时间。只支持 FREQ=WEEKLY + BYDAY（本项目用到的那种）。 */
    static List<Long> occurrences(Event e, long now) {
        List<Long> res = new ArrayList<Long>();
        if (e.rrule == null || e.rrule.isEmpty() || !e.rrule.contains("FREQ=WEEKLY")) {
            res.add(Long.valueOf(e.start));
            return res;
        }
        List<Integer> days = new ArrayList<Integer>();
        Matcher m = Pattern.compile("BYDAY=([A-Z,]+)").matcher(e.rrule);
        if (m.find()) {
            for (String d : m.group(1).split(",")) {
                Integer cal = dayOfWeek(d.trim());
                if (cal != null) days.add(cal);
            }
        }
        if (days.isEmpty()) {
            res.add(Long.valueOf(e.start));
            return res;
        }
        Long until = null;
        Matcher u = Pattern.compile("UNTIL=(\\d{8})").matcher(e.rrule);
        if (u.find()) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                until = Long.valueOf(f.parse(u.group(1)).getTime());
            } catch (Exception ignore) { }
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(e.start);
        int added = 0;
        for (int i = 0; i < 400 && added < MAX_OCCURRENCES; i++) {
            if (until != null && c.getTimeInMillis() > until.longValue() + 86400_000L) break;
            if (days.contains(Integer.valueOf(c.get(Calendar.DAY_OF_WEEK)))) {
                long t = c.getTimeInMillis();
                if (t + 86_400_000L >= now) {
                    res.add(Long.valueOf(t));
                    added++;
                }
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return res;
    }

    private static Integer dayOfWeek(String s) {
        if (s.equals("MO")) return Integer.valueOf(Calendar.MONDAY);
        if (s.equals("TU")) return Integer.valueOf(Calendar.TUESDAY);
        if (s.equals("WE")) return Integer.valueOf(Calendar.WEDNESDAY);
        if (s.equals("TH")) return Integer.valueOf(Calendar.THURSDAY);
        if (s.equals("FR")) return Integer.valueOf(Calendar.FRIDAY);
        if (s.equals("SA")) return Integer.valueOf(Calendar.SATURDAY);
        if (s.equals("SU")) return Integer.valueOf(Calendar.SUNDAY);
        return null;
    }

    // ---------------------------------------------------------------- 存取

    public static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveEvents(Context c, List<Event> evs) {
        JSONArray a = new JSONArray();
        try {
            for (Event e : evs) a.put(e.toJson());
        } catch (Exception ignore) { }
        p(c).edit().putString(KEY_EVENTS, a.toString()).apply();
    }

    public static List<Event> loadEvents(Context c) {
        List<Event> out = new ArrayList<Event>();
        String s = p(c).getString(KEY_EVENTS, "");
        if (s == null || s.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) out.add(Event.fromJson(a.getJSONObject(i)));
        } catch (Exception ignore) { }
        return out;
    }

    public static String url(Context c) {
        String u = p(c).getString(KEY_URL, "");
        return (u == null || u.isEmpty()) ? DEFAULT_URL : u;
    }

    /** 一条待提醒项：下一次触发时间 + 事件标题。 */
    public static final class Up {
        public final long trigger;
        public final String title;
        /** 是否来自「我的每日提醒」（而非秋招 ICS），界面据此区分展示。 */
        public final boolean daily;

        Up(long trigger, String title) {
            this(trigger, title, false);
        }

        Up(long trigger, String title, boolean daily) {
            this.trigger = trigger;
            this.title = title;
            this.daily = daily;
        }
    }

    // ------------------------------------------------------------ 自定义每日提醒

    public static final String KEY_REMS = "rems";
    public static final String KEY_SEEDED = "seeded_rems";

    /** 一条自定义的每日提醒（与 ICS 无关，存在本机）。 */
    public static final class Rem {
        public String title = "";
        public int hour = 21;
        public int minute = 0;

        public String id() {
            return title + "@" + hour + ":" + minute;
        }
    }

    public static List<Rem> loadRems(Context c) {
        List<Rem> out = new ArrayList<Rem>();
        String s = p(c).getString(KEY_REMS, "");
        if (s == null || s.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Rem r = new Rem();
                r.title = o.optString("title", "");
                r.hour = o.optInt("hour", 21);
                r.minute = o.optInt("minute", 0);
                if (!r.title.isEmpty()) out.add(r);
            }
        } catch (Exception ignore) { }
        return out;
    }

    public static void saveRems(Context c, List<Rem> list) {
        JSONArray a = new JSONArray();
        try {
            for (Rem r : list) {
                JSONObject o = new JSONObject();
                o.put("title", r.title);
                o.put("hour", r.hour);
                o.put("minute", r.minute);
                a.put(o);
            }
        } catch (Exception ignore) { }
        p(c).edit().putString(KEY_REMS, a.toString()).apply();
    }

    /** 首次运行时预置一条「多邻国打卡 21:00」；用户删掉后不再重复预置。 */
    public static void seedRems(Context c) {
        SharedPreferences sp = p(c);
        if (sp.getBoolean(KEY_SEEDED, false)) return;
        List<Rem> list = loadRems(c);
        if (list.isEmpty()) {
            Rem r = new Rem();
            r.title = "多邻国打卡";
            r.hour = 21;
            r.minute = 0;
            list.add(r);
            saveRems(c, list);
        }
        sp.edit().putBoolean(KEY_SEEDED, true).apply();
    }

    public static void addRem(Context c, String title, int hour, int minute) {
        List<Rem> list = loadRems(c);
        Rem r = new Rem();
        r.title = title;
        r.hour = hour;
        r.minute = minute;
        list.add(r);
        saveRems(c, list);
    }

    public static void delRem(Context c, Rem target) {
        List<Rem> list = loadRems(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id().equals(target.id())) list.remove(i);
        }
        saveRems(c, list);
    }

    /** 某个「时:分」在 now 之后第 dayOffset 天的触发时刻。 */
    private static long nextAt(int hour, int minute, int dayOffset, long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.add(Calendar.DAY_OF_YEAR, dayOffset);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /**
     * 按触发时间升序列出所有未来提醒，供界面展示。
     * 目的是让这个 App 自己能"看见"截止日，不必再依赖另一个日历 App。
     */
    public static List<Up> upcoming(Context c, long now) {
        List<Up> rows = new ArrayList<Up>();
        for (Event e : loadEvents(c)) {
            long best = Long.MAX_VALUE;
            List<Long> occ = occurrences(e, now);
            for (int i = 0; i < occ.size(); i++) {
                long o = occ.get(i).longValue();
                for (int j = 0; j < e.offsets.size(); j++) {
                    long at = o + e.offsets.get(j).longValue();
                    if (at > now + 5_000L && at < best) best = at;
                }
            }
            if (best != Long.MAX_VALUE) rows.add(new Up(best, e.title));
        }
        // 自定义每日提醒：取每条的下一次触发
        for (Rem r : loadRems(c)) {
            long t = nextAt(r.hour, r.minute, 0, now);
            if (t <= now + 5_000L) t = nextAt(r.hour, r.minute, 1, now);
            rows.add(new Up(t, r.title, true));
        }
        Collections.sort(rows, new Comparator<Up>() {
            @Override
            public int compare(Up a, Up b) {
                if (a.trigger < b.trigger) return -1;
                if (a.trigger > b.trigger) return 1;
                return 0;
            }
        });
        return rows;
    }

    /** 把未来提醒格式化成多行文本（界面直接用）。显示前 limit 条。 */
    public static String upcomingText(Context c, int limit) {
        long now = System.currentTimeMillis();
        List<Up> rows = upcoming(c, now);
        if (rows.isEmpty()) return "（暂无未来提醒）";
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Up u : rows) {
            if (n++ >= limit) break;
            sb.append(f.format(new Date(u.trigger))).append("  ").append(u.title).append('\n');
        }
        if (rows.size() > limit) {
            sb.append("… 另有 ").append(rows.size() - limit).append(" 条");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 排程

    static PendingIntent alarmPi(Context c, int code, String title, String desc, long fireAt) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction("com.qiuzhao.alarm.FIRE");
        i.putExtra("title", title);
        i.putExtra("desc", desc);
        i.putExtra("code", code);
        i.putExtra("fireAt", fireAt);
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 取消上次排的全部闹钟。 */
    public static void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        String s = p(c).getString(KEY_CODES, "");
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(",")) {
            if (part.isEmpty()) continue;
            try {
                int code = Integer.parseInt(part);
                am.cancel(alarmPi(c, code, "", "", 0L));
            } catch (Exception ignore) { }
        }
        p(c).edit().putString(KEY_CODES, "").apply();
    }

    /** 排一个闹钟，成功则把 code 记进 codes（供 cancelAll 用）。 */
    private static boolean scheduleOne(Context c, AlarmManager am, StringBuilder codes,
                                       int code, String title, String desc, long fireAt) {
        PendingIntent pi = alarmPi(c, code, title, desc, fireAt);
        Intent show = new Intent(c, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(c, code, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(fireAt, showPi), pi);
            if (codes.length() > 0) codes.append(',');
            codes.append(code);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "排程失败 code=" + code, t);
            return false;
        }
    }

    /** 按已存的事件 + 自定义每日提醒重排全部闹钟，返回排上的个数。 */
    public static int scheduleAll(Context c) {
        cancelAll(c);
        seedRems(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return 0;
        long now = System.currentTimeMillis();
        StringBuilder codes = new StringBuilder();
        int n = 0;

        for (Event e : loadEvents(c)) {
            List<Long> occ = occurrences(e, now);
            for (int oi = 0; oi < occ.size(); oi++) {
                long occAt = occ.get(oi).longValue();
                for (int fi = 0; fi < e.offsets.size(); fi++) {
                    long fireAt = occAt + e.offsets.get(fi).longValue();
                    if (fireAt <= now + 5_000L) continue;
                    int code = Math.abs((e.uid + "#" + oi + "#" + fi).hashCode());
                    if (scheduleOne(c, am, codes, code, e.title, e.desc, fireAt)) n++;
                }
            }
        }

        // 自定义每日提醒：往后排 10 天（每天 20:05 会自动重排，够用）
        for (Rem r : loadRems(c)) {
            for (int d = 0; d < 10; d++) {
                long fireAt = nextAt(r.hour, r.minute, d, now);
                if (fireAt <= now + 5_000L) continue;
                int code = Math.abs(("rem|" + r.id() + "|" + d).hashCode());
                if (scheduleOne(c, am, codes, code, r.title, "每日提醒", fireAt)) n++;
            }
        }

        p(c).edit().putString(KEY_CODES, codes.toString()).apply();
        return n;
    }

    // ---------------------------------------------------------------- 通知

    /** 建立「闹钟音量流」通知渠道：静音/震动模式下仍会响。 */
    public static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CH_ID, "截止提醒（闹钟式）",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("截止日提醒，走闹钟音量，静音/震动模式下也会响");
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = Settings.System.DEFAULT_ALARM_ALERT_URI;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ch.setSound(sound, attrs);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 400, 800, 400, 800});
        try {
            ch.setBypassDnd(true);
        } catch (Throwable ignore) { }
        nm.createNotificationChannel(ch);
    }

    /** 排程的日志 TAG，便于用 adb logcat 排查（sideload 场景很需要）。 */
    public static final String TAG = "QZAlarm";

    /** 从"已关闭"名单里移除某个 code（测试提醒每次都必须能响）。 */
    public static void clearDismissed(Context c, int code) {
        String s = p(c).getString(KEY_DISMISSED, "");
        if (s == null || s.isEmpty()) return;
        String want = String.valueOf(code);
        StringBuilder sb = new StringBuilder();
        for (String part : s.split(",")) {
            if (part.isEmpty() || part.equals(want)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(part);
        }
        p(c).edit().putString(KEY_DISMISSED, sb.toString()).apply();
    }

    public static void fire(Context c, String title, String desc, int code) {
        Log.i(TAG, "fire() 进入: code=" + code + " title=" + title);
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = new Intent(c, AlarmActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("title", title);
        open.putExtra("desc", desc);
        open.putExtra("code", code);
        PendingIntent full = PendingIntent.getActivity(c, code + 7, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent dim = new Intent(c, AlarmReceiver.class);
        dim.setAction("com.qiuzhao.alarm.DISMISS");
        dim.putExtra("code", code);
        PendingIntent dismiss = PendingIntent.getBroadcast(c, code + 13, dim,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(c, CH_ID)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(desc == null || desc.isEmpty() ? "秋招截止提醒" : desc)
                .setStyle(new Notification.BigTextStyle().bigText(desc == null ? title : desc))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(full)
                .setFullScreenIntent(full, true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭提醒", dismiss);
        try {
            nm.notify(code, b.build());
            Log.i(TAG, "fire(): 通知已发出 code=" + code);
        } catch (Throwable t) {
            Log.e(TAG, "fire(): 通知发出失败", t);
        }

        // 没被关掉就 1 分钟后再响一次，模拟"响到你按掉"
        int rep = p(c).getInt(KEY_REPEAT + code, 0);
        if (rep < RENOTIFY_MAX && !isDismissed(c, code)) {
            p(c).edit().putInt(KEY_REPEAT + code, rep + 1).apply();
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                long again = System.currentTimeMillis() + RENOTIFY_GAP;
                PendingIntent pi = alarmPi(c, code, title, desc, again);
                try {
                    am.setAlarmClock(new AlarmManager.AlarmClockInfo(again, null), pi);
                } catch (Throwable ignore) { }
            }
        }
    }

    public static boolean isDismissed(Context c, int code) {
        String s = p(c).getString(KEY_DISMISSED, "");
        return s != null && ("," + s + ",").contains("," + code + ",");
    }

    public static void dismiss(Context c, int code) {
        String s = p(c).getString(KEY_DISMISSED, "");
        if (s == null) s = "";
        if (!(("," + s + ",").contains("," + code + ","))) {
            if (!s.isEmpty()) s += ",";
            s += code;
        }
        p(c).edit().putString(KEY_DISMISSED, s).apply();
        p(c).edit().putInt(KEY_REPEAT + code, 0).apply();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(code);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmPi(c, code, "", "", 0L));
    }

    // ---------------------------------------------------------------- 同步

    /** 拉取 + 解析 + 保存 + 重排。任何异常都写进状态供界面显示。 */
    public static String sync(Context c) {
        String msg;
        try {
            String text = fetch(url(c));
            List<Event> evs = parseIcs(text);
            if (evs.isEmpty()) {
                msg = "同步失败：解析到 0 个事件（文件变了？）";
            } else {
                saveEvents(c, evs);
                p(c).edit().putString(KEY_DISMISSED, "").apply();
                int n = scheduleAll(c);
                SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
                msg = "同步成功：" + evs.size() + " 个事件，排了 " + n + " 个闹钟（"
                        + f.format(new java.util.Date()) + "）";
            }
        } catch (Exception e) {
            msg = "同步失败：" + e.getClass().getSimpleName() + " " + e.getMessage();
        }
        p(c).edit().putString(KEY_LAST, msg).apply();
        return msg;
    }

    /** 排一段测试闹钟：delayMs 毫秒后响，用来验证静音下到底响不响。 */
    public static void testAlarm(Context c, long delayMs, int code) {
        // 测试提醒必须每次都能响：先清掉这个 code 之前的"已关闭"标记
        clearDismissed(c, code);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            Log.e(TAG, "testAlarm: AlarmManager 为空");
            return;
        }
        long at = System.currentTimeMillis() + delayMs;
        PendingIntent pi = alarmPi(c, code, "测试提醒", "如果你在静音/震动模式下听到这声，说明闹钟音量流生效了", at);
        Intent show = new Intent(c, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(c, code, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
            Log.i(TAG, "testAlarm: 已排程，code=" + code + " firesAt=" + at);
        } catch (Throwable t) {
            Log.e(TAG, "testAlarm: 排程失败", t);
        }
    }

    /**
     * 排下一次每日自动同步（默认 20:05）。没有精确闹钟权限时退化为非精确排程，
     * 保证「至少会同步」，不会因为权限没给就彻底不同步。
     */
    public static void scheduleDailySync(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, SYNC_HOUR);
        cal.set(Calendar.MINUTE, SYNC_MINUTE);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        long at = cal.getTimeInMillis();
        Intent i = new Intent(c, SyncReceiver.class);
        i.setAction("com.qiuzhao.alarm.SYNC");
        PendingIntent pi = PendingIntent.getBroadcast(c, SYNC_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Throwable t) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Throwable ignore) { }
        }
    }
}
