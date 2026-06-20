package com.devicespooflab.hooks;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.devicespooflab.hooks.data.AppSettingsStore;
import com.devicespooflab.hooks.data.ConfigFileManager;
import com.devicespooflab.hooks.data.DevicePreset;
import com.devicespooflab.hooks.data.DevicePresetCatalog;
import com.devicespooflab.hooks.data.DeviceProfile;
import com.devicespooflab.hooks.data.ProfileStore;
import com.devicespooflab.hooks.data.SpoofProfile;
import com.devicespooflab.hooks.databinding.ActivityProfileEditBinding;
import com.devicespooflab.hooks.ui.DeviceSettingsFragment;
import com.devicespooflab.hooks.proxy.ProxyService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class ProfileEditActivity extends AppCompatActivity implements DeviceSettingsHost {

    public static final String EXTRA_PROFILE_ID = "profile_id";

    private ActivityProfileEditBinding binding;
    private ConfigFileManager configFileManager;
    private List<DevicePreset> presets = new ArrayList<>();
    private ConfigFileManager.LoadedConfig profileConfig;
    private DeviceSettingsFragment settingsFragment;
    private SpoofProfile profile;
    private List<SpoofProfile> allProfiles;

    private final ActivityResultLauncher<Intent> appPickerLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                return;
            }
            ArrayList<String> packages = result.getData().getStringArrayListExtra(
                SafeModeAppsActivity.EXTRA_RESULT_SELECTED_PACKAGES
            );
            applyAssignedApps(packages);
        });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppSettingsStore.applyActivityTheme(this);
        AppSettingsStore.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityProfileEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String profileId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        allProfiles = ProfileStore.load(this);
        profile = findProfile(profileId, allProfiles);
        if (profile == null) {
            finish();
            return;
        }

        configFileManager = new ConfigFileManager();
        presets = new DevicePresetCatalog().load(this);
        if (presets == null) {
            presets = new ArrayList<>();
        }

        buildProfileConfig();

        if (savedInstanceState == null) {
            settingsFragment = new DeviceSettingsFragment();
            getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, settingsFragment)
                .commitNow();
        } else {
            settingsFragment = (DeviceSettingsFragment) getSupportFragmentManager()
                .findFragmentById(R.id.fragment_container);
        }

        hideFragmentAppBar();

        binding.inputProfileName.setText(profile.getName());
        refreshAppsLabel();

        binding.assignAppsRow.setOnClickListener(v -> openAppPicker());
        binding.deleteProfileRow.setOnClickListener(v -> showDeleteConfirm());
        binding.saveFab.setOnClickListener(v -> save());
    }

    @Override
    public List<DevicePreset> getPresets() {
        return presets;
    }

    @Override
    public ConfigFileManager.LoadedConfig getLoadedConfigState() {
        return profileConfig;
    }

    @Override
    public boolean isProfileContext() {
        return true;
    }

    private void buildProfileConfig() {
        DeviceProfile parsedProfile = configFileManager.parseDeviceProfile(profile.getPropertiesText());
        Map<String, String> extraProperties = configFileManager.parseExtraProperties(profile.getPropertiesText());
        String presetId = configFileManager.matchPresetId(parsedProfile, presets);
        boolean customMode = presetId == null;
        if (!customMode) {
            DeviceProfile presetProfile = findPresetProfile(presetId);
            customMode = presetProfile == null || !parsedProfile.matchesPreset(presetProfile);
        }
        profileConfig = new ConfigFileManager.LoadedConfig(
            configFileManager.getConfigFile(this),
            parsedProfile,
            extraProperties,
            presetId,
            customMode
        );
    }

    private DeviceProfile findPresetProfile(String presetId) {
        if (presetId == null) return null;
        for (DevicePreset preset : presets) {
            if (presetId.equals(preset.getId())) {
                return preset.getProfile();
            }
        }
        return null;
    }

    private void hideFragmentAppBar() {
        if (settingsFragment == null || settingsFragment.getView() == null) {
            return;
        }
        android.view.View appBar = settingsFragment.getView().findViewById(R.id.device_settings_app_bar);
        if (appBar != null) {
            appBar.setVisibility(android.view.View.GONE);
        }
    }

    private void refreshAppsLabel() {
        int count = profile.getApps().size();
        if (count == 0) {
            binding.assignAppsLabel.setText(R.string.profile_edit_apps_none);
        } else {
            binding.assignAppsLabel.setText(getString(R.string.profile_edit_apps_count, count));
        }
    }

    private void openAppPicker() {
        appPickerLauncher.launch(
            SafeModeAppsActivity.createIntent(this, profile.getApps())
        );
    }

    private void applyAssignedApps(ArrayList<String> packages) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (packages != null) {
            selected.addAll(packages);
        }
        // Remove these apps from other profiles
        for (SpoofProfile other : allProfiles) {
            if (other == profile) {
                continue;
            }
            other.getApps().removeAll(selected);
        }
        profile.setApps(selected);
        refreshAppsLabel();
        persistProfiles();
    }

    private void save() {
        String newName = binding.inputProfileName.getText() == null
            ? ""
            : binding.inputProfileName.getText().toString().trim();
        if (newName.isEmpty()) {
            binding.layoutProfileName.setError(getString(R.string.profile_edit_name_hint));
            return;
        }
        binding.layoutProfileName.setError(null);

        if (settingsFragment == null) return;
        DeviceSettingsFragment.Draft draft = settingsFragment.buildDraft();
        if (draft == null) return;

        profile.setName(newName);
        String newPropertiesText = configFileManager.buildProfileBlock(draft.profile, draft.extraProperties);
        profile.setPropertiesText(newPropertiesText);

        profileConfig = new ConfigFileManager.LoadedConfig(
            profileConfig.getConfigFile(),
            draft.profile,
            draft.extraProperties,
            draft.selectedPresetId,
            draft.customMode
        );

        settingsFragment.refreshFromHost(true);
        persistProfiles();
        rebuildConfigFile();
        ProxyService.startOrRestart(this);
        setResult(RESULT_OK);
        Snackbar.make(binding.getRoot(), R.string.save_success, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.saveFab)
            .show();
    }

    private void showDeleteConfirm() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profiles_action_delete)
            .setMessage(getString(R.string.profiles_delete_confirm, profile.getName()))
            .setPositiveButton(R.string.profiles_action_delete, (dialog, which) -> deleteProfile())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void deleteProfile() {
        allProfiles.remove(profile);
        persistProfiles();
        rebuildConfigFile();
        ProxyService.startOrRestart(this);
        setResult(RESULT_OK);
        finish();
    }

    private void persistProfiles() {
        ProfileStore.save(this, allProfiles);
    }

    private void rebuildConfigFile() {
        Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                List<DevicePreset> catalog = new DevicePresetCatalog().load(appContext);
                ConfigFileManager.LoadedConfig loaded = configFileManager.ensureLoaded(appContext, catalog);
                configFileManager.save(
                    appContext,
                    loaded.getProfile(),
                    loaded.getExtraProperties(),
                    loaded.getSelectedPresetId(),
                    loaded.isCustomMode()
                );
            } catch (Exception ignored) {
            }
        }, "spoofmydevice-profile-edit-save").start();
    }

    private static SpoofProfile findProfile(String profileId, List<SpoofProfile> profiles) {
        if (profileId == null) return null;
        for (SpoofProfile p : profiles) {
            if (profileId.equals(p.getId())) return p;
        }
        return null;
    }

    public static Intent createIntent(Context context, String profileId) {
        return new Intent(context, ProfileEditActivity.class)
            .putExtra(EXTRA_PROFILE_ID, profileId);
    }
}
