package com.lwl.bookreader.ui.reader;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.lwl.bookreader.R;
import com.lwl.bookreader.data.AppDatabase;
import com.lwl.bookreader.data.Book;
import com.lwl.bookreader.data.BookRepository;
import com.lwl.bookreader.data.Bookmark;
import com.lwl.bookreader.data.Note;
import com.lwl.bookreader.util.TimeFormat;
import com.lwl.bookreader.data.epub.EpubBook;
import com.lwl.bookreader.data.epub.EpubExtractor;
import com.lwl.bookreader.data.epub.EpubParser;
import com.lwl.bookreader.data.mobi.MobiParser;
import com.lwl.bookreader.databinding.ActivityReaderBinding;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** EPUB 阅读页(图 04):WebView 渲染章节,支持翻页、目录跳转、进度记忆。 */
public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_BOOK_ID = "book_id";

    private ActivityReaderBinding binding;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService dbExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BookRepository repository;

    private Book book;
    private EpubBook epub;
    private File extractDir;
    private int currentChapter;
    private boolean barsVisible = true;

    private boolean restorePending;
    private float pendingScrollFraction;
    private boolean userSeeking;

    private TtsManager tts;
    private boolean ttsWanted;          // 用户希望朗读(用于切章自动续读)
    private boolean ttsPrepared;        // 当前章节已注入分句脚本

    private ReaderPrefs prefs;

    /** 注入脚本:把正文按句包成 span,回传句子数组,并提供高亮函数。 */
    private static final String TTS_SCRIPT =
            "(function(){try{"
            + "if(window.__ttsReady){return;}"
            + "var idx=0,sentences=[];"
            + "var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);"
            + "var ns=[];while(w.nextNode()){ns.push(w.currentNode);}"
            + "ns.forEach(function(n){var t=n.nodeValue;"
            + "if(!t||!t.replace(/\\s/g,'').length){return;}"
            + "var parts=t.match(/[^。！？!?；…\\n]+[。！？!?；…]?|\\n+/g);"
            + "if(!parts){return;}var frag=document.createDocumentFragment();"
            + "parts.forEach(function(p){"
            + "if(!p.replace(/\\s/g,'').length){frag.appendChild(document.createTextNode(p));return;}"
            + "var s=document.createElement('span');s.className='ttsseg';"
            + "s.setAttribute('data-i',idx);s.textContent=p;frag.appendChild(s);"
            + "sentences.push(p.replace(/\\s+/g,' ').trim());idx++;});"
            + "if(n.parentNode){n.parentNode.replaceChild(frag,n);}});"
            + "var st=document.createElement('style');"
            + "st.textContent='.ttshl{background:#FFE08A;border-radius:3px;}';"
            + "document.head.appendChild(st);window.__ttsReady=true;"
            + "window.__ttsHighlight=function(i){var pv=document.querySelector('.ttshl');"
            + "if(pv){pv.classList.remove('ttshl');}"
            + "var el=document.querySelector('.ttsseg[data-i=\"'+i+'\"]');"
            + "if(el){el.classList.add('ttshl');el.scrollIntoView({block:'center'});}};"
            + "Android.onSentences(JSON.stringify(sentences));"
            + "}catch(e){Android.onSentences('[]');}})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityReaderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        repository = new BookRepository(this);
        prefs = new ReaderPrefs(this);

        setupWebView();
        setupControls();
        applyBrightness();
        applyThemeChrome();

        long id = getIntent().getLongExtra(EXTRA_BOOK_ID, -1);
        loadBook(id);
    }

    @SuppressLint({"ClickableViewAccessibility", "SetJavaScriptEnabled"})
    private void setupWebView() {
        WebSettings ws = binding.web.getSettings();
        ws.setAllowFileAccess(true);
        ws.setJavaScriptEnabled(true);       // 朗读高亮需要 JS
        ws.setDefaultTextEncodingName("UTF-8");
        ws.setUseWideViewPort(false);
        ws.setLoadWithOverviewMode(false);
        ws.setTextZoom(prefs.getFontZoom());
        binding.web.setBackgroundColor(prefs.bgColor());
        binding.web.addJavascriptInterface(new TtsBridge(), "Android");

        binding.web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectReaderCss();
                if (restorePending) {
                    restorePending = false;
                    final float frac = pendingScrollFraction;
                    main.postDelayed(() -> applyScrollFraction(frac), 150);
                }
                ttsPrepared = false;
                if (ttsWanted) {
                    // 切章后自动续读:重新注入分句脚本
                    main.postDelayed(() -> binding.web.evaluateJavascript(TTS_SCRIPT, null), 200);
                }
                updateProgressUi();
            }
        });

        binding.web.setOnScrollChange((scrollY, range) -> {
            if (!userSeeking) updateProgressUi();
        });

        GestureDetector gesture = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(@NonNull MotionEvent e) {
                        int w = binding.web.getWidth();
                        float x = e.getX();
                        if (x < w * 0.30f) {
                            turnChapter(-1);
                        } else if (x > w * 0.70f) {
                            turnChapter(1);
                        } else {
                            toggleBars();
                        }
                        return false;
                    }
                });
        binding.web.setOnTouchListener((v, e) -> {
            gesture.onTouchEvent(e);
            return false;
        });
    }

    private void setupControls() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnTts.setOnClickListener(v -> toggleTts());
        binding.btnBookmark.setOnClickListener(v -> addBookmark());
        binding.toolToc.setOnClickListener(v -> showToc());
        binding.toolNote.setOnClickListener(v -> handleNote());
        binding.toolBookmark.setOnClickListener(v -> showBookmarks());
        binding.toolSettings.setOnClickListener(v -> showSettings());

        binding.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    binding.progressText.setText((progress / 10) + "%");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                seekTo(s.getProgress() / 1000f);
            }
        });
    }

    private void loadBook(long id) {
        io.execute(() -> {
            try {
                Book b = AppDatabase.getInstance(this).bookDao().findById(id);
                if (b == null) {
                    finishWithToast(getString(R.string.reader_open_failed, "not found"));
                    return;
                }
                File bookFile = new File(b.filePath);
                File dir = new File(getCacheDir(), "reader/" + id);
                EpubBook eb;
                if ("mobi".equalsIgnoreCase(b.format)) {
                    eb = MobiParser.openForReading(bookFile, dir);
                } else if ("epub".equalsIgnoreCase(b.format)) {
                    EpubExtractor.extract(bookFile, dir);
                    eb = EpubParser.openForReading(bookFile);
                } else {
                    finishWithToast(getString(R.string.reader_unsupported));
                    return;
                }
                if (eb.spine.isEmpty()) {
                    finishWithToast(getString(R.string.reader_empty_book));
                    return;
                }
                main.post(() -> onLoaded(b, eb, dir));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                finishWithToast(getString(R.string.reader_open_failed, msg));
            }
        });
    }

    private void onLoaded(Book b, EpubBook eb, File dir) {
        this.book = b;
        this.epub = eb;
        this.extractDir = dir;
        binding.readerTitle.setText(b.title);

        currentChapter = clamp(b.chapterIndex, 0, eb.spine.size() - 1);
        pendingScrollFraction = b.chapterProgress;
        restorePending = true;
        loadChapter(currentChapter);
    }

    private void loadChapter(int index) {
        if (epub == null) return;
        currentChapter = clamp(index, 0, epub.spine.size() - 1);
        File f = new File(extractDir, epub.spine.get(currentChapter).zipPath);
        binding.web.loadUrl(Uri.fromFile(f).toString());
        updateProgressUi();
    }

    private void turnChapter(int delta) {
        if (epub == null) return;
        int target = currentChapter + delta;
        if (target < 0 || target >= epub.spine.size()) return;
        restorePending = false;
        loadChapter(target);
    }

    /** 拖动进度条:overall ∈ [0,1] 映射到 章节 + 章内位置。 */
    private void seekTo(float overall) {
        if (epub == null) return;
        int total = epub.spine.size();
        float scaled = overall * total;
        int target = clamp((int) scaled, 0, total - 1);
        float rem = scaled - target;
        if (target == currentChapter) {
            applyScrollFraction(rem);
            updateProgressUi();
        } else {
            pendingScrollFraction = rem;
            restorePending = true;
            loadChapter(target);
        }
    }

    private void applyScrollFraction(float frac) {
        int max = Math.max(0, binding.web.verticalRange() - binding.web.getHeight());
        binding.web.scrollTo(0, (int) (clampF(frac, 0f, 1f) * max));
    }

    private float scrollFraction() {
        int max = binding.web.verticalRange() - binding.web.getHeight();
        if (max <= 0) return 0f;
        return clampF(binding.web.getScrollY() / (float) max, 0f, 1f);
    }

    private void updateProgressUi() {
        if (epub == null || epub.spine.isEmpty()) return;
        float overall = (currentChapter + scrollFraction()) / epub.spine.size();
        binding.seek.setProgress((int) (overall * 1000));
        binding.progressText.setText((int) (overall * 100) + "%");
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        int v = barsVisible ? View.VISIBLE : View.GONE;
        binding.topBar.setVisibility(v);
        binding.bottomBar.setVisibility(v);
    }

    private void showToc() {
        if (epub == null) return;
        List<TocAdapter.Row> rows = new ArrayList<>();
        if (!epub.toc.isEmpty()) {
            for (EpubBook.Toc t : epub.toc) {
                rows.add(new TocAdapter.Row(
                        t.title != null ? t.title : t.zipPath, t.zipPath, t.depth));
            }
        } else {
            for (int i = 0; i < epub.spine.size(); i++) {
                EpubBook.Spine s = epub.spine.get(i);
                String title = s.title != null ? s.title
                        : getString(R.string.reader_chapter_n, i + 1);
                rows.add(new TocAdapter.Row(title, s.zipPath, 0));
            }
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.reader_no_toc, Toast.LENGTH_SHORT).show();
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_toc, null);
        RecyclerView list = content.findViewById(R.id.toc_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new TocAdapter(rows, zipPath -> {
            jumpToZipPath(zipPath);
            dialog.dismiss();
        }));
        dialog.setContentView(content);
        dialog.show();
    }

    private void jumpToZipPath(String zipPath) {
        for (int i = 0; i < epub.spine.size(); i++) {
            if (epub.spine.get(i).zipPath.equals(zipPath)) {
                restorePending = false;
                loadChapter(i);
                return;
            }
        }
    }

    // ---- 阅读设置 ----

    private void applyBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float b = prefs.getBrightness();
        lp.screenBrightness = b < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : b;
        getWindow().setAttributes(lp);
    }

    /** 应用主题底色到页面外壳(根布局 + WebView 背景)。 */
    private void applyThemeChrome() {
        binding.getRoot().setBackgroundColor(prefs.bgColor());
        binding.web.setBackgroundColor(prefs.bgColor());
    }

    /** 注入主题色 / 行距 CSS。 */
    private void injectReaderCss() {
        String bg = hex(prefs.bgColor());
        String fg = hex(prefs.textColor());
        float lh = prefs.lineHeight();
        String css = "html,body{background:" + bg + " !important;color:" + fg
                + " !important;line-height:" + lh + " !important;}"
                + "a{color:#3F8CFF !important;}img{max-width:100% !important;height:auto !important;}";
        String js = "(function(){var id='__readercss';var s=document.getElementById(id);"
                + "if(!s){s=document.createElement('style');s.id=id;"
                + "(document.head||document.documentElement).appendChild(s);}"
                + "s.textContent=" + jsString(css) + ";})()";
        binding.web.evaluateJavascript(js, null);
    }

    private void showSettings() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_settings, null);

        SeekBar brightness = v.findViewById(R.id.seek_brightness);
        float b = prefs.getBrightness();
        brightness.setProgress(b < 0 ? 60 : (int) (b * 100));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (u) { prefs.setBrightness(p / 100f); applyBrightness(); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        TextView fontVal = v.findViewById(R.id.tv_font);
        fontVal.setText(prefs.getFontZoom() + "%");
        v.findViewById(R.id.btn_font_minus).setOnClickListener(x -> {
            prefs.setFontZoom(prefs.getFontZoom() - 10);
            binding.web.getSettings().setTextZoom(prefs.getFontZoom());
            fontVal.setText(prefs.getFontZoom() + "%");
        });
        v.findViewById(R.id.btn_font_plus).setOnClickListener(x -> {
            prefs.setFontZoom(prefs.getFontZoom() + 10);
            binding.web.getSettings().setTextZoom(prefs.getFontZoom());
            fontVal.setText(prefs.getFontZoom() + "%");
        });

        buildLineSpacingOptions(v.findViewById(R.id.line_container));
        buildThemeOptions(v.findViewById(R.id.theme_container));

        dialog.setContentView(v);
        dialog.show();
    }

    private void buildLineSpacingOptions(LinearLayout container) {
        container.removeAllViews();
        String[] labels = {getString(R.string.ls_small), getString(R.string.ls_mid),
                getString(R.string.ls_large), getString(R.string.ls_xlarge)};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            com.google.android.material.button.MaterialButton btn =
                    new com.google.android.material.button.MaterialButton(this, null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btn.setText(labels[i]);
            btn.setAllCaps(false);
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    (int) (40 * getResources().getDisplayMetrics().density), 1f);
            lp.setMarginEnd(8);
            btn.setLayoutParams(lp);
            btn.setInsetTop(0);
            btn.setInsetBottom(0);
            if (idx == prefs.getLineSpacing()) {
                btn.setBackgroundColor(0x223F8CFF);
            }
            btn.setOnClickListener(x -> {
                prefs.setLineSpacing(idx);
                injectReaderCss();
                buildLineSpacingOptions(container);
            });
            container.addView(btn);
        }
    }

    private void buildThemeOptions(LinearLayout container) {
        container.removeAllViews();
        int size = (int) (40 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < ReaderPrefs.THEME_BG.length; i++) {
            final int idx = i;
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd((int) (12 * getResources().getDisplayMetrics().density));
            sw.setLayoutParams(lp);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(ReaderPrefs.THEME_BG[i]);
            d.setStroke((int) ((idx == prefs.getTheme() ? 3 : 1)
                    * getResources().getDisplayMetrics().density),
                    idx == prefs.getTheme() ? 0xFF3F8CFF : 0xFFCCCCCC);
            sw.setBackground(d);
            sw.setOnClickListener(x -> {
                prefs.setTheme(idx);
                applyThemeChrome();
                injectReaderCss();
                buildThemeOptions(container);
            });
            container.addView(sw);
        }
    }

    // ---- 书签 / 笔记 ----

    private void addBookmark() {
        if (book == null) return;
        evalForString(
                "(function(){try{var el=document.elementFromPoint(8,8);"
                + "var t=(el?el.innerText:'')||document.body.innerText||'';"
                + "return t.replace(/\\s+/g,' ').trim().substring(0,40);}catch(e){return '';}})()",
                snippet -> {
                    Bookmark bm = new Bookmark();
                    bm.bookId = book.id;
                    bm.chapterIndex = currentChapter;
                    bm.chapterProgress = scrollFraction();
                    bm.snippet = snippet.isEmpty() ? book.title : snippet;
                    bm.createTime = System.currentTimeMillis();
                    dbExec.execute(() -> AppDatabase.getInstance(this).bookmarkDao().insert(bm));
                    Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show();
                });
    }

    private void showBookmarks() {
        if (book == null) return;
        dbExec.execute(() -> {
            List<Bookmark> list = AppDatabase.getInstance(this).bookmarkDao().listByBook(book.id);
            main.post(() -> {
                List<MarkAdapter.Row> rows = new ArrayList<>();
                for (Bookmark bm : list) {
                    rows.add(new MarkAdapter.Row(bm.snippet,
                            TimeFormat.relative(this, bm.createTime),
                            bm.chapterIndex, bm.chapterProgress, bm));
                }
                showMarksSheet(getString(R.string.reader_bookmark),
                        getString(R.string.bookmark_empty), rows,
                        ref -> dbExec.execute(() ->
                                AppDatabase.getInstance(this).bookmarkDao().delete((Bookmark) ref)));
            });
        });
    }

    private void handleNote() {
        if (book == null) return;
        evalForString(
                "(function(){try{return (window.getSelection?window.getSelection().toString():'')"
                + ".replace(/\\s+/g,' ').trim();}catch(e){return '';}})()",
                sel -> {
                    if (!sel.isEmpty()) {
                        Note n = new Note();
                        n.bookId = book.id;
                        n.chapterIndex = currentChapter;
                        n.chapterProgress = scrollFraction();
                        n.text = sel;
                        n.createTime = System.currentTimeMillis();
                        dbExec.execute(() -> AppDatabase.getInstance(this).noteDao().insert(n));
                        Toast.makeText(this, R.string.note_added, Toast.LENGTH_SHORT).show();
                    } else {
                        showNotes();
                    }
                });
    }

    private void showNotes() {
        if (book == null) return;
        dbExec.execute(() -> {
            List<Note> list = AppDatabase.getInstance(this).noteDao().listByBook(book.id);
            main.post(() -> {
                List<MarkAdapter.Row> rows = new ArrayList<>();
                for (Note n : list) {
                    rows.add(new MarkAdapter.Row(n.text,
                            TimeFormat.relative(this, n.createTime),
                            n.chapterIndex, n.chapterProgress, n));
                }
                showMarksSheet(getString(R.string.reader_note),
                        getString(R.string.note_empty), rows,
                        ref -> dbExec.execute(() ->
                                AppDatabase.getInstance(this).noteDao().delete((Note) ref)));
                if (rows.isEmpty()) {
                    Toast.makeText(this, R.string.note_select_first, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private interface DeleteRef {
        void delete(Object ref);
    }

    private void showMarksSheet(String title, String empty,
                                List<MarkAdapter.Row> rows, DeleteRef onDelete) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_marks, null);
        ((TextView) v.findViewById(R.id.marks_title)).setText(title);
        TextView emptyView = v.findViewById(R.id.marks_empty);
        androidx.recyclerview.widget.RecyclerView list = v.findViewById(R.id.marks_list);
        emptyView.setText(empty);
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        list.setAdapter(new MarkAdapter(rows, new MarkAdapter.Listener() {
            @Override
            public void onClick(MarkAdapter.Row row) {
                jumpTo(row.chapterIndex, row.chapterProgress);
                dialog.dismiss();
            }

            @Override
            public void onLongClick(MarkAdapter.Row row) {
                onDelete.delete(row.ref);
                rows.remove(row);
                list.getAdapter().notifyDataSetChanged();
                emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                Toast.makeText(ReaderActivity.this, R.string.mark_deleted, Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.setContentView(v);
        dialog.show();
    }

    private void jumpTo(int chapterIndex, float progress) {
        restorePending = true;
        pendingScrollFraction = progress;
        loadChapter(chapterIndex);
    }

    private void evalForString(String js, ValueConsumer consumer) {
        binding.web.evaluateJavascript(js, value -> consumer.accept(unquote(value)));
    }

    private interface ValueConsumer {
        void accept(String value);
    }

    private static String unquote(String jsonStringValue) {
        if (jsonStringValue == null || "null".equals(jsonStringValue)) return "";
        try {
            Object o = new org.json.JSONTokener(jsonStringValue).nextValue();
            return o == null ? "" : o.toString();
        } catch (Exception e) {
            return jsonStringValue;
        }
    }

    private static String hex(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }

    private static String jsString(String s) {
        return org.json.JSONObject.quote(s);
    }

    // ---- 语音朗读 ----

    private void toggleTts() {
        if (epub == null) return;
        if (tts != null && tts.isPlaying()) {
            ttsWanted = false;
            tts.pause();
            updateTtsIcon();
            return;
        }
        ttsWanted = true;
        updateTtsIcon();
        if (tts == null) {
            tts = new TtsManager(this, ttsCallback);   // onReady 后自动开始
        } else {
            startTtsForCurrentChapter();
        }
    }

    private void startTtsForCurrentChapter() {
        if (tts == null || !tts.isReady()) return;
        if (ttsPrepared && tts.hasSegments()) {
            tts.resume();
        } else {
            binding.web.evaluateJavascript(TTS_SCRIPT, null);   // 回调 onSentences 后开始
        }
    }

    private void updateTtsIcon() {
        binding.btnTts.setImageResource(ttsWanted ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private final TtsManager.Callback ttsCallback = new TtsManager.Callback() {
        @Override
        public void onReady(boolean ok) {
            if (!ok) {
                ttsWanted = false;
                updateTtsIcon();
                Toast.makeText(ReaderActivity.this,
                        R.string.reader_tts_init_failed, Toast.LENGTH_LONG).show();
                return;
            }
            if (ttsWanted) startTtsForCurrentChapter();
        }

        @Override
        public void onSegmentStart(int index) {
            binding.web.evaluateJavascript(
                    "window.__ttsHighlight&&window.__ttsHighlight(" + index + ")", null);
        }

        @Override
        public void onChapterFinished() {
            if (epub != null && currentChapter + 1 < epub.spine.size()) {
                restorePending = false;
                loadChapter(currentChapter + 1);   // onPageFinished 因 ttsWanted 会重新注入并续读
            } else {
                ttsWanted = false;
                updateTtsIcon();
                Toast.makeText(ReaderActivity.this,
                        R.string.reader_tts_finished, Toast.LENGTH_SHORT).show();
            }
        }
    };

    /** 供注入脚本回传分句结果。 */
    private class TtsBridge {
        @JavascriptInterface
        public void onSentences(String json) {
            final List<String> segs = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) segs.add(s);
                }
            } catch (Exception ignored) {
            }
            main.post(() -> {
                ttsPrepared = true;
                if (tts == null) return;
                if (segs.isEmpty()) {
                    // 本章无文字(如纯封面页):自动跳下一章续读
                    if (ttsWanted && epub != null && currentChapter + 1 < epub.spine.size()) {
                        restorePending = false;
                        loadChapter(currentChapter + 1);
                    } else if (ttsWanted) {
                        ttsWanted = false;
                        updateTtsIcon();
                        Toast.makeText(ReaderActivity.this,
                                R.string.reader_tts_no_text, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                tts.setSegments(segs);
                if (ttsWanted) tts.playFrom(0);
            });
        }
    }

    private void saveProgress() {
        if (book == null || epub == null) return;
        book.chapterIndex = currentChapter;
        book.chapterProgress = scrollFraction();
        book.lastReadTime = System.currentTimeMillis();
        repository.update(book);
    }

    private void finishWithToast(String msg) {
        main.post(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        if (tts != null && tts.isPlaying()) {
            ttsWanted = false;
            tts.pause();
            updateTtsIcon();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
