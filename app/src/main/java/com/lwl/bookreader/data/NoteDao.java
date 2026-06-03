package com.lwl.bookreader.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface NoteDao {

    @Insert
    long insert(Note note);

    @Delete
    void delete(Note note);

    @Query("SELECT * FROM note WHERE bookId = :bookId ORDER BY createTime DESC")
    List<Note> listByBook(long bookId);
}
