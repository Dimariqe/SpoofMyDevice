package com.devicespooflab.hooks;

import com.devicespooflab.hooks.data.ConfigFileManager;
import com.devicespooflab.hooks.data.DevicePreset;

import java.util.List;

public interface DeviceSettingsHost {
    List<DevicePreset> getPresets();
    ConfigFileManager.LoadedConfig getLoadedConfigState();
    default boolean isProfileContext() { return false; }
}
