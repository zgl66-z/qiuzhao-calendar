package com.qiuzhao.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 闹钟触发 / 用户点「关闭提醒」时进来。 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        int code = intent.getIntExtra("code", 0);
        Log.i(Sched.TAG, "AlarmReceiver 收到: action=" + action + " code=" + code);
        if ("com.qiuzhao.alarm.DISMISS".equals(action)) {
            Sched.dismiss(context, code);
            return;
        }
        String title = intent.getStringExtra("title");
        String desc = intent.getStringExtra("desc");
        if (title == null) title = "秋招截止提醒";
        if (Sched.isDismissed(context, code)) {
            Log.w(Sched.TAG, "onReceive: code=" + code + " 已被标记关闭，跳过（这会导致不响）");
            return;
        }
        Sched.fire(context, title, desc == null ? "" : desc, code);
    }
}
