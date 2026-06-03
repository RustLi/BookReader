package com.lwl.bookreader.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface BookmarkDao {

    @Insert
    long insert(Bookmark bookmark);

    @Delete
    void delete(Bookmark bookmark);

    @Query("SELECT * FROM bookmark WHERE bookId = :bookId ORDER BY createTime DESC")
    List<Bookmark> listByBook(long bookId);
}
