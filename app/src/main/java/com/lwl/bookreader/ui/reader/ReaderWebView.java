package com.lwl.bookreader.ui.reader;

import android.content.Context;
import android.util.AttributeSet;
import android.webkit.WebView;

/** 暴露竖直滚动范围并回调滚动变化的 WebView,用于阅读进度联动。 */
public class ReaderWebView extends WebView {

    public interface OnScrollChange {
        void onScroll(int scrollY, int range);
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

    /** 内容总高度(像素)。 */
    public int verticalRange() {
        return computeVerticalScrollRange();
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (scrollChange != null) {
            scrollChange.onScroll(t, computeVerticalScrollRange());
        }
    }
}
