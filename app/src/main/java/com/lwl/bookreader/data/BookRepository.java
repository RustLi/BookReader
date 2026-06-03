package com.lwl.bookreader.data;

import android.content.Context;

import androidx.lifecycle.LiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 书籍数据仓库:封装 DAO,写操作放到后台线程。 */
public class BookRepository {

    private final BookDao bookDao;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public BookRepository(Context context) {
        this.bookDao = AppDatabase.getInstance(context).bookDao();
    }

    public LiveData<List<Book>> search(String keyword) {
        return bookDao.search("%" + (keyword == null ? "" : keyword) + "%");
    }

    public LiveData<List<Book>> getRecentlyRead() {
        return bookDao.getRecentlyRead();
    }

    public void insert(Book book) {
        ioExecutor.execute(() -> bookDao.insert(book));
    }

    public void delete(Book book) {
        ioExecutor.execute(() -> bookDao.delete(book));
    }

    public void update(Book book) {
        ioExecutor.execute(() -> bookDao.update(book));
    }
}
