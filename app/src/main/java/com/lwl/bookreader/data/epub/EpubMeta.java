package com.lwl.bookreader.data.epub;

/** EPUB 解析出的元数据(task-04 用:书名、作者、封面)。 */
public class EpubMeta {

    /** 书名(可能为空) */
    public String title;

    /** 作者(可能为空) */
    public String author;

    /** 封面图片字节(可能为空) */
    public byte[] cover;

    /** 封面扩展名,如 jpg / png(用于落盘文件名) */
    public String coverExt = "jpg";
}
