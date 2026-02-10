package com.limelight.ui;


import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.limelight.R;

public class AdapterFragment extends Fragment {
    private AdapterFragmentCallbacks callbacks;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        callbacks = (AdapterFragmentCallbacks) context;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(callbacks.getAdapterFragmentLayoutId(), container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        callbacks.receiveAbsListView(view.findViewById(R.id.fragmentView));
    }
}
