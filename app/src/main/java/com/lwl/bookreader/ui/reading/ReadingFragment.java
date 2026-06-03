package com.lwl.bookreader.ui.reading;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.lwl.bookreader.R;
import com.lwl.bookreader.data.Book;
import com.lwl.bookreader.databinding.FragmentReadingBinding;
import com.lwl.bookreader.ui.reader.ReaderActivity;
import com.lwl.bookreader.util.TimeFormat;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** 阅读中 Tab(图 03):继续阅读大卡片 + 最近读过列表。 */
public class ReadingFragment extends Fragment {

    private FragmentReadingBinding binding;
    private ReadingViewModel viewModel;
    private RecentAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentReadingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(this).get(ReadingViewModel.class);

        adapter = new RecentAdapter(this::openReader);
        binding.recentList.setAdapter(adapter);

        viewModel.getRecentlyRead().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(List<Book> books) {
        boolean empty = books == null || books.isEmpty();
        binding.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.continueCard.setVisibility(empty ? View.GONE : View.VISIBLE);

        if (empty) {
            binding.recentHeader.setVisibility(View.GONE);
            adapter.submitList(Collections.emptyList());
            return;
        }

        // 第一本 = 继续阅读大卡片
        Book top = books.get(0);
        binding.continueTitle.setText(top.title);
        binding.continueTime.setText(TimeFormat.relative(requireContext(), top.lastReadTime));
        if (!TextUtils.isEmpty(top.coverPath)) {
            Glide.with(this).load(new File(top.coverPath))
                    .placeholder(R.drawable.bg_cover_placeholder).into(binding.continueCover);
        } else {
            binding.continueCover.setImageDrawable(null);
        }
        binding.continueCard.setOnClickListener(v -> openReader(top));
        binding.continueButton.setOnClickListener(v -> openReader(top));

        // 其余 = 最近读过列表
        List<Book> rest = new java.util.ArrayList<>(books.subList(1, books.size()));
        binding.recentHeader.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.submitList(rest);
    }

    private void openReader(Book book) {
        Intent intent = new Intent(requireContext(), ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
