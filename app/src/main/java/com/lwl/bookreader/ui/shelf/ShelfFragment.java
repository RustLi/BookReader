package com.lwl.bookreader.ui.shelf;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.lwl.bookreader.R;
import com.lwl.bookreader.data.Book;
import com.lwl.bookreader.databinding.FragmentShelfBinding;

/** 书架 Tab(图 01 / 02):网格展示书籍,支持筛选与删除。 */
public class ShelfFragment extends Fragment {

    private FragmentShelfBinding binding;
    private ShelfViewModel viewModel;
    private BookAdapter adapter;
    private int sampleSeq = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentShelfBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(this).get(ShelfViewModel.class);

        adapter = new BookAdapter(new BookAdapter.OnBookAction() {
            @Override
            public void onClick(Book book) {
                // task-05 接入阅读页;此处暂不处理
            }

            @Override
            public void onLongClick(Book book) {
                confirmDelete(book);
            }
        });
        binding.recycler.setAdapter(adapter);

        binding.filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                viewModel.setKeyword(s.toString());
            }
        });

        // 临时:点击 + 插入示例书,用于验证书架渲染与增删。task-04 将替换为导入弹窗。
        binding.btnAdd.setOnClickListener(v -> viewModel.addSampleBook(++sampleSeq));

        viewModel.getBooks().observe(getViewLifecycleOwner(), books -> {
            adapter.submitList(books);
            boolean empty = books == null || books.isEmpty();
            binding.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        });
    }

    private void confirmDelete(Book book) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.shelf_delete_title, book.title))
                .setMessage(R.string.shelf_delete_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete,
                        (d, w) -> viewModel.delete(book))
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
