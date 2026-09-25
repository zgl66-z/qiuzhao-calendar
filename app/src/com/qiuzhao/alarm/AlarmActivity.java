package com.qiuzhao.alarm;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 全屏闹钟页：锁屏上直接弹出，用闹钟音量流循环响铃 + 震动，直到点「关闭」。 */
public class AlarmActivity extends Activity {

    private MediaPlayer player;
    private Vibrator vibrator;
    private int code = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String title = getIntent().getStringExtra("title");
        String desc = getIntent().getStringExtra("desc");
        code = getIntent().getIntExtra("code", 0);
        if (title == null) title = "秋招截止提醒";
        if (desc == null) desc = "";

        final float density = getResources().getDisplayMetrics().density;
        int dp = (int) (24 * density);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1E2A44, 0xFF111827}));

        // 装饰圆，制造层次
        View c1 = circle((int) (170 * density), 0x14FFFFFF);
        FrameLayout.LayoutParams c1l = new FrameLayout.LayoutParams(
                (int) (170 * density), (int) (170 * density));
        c1l.gravity = Gravity.TOP | Gravity.RIGHT;
        c1l.topMargin = -(int) (60 * density);
        c1l.rightMargin = -(int) (40 * density);
        root.addView(c1, c1l);

        View c2 = circle((int) (90 * density), 0x0CFFFFFF);
        FrameLayout.LayoutParams c2l = new FrameLayout.LayoutParams(
                (int) (90 * density), (int) (90 * density));
        c2l.gravity = Gravity.BOTTOM | Gravity.LEFT;
        c2l.bottomMargin = (int) (40 * density);
        c2l.leftMargin = -(int) (24 * density);
        root.addView(c2, c2l);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        int pad = (int) (24 * density);
        center.setPadding(pad, pad, pad, pad);
        root.addView(center, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部小标签
        TextView tag = new TextView(this);
        tag.setText("闹钟提醒");
        tag.setTextSize(12);
        tag.setTextColor(0xFF9FB3E8);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(0, 0, 0, (int) (10 * density));
        center.addView(tag);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(24f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing((int) (4 * density), 1f);
        center.addView(tv);

        if (!desc.isEmpty()) {
            TextView dv = new TextView(this);
            dv.setText(desc);
            dv.setTextColor(Color.parseColor("#c7d2fe"));
            dv.setTextSize(15f);
            dv.setGravity(Gravity.CENTER);
            dv.setLineSpacing((int) (3 * density), 1f);
            dv.setPadding(0, pad / 2, 0, pad / 2);
            center.addView(dv);
        }

        Button stop = new Button(this);
        stop.setText("关闭提醒");
        stop.setTextSize(17f);
        stop.setAllCaps(false);
        stop.setTextColor(Color.WHITE);
        stop.setBackground(buttonBg((int) (26 * density)));
        stop.setElevation((int) (6 * density));
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                silence();
                Sched.dismiss(AlarmActivity.this, code);
                finish();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (int) (52 * density));
        lp.topMargin = pad;
        stop.setPadding((int) (34 * density), 0, (int) (34 * density), 0);
        center.addView(stop, lp);

        setContentView(root);
        startRinging();
    }

    private static android.graphics.drawable.Drawable buttonBg(int radiusPx) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF3A72C9, 0xFF2456A6});
        d.setCornerRadius(radiusPx);
        return d;
    }

    private View circle(int size, int color) {
        View v = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
        return v;
    }

    private void startRinging() {
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = Settings.System.DEFAULT_ALARM_ALERT_URI;
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, sound);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Throwable ignore) { }

        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = new long[]{0, 800, 400, 800, 400};
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Throwable ignore) { }
    }

    private void silence() {
        try {
            if (player != null) {
                if (player.isPlaying()) player.stop();
                player.release();
                player = null;
            }
        } catch (Throwable ignore) { }
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Throwable ignore) { }
    }

    @Override
    public void onBackPressed() {
        silence();
        Sched.dismiss(this, code);
        finish();
    }

    @Override
    protected void onDestroy() {
        silence();
        super.onDestroy();
    }
}
