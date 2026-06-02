package com.lwl.bookreader.ui.shelf;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.lwl.bookreader.R;
import com.lwl.bookreader.data.Book;
import com.lwl.bookreader.databinding.ItemBookBinding;

import java.io.File;

/** 书架网格适配器:展示封面、书名与状态。 */
public class BookAdapter extends ListAdapter<Book, BookAdapter.BookViewHolder> {

    public interface OnBookAction {
        void onClick(Book book);
        void onLongClick(Book book);
    }

    private final OnBookAction action;

    public BookAdapter(OnBookAction action) {
        super(DIFF);
        this.action = action;
    }

    private static final DiffUtil.ItemCallback<Book> DIFF = new DiffUtil.ItemCallback<Book>() {
        @Override
        public boolean areItemsTheSame(@NonNull Book a, @NonNull Book b) {
            return a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Book a, @NonNull Book b) {
            return TextUtils.equals(a.title, b.title)
                    && TextUtils.equals(a.coverPath, b.coverPath)
                    && a.lastReadTime == b.lastReadTime;
        }
    };

    @NonNull
    @Override
    public BookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBookBinding binding = ItemBookBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new BookViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull BookViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class BookViewHolder extends RecyclerView.ViewHolder {
        private final ItemBookBinding binding;

        BookViewHolder(ItemBookBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Book book) {
            binding.title.setText(book.title);
            binding.status.setText(ShelfFormat.statusText(itemView.getContext(), book));

            if (!TextUtils.isEmpty(book.coverPath)) {
                Glide.with(itemView)
                        .load(new File(book.coverPath))
                        .placeholder(R.drawable.bg_cover_placeholder)
                        .into(binding.cover);
            } else {
                Glide.with(itemView).clear(binding.cover);
                binding.cover.setImageDrawable(null);
            }

            itemView.setOnClickListener(v -> {
                if (action != null) action.onClick(book);
            });
            itemView.setOnLongClickListener(v -> {
                if (action != null) action.onLongClick(book);
                return true;
            });
        }
    }
}
