package com.devicespooflab.hooks;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.devicespooflab.hooks.data.AppSettingsStore;
import com.devicespooflab.hooks.data.ConfigFileManager;
import com.devicespooflab.hooks.data.DevicePreset;
import com.devicespooflab.hooks.data.DevicePresetCatalog;
import com.devicespooflab.hooks.databinding.ActivityDeviceEditBinding;
import com.devicespooflab.hooks.ui.DeviceSettingsFragment;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

public class DeviceEditActivity extends AppCompatActivity implements DeviceSettingsHost {

    private ActivityDeviceEditBinding binding;
    private ConfigFileManager configFileManager;
    private List<DevicePreset> presets = new ArrayList<>();
    private ConfigFileManager.LoadedConfig loadedConfig;
    private DeviceSettingsFragment settingsFragment;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppSettingsStore.applyActivityTheme(this);
        AppSettingsStore.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityDeviceEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        configFileManager = new ConfigFileManager();
        presets = new DevicePresetCatalog().load(this);
        if (presets == null) {
            presets = new ArrayList<>();
        }
        try {
            loadedConfig = configFileManager.ensureLoaded(this, presets);
        } catch (Exception e) {
            finish();
            return;
        }

        if (savedInstanceState == null) {
            settingsFragment = new DeviceSettingsFragment();
            getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, settingsFragment)
                .commitNow();
        } else {
            settingsFragment = (DeviceSettingsFragment) getSupportFragmentManager()
                .findFragmentById(R.id.fragment_container);
        }

        binding.saveFab.setOnClickListener(v -> save());
    }

    @Override
    public List<DevicePreset> getPresets() {
        return presets;
    }

    @Override
    public ConfigFileManager.LoadedConfig getLoadedConfigState() {
        return loadedConfig;
    }

    private void save() {
        if (settingsFragment == null) {
            return;
        }
        DeviceSettingsFragment.Draft draft = settingsFragment.buildDraft();
        if (draft == null) {
            return;
        }
        try {
            loadedConfig = configFileManager.save(
                this,
                draft.profile,
                draft.extraProperties,
                draft.selectedPresetId,
                draft.customMode
            );
            settingsFragment.refreshFromHost(true);
            setResult(RESULT_OK);
            Snackbar.make(binding.getRoot(), R.string.save_success, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.saveFab)
                .show();
        } catch (Exception e) {
            Snackbar.make(
                binding.getRoot(),
                getString(R.string.save_failed) + " " + e.getMessage(),
                Snackbar.LENGTH_LONG
            ).setAnchorView(binding.saveFab).show();
        }
    }

    public static Intent createIntent(Context context) {
        return new Intent(context, DeviceEditActivity.class);
    }
}
