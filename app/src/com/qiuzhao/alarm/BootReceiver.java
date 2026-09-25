package com.qiuzhao.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后重新排程（否则重启一次提醒就全丢了）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Sched.ensureChannel(context);
            Sched.scheduleAll(context);
            Sched.scheduleDailySync(context);
        }
    }
}
