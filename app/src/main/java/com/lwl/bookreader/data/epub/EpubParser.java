package com.lwl.bookreader.data.epub;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 轻量 EPUB 解析器:基于 java.util.zip + XmlPullParser,零三方依赖。
 * EPUB 即一个 ZIP:META-INF/container.xml 指向 .opf,.opf 内含元数据与清单。
 */
public final class EpubParser {

    private EpubParser() {
    }

    /** 解析书名、作者、封面。失败时抛 Exception 由调用方处理。 */
    public static EpubMeta parse(java.io.File epubFile) throws Exception {
        EpubMeta meta = new EpubMeta();
        try (ZipFile zip = new ZipFile(epubFile)) {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) {
                throw new IllegalStateException("未找到 OPF,可能不是有效的 EPUB");
            }
            String opfDir = parentDir(opfPath);

            // 解析 OPF
            String coverMetaContent = null;     // <meta name="cover" content="...">
            String coverHrefByProps = null;     // EPUB3: properties="cover-image"
            List<Item> manifest = new ArrayList<>();

            ZipEntry opf = zip.getEntry(opfPath);
            try (InputStream in = zip.getInputStream(opf)) {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(in, null);
                int event = p.getEventType();
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        String name = local(p.getName());
                        if ("title".equals(name) && meta.title == null) {
                            meta.title = trimToNull(safeText(p));
                        } else if ("creator".equals(name) && meta.author == null) {
                            meta.author = trimToNull(safeText(p));
                        } else if ("meta".equals(name)) {
                            if ("cover".equalsIgnoreCase(p.getAttributeValue(null, "name"))) {
                                coverMetaContent = p.getAttributeValue(null, "content");
                            }
                        } else if ("item".equals(name)) {
                            Item it = new Item();
                            it.id = p.getAttributeValue(null, "id");
                            it.href = p.getAttributeValue(null, "href");
                            it.type = p.getAttributeValue(null, "media-type");
                            String props = p.getAttributeValue(null, "properties");
                            if (it.href != null) {
                                manifest.add(it);
                            }
                            if (props != null && props.contains("cover-image") && it.href != null) {
                                coverHrefByProps = it.href;
                            }
                        }
                    }
                    event = p.next();
                }
            }

            for (String href : coverCandidates(manifest, coverHrefByProps, coverMetaContent)) {
                String coverPath = resolve(opfDir, decodePercent(href));
                ZipEntry coverEntry = findEntry(zip, coverPath);
                if (coverEntry != null) {
                    meta.cover = readAll(zip.getInputStream(coverEntry));
                    meta.coverExt = ext(coverPath);
                    break;
                }
            }
        }
        return meta;
    }

    private static final class Item {
        String id;
        String href;
        String type;
    }

    private static boolean isImage(Item it) {
        return it.type != null && it.type.startsWith("image/");
    }

    /**
     * 按优先级返回封面 href 候选(去重、有序),调用方逐个尝试直到 ZIP 中存在对应条目:
     * properties=cover-image > meta(id/文件名) > id="cover" > 含 cover 的图片 > 首张图片。
     */
    private static List<String> coverCandidates(List<Item> manifest, String byProps,
                                                String metaContent) {
        Set<String> out = new LinkedHashSet<>();
        if (byProps != null) out.add(byProps);

        if (metaContent != null) {
            for (Item it : manifest) {                       // content 是 item id
                if (metaContent.equals(it.id)) out.add(it.href);
            }
            for (Item it : manifest) {                       // content 是文件名,匹配 href
                if (it.href.equals(metaContent) || it.href.endsWith("/" + metaContent)
                        || decodePercent(it.href).endsWith("/" + metaContent)) {
                    out.add(it.href);
                }
            }
        }
        for (Item it : manifest) {                           // 常见约定 id="cover"
            if ("cover".equalsIgnoreCase(it.id) && (isImage(it) || it.type == null)) {
                out.add(it.href);
            }
        }
        for (Item it : manifest) {                           // id/href 含 cover 的图片
            if (isImage(it) && ((it.id != null && it.id.toLowerCase().contains("cover"))
                    || it.href.toLowerCase().contains("cover"))) {
                out.add(it.href);
            }
        }
        for (Item it : manifest) {                           // 兜底:第一张图片
            if (isImage(it)) out.add(it.href);
        }
        return new ArrayList<>(out);
    }

    /** 容错查找 ZIP 条目:直接命中,或比对(解码后)名称。 */
    private static ZipEntry findEntry(ZipFile zip, String path) {
        ZipEntry e = zip.getEntry(path);
        if (e != null) return e;
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry cur = en.nextElement();
            String name = cur.getName();
            if (name.equals(path) || decodePercent(name).equals(path)) {
                return cur;
            }
        }
        return null;
    }

    /** 仅解码 %XX(保留 '+' 等字面量),用于 EPUB href 路径。 */
    private static String decodePercent(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            byte[] cb = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            bytes.write(cb, 0, cb.length);
        }
        return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String findOpfPath(ZipFile zip) throws Exception {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) return null;
        try (InputStream in = zip.getInputStream(container)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "rootfile".equals(local(p.getName()))) {
                    String full = p.getAttributeValue(null, "full-path");
                    if (full != null) return full;
                }
                event = p.next();
            }
        }
        return null;
    }

    /** 取 START_TAG 的文本内容(避免 nextText 在非纯文本时抛异常)。 */
    private static String safeText(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        int event = p.next();
        while (event != XmlPullParser.END_DOCUMENT && depth > 0) {
            if (event == XmlPullParser.TEXT) {
                sb.append(p.getText());
            } else if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
            if (depth == 0) break;
            event = p.next();
        }
        return sb.toString();
    }

    private static String local(String name) {
        if (name == null) return "";
        int i = name.indexOf(':');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    private static String parentDir(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(0, i) : "";
    }

    /** 将相对 href 解析为 ZIP 内绝对路径,处理 ./ 与 ../ 。 */
    private static String resolve(String baseDir, String href) {
        String combined = baseDir.isEmpty() ? href : baseDir + "/" + href;
        String[] parts = combined.split("/");
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.addLast(part);
            }
        }
        return String.join("/", stack);
    }

    private static String ext(String path) {
        int i = path.lastIndexOf('.');
        String e = i >= 0 ? path.substring(i + 1).toLowerCase() : "jpg";
        return e.length() <= 4 ? e : "jpg";
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream src = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
