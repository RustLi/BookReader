package com.lwl.bookreader.ui.shelf;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.lwl.bookreader.R;
import com.lwl.bookreader.data.Book;
import com.lwl.bookreader.databinding.FragmentShelfBinding;
import com.lwl.bookreader.databinding.SheetImportBinding;

/** 书架 Tab(图 01 / 02):网格展示书籍,支持导入、筛选与删除。 */
public class ShelfFragment extends Fragment {

    private FragmentShelfBinding binding;
    private ShelfViewModel viewModel;
    private BookAdapter adapter;

    /** 系统文件选择器:选取本地图书。 */
    private final ActivityResultLauncher<String[]> openDocLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    viewModel.importFromUri(uri);
                }
            });

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
                Intent intent = new Intent(requireContext(),
                        com.lwl.bookreader.ui.reader.ReaderActivity.class);
                intent.putExtra(com.lwl.bookreader.ui.reader.ReaderActivity.EXTRA_BOOK_ID, book.id);
                startActivity(intent);
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

        binding.btnAdd.setOnClickListener(v -> showImportSheet());

        viewModel.getBooks().observe(getViewLifecycleOwner(), books -> {
            adapter.submitList(books);
            boolean empty = books == null || books.isEmpty();
            binding.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        });

        viewModel.getToast().observe(getViewLifecycleOwner(),
                msg -> Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show());
    }

    private void showImportSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        SheetImportBinding sheet = SheetImportBinding.inflate(getLayoutInflater());
        dialog.setContentView(sheet.getRoot());

        sheet.sheetOnline.setOnClickListener(v -> {
            comingSoon();
            dialog.dismiss();
        });
        sheet.sheetWifi.setOnClickListener(v -> {
            comingSoon();
            dialog.dismiss();
        });
        sheet.sheetLocal.setOnClickListener(v -> {
            dialog.dismiss();
            openDocLauncher.launch(new String[]{"*/*"});
        });
        sheet.sheetCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void comingSoon() {
        Toast.makeText(requireContext(), R.string.import_coming_soon, Toast.LENGTH_SHORT).show();
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
