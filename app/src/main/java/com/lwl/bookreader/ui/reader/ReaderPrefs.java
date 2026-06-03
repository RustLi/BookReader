package com.lwl.bookreader.ui.reader;

import android.content.Context;
import android.content.SharedPreferences;

/** 阅读设置持久化(字号 / 主题 / 行距 / 亮度)。 */
public class ReaderPrefs {

    /** 主题:背景色 + 文字色。 */
    public static final int[] THEME_BG = {0xFFEDE5D3, 0xFFFFFFFF, 0xFFC7EDCC, 0xFF1E1E1E};
    public static final int[] THEME_TEXT = {0xFF3A3026, 0xFF222222, 0xFF2E3A2E, 0xFFB0B0B0};
    public static final String[] THEME_NAME = {"米黄", "纯白", "护眼", "夜间"};

    /** 行距档位(line-height 倍数)。 */
    public static final float[] LINE_HEIGHTS = {1.4f, 1.7f, 2.0f, 2.4f};

    private static final String FILE = "reader_prefs";
    private final SharedPreferences sp;

    public ReaderPrefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int getFontZoom() {
        return sp.getInt("font_zoom", 110);
    }

    public void setFontZoom(int zoom) {
        sp.edit().putInt("font_zoom", clamp(zoom, 70, 220)).apply();
    }

    public int getTheme() {
        return clamp(sp.getInt("theme", 0), 0, THEME_BG.length - 1);
    }

    public void setTheme(int idx) {
        sp.edit().putInt("theme", clamp(idx, 0, THEME_BG.length - 1)).apply();
    }

    public int getLineSpacing() {
        return clamp(sp.getInt("line_spacing", 1), 0, LINE_HEIGHTS.length - 1);
    }

    public void setLineSpacing(int idx) {
        sp.edit().putInt("line_spacing", clamp(idx, 0, LINE_HEIGHTS.length - 1)).apply();
    }

    /** 亮度 0~1;<0 表示跟随系统。 */
    public float getBrightness() {
        return sp.getFloat("brightness", -1f);
    }

    public void setBrightness(float b) {
        sp.edit().putFloat("brightness", b).apply();
    }

    public int bgColor() {
        return THEME_BG[getTheme()];
    }

    public int textColor() {
        return THEME_TEXT[getTheme()];
    }

    public float lineHeight() {
        return LINE_HEIGHTS[getLineSpacing()];
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
