package com.lwl.bookreader.ui.reading;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.lwl.bookreader.R;
import com.lwl.bookreader.data.Book;
import com.lwl.bookreader.databinding.ItemRecentBinding;
import com.lwl.bookreader.util.TimeFormat;

import java.io.File;

/** “最近读过”列表适配器。 */
public class RecentAdapter extends ListAdapter<Book, RecentAdapter.VH> {

    public interface OnContinue {
        void onContinue(Book book);
    }

    private final OnContinue onContinue;

    public RecentAdapter(OnContinue onContinue) {
        super(DIFF);
        this.onContinue = onContinue;
    }

    private static final DiffUtil.ItemCallback<Book> DIFF = new DiffUtil.ItemCallback<Book>() {
        @Override
        public boolean areItemsTheSame(@NonNull Book a, @NonNull Book b) {
            return a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Book a, @NonNull Book b) {
            return a.lastReadTime == b.lastReadTime && TextUtils.equals(a.title, b.title);
        }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemRecentBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(getItem(position));
    }

    class VH extends RecyclerView.ViewHolder {
        private final ItemRecentBinding binding;

        VH(ItemRecentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Book book) {
            binding.title.setText(book.title);
            binding.time.setText(TimeFormat.relative(itemView.getContext(), book.lastReadTime));
            if (!TextUtils.isEmpty(book.coverPath)) {
                Glide.with(itemView).load(new File(book.coverPath))
                        .placeholder(R.drawable.bg_cover_placeholder).into(binding.cover);
            } else {
                Glide.with(itemView).clear(binding.cover);
                binding.cover.setImageDrawable(null);
            }
            binding.btnContinue.setOnClickListener(v -> {
                if (onContinue != null) onContinue.onContinue(book);
            });
            itemView.setOnClickListener(v -> {
                if (onContinue != null) onContinue.onContinue(book);
            });
        }
    }
}
