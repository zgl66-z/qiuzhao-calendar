package com.qiuzhao.alarm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一条日历事件：标题 + 开始时间 + 若干条 VALARM 提前量。 */
public class Event {
    public String uid = "";
    public String title = "";
    public String desc = "";
    /** 事件开始时间（毫秒）。 */
    public long start = 0L;
    /** VALARM 偏移（毫秒，负数=提前触发）。 */
    public List<Long> offsets = new ArrayList<Long>();
    /** 原始 RRULE，空串表示单次事件。 */
    public String rrule = "";

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("uid", uid);
        o.put("title", title);
        o.put("desc", desc);
        o.put("start", start);
        o.put("rrule", rrule);
        JSONArray a = new JSONArray();
        for (int i = 0; i < offsets.size(); i++) {
            a.put(offsets.get(i).longValue());
        }
        o.put("off", a);
        return o;
    }

    public static Event fromJson(JSONObject o) {
        Event e = new Event();
        e.uid = o.optString("uid", "");
        e.title = o.optString("title", "");
        e.desc = o.optString("desc", "");
        e.start = o.optLong("start", 0L);
        e.rrule = o.optString("rrule", "");
        JSONArray a = o.optJSONArray("off");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                e.offsets.add(Long.valueOf(a.optLong(i)));
            }
        }
        return e;
    }
}
