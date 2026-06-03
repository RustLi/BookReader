package com.lwl.bookreader.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** 笔记:对某本书选中文字的记录。 */
@Entity(tableName = "note")
public class Note {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long bookId;
    public int chapterIndex;
    public float chapterProgress;
    /** 选中的文字 */
    public String text;
    public long createTime;
}
