package com.lwl.bookreader.ui.shelf;

import android.content.Context;

import com.lwl.bookreader.R;
import com.lwl.bookreader.data.Book;

/** 书架条目状态文案工具。 */
final class ShelfFormat {

    private ShelfFormat() {
    }

    static String statusText(Context ctx, Book book) {
        if (book.lastReadTime <= 0) {
            return ctx.getString(R.string.shelf_status_new);
        }
        long diff = System.currentTimeMillis() - book.lastReadTime;
        long minute = 60_000L, hour = 60 * minute, day = 24 * hour;
        if (diff < hour) {
            return ctx.getString(R.string.shelf_status_just);
        } else if (diff < day) {
            return ctx.getString(R.string.shelf_status_hours, diff / hour);
        } else {
            return ctx.getString(R.string.shelf_status_days, diff / day);
        }
    }
}
