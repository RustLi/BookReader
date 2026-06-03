package com.lwl.bookreader.ui.reader;

import android.content.Context;
import android.util.AttributeSet;
import android.webkit.WebView;

/** 暴露横向滚动范围并回调滚动变化的 WebView，用于多栏分页阅读进度联动。 */
public class ReaderWebView extends WebView {

    public interface OnScrollChange {
        void onScroll(int scrollX, int range);
    }

    private OnScrollChange scrollChange;

    public ReaderWebView(Context context) {
        super(context);
    }

    public ReaderWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOnScrollChange(OnScrollChange l) {
        this.scrollChange = l;
    }

    /** 内容横向总宽度（物理像素），等于总页数 × 页宽。 */
    public int horizontalRange() {
        return computeHorizontalScrollRange();
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (scrollChange != null) {
            scrollChange.onScroll(l, computeHorizontalScrollRange());
        }
    }
}
