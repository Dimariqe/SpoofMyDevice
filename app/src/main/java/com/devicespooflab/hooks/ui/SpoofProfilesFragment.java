package com.devicespooflab.hooks.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.devicespooflab.hooks.DeviceEditActivity;
import com.devicespooflab.hooks.MainActivity;
import com.devicespooflab.hooks.ProfileEditActivity;
import com.devicespooflab.hooks.R;
import com.devicespooflab.hooks.data.ConfigFileManager;
import com.devicespooflab.hooks.data.DevicePreset;
import com.devicespooflab.hooks.data.DevicePresetCatalog;
import com.devicespooflab.hooks.data.DeviceProfile;
import com.devicespooflab.hooks.data.ProfileStore;
import com.devicespooflab.hooks.data.SpoofProfile;
import com.devicespooflab.hooks.databinding.FragmentSpoofProfilesBinding;
import com.devicespooflab.hooks.databinding.ItemProfileBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class SpoofProfilesFragment extends Fragment {

    private FragmentSpoofProfilesBinding binding;
    private ConfigFileManager configFileManager;
    private final List<DevicePreset> presets = new ArrayList<>();
    private final List<SpoofProfile> profiles = new ArrayList<>();
    private ProfilesAdapter adapter;

    private final ActivityResultLauncher<Intent> deviceEditLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == android.app.Activity.RESULT_OK
                    && requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).reloadConfig();
            }
            refreshDefaultProfileSummary();
        });

    private final ActivityResultLauncher<Intent> profileEditLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            reloadProfiles();
        });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentSpoofProfilesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        configFileManager = new ConfigFileManager();

        adapter = new ProfilesAdapter();
        binding.profilesRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.profilesRecyclerView.setAdapter(adapter);

        binding.defaultProfileRow.setOnClickListener(v ->
            deviceEditLauncher.launch(DeviceEditActivity.createIntent(requireContext()))
        );

        binding.addProfileFab.setOnClickListener(v -> showCreateProfileDialog());

        adjustFabForBottomNav();
        loadPresetsAsync();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadProfiles();
        refreshDefaultProfileSummary();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    public void refresh() {
        if (binding == null) {
            return;
        }
        reloadProfiles();
        refreshDefaultProfileSummary();
    }

    private void reloadProfiles() {
        if (binding == null) {
            return;
        }
        profiles.clear();
        profiles.addAll(ProfileStore.load(requireContext()));
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void refreshDefaultProfileSummary() {
        if (binding == null) {
            return;
        }
        String summary = buildDefaultProfileSummary();
        binding.defaultProfileSummary.setText(summary);
    }

    private String buildDefaultProfileSummary() {
        if (!(requireActivity() instanceof MainActivity)) {
            return getString(R.string.spoof_profiles_default_summary);
        }
        ConfigFileManager.LoadedConfig config =
            ((MainActivity) requireActivity()).getLoadedConfigState();
        if (config == null) {
            return getString(R.string.spoof_profiles_default_summary);
        }
        DeviceProfile profile = config.getProfile();
        if (profile == null) {
            return getString(R.string.spoof_profiles_default_summary);
        }
        String model = profile.getModel();
        if (model != null && !model.isEmpty()) {
            return model;
        }
        return getString(R.string.spoof_profiles_default_summary);
    }

    private void adjustFabForBottomNav() {
        View bnv = requireActivity().findViewById(R.id.bottom_navigation);
        if (bnv == null) return;
        bnv.post(() -> {
            if (binding == null) return;
            int extraDp = Math.round(16 * getResources().getDisplayMetrics().density);
            MarginLayoutParams lp = (MarginLayoutParams) binding.addProfileFab.getLayoutParams();
            lp.bottomMargin = bnv.getHeight() + extraDp;
            binding.addProfileFab.setLayoutParams(lp);
        });
    }

    private void loadPresetsAsync() {
        android.content.Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            List<DevicePreset> loaded = new DevicePresetCatalog().load(appContext);
            android.app.Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    presets.clear();
                    if (loaded != null) {
                        presets.addAll(loaded);
                    }
                });
            }
        }, "spoofmydevice-spoof-profiles-presets").start();
    }

    private void updateEmptyState() {
        if (binding == null) {
            return;
        }
        binding.emptyProfilesText.setVisibility(
            profiles.isEmpty() ? View.VISIBLE : View.GONE
        );
    }

    private void showCreateProfileDialog() {
        if (presets.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_profiles_title)
                .setMessage(R.string.profiles_presets_unavailable)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        CharSequence[] presetLabels = new CharSequence[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            presetLabels[i] = presets.get(i).getDisplayName();
        }
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profiles_choose_preset)
            .setItems(presetLabels, (dialog, which) -> {
                if (which >= 0 && which < presets.size()) {
                    createProfileFromPreset(presets.get(which));
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void createProfileFromPreset(DevicePreset preset) {
        String propertiesText = configFileManager.buildDeviceProfileBlock(preset.getProfile());
        SpoofProfile profile = new SpoofProfile(
            ProfileStore.generateId(),
            preset.getDisplayName(),
            propertiesText,
            new LinkedHashSet<>()
        );
        profiles.add(profile);
        ProfileStore.save(requireContext(), profiles);
        adapter.notifyItemInserted(profiles.size() - 1);
        updateEmptyState();
        openProfileEdit(profile);
    }

    private void openProfileEdit(SpoofProfile profile) {
        profileEditLauncher.launch(
            ProfileEditActivity.createIntent(requireContext(), profile.getId())
        );
    }

    private final class ProfilesAdapter extends RecyclerView.Adapter<ProfilesAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemProfileBinding itemBinding = ItemProfileBinding.inflate(
                getLayoutInflater(), parent, false
            );
            return new ViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(profiles.get(position));
        }

        @Override
        public int getItemCount() {
            return profiles.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            private final ItemProfileBinding itemBinding;

            ViewHolder(ItemProfileBinding itemBinding) {
                super(itemBinding.getRoot());
                this.itemBinding = itemBinding;
            }

            void bind(SpoofProfile profile) {
                itemBinding.profileName.setText(profile.getName());
                int appCount = profile.getApps().size();
                if (appCount == 0) {
                    itemBinding.profileApps.setText(R.string.profiles_no_apps);
                } else {
                    itemBinding.profileApps.setText(
                        getString(R.string.profiles_app_count, appCount)
                    );
                }
                itemBinding.getRoot().setOnClickListener(v -> openProfileEdit(profile));
            }
        }
    }
}
