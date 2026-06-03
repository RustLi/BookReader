package com.lwl.bookreader.data;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import com.lwl.bookreader.data.epub.EpubMeta;
import com.lwl.bookreader.data.epub.EpubParser;
import com.lwl.bookreader.data.mobi.MobiParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 本地图书导入:复制文件到私有目录 -> 解析元数据/封面 -> 写入 Room。 */
public class BookImporter {

    public interface Callback {
        void onSuccess(String title);
        void onError(String message);
    }

    private final Context appContext;
    private final BookRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public BookImporter(Context context, BookRepository repository) {
        this.appContext = context.getApplicationContext();
        this.repository = repository;
    }

    public void importFromUri(Uri uri, Callback callback) {
        executor.execute(() -> {
            File bookFile = null;
            File coverFile = null;
            try {
                String displayName = queryDisplayName(uri);
                String id = UUID.randomUUID().toString();
                String format = detectFormat(displayName);

                File booksDir = new File(appContext.getFilesDir(), "books");
                File coversDir = new File(appContext.getFilesDir(), "covers");
                //noinspection ResultOfMethodCallIgnored
                booksDir.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                coversDir.mkdirs();

                bookFile = new File(booksDir, id + "." + format);
                copyUriToFile(uri, bookFile);

                EpubMeta meta = "mobi".equals(format)
                        ? MobiParser.parseMeta(bookFile)
                        : EpubParser.parse(bookFile);

                String title = !TextUtils.isEmpty(meta.title)
                        ? meta.title : stripExtension(displayName);
                if (TextUtils.isEmpty(title)) title = "未命名图书";

                String coverPath = null;
                if (meta.cover != null && meta.cover.length > 0) {
                    coverFile = new File(coversDir, id + "." + meta.coverExt);
                    try (OutputStream os = new FileOutputStream(coverFile)) {
                        os.write(meta.cover);
                    }
                    coverPath = coverFile.getAbsolutePath();
                }

                Book book = new Book();
                book.title = title;
                book.author = meta.author;
                book.format = format;
                book.filePath = bookFile.getAbsolutePath();
                book.coverPath = coverPath;
                book.addTime = System.currentTimeMillis();
                book.lastReadTime = 0;
                repository.insert(book);

                final String okTitle = title;
                main.post(() -> callback.onSuccess(okTitle));
            } catch (Exception e) {
                deleteQuietly(bookFile);
                deleteQuietly(coverFile);
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> callback.onError(msg));
            }
        });
    }

    private void copyUriToFile(Uri uri, File dest) throws Exception {
        try (InputStream in = appContext.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IllegalStateException("无法读取所选文件");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = appContext.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 依文件名后缀判断格式,默认 epub。 */
    private static String detectFormat(String name) {
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".mobi") || lower.endsWith(".azw") || lower.endsWith(".azw3")) {
                return "mobi";
            }
        }
        return "epub";
    }

    private static String stripExtension(String name) {
        if (name == null) return null;
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
