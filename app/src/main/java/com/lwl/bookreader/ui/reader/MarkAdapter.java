package com.lwl.bookreader.ui.reader;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lwl.bookreader.databinding.ItemMarkBinding;

import java.util.List;

/** 书签 / 笔记通用列表适配器。 */
public class MarkAdapter extends RecyclerView.Adapter<MarkAdapter.VH> {

    /** 一行:展示文字 + 时间,携带跳转位置与原始对象(用于删除)。 */
    public static class Row {
        public final String text;
        public final String time;
        public final int chapterIndex;
        public final float chapterProgress;
        public final Object ref;

        public Row(String text, String time, int chapterIndex, float chapterProgress, Object ref) {
            this.text = text;
            this.time = time;
            this.chapterIndex = chapterIndex;
            this.chapterProgress = chapterProgress;
            this.ref = ref;
        }
    }

    public interface Listener {
        void onClick(Row row);
        void onLongClick(Row row);
    }

    private final List<Row> rows;
    private final Listener listener;

    public MarkAdapter(List<Row> rows, Listener listener) {
        this.rows = rows;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemMarkBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Row row = rows.get(position);
        holder.binding.markText.setText(row.text);
        holder.binding.markTime.setText(row.time);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(row);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onLongClick(row);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemMarkBinding binding;

        VH(ItemMarkBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
