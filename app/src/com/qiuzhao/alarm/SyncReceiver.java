package com.qiuzhao.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 每日自动同步：拉最新 ICS 并重排闹钟，然后把下一次同步再排上。 */
public class SyncReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Sched.ensureChannel(app);
                    Sched.sync(app);
                    Sched.scheduleDailySync(app);
                } catch (Throwable ignore) {
                    // 失败也要把下一次排上，否则以后就再也不自动同步了
                    try { Sched.scheduleDailySync(app); } catch (Throwable ignore2) { }
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }
}
