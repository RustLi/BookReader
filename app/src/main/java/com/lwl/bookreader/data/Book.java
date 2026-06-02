package com.lwl.bookreader.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** 一本书的元数据与阅读进度。 */
@Entity(tableName = "book")
public class Book {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 书名 */
    @NonNull
    public String title = "";

    /** 作者 */
    public String author;

    /** 封面图片在本地的路径(可空,空则显示占位封面) */
    public String coverPath;

    /** 书籍文件在 App 私有目录的路径 */
    public String filePath;

    /** 格式:epub / mobi */
    public String format;

    /** 阅读进度:章节下标 */
    public int chapterIndex;

    /** 阅读进度:当前章节内百分比(0~1) */
    public float chapterProgress;

    /** 最近阅读时间(毫秒);0 表示从未阅读 */
    public long lastReadTime;

    /** 加入书架的时间(毫秒) */
    public long addTime;
}
