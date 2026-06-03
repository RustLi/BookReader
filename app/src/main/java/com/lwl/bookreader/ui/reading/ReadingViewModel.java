package com.lwl.bookreader.ui.reading;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.lwl.bookreader.data.Book;
import com.lwl.bookreader.data.BookRepository;

import java.util.List;

public class ReadingViewModel extends AndroidViewModel {

    private final LiveData<List<Book>> recentlyRead;

    public ReadingViewModel(@NonNull Application application) {
        super(application);
        recentlyRead = new BookRepository(application).getRecentlyRead();
    }

    public LiveData<List<Book>> getRecentlyRead() {
        return recentlyRead;
    }
}
