package com.qiuzhao.alarm;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.view.WindowManager;
import android.widget.Button;
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#111827"));
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(24f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        root.addView(tv);

        if (!desc.isEmpty()) {
            TextView dv = new TextView(this);
            dv.setText(desc);
            dv.setTextColor(Color.parseColor("#c7d2fe"));
            dv.setTextSize(15f);
            dv.setGravity(Gravity.CENTER);
            dv.setPadding(0, pad / 2, 0, pad / 2);
            root.addView(dv);
        }

        Button stop = new Button(this);
        stop.setText("关闭提醒");
        stop.setTextSize(18f);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                silence();
                Sched.dismiss(AlarmActivity.this, code);
                finish();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        root.addView(stop, lp);

        setContentView(root);
        startRinging();
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
