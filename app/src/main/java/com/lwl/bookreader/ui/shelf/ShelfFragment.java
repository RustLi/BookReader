package com.lwl.bookreader.ui.shelf;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.lwl.bookreader.databinding.FragmentPlaceholderBinding;

/** 书架 Tab(图 01 / 02)。占位实现,后续在 task-03 完善。 */
public class ShelfFragment extends Fragment {

    private FragmentPlaceholderBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPlaceholderBinding.inflate(inflater, container, false);
        binding.placeholderText.setText("书架");
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
