package com.lwl.bookreader.data.epub;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 将 EPUB(ZIP)解压到目录,供 WebView 以 file:// 加载章节及其相对资源。 */
public final class EpubExtractor {

    private EpubExtractor() {
    }

    /**
     * 解压到 destDir,完成后写入 .done 标记;已解压则直接复用。
     * @return destDir
     */
    public static File extract(File epubFile, File destDir) throws Exception {
        File marker = new File(destDir, ".done");
        if (marker.exists()) {
            return destDir;
        }
        //noinspection ResultOfMethodCallIgnored
        destDir.mkdirs();
        String destCanonical = destDir.getCanonicalPath();

        try (ZipFile zip = new ZipFile(epubFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File out = new File(destDir, entry.getName());
                // 防 Zip Slip:确保目标在 destDir 之内
                if (!out.getCanonicalPath().startsWith(destCanonical + File.separator)
                        && !out.getCanonicalPath().equals(destCanonical)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        marker.createNewFile();
        return destDir;
    }
}
