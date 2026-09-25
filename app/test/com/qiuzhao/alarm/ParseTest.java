package com.qiuzhao.alarm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * 离线验证：用真实的 ICS 文件跑 Sched 的解析与展开逻辑，确认事件/触发时间都对。
 * 只调用不碰任何 android.* 的纯函数，所以能在普通 JVM 上跑。
 *
 * 注意：样本路径是本文件内写死的常量，且会做规范化校验、限定在工程目录内，
 * 不接受外部传入路径（避免路径穿越）。
 */
public class ParseTest {

    /** 样本固定放在工程的 test/ 目录下。 */
    private static final String SAMPLE = "test" + File.separator + "sample.ics";

    /** 解析样本文件，并把路径规范化后限定在工程目录内。 */
    private static String readSample() throws Exception {
        File base = new File(".").getCanonicalFile();
        File f = new File(SAMPLE).getCanonicalFile();
        if (!f.getPath().startsWith(base.getPath() + File.separator)) {
            throw new SecurityException("样本路径越出工程目录：" + f);
        }
        if (!f.isFile()) {
            throw new IllegalStateException("样本不存在：" + f);
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } finally {
            r.close();
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        // --- 时长解析单元检查 ---
        System.out.println("== parseDuration ==");
        String[] tests = {"-P6D", "-P1D", "-PT5M", "-P1W", "-P1DT2H30M", "-PT1H30M", "P1D", "PT0M"};
        long[] want = {-518400000L, -86400000L, -300000L, -604800000L,
                -95400000L, -5400000L, 86400000L, 0L};
        boolean durOk = true;
        for (int i = 0; i < tests.length; i++) {
            Long got = Sched.parseDuration(tests[i]);
            long g = got == null ? Long.MIN_VALUE : got.longValue();
            boolean ok = g == want[i];
            if (!ok) durOk = false;
            System.out.printf("   %-12s -> %-14s %s%n", tests[i], String.valueOf(g),
                    ok ? "OK" : "期望 " + want[i]);
        }
        System.out.println("   时长解析: " + (durOk ? "全部通过" : "有错！"));

        // --- 真实文件解析 ---
        System.out.println();
        System.out.println("== 解析真实 ICS ==");
        List<Event> evs = Sched.parseIcs(readSample());
        System.out.println("   事件数: " + evs.size());

        long now = System.currentTimeMillis();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.CHINA);
        int alarmCount = 0;
        int withOffsets = 0;
        StringBuilder detail = new StringBuilder();
        for (Event e : evs) {
            if (!e.offsets.isEmpty()) withOffsets++;
            List<Long> occ = Sched.occurrences(e, now);
            StringBuilder times = new StringBuilder();
            int n = 0;
            for (Long o : occ) {
                for (Long off : e.offsets) {
                    long at = o.longValue() + off.longValue();
                    if (at > now + 5000L) {
                        n++;
                        if (n <= 3) {
                            times.append("     触发 ").append(f.format(new java.util.Date(at)))
                                 .append("  (事件 ").append(f.format(new java.util.Date(o.longValue())))
                                 .append(", 提前 ").append(-off.longValue() / 60000L)
                                 .append(" 分)").append('\n');
                        }
                    }
                }
            }
            alarmCount += n;
            detail.append("   [").append(e.uid).append("] ").append(e.title).append('\n')
                  .append("      开始 ").append(f.format(new java.util.Date(e.start)))
                  .append(" | VALARM ").append(e.offsets.size()).append(" 条")
                  .append(" | 未来触发 ").append(n).append(" 次")
                  .append(" | rrule=").append(e.rrule.isEmpty() ? "(无)" : e.rrule).append('\n')
                  .append(times);
        }
        System.out.println();
        System.out.println("== 逐条事件与触发时间（只列未来的）==");
        System.out.print(detail);

        System.out.println();
        System.out.println("== 汇总 ==");
        System.out.println("   事件总数        : " + evs.size());
        System.out.println("   带 VALARM 的事件: " + withOffsets);
        System.out.println("   未来闹钟总数    : " + alarmCount);
        System.out.println("   判定            : " + (evs.size() > 0 && withOffsets == evs.size()
                ? "通过（每个事件都解析出了提醒）" : "有问题，请检查"));
    }
}
