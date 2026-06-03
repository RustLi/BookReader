package com.lwl.bookreader.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** 书签:记录某本书的某个阅读位置。 */
@Entity(tableName = "bookmark")
public class Bookmark {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long bookId;
    public int chapterIndex;
    public float chapterProgress;
    /** 位置处的文字摘要,用于列表展示 */
    public String snippet;
    public long createTime;
}
