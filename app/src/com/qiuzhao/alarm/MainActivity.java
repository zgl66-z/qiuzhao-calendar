package com.qiuzhao.alarm;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 三页签界面：事件 / 添加 / 我的。底部导航栏，纯代码构建，不引入任何布局资源。
 * 配色沿用《秋招企业监控》文档那套蓝。
 */
public class MainActivity extends Activity {

    private static final int BLUE = 0xFF2456A6;
    private static final int BLUE_MID = 0xFF3A72C9;
    private static final int BLUE_L = 0xFFEAF1FB;
    private static final int BLUE_D = 0xFF1B3C73;
    private static final int INK = 0xFF22262C;
    private static final int GRAY = 0xFF8A93A0;
    private static final int GRAY_NAV = 0xFF9AA3B0;
    private static final int LINE = 0xFFE4E9F2;
    private static final int PAGE = 0xFFF6F8FB;
    private static final int GREEN = 0xFF1E7B4D;
    private static final int RED = 0xFFC0392B;
    private static final int STROKE = 0xFFCFD8E6;

    private static final int LIST_LIMIT = 25;
    private static final String[] TAB_LABELS = {"事件", "添加", "我的"};

    private final View[] pages = new View[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];
    private int tab = 0;

    // 事件页
    private LinearLayout headerView;
    private TextView headerMeta;
    private LinearLayout listBox;
    private LinearLayout addBody;
    private LinearLayout mineBody;
    private LinearLayout navBar;
    private int insetTop = 0;
    private int insetBottom = 0;
    // 添加页
    private LinearLayout remBox;
    private EditText remTimeBox;
    private EditText remTitleBox;
    // 我的页
    private TextView status;
    private LinearLayout permBox;
    private EditText urlBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Sched.ensureChannel(this);
        Sched.scheduleDailySync(this);
        Sched.seedRems(this);

        try {
            getWindow().setStatusBarColor(BLUE);
        } catch (Throwable ignore) { }
        // 蓝底上要用白色状态栏图标
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController wic = getWindow().getInsetsController();
                if (wic != null) {
                    wic.setSystemBarsAppearance(
                            0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(0);
            }
        } catch (Throwable ignore) { }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        FrameLayout host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pages[0] = buildEventsPage();
        pages[1] = buildAddPage();
        pages[2] = buildMinePage();
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            host.addView(pages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        navBar = buildNavBar();
        root.addView(navBar);

        setContentView(root);

        // 统一在根节点收窗口内衬，再分发给三个地方。
        // （之前分别挂在子 View 上，底部那个收不到，导致导航文字被切）
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    insetTop = insets.getInsets(WindowInsets.Type.systemBars()).top;
                    insetBottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
                } else {
                    insetTop = insets.getSystemWindowInsetTop();
                    insetBottom = insets.getSystemWindowInsetBottom();
                }
                applyInsets();
                return insets;
            }
        });
        applyInsets();

        showTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------ 页签

    private LinearLayout buildNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFFFFFFFF);

        int[] icons = {R.drawable.nav_events, R.drawable.nav_add, R.drawable.nav_mine};
        for (int i = 0; i < 3; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(9), 0, dp(9));

            ImageView iv = new ImageView(this);
            iv.setImageResource(icons[i]);
            int s = dp(23);
            item.addView(iv, new LinearLayout.LayoutParams(s, s));

            TextView tv = new TextView(this);
            tv.setText(TAB_LABELS[i]);
            tv.setTextSize(11);
            // 关键：TextView 的默认对齐是「左对齐」，而它的宽度会被撑满整个导航项，
            // 不显式设成居中，文字就会贴在每一项最左边，看起来和居中图标错位。
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(3), 0, 0);
            item.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final int idx = i;
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(idx);
                }
            });
            bar.addView(item, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            navIcons[i] = iv;
            navLabels[i] = tv;
        }

        // 底部内衬由根节点的内衬分发统一处理（见 applyInsets）
        return bar;
    }

    /** 把窗口内衬分发到三处：事件页头图顶部、另两页内容顶部、导航栏底部。 */
    private void applyInsets() {
        if (headerView != null) {
            headerView.setPadding(dp(20), insetTop + dp(14), dp(20), dp(20));
        }
        if (addBody != null) {
            addBody.setPadding(dp(14), insetTop + dp(14), dp(14), dp(24));
        }
        if (mineBody != null) {
            mineBody.setPadding(dp(14), insetTop + dp(14), dp(14), dp(24));
        }
        if (navBar != null) {
            navBar.setPadding(0, dp(9), 0, dp(9) + insetBottom);
        }
    }

    /**
     * 状态栏样式跟着页签变：事件页头图是深蓝，用白色图标；
     * 添加/我的页是浅色背景，用深色图标，否则白图标在浅底上看不见。
     */
    private void applyStatusBarForTab(int i) {
        boolean darkBg = (i == 0);
        try {
            getWindow().setStatusBarColor(darkBg ? BLUE : PAGE);
        } catch (Throwable ignore) { }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController wic = getWindow().getInsetsController();
                if (wic != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                    wic.setSystemBarsAppearance(darkBg ? 0 : mask, mask);
                }
            } else {
                int flag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                getWindow().getDecorView().setSystemUiVisibility(darkBg ? 0 : flag);
            }
        } catch (Throwable ignore) { }
    }

    private void showTab(int i) {
        tab = i;
        for (int j = 0; j < 3; j++) {
            pages[j].setVisibility(j == i ? View.VISIBLE : View.GONE);
            int col = (j == i) ? BLUE : GRAY_NAV;
            navIcons[j].setColorFilter(col);
            navLabels[j].setTextColor(col);
            navLabels[j].setTypeface(j == i ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        applyStatusBarForTab(i);
        refresh();
    }

    // ------------------------------------------------------------ 页面骨架

    private ScrollView newPage() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(PAGE);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        sc.addView(v);
        return sc;
    }

    private static LinearLayout body(ScrollView sc) {
        return (LinearLayout) sc.getChildAt(0);
    }

    private LinearLayout contentBox(LinearLayout parent, boolean topPadding) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), topPadding ? dp(14) : 0, dp(14), dp(24));
        parent.addView(c);
        return c;
    }

    private TextView pageTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(19);
        t.setTextColor(BLUE_D);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(2), dp(6), 0, dp(12));
        return t;
    }

    // ------------------------------------------------------------ 事件页

    private ScrollView buildEventsPage() {
        ScrollView sc = newPage();
        LinearLayout root = body(sc);

        headerView = buildHeader();
        root.addView(headerView);

        LinearLayout content = contentBox(root, true);
        LinearLayout box = card(content, "即将提醒", "含每日提醒，按时间排序");
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(listBox);
        return sc;
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{BLUE, BLUE_MID});
        float r = dp(20);
        bg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        header.setBackground(bg);
        header.setPadding(dp(20), dp(14), dp(20), dp(20));

        TextView t = new TextView(this);
        t.setText("别错过");
        t.setTextSize(21);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(t);

        TextView sub = new TextView(this);
        sub.setText("截止日与每日打卡 · 静音与震动模式下也会响");
        sub.setTextSize(12.5f);
        sub.setTextColor(0xFFCFE0FF);
        sub.setPadding(0, dp(6), 0, 0);
        header.addView(sub);

        headerMeta = new TextView(this);
        headerMeta.setTextSize(12);
        headerMeta.setTextColor(0xFFE8F0FF);
        headerMeta.setPadding(0, dp(12), 0, 0);
        header.addView(headerMeta);
        return header;
    }

    // ------------------------------------------------------------ 添加页

    private ScrollView buildAddPage() {
        ScrollView sc = newPage();
        LinearLayout root = body(sc);
        addBody = contentBox(root, false);
        LinearLayout content = addBody;

        content.addView(pageTitle("添加提醒"));

        LinearLayout manage = card(content, "已有的每日提醒", "点右侧可删除");
        remBox = new LinearLayout(this);
        remBox.setOrientation(LinearLayout.VERTICAL);
        manage.addView(remBox);

        LinearLayout form = card(content, "新增每日提醒", "每天固定时间，走闹钟音量");

        TextView l1 = new TextView(this);
        l1.setText("时间（24 小时制）");
        l1.setTextSize(12);
        l1.setTextColor(GRAY);
        form.addView(l1);

        remTimeBox = new EditText(this);
        remTimeBox.setText("21:00");
        remTimeBox.setTextSize(14);
        remTimeBox.setInputType(InputType.TYPE_CLASS_DATETIME);
        remTimeBox.setBackground(shape(PAGE, 8, LINE, 1));
        remTimeBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(6);
        tl.bottomMargin = dp(12);
        form.addView(remTimeBox, tl);

        TextView l2 = new TextView(this);
        l2.setText("内容");
        l2.setTextSize(12);
        l2.setTextColor(GRAY);
        form.addView(l2);

        remTitleBox = new EditText(this);
        remTitleBox.setHint("例如 多邻国打卡");
        remTitleBox.setTextSize(14);
        remTitleBox.setSingleLine(true);
        remTitleBox.setBackground(shape(PAGE, 8, LINE, 1));
        remTitleBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2.topMargin = dp(6);
        t2.bottomMargin = dp(14);
        form.addView(remTitleBox, t2);

        form.addView(primary("添加提醒", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addRemFromInput();
            }
        }));

        return sc;
    }

    // ------------------------------------------------------------ 我的页

    private ScrollView buildMinePage() {
        ScrollView sc = newPage();
        LinearLayout root = body(sc);
        mineBody = contentBox(root, false);
        LinearLayout content = mineBody;

        content.addView(pageTitle("我的"));

        LinearLayout st = card(content, "运行状态", null);
        status = new TextView(this);
        status.setTextSize(13.5f);
        status.setTextColor(BLUE_D);
        status.setLineSpacing(dp(3), 1f);
        st.addView(status);
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.setPadding(0, dp(8), 0, 0);
        st.addView(permBox);

        LinearLayout ops = card(content, "操作", null);
        ops.addView(primary("立即同步（拉取最新 ICS）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSync();
            }
        }));
        ops.addView(ghost("测试提醒（10 秒后响）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Sched.testAlarm(MainActivity.this, 10_000L, 999001);
                toast("10 秒后响。请先把手机调成静音或震动模式再测。");
            }
        }));

        LinearLayout perm = card(content, "权限与保障", "未开会导致提醒不响");
        perm.addView(ghost("① 允许通知", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askNotifications();
            }
        }));
        perm.addView(ghost("② 允许精确闹钟", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", true);
            }
        }));
        perm.addView(ghost("③ 允许全屏提醒（锁屏弹出）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", true);
            }
        }));
        perm.addView(ghost("④ 加入电池优化白名单", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    toast("请手动到电池设置里设为「无限制」");
                }
            }
        }));
        perm.addView(ghost("⑤ 授予勿扰访问权限（可选）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                } catch (Throwable t) {
                    toast("请手动到 设置-通知-勿扰权限 里授予");
                }
            }
        }));

        TextView note = new TextView(this);
        note.setText("vivo 还需手动开：设置 → 权限管理 → 自启动；设置 → 电池 → 后台高耗电 → 允许。");
        note.setTextSize(11.5f);
        note.setTextColor(GRAY);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(0, dp(4), 0, 0);
        perm.addView(note);

        LinearLayout url = card(content, "ICS 地址", "一般不用改");
        urlBox = new EditText(this);
        urlBox.setText(Sched.url(this));
        urlBox.setTextSize(11.5f);
        urlBox.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setBackground(shape(PAGE, 8, LINE, 1));
        urlBox.setPadding(dp(10), dp(9), dp(10), dp(9));
        url.addView(urlBox);
        Button save = ghost("保存地址并同步", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = urlBox.getText().toString().trim();
                if (u.isEmpty()) u = Sched.DEFAULT_URL;
                Sched.p(MainActivity.this).edit().putString(Sched.KEY_URL, u).apply();
                doSync();
            }
        });
        LinearLayout.LayoutParams sl = (LinearLayout.LayoutParams) save.getLayoutParams();
        sl.topMargin = dp(10);
        sl.bottomMargin = 0;
        url.addView(save);

        LinearLayout about = card(content, "关于", null);
        TextView ab = new TextView(this);
        ab.setText("别错过 v1.0\n"
                + "把监控的 ICS 截止日与你的每日打卡排成系统闹钟，"
                + "走闹钟音量流，静音/震动模式下也会响。\n"
                + "数据全部在本机，除拉取 ICS 外不联网。");
        ab.setTextSize(12);
        ab.setTextColor(GRAY);
        ab.setLineSpacing(dp(3), 1f);
        about.addView(ab);

        return sc;
    }

    // ------------------------------------------------------------ 控件工厂

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int fill, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private LinearLayout card(LinearLayout parent, String title, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(0xFFFFFFFF, 12, LINE, 1));
        box.setPadding(dp(15), dp(14), dp(15), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(box, lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(BLUE_D);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t);
        if (hint != null) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextSize(11);
            h.setTextColor(GRAY);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hl.leftMargin = dp(8);
            head.addView(h, hl);
        }
        head.setPadding(0, 0, 0, dp(10));
        box.addView(head);
        return box;
    }

    private Button primary(String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(15);
        btn.setTextColor(Color.WHITE);
        btn.setBackground(shape(BLUE, 10, 0, 0));
        btn.setStateListAnimator(null);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        btn.setLayoutParams(lp);
        return btn;
    }

    private Button ghost(String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTextColor(BLUE);
        btn.setBackground(shape(0xFFFFFFFF, 10, STROKE, 1));
        btn.setStateListAnimator(null);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        btn.setLayoutParams(lp);
        return btn;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    // ------------------------------------------------------------ 动态内容

    private void fillList() {
        listBox.removeAllViews();
        List<Sched.Up> rows = Sched.upcoming(this, System.currentTimeMillis());
        if (rows.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("暂无未来提醒，到「我的」页点「立即同步」。");
            t.setTextSize(13);
            t.setTextColor(GRAY);
            listBox.addView(t);
            return;
        }
        SimpleDateFormat full = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        SimpleDateFormat hmOnly = new SimpleDateFormat("HH:mm", Locale.CHINA);
        int n = 0;
        for (Sched.Up u : rows) {
            if (n >= LIST_LIMIT) break;
            if (n > 0) listBox.addView(divider());

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));

            TextView time = new TextView(this);
            time.setText(u.daily
                    ? "每日 " + hmOnly.format(new Date(u.trigger))
                    : full.format(new Date(u.trigger)));
            time.setTextSize(12);
            time.setTextColor(u.daily ? GREEN : BLUE);
            time.setTypeface(Typeface.DEFAULT_BOLD);
            time.setBackground(shape(u.daily ? 0xFFE8F5EE : BLUE_L, 6, 0, 0));
            time.setPadding(dp(8), dp(3), dp(8), dp(3));
            row.addView(time);

            TextView title = new TextView(this);
            title.setText(u.title);
            title.setTextSize(13.5f);
            title.setTextColor(INK);
            title.setLineSpacing(dp(3), 1f);
            title.setPadding(0, dp(6), 0, 0);
            row.addView(title);

            listBox.addView(row);
            n++;
        }
        if (rows.size() > LIST_LIMIT) {
            TextView more = new TextView(this);
            more.setText("… 另有 " + (rows.size() - LIST_LIMIT) + " 条，都已排进系统闹钟");
            more.setTextSize(12);
            more.setTextColor(GRAY);
            more.setPadding(0, dp(9), 0, 0);
            listBox.addView(more);
        }
    }

    private void fillRems() {
        remBox.removeAllViews();
        List<Sched.Rem> rems = Sched.loadRems(this);
        if (rems.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("还没有每日提醒，在下面加一条。");
            t.setTextSize(12.5f);
            t.setTextColor(GRAY);
            remBox.addView(t);
            return;
        }
        for (int i = 0; i < rems.size(); i++) {
            final Sched.Rem r = rems.get(i);
            if (i > 0) remBox.addView(divider());

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));

            TextView time = new TextView(this);
            time.setText(String.format(Locale.CHINA, "%02d:%02d", r.hour, r.minute));
            time.setTextSize(12);
            time.setTextColor(GREEN);
            time.setTypeface(Typeface.DEFAULT_BOLD);
            time.setBackground(shape(0xFFE8F5EE, 6, 0, 0));
            time.setPadding(dp(8), dp(3), dp(8), dp(3));
            row.addView(time);

            TextView title = new TextView(this);
            title.setText(r.title);
            title.setTextSize(13.5f);
            title.setTextColor(INK);
            title.setPadding(dp(10), 0, 0, 0);
            row.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView del = new TextView(this);
            del.setText("删除");
            del.setTextSize(12.5f);
            del.setTextColor(RED);
            del.setPadding(dp(10), dp(4), 0, dp(4));
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Sched.delRem(MainActivity.this, r);
                    Sched.scheduleAll(MainActivity.this);
                    refresh();
                }
            });
            row.addView(del);

            remBox.addView(row);
        }
    }

    private void fillPerms() {
        permBox.removeAllViews();
        boolean notif = true;
        if (Build.VERSION.SDK_INT >= 33) {
            notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        permBox.addView(permLine(notif, "通知权限", "未开则弹不出提醒"));
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            boolean ex = am != null && am.canScheduleExactAlarms();
            permBox.addView(permLine(ex, "精确闹钟", "未开则触发时间不准"));
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean ig = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        permBox.addView(permLine(ig, "电池白名单", "未加则后台可能被杀"));
    }

    private TextView permLine(boolean ok, String name, String why) {
        TextView t = new TextView(this);
        t.setText((ok ? "✓ " : "✗ ") + name + (ok ? "" : "　" + why));
        t.setTextSize(12.5f);
        t.setTextColor(ok ? GREEN : RED);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    // ------------------------------------------------------------ 行为

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void open(String action, boolean withPackage) {
        try {
            Intent i = new Intent(action);
            if (withPackage) i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(action));
            } catch (Throwable t2) {
                toast("这台机型没有这个设置页，请手动到系统设置里找");
            }
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            toast("系统版本不需要单独申请通知权限");
        }
    }

    private void addRemFromInput() {
        String t = remTimeBox.getText().toString().trim().replace('：', ':');
        String name = remTitleBox.getText().toString().trim();
        if (name.isEmpty()) {
            toast("先填提醒内容");
            return;
        }
        int hour;
        int min;
        try {
            String[] part = t.split(":");
            hour = Integer.parseInt(part[0].trim());
            min = Integer.parseInt(part[1].trim());
        } catch (Throwable e) {
            toast("时间格式请用 HH:mm，例如 21:00");
            return;
        }
        if (hour < 0 || hour > 23 || min < 0 || min > 59) {
            toast("时间不合法：小时 0-23、分钟 0-59");
            return;
        }
        Sched.addRem(this, name, hour, min);
        Sched.scheduleAll(this);
        remTitleBox.setText("");
        toast("已添加：" + String.format(Locale.CHINA, "%02d:%02d", hour, min) + " " + name);
        refresh();
    }

    private void doSync() {
        status.setText("正在同步…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String msg = Sched.sync(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(msg);
                        Sched.scheduleDailySync(MainActivity.this);
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void refresh() {
        String last = Sched.p(this).getString(Sched.KEY_LAST, "");
        status.setText(last == null || last.isEmpty() ? "尚未同步，到「我的」页点「立即同步」." : last);

        List<Sched.Up> rows = Sched.upcoming(this, System.currentTimeMillis());
        long next = rows.isEmpty() ? 0L : rows.get(0).trigger;
        if (next > 0) {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
            headerMeta.setText("共 " + rows.size() + " 条待提醒　·　最近一条 "
                    + f.format(new Date(next)));
        } else {
            headerMeta.setText("尚无待提醒项");
        }

        fillPerms();
        fillList();
        fillRems();
    }
}
