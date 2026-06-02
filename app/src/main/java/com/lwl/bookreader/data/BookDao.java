package com.lwl.bookreader.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface BookDao {

    @Insert
    long insert(Book book);

    @Update
    void update(Book book);

    @Delete
    void delete(Book book);

    /** 按书名模糊筛选,最近阅读优先,其次最近添加。 */
    @Query("SELECT * FROM book WHERE title LIKE :keyword " +
            "ORDER BY lastReadTime DESC, addTime DESC")
    LiveData<List<Book>> search(String keyword);

    @Query("SELECT * FROM book ORDER BY lastReadTime DESC, addTime DESC")
    LiveData<List<Book>> getAll();

    @Query("SELECT * FROM book WHERE id = :id")
    Book findById(long id);
}
