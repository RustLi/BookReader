package com.lwl.bookreader.data.epub;

import java.util.ArrayList;
import java.util.List;

/** 供阅读使用的 EPUB 结构:阅读顺序(spine)与目录(toc)。 */
public class EpubBook {

    /** 一个可阅读单元(spine itemref 对应的章节文件)。 */
    public static class Spine {
        /** ZIP 内的完整路径(已 %XX 解码),也用于在解压目录定位文件。 */
        public String zipPath;
        /** 章节标题(来自 NCX,可能为空)。 */
        public String title;
    }

    /** 目录项(来自 toc.ncx)。 */
    public static class Toc {
        public String title;
        /** 指向的 ZIP 路径(去锚点、已解码)。 */
        public String zipPath;
        /** 层级深度,0 为顶层。 */
        public int depth;
    }

    public String title;
    public String author;
    public final List<Spine> spine = new ArrayList<>();
    public final List<Toc> toc = new ArrayList<>();
}
