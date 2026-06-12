package com.lwl.bookreader.ui.reader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import android.view.ViewGroup;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
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
    private String currentChapterTitle = "";

    private boolean restorePending;
    private float pendingScrollFraction;
    private boolean userSeeking;

    private TtsManager tts;
    private boolean ttsWanted;          // 用户希望朗读(用于切章自动续读)
    private boolean ttsPrepared;        // 当前章节已注入分句脚本
    private boolean ttsServiceStarted;  // 前台服务(锁屏/通知栏控制)已启动
    private ActivityResultLauncher<String> notifPermLauncher;
    /**
     * 用户/锁屏翻页后期望显示的最小页号(0 基)。-1 表示无期望,语音可自由驱动正文。
     * onSegmentStart 仅当语音句子所在页 >= 此值时才推进显示,即"只允许语音往前追,不允许拉回",
     * 解决手动翻页被语音拉回旧页的问题。语音自然追上后由 onSegmentStart 清零。
     */
    private int ttsTargetPage = -1;
    /**
     * 翻页序号:每次 syncTtsToTargetPageIfPlaying 递增。
     * playFromPage 的异步回包闭包持有发起时的序号,回包时只有等于当前序号才生效,
     * 防止快速连点翻页时旧回包乱序到达污染当前状态。
     */
    private int ttsFlipSeq;
    private int ttsPendingTargetPage = -1;  // 锁屏/不可见期间 turnPage 写入,onResume 兜底校正显示页
    private int[] pageFirstSentence;    // 页号→首句索引,锁屏翻页直接查表无需 JS
    private int pageMapGeneration;      // 每次切章/重排自增;映射表异步回包按代次校验,丢弃过期结果

    private ReaderPrefs prefs;

    /** 量取多栏分页后的真实页数（每页恰为一个视口宽），修正原生 horizontalRange 偏小的问题。 */
    private static final String PAGINATION_MEASURE_JS =
            "(function(){"
            + "var de=document.documentElement,b=document.body;"
            + "var pw=de.clientWidth||window.innerWidth||1;"
            + "var cw=Math.max(de.scrollWidth,b?b.scrollWidth:0,de.offsetWidth,pw);"
            + "var pages=Math.max(1,Math.round(cw/pw));"
            + "return JSON.stringify({pages:pages,pageCss:pw,contentCss:cw});"
            + "})()";

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
            // 高亮当前句并返回它跨越的页区间 '起页,止页'(绝对坐标计算,锁屏下亦准确)。
            // 原生侧仅当当前页不在区间内才按整页对齐滚动:避免跨页句把刚翻过去的页拉回来,
            // 也避免 scrollIntoView 自由滚动导致半页错位、边距不一致。
            + "window.__ttsHighlight=function(i){var pv=document.querySelector('.ttshl');"
            + "if(pv){pv.classList.remove('ttshl');}"
            + "var el=document.querySelector('.ttsseg[data-i=\"'+i+'\"]');"
            + "if(!el){return '-1';}el.classList.add('ttshl');"
            + "var r=el.getBoundingClientRect();var vw=window.innerWidth||1;"
            + "var l=window.pageXOffset+r.left;"
            + "return Math.floor(l/vw)+','+Math.floor((l+Math.max(r.width,1)-1)/vw);};"
            // 返回指定页(0 基)首句的句子索引。
            // 关键:页归属按"句子起点所在页"而不是"右边缘越过该页左边缘"。
            // 这样跨页句归属于它起点的那一页;p 页首句一定是完全开始于第 p 页或之后的句子,
            // 从该句开头朗读时听到的内容与画面对齐,不会出现跨页句把上一页内容读出来的错位。
            // 用 pageXOffset+rect 的绝对坐标判断:锁屏下渲染进程未同步的滚动偏移会被抵消。
            + "window.__ttsFirstOfPage=function(p){"
            + "var els=document.querySelectorAll('.ttsseg');var vw=window.innerWidth||1;"
            + "var thr=p*vw-1;"   // -1 容错:句子起点几乎贴左边缘也算属于该页
            + "for(var k=0;k<els.length;k++){var r=els[k].getBoundingClientRect();"
            + "if(window.pageXOffset+r.left>=thr){"
            + "return parseInt(els[k].getAttribute('data-i'),10)||0;}}"
            + "return -1;};"
            // 一次性返回整张"页号→首句索引"表(JSON 数组,下标即页号)。
            // 与 __ttsFirstOfPage 同语义、同坐标(纯用 JS 的 innerWidth,不掺原生设备像素),
            // 单次调用算完所有页,替代原来逐页 N 次 evaluateJavascript,既一致又省往返。
            + "window.__ttsPageMap=function(){"
            + "var els=document.querySelectorAll('.ttsseg');var vw=window.innerWidth||1;"
            + "var starts=[];for(var k=0;k<els.length;k++){"
            + "var r=els[k].getBoundingClientRect();"
            + "var sp=Math.floor((window.pageXOffset+r.left)/vw);if(sp<0)sp=0;starts.push(sp);}"
            + "var total=starts.length?starts[starts.length-1]+1:1;"
            + "var res=new Array(total);var j=0;"
            + "for(var p=0;p<total;p++){while(j<starts.length&&starts[j]<p)j++;"
            + "res[p]=j<els.length?(parseInt(els[j].getAttribute('data-i'),10)||0):-1;}"
            + "return JSON.stringify(res);};"
            + "Android.onSentences(JSON.stringify(sentences));"
            + "}catch(e){Android.onSentences('[]');}})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityReaderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        repository = new BookRepository(this);
        prefs = new ReaderPrefs(this);
        // Android 13+ 通知权限(未授权时朗读仍可用,只是无锁屏/通知栏控制)
        notifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> { });

        applyWindowInsets();
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
        binding.web.setHorizontalScrollBarEnabled(false);
        binding.web.setVerticalScrollBarEnabled(false);

        binding.web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectReaderCss();
                extractAndShowChapterHeading();
                if (restorePending) {
                    restorePending = false;
                    final float frac = pendingScrollFraction;
                    schedulePaginationRefresh(() -> {
                        applyScrollFraction(frac);
                        updatePageInfo();
                    });
                } else {
                    schedulePaginationRefresh(ReaderActivity.this::refreshAfterRelayout);
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
                            turnPage(-1);
                        } else if (x > w * 0.70f) {
                            turnPage(1);
                        } else {
                            toggleBars();
                        }
                        return false;
                    }
                });
        binding.web.setOnTouchListener((v, e) -> {
            gesture.onTouchEvent(e);
            return true;   // 消费所有触摸事件，禁止 WebView 原生滑动
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
        pageFirstSentence = null;
        pageMapGeneration++;   // 作废上一章映射表的在途异步回包
        // 切章会重排正文,旧的页号目标失效,清理避免污染下章的 onSegmentStart/onResume
        ttsTargetPage = -1;
        ttsPendingTargetPage = -1;
        binding.web.clearMeasuredHorizontalRange();
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
        int pageW = binding.web.getWidth();
        int max = Math.max(0, binding.web.horizontalRange() - pageW);
        int scrollX = (int) (clampF(frac, 0f, 1f) * max);
        if (pageW > 0) scrollX = (scrollX / pageW) * pageW;  // 对齐页边界
        binding.web.scrollTo(scrollX, 0);
    }

    private float scrollFraction() {
        int max = binding.web.horizontalRange() - binding.web.getWidth();
        if (max <= 0) return 0f;
        return clampF(binding.web.getScrollX() / (float) max, 0f, 1f);
    }

    private void updateProgressUi() {
        if (epub == null || epub.spine.isEmpty()) return;
        float overall = (currentChapter + scrollFraction()) / epub.spine.size();
        binding.seek.setProgress((int) (overall * 1000));
        binding.progressText.setText((int) (overall * 100) + "%");
        updatePageInfo();
    }

    private void applyWindowInsets() {
        float d = getResources().getDisplayMetrics().density;
        int pad4 = (int) (4 * d), pad6 = (int) (6 * d), pad16 = (int) (16 * d);
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.topBar.setPadding(pad4, sys.top + pad6, pad4, pad6);
            binding.bottomBar.setPadding(0, 0, 0, sys.bottom + pad6);
            // 章节标题浮层：顶部避开状态栏
            binding.tvChapterTitle.setPadding(pad16, sys.top + pad6, pad16, 0);
            // 页码浮层：右下角避开导航栏
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) binding.tvPageInfo.getLayoutParams();
            lp.bottomMargin = sys.bottom + pad6;
            lp.rightMargin = pad16;
            binding.tvPageInfo.setLayoutParams(lp);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        int barVis = barsVisible ? View.VISIBLE : View.GONE;
        int overlayVis = barsVisible ? View.GONE : View.VISIBLE;
        binding.topBar.setVisibility(barVis);
        binding.bottomBar.setVisibility(barVis);
        binding.tvChapterTitle.setVisibility(overlayVis);
        binding.tvPageInfo.setVisibility(overlayVis);
        // 系统栏始终保持隐藏（沉浸式），点击中间只切换应用内顶/底栏浮层。
        // 这样 WebView 视口尺寸恒定、不随系统栏显隐缩放，正文不再跳动。
    }

    /** 隐藏系统状态栏 / 导航栏，并设置为滑动临时显示，保持沉浸式且不改变 WebView 视口。 */
    private void hideSystemBars() {
        WindowInsetsControllerCompat ctrl =
                WindowCompat.getInsetsController(getWindow(), binding.getRoot());
        ctrl.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        ctrl.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 系统栏始终隐藏（即使应用内工具栏显示），保证 WebView 视口恒定不缩放。
        if (hasFocus) hideSystemBars();
    }

    private void turnPage(int delta) {
        turnPage(delta, false);
    }

    private void turnPage(int delta, boolean remeasureTried) {
        if (epub == null) return;
        int pageW = binding.web.getWidth();
        if (pageW == 0) return;
        int scrollX = binding.web.getScrollX();
        int contentW = binding.web.horizontalRange();
        if (contentW <= pageW + 10 && !remeasureTried) {
            // 尚未量取到多栏总宽时先刷新，避免误判为章末直接切章
            refreshPaginationMetrics(() -> turnPage(delta, true));
            return;
        }
        int curPage = scrollX / pageW;
        int totalPages = Math.max(1, (contentW + pageW - 1) / pageW);
        if (delta > 0) {
            if (scrollX + pageW >= contentW - 10) {
                int target = currentChapter + 1;
                if (target < epub.spine.size()) {
                    restorePending = false;
                    loadChapter(target);
                }
                return;
            }
            int targetPage = Math.min(curPage + 1, totalPages - 1);
            scrollToPage(targetPage);
            syncTtsToTargetPageIfPlaying(targetPage);
        } else {
            if (scrollX <= 0) {
                int target = currentChapter - 1;
                if (target >= 0) {
                    pendingScrollFraction = 1f;
                    restorePending = true;
                    loadChapter(target);
                }
                return;
            }
            int targetPage = Math.max(curPage - 1, 0);
            scrollToPage(targetPage);
            syncTtsToTargetPageIfPlaying(targetPage);
        }
    }

    /** 显式滚动到指定页(0 基),并刷新页码。锁屏不可见时也写入 pending,onResume 兜底校正。 */
    private void scrollToPage(int page) {
        int pageW = binding.web.getWidth();
        if (pageW <= 0) return;
        binding.web.scrollTo(page * pageW, 0);
        updatePageInfo();
        // 锁屏期间 WebView 渲染线程被节流,此次 scrollTo 可能未真正落到画面,
        // 留一份 pending 让 onResume 在可见后再次对齐到目标页。
        ttsPendingTargetPage = page;
    }

    /** 朗读过程中翻页:让语音跳到目标页首句继续朗读,且立即同步目标句高亮。 */
    private void syncTtsToTargetPageIfPlaying(int targetPage) {
        if (tts == null || !tts.isPlaying()) return;
        // 记录用户期望的最小显示页:onSegmentStart 只允许语音往前追,不允许拉回此页之前。
        ttsTargetPage = targetPage;
        // 递增序号:使所有尚未到达的旧 JS 回包作废,只有本次发起的回包才能生效。
        ttsFlipSeq++;
        playFromPage(targetPage, ttsFlipSeq);
        // 锁屏副标题刷新一次,让控制中心立刻反映新状态
        if (ttsServiceStarted) updateTtsService(true);
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

    /** 应用主题底色到页面外壳(根布局 + WebView 背景)，同时更新浮层文字颜色。 */
    private void applyThemeChrome() {
        binding.getRoot().setBackgroundColor(prefs.bgColor());
        binding.web.setBackgroundColor(prefs.bgColor());
        int overlayColor = (prefs.textColor() & 0x00FFFFFF) | 0x99000000;
        binding.tvChapterTitle.setTextColor(overlayColor);
        binding.tvPageInfo.setTextColor(overlayColor);
    }

    /** 注入主题色 / 行距 / 多栏分页 CSS。 */
    private void injectReaderCss() {
        String bg = hex(prefs.bgColor());
        String fg = hex(prefs.textColor());
        float lh = prefs.lineHeight();
        // 关键：使用视口相对单位（vw/100% 高度），让每一页恰好等于一个视口宽。
        // 列宽 90vw + 列间距 10vw => 列距 100vw，配合 body 左右 5vw 内边距，
        // 每页都有 5vw 留白且页边界与 WebView 像素宽（getWidth）精确对齐，翻页步长稳定。
        // 切勿用 getWidth()/getHeight() 的设备像素当 CSS px，否则只会排出一列、误判为整章一页。
        String css = "html{overflow-x:visible !important;overflow-y:hidden !important;"
                + "height:100% !important;margin:0 !important;padding:0 !important;"
                + "box-sizing:border-box !important;}"
                + "body{background:" + bg + " !important;color:" + fg + " !important;"
                + "-webkit-column-width:90vw !important;column-width:90vw !important;"
                + "-webkit-column-gap:10vw !important;column-gap:10vw !important;"
                + "height:100% !important;overflow-x:visible !important;overflow-y:hidden !important;"
                // border-box：列高含上下内边距，避免内容被视口裁切；加大上下边距留出呼吸空间
                + "box-sizing:border-box !important;"
                + "padding:2.2em 5vw !important;margin:0 !important;}"
                + "body,body *{line-height:" + lh + " !important;}"
                + "body,body *{color:" + fg + " !important;}"
                + "a{color:#3F8CFF !important;}"
                + "img{max-width:100% !important;height:auto !important;}";

        String js = "(function(){"
                + "var id='__readercss';var s=document.getElementById(id);"
                + "if(!s){s=document.createElement('style');s.id=id;"
                + "(document.head||document.documentElement).appendChild(s);}"
                + "s.textContent=" + jsString(css) + ";"
                + "})()";
        binding.web.evaluateJavascript(js, v -> schedulePaginationRefresh(null));
    }

    /** 分页 CSS 注入后延迟量取内容宽度（多栏 reflow 需要短暂等待）。 */
    private void schedulePaginationRefresh(Runnable after) {
        main.postDelayed(() -> refreshPaginationMetrics(null), 80);
        main.postDelayed(() -> refreshPaginationMetrics(after), 320);
    }

    private void refreshPaginationMetrics(Runnable after) {
        binding.web.evaluateJavascript(PAGINATION_MEASURE_JS, value -> main.post(() -> {
            try {
                org.json.JSONObject o = new org.json.JSONObject(unquote(value));
                int pages = o.optInt("pages", 0);
                int pageW = binding.web.getWidth();
                // 每页恰为一个视口宽，总宽=页数×页宽，保证为页宽整数倍、翻页边界精确对齐
                if (pages > 0 && pageW > 0) {
                    binding.web.setMeasuredHorizontalRange(pages * pageW);
                }
            } catch (Exception ignored) {
            }
            if (after != null) after.run();
        }));
    }

    /** 提取章节标题并显示在浮层 TextView，同时在 HTML 中隐藏该元素。 */
    private void extractAndShowChapterHeading() {
        String js = "(function(){"
                + "if(window.__headProcessed)return '';"
                + "window.__headProcessed=true;"
                + "var b=document.body;if(!b)return '';"
                + "var el=b.firstElementChild;"
                + "while(el&&!el.textContent.trim()){el=el.nextElementSibling;}"
                + "if(!el)return '';"
                + "var t=el.tagName;"
                + "var txt=el.textContent.replace(/\\s+/g,' ').trim();"
                + "if(t==='H1'||t==='H2'||t==='H3'||"
                + "(txt.length<=50&&/第[一二三四五六七八九十百千\\d]+[回章节卷]/.test(txt))){"
                + "el.style.display='none';"
                + "return txt;"
                + "}"
                + "return '';"
                + "})()";
        binding.web.evaluateJavascript(js, value -> {
            final String title = unquote(value);
            main.post(() -> {
                if (!title.isEmpty()) {
                    currentChapterTitle = title;
                    binding.tvChapterTitle.setText(title);
                    // 朗读中切章:把新章节标题同步到锁屏/通知栏
                    if (ttsServiceStarted) {
                        updateTtsService(tts != null && tts.isPlaying());
                    }
                }
            });
        });
    }

    /** 更新右下角页码（当前页 / 总页）。 */
    private void updatePageInfo() {
        int pageW = binding.web.getWidth();
        if (pageW <= 0) return;
        int currentPage = binding.web.getScrollX() / pageW + 1;
        int totalPages = Math.max(1, (binding.web.horizontalRange() + pageW - 1) / pageW);
        binding.tvPageInfo.setText(currentPage + " / " + totalPages);
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
            injectReaderCss();
            schedulePaginationRefresh(ReaderActivity.this::refreshAfterRelayout);
        });
        v.findViewById(R.id.btn_font_plus).setOnClickListener(x -> {
            prefs.setFontZoom(prefs.getFontZoom() + 10);
            binding.web.getSettings().setTextZoom(prefs.getFontZoom());
            fontVal.setText(prefs.getFontZoom() + "%");
            injectReaderCss();
            schedulePaginationRefresh(ReaderActivity.this::refreshAfterRelayout);
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
                schedulePaginationRefresh(ReaderActivity.this::refreshAfterRelayout);
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
                schedulePaginationRefresh(ReaderActivity.this::refreshAfterRelayout);
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
        if (tts != null && tts.isPlaying()) {
            ttsPause();
        } else {
            ttsPlay();
        }
    }

    /** 开始/恢复朗读(按钮、锁屏、通知栏共用入口)。 */
    private void ttsPlay() {
        if (epub == null) return;
        requestNotificationPermissionIfNeeded();
        TtsService.setController(ttsController);
        ttsWanted = true;
        updateTtsIcon();
        if (tts == null) {
            tts = new TtsManager(this, ttsCallback);   // onReady 后自动开始
        } else {
            startTtsForCurrentChapter();
        }
        updateTtsService(true);
    }

    /** 暂停朗读:保留前台服务与通知,便于从锁屏/通知栏恢复。 */
    private void ttsPause() {
        ttsWanted = false;
        if (tts != null) tts.pause();
        updateTtsIcon();
        if (ttsServiceStarted) updateTtsService(false);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /** 同步书名/章节/播放状态到前台服务(锁屏与通知栏)。 */
    private void updateTtsService(boolean playing) {
        String title = book != null && !book.title.isEmpty()
                ? book.title : getString(R.string.app_name);
        TtsService.update(this, title, currentChapterTitle, playing);
        ttsServiceStarted = true;
    }

    private void stopTtsService() {
        if (ttsServiceStarted) {
            TtsService.stop(this);
            ttsServiceStarted = false;
        }
    }

    /** 锁屏/通知栏控制事件:回主线程驱动现有播放与翻页逻辑。 */
    private final TtsService.Controller ttsController = new TtsService.Controller() {
        @Override public void onPlay() { main.post(ReaderActivity.this::ttsPlay); }
        @Override public void onPause() { main.post(ReaderActivity.this::ttsPause); }
        @Override public void onNextPage() { main.post(() -> turnPage(1)); }
        @Override public void onPrevPage() { main.post(() -> turnPage(-1)); }
    };

    private void startTtsForCurrentChapter() {
        if (tts == null || !tts.isReady()) return;
        if (ttsPrepared && tts.hasSegments()) {
            // 开始/恢复朗读:从当前句继续(resume)即可,不走 JS 异步定位,
            // 避免 WebView 重排未落定时 __ttsFirstOfPage 返回 -1 导致 playFrom 不被调用、
            // 进而 playing 一直为 false、按钮"按了没反应/无法暂停"。
            tts.resume();
        } else {
            binding.web.evaluateJavascript(TTS_SCRIPT, null);   // 回调 onSentences 后开始
        }
    }

    /** 从当前翻到的页面的首句开始朗读,而不是固定从本章开头。 */
    private void playFromCurrentPage() {
        int pageW = binding.web.getWidth();
        // 非翻页路径(onResume / 章首注入完成),不持序号
        playFromPage(pageW > 0 ? binding.web.getScrollX() / pageW : 0, 0);
    }

    /**
     * 从指定页(0 基)首句开始朗读。优先查原生映射表(锁屏下无需 JS),否则回退到 JS 查询。
     *
     * @param flipSeq 翻页序号:>0 时表示本次为翻页驱动,异步回包返回时若已被新一次翻页覆盖
     *                则丢弃;==0 表示非翻页路径(如 onResume),不做序号校验。
     */
    private void playFromPage(int page, int flipSeq) {
        if (tts == null) return;
        // 原生映射表已就绪:直接查表,不依赖 evaluateJavascript(锁屏下 WebView 可能挂起)。
        // 同步路径下,本次写入 ttsFlipSeq 后立刻调用,序号天然等价,无需校验。
        if (pageFirstSentence != null && page >= 0 && page < pageFirstSentence.length
                && pageFirstSentence[page] >= 0) {
            int sentence = pageFirstSentence[page];
            tts.playFrom(sentence);
            highlightSentenceImmediate(sentence);
            return;
        }
        binding.web.evaluateJavascript(
                "window.__ttsFirstOfPage?window.__ttsFirstOfPage(" + page + "):-1",
                value -> {
                    if (tts == null) return;
                    // 翻页路径:回包时若序号已被新翻页覆盖,本次结果作废,避免乱序回包污染状态
                    if (flipSeq > 0 && flipSeq != ttsFlipSeq) return;
                    int from = -1;
                    try {
                        from = Integer.parseInt(unquote(value));
                    } catch (Exception ignored) {
                    }
                    // JS 重排未落定/边界返回 -1 时,兜底从首句开始,保证 playing 被置为 true
                    int sentence = Math.max(from, 0);
                    tts.playFrom(sentence);
                    highlightSentenceImmediate(sentence);
                });
    }

    /** 立刻把 DOM 高亮切到指定句,不滚动页面(滚动由 turnPage 已经做过)。 */
    private void highlightSentenceImmediate(int sentence) {
        binding.web.evaluateJavascript(
                "window.__ttsHighlight?window.__ttsHighlight(" + sentence + "):'-1'",
                null);
    }

    /**
     * 预建"页号→首句索引"原生映射表(供锁屏翻页直接查表,无需 JS)。
     * 单次调用 __ttsPageMap 取回整张表:页号、首句完全由 JS 用 innerWidth 算出,
     * 与高亮/翻页判断同坐标系,不再掺入原生设备像素页数,避免双坐标漂移导致音画错位。
     * 句子加载完毕、以及每次版面重排(换字号/行距/主题)后都应调用,保证表与当前版面一致。
     */
    private void buildPageSentenceMap() {
        final int gen = pageMapGeneration;
        binding.web.evaluateJavascript(
                "window.__ttsPageMap?window.__ttsPageMap():'[]'",
                value -> {
                    if (gen != pageMapGeneration) return;  // 章节/版面已变,丢弃过期结果
                    int[] map = null;
                    try {
                        JSONArray a = new JSONArray(unquote(value));
                        map = new int[a.length()];
                        for (int i = 0; i < map.length; i++) map[i] = a.optInt(i, -1);
                    } catch (Exception ignored) {
                    }
                    pageFirstSentence = map;
                });
    }

    /** 版面重排后的统一收尾:刷新页码,并在朗读中时重建页→句映射,防止翻页跳到错位句子。 */
    private void refreshAfterRelayout() {
        updatePageInfo();
        if (tts != null && ttsPrepared && tts.hasSegments()) {
            pageMapGeneration++;      // 版面已变,作废旧映射的在途回包
            buildPageSentenceMap();
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
                stopTtsService();
                Toast.makeText(ReaderActivity.this,
                        R.string.reader_tts_init_failed, Toast.LENGTH_LONG).show();
                return;
            }
            if (ttsWanted) startTtsForCurrentChapter();
        }

        @Override
        public void onSegmentStart(int index) {
            // __ttsHighlight 既负责 DOM 上色,又返回该句覆盖的页区间[start,end]
            binding.web.evaluateJavascript(
                    "window.__ttsHighlight?window.__ttsHighlight(" + index + "):'-1'",
                    value -> {
                        int start, end;
                        try {
                            String[] parts = unquote(value).split(",");
                            start = Integer.parseInt(parts[0].trim());
                            end = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : start;
                        } catch (Exception e) {
                            return;
                        }
                        if (start < 0) return;
                        int pageW = binding.web.getWidth();
                        if (pageW <= 0) return;
                        int cur = binding.web.getScrollX() / pageW;
                        // 单向跟随:用户/锁屏翻页设了期望页,语音句子还在期望页之前时,
                        // 不滚动也不强制对齐,等语音自然朗读追到期望页。
                        // 句子覆盖区间一旦达到/越过期望页,本次自动翻页生效并清零期望页。
                        if (ttsTargetPage >= 0) {
                            if (end < ttsTargetPage) return;     // 还没追上,放着
                            ttsTargetPage = -1;                   // 已追上,放开自动跟随
                        }
                        if (cur >= start && cur <= end) return;   // 当前句覆盖当前页,不动
                        // 仅当画面落后于句起点所在页时,前进到 start;
                        // 不允许把画面拉回到 start 之前(跨页句的 start 可能是上一页)。
                        if (cur < start) {
                            binding.web.scrollTo(start * pageW, 0);
                            updatePageInfo();
                        }
                    });
        }

        @Override
        public void onChapterFinished() {
            if (epub != null && currentChapter + 1 < epub.spine.size()) {
                restorePending = false;
                loadChapter(currentChapter + 1);   // onPageFinished 因 ttsWanted 会重新注入并续读
            } else {
                ttsWanted = false;
                if (tts != null) tts.pause();   // 释放 WakeLock
                updateTtsIcon();
                stopTtsService();
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
                        tts.pause();   // 释放 WakeLock
                        updateTtsIcon();
                        stopTtsService();
                        Toast.makeText(ReaderActivity.this,
                                R.string.reader_tts_no_text, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                tts.setSegments(segs);
                buildPageSentenceMap();  // 预建原生映射表,供锁屏翻页直接查表
                if (ttsWanted) playFromCurrentPage();
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
        // 熄屏/切后台不暂停朗读,由 WakeLock 维持 CPU 继续播放;
        // 真正退出阅读页时在 onDestroy 中 shutdown。
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tts != null && tts.isPlaying()) {
            // 从锁屏/后台恢复。优先消费 ttsPendingTargetPage(锁屏期间翻页写入),
            // 把 WebView 可见后的实际显示位置校正到目标页,再让语音跟随。
            // 如果没有 pending,则把语音对齐到当前显示页(正常恢复路径)。
            final int pending = ttsPendingTargetPage;
            ttsPendingTargetPage = -1;
            main.postDelayed(() -> {
                int pageW = binding.web.getWidth();
                if (pending >= 0 && pageW > 0) {
                    // 校正显示页到锁屏期间翻到的目标页
                    binding.web.scrollTo(pending * pageW, 0);
                    updatePageInfo();
                    // 走非翻页路径(无需序号校验):此时已经回到前台,不会再有竞态
                    playFromPage(pending, 0);
                } else {
                    playFromCurrentPage();
                }
            }, 200);
        } else {
            ttsPendingTargetPage = -1;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TtsService.setController(null);
        stopTtsService();
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
