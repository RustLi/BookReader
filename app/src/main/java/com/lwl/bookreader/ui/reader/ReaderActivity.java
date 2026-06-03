package com.lwl.bookreader.ui.reader;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.SeekBar;
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

        setupWebView();
        setupControls();

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
        binding.web.addJavascriptInterface(new TtsBridge(), "Android");

        binding.web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
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
        binding.btnBookmark.setOnClickListener(v -> comingSoon());
        binding.toolToc.setOnClickListener(v -> showToc());
        binding.toolNote.setOnClickListener(v -> comingSoon());
        binding.toolBookmark.setOnClickListener(v -> comingSoon());
        binding.toolSettings.setOnClickListener(v -> comingSoon());

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

    private void comingSoon() {
        Toast.makeText(this, R.string.reader_coming_soon, Toast.LENGTH_SHORT).show();
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
