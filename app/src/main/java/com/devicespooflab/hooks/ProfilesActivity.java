package com.devicespooflab.hooks;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.devicespooflab.hooks.data.AppSettingsStore;
import com.devicespooflab.hooks.proxy.ProxyService;
import com.devicespooflab.hooks.data.ConfigFileManager;
import com.devicespooflab.hooks.data.DevicePreset;
import com.devicespooflab.hooks.data.DevicePresetCatalog;
import com.devicespooflab.hooks.data.ProfileStore;
import com.devicespooflab.hooks.data.SpoofProfile;
import com.devicespooflab.hooks.databinding.ActivityProfilesBinding;
import com.devicespooflab.hooks.databinding.ItemProfileBinding;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class ProfilesActivity extends AppCompatActivity {

    private ActivityProfilesBinding binding;
    private ConfigFileManager configFileManager;
    private final List<DevicePreset> presets = new ArrayList<>();
    private final List<SpoofProfile> profiles = new ArrayList<>();
    private ProfilesAdapter adapter;
    private String pendingAssignProfileId;

    private final androidx.activity.result.ActivityResultLauncher<Intent> appPickerLauncher =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                return;
            }
            ArrayList<String> packages = result.getData().getStringArrayListExtra(
                SafeModeAppsActivity.EXTRA_RESULT_SELECTED_PACKAGES
            );
            applyAssignedApps(pendingAssignProfileId, packages);
        });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppSettingsStore.applyActivityTheme(this);
        AppSettingsStore.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityProfilesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.topAppBar);
        configureTopBarAppearance();

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.settings_profiles_title);
        }
        binding.topAppBar.setNavigationOnClickListener(v -> finish());

        configFileManager = new ConfigFileManager();
        profiles.clear();
        profiles.addAll(ProfileStore.load(this));

        adapter = new ProfilesAdapter();
        binding.profilesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.profilesRecyclerView.setAdapter(adapter);
        updateEmptyState();

        binding.addProfileFab.setOnClickListener(v -> showCreateProfileDialog());

        loadPresetsAsync();
    }

    private void configureTopBarAppearance() {
        int backgroundColor = MaterialColors.getColor(
            binding.topAppBar,
            android.R.attr.colorBackground
        );
        int onBackgroundColor = MaterialColors.getColor(
            binding.topAppBar,
            com.google.android.material.R.attr.colorOnBackground
        );
        int topBarColor = ColorUtils.blendARGB(backgroundColor, onBackgroundColor, 0.08f);

        binding.topAppBarLayout.setBackgroundColor(topBarColor);
        binding.topAppBarLayout.setLiftOnScroll(false);
        binding.topAppBar.setElevation(0f);
        binding.topAppBar.setBackgroundColor(topBarColor);
    }

    private void loadPresetsAsync() {
        new Thread(() -> {
            List<DevicePreset> loadedPresets = new DevicePresetCatalog().load(this);
            runOnUiThread(() -> {
                presets.clear();
                if (loadedPresets != null) {
                    presets.addAll(loadedPresets);
                }
            });
        }, "spoofmydevice-profiles-presets").start();
    }

    private void updateEmptyState() {
        binding.emptyText.setVisibility(profiles.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void showCreateProfileDialog() {
        if (presets.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_profiles_title)
                .setMessage(R.string.profiles_presets_unavailable)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        CharSequence[] presetLabels = new CharSequence[presets.size()];
        for (int index = 0; index < presets.size(); index++) {
            presetLabels[index] = presets.get(index).getDisplayName();
        }
        new MaterialAlertDialogBuilder(this)
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
        adapter.notifyItemInserted(profiles.size() - 1);
        updateEmptyState();
        persistAndRegenerate();
        openAppPicker(profile);
    }

    private void showProfileOptionsDialog(SpoofProfile profile) {
        CharSequence[] options = new CharSequence[] {
            getString(R.string.profiles_action_assign_apps),
            getString(R.string.profiles_action_rename),
            getString(R.string.profiles_action_delete)
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(profile.getName())
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    openAppPicker(profile);
                } else if (which == 1) {
                    showRenameDialog(profile);
                } else if (which == 2) {
                    showDeleteDialog(profile);
                }
            })
            .show();
    }

    private void showRenameDialog(SpoofProfile profile) {
        TextInputLayout inputLayout = new TextInputLayout(this);
        TextInputEditText input = new TextInputEditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(profile.getName());
        if (profile.getName() != null) {
            input.setSelection(profile.getName().length());
        }
        inputLayout.addView(input);
        FrameLayout container = new FrameLayout(this);
        int horizontalPadding = Math.round(24f * getResources().getDisplayMetrics().density);
        container.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        container.addView(inputLayout, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profiles_action_rename)
            .setView(container)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String newName = input.getText() == null ? "" : input.getText().toString().trim();
                if (!newName.isEmpty()) {
                    profile.setName(newName);
                    adapter.notifyDataSetChanged();
                    persistAndRegenerate();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showDeleteDialog(SpoofProfile profile) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profiles_action_delete)
            .setMessage(getString(R.string.profiles_delete_confirm, profile.getName()))
            .setPositiveButton(R.string.profiles_action_delete, (dialog, which) -> {
                int position = profiles.indexOf(profile);
                if (position >= 0) {
                    profiles.remove(position);
                    adapter.notifyItemRemoved(position);
                    updateEmptyState();
                    persistAndRegenerate();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void openAppPicker(SpoofProfile profile) {
        pendingAssignProfileId = profile.getId();
        appPickerLauncher.launch(
            SafeModeAppsActivity.createIntent(this, profile.getApps())
        );
    }

    private void applyAssignedApps(String profileId, ArrayList<String> packages) {
        if (profileId == null) {
            return;
        }
        SpoofProfile target = findProfile(profileId);
        if (target == null) {
            return;
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (packages != null) {
            selected.addAll(packages);
        }
        target.setApps(selected);
        for (SpoofProfile other : profiles) {
            if (other == target) {
                continue;
            }
            other.getApps().removeAll(selected);
        }
        adapter.notifyDataSetChanged();
        persistAndRegenerate();
    }

    private SpoofProfile findProfile(String profileId) {
        for (SpoofProfile profile : profiles) {
            if (profile.getId().equals(profileId)) {
                return profile;
            }
        }
        return null;
    }

    private void persistAndRegenerate() {
        ProfileStore.save(this, profiles);
        ProxyService.startOrRestart(this);
        Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                ConfigFileManager.LoadedConfig loaded = configFileManager.ensureLoaded(
                    appContext,
                    new DevicePresetCatalog().load(appContext)
                );
                configFileManager.save(
                    appContext,
                    loaded.getProfile(),
                    loaded.getExtraProperties(),
                    loaded.getSelectedPresetId(),
                    loaded.isCustomMode()
                );
            } catch (Exception ignored) {
            }
        }, "spoofmydevice-profiles-save").start();
    }

    public static Intent createIntent(Context context) {
        return new Intent(context, ProfilesActivity.class);
    }

    private final class ProfilesAdapter extends RecyclerView.Adapter<ProfilesAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemProfileBinding itemBinding = ItemProfileBinding.inflate(
                getLayoutInflater(),
                parent,
                false
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
                itemBinding.getRoot().setOnClickListener(v -> showProfileOptionsDialog(profile));
            }
        }
    }
}
