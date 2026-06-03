package com.lwl.bookreader.util;

import android.content.Context;

import com.lwl.bookreader.R;

/** 阅读时间相对文案。 */
public final class TimeFormat {

    private TimeFormat() {
    }

    /** 返回「刚刚 / X小时前 / X天前」(不含「读过」后缀)。 */
    public static String relative(Context ctx, long time) {
        if (time <= 0) return "";
        long diff = System.currentTimeMillis() - time;
        long minute = 60_000L, hour = 60 * minute, day = 24 * hour;
        if (diff < hour) {
            return ctx.getString(R.string.time_just);
        } else if (diff < day) {
            return ctx.getString(R.string.time_hours, diff / hour);
        } else {
            return ctx.getString(R.string.time_days, diff / day);
        }
    }
}
