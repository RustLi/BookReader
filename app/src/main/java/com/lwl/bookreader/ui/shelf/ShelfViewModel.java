package com.lwl.bookreader.ui.shelf;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.lwl.bookreader.data.Book;
import com.lwl.bookreader.data.BookRepository;

import java.util.List;

public class ShelfViewModel extends AndroidViewModel {

    private final BookRepository repository;
    private final MutableLiveData<String> keyword = new MutableLiveData<>("");
    private final LiveData<List<Book>> books;

    public ShelfViewModel(@NonNull Application application) {
        super(application);
        repository = new BookRepository(application);
        books = Transformations.switchMap(keyword, repository::search);
    }

    public LiveData<List<Book>> getBooks() {
        return books;
    }

    public void setKeyword(String text) {
        keyword.setValue(text == null ? "" : text.trim());
    }

    public void delete(Book book) {
        repository.delete(book);
    }

    /** 临时:插入一本示例书,用于验证书架渲染与增删。task-04 接入真实导入后移除。 */
    public void addSampleBook(int index) {
        Book b = new Book();
        b.title = "示例图书 " + index;
        b.author = "佚名";
        b.format = (index % 2 == 0) ? "epub" : "mobi";
        b.addTime = System.currentTimeMillis();
        b.lastReadTime = 0;
        repository.insert(b);
    }
}
