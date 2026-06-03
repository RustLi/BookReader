package com.lwl.bookreader.ui.reader;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lwl.bookreader.R;

import java.util.List;

/** 目录列表适配器。 */
public class TocAdapter extends RecyclerView.Adapter<TocAdapter.VH> {

    public interface OnPick {
        void onPick(String zipPath);
    }

    private final List<Row> rows;
    private final OnPick onPick;

    public static class Row {
        public final String title;
        public final String zipPath;
        public final int depth;

        public Row(String title, String zipPath, int depth) {
            this.title = title;
            this.zipPath = zipPath;
            this.depth = depth;
        }
    }

    public TocAdapter(List<Row> rows, OnPick onPick) {
        this.rows = rows;
        this.onPick = onPick;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_toc, parent, false);
        return new VH(tv);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Row row = rows.get(position);
        holder.title.setText(row.title);
        int indent = (int) (holder.itemView.getResources().getDisplayMetrics().density
                * 16 * Math.max(0, row.depth));
        holder.title.setPadding(holder.basePadding + indent, 0, holder.basePadding, 0);
        holder.itemView.setOnClickListener(v -> {
            if (onPick != null) onPick.onPick(row.zipPath);
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final int basePadding;

        VH(TextView itemView) {
            super(itemView);
            this.title = itemView;
            this.basePadding = itemView.getPaddingLeft();
        }
    }
}
