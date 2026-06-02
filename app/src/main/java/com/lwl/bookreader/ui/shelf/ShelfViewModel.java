package com.lwl.bookreader.ui.shelf;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.lwl.bookreader.data.Book;
import com.lwl.bookreader.data.BookImporter;
import com.lwl.bookreader.data.BookRepository;
import com.lwl.bookreader.util.SingleLiveEvent;

import java.util.List;

public class ShelfViewModel extends AndroidViewModel {

    private final BookRepository repository;
    private final BookImporter importer;
    private final MutableLiveData<String> keyword = new MutableLiveData<>("");
    private final LiveData<List<Book>> books;
    private final SingleLiveEvent<String> toast = new SingleLiveEvent<>();

    public ShelfViewModel(@NonNull Application application) {
        super(application);
        repository = new BookRepository(application);
        importer = new BookImporter(application, repository);
        books = Transformations.switchMap(keyword, repository::search);
    }

    public LiveData<List<Book>> getBooks() {
        return books;
    }

    public LiveData<String> getToast() {
        return toast;
    }

    public void setKeyword(String text) {
        keyword.setValue(text == null ? "" : text.trim());
    }

    public void delete(Book book) {
        repository.delete(book);
    }

    /** 导入本地图书(epub)。 */
    public void importFromUri(Uri uri) {
        importer.importFromUri(uri, new BookImporter.Callback() {
            @Override
            public void onSuccess(String title) {
                toast.setValue(getApplication().getString(
                        com.lwl.bookreader.R.string.import_success, title));
            }

            @Override
            public void onError(String message) {
                toast.setValue(getApplication().getString(
                        com.lwl.bookreader.R.string.import_failed, message));
            }
        });
    }
}
