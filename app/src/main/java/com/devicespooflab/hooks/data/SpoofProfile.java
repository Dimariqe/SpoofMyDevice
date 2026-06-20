package com.devicespooflab.hooks.data;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named device configuration that can be applied to a specific set of apps.
 *
 * The {@code propertiesText} field stores the fully generated {@code key=value}
 * property block (the same format that {@link ConfigFileManager} writes for the
 * default profile). The hook overlays these values on top of the default profile
 * for every package listed in {@code apps}.
 */
public class SpoofProfile {

    private final String id;
    private String name;
    private String propertiesText;
    private final LinkedHashSet<String> apps = new LinkedHashSet<>();

    public SpoofProfile(String id, String name, String propertiesText, Set<String> apps) {
        this.id = id;
        this.name = name;
        this.propertiesText = propertiesText == null ? "" : propertiesText;
        if (apps != null) {
            this.apps.addAll(apps);
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPropertiesText() {
        return propertiesText;
    }

    public void setPropertiesText(String propertiesText) {
        this.propertiesText = propertiesText == null ? "" : propertiesText;
    }

    public LinkedHashSet<String> getApps() {
        return apps;
    }

    public void setApps(Set<String> packageNames) {
        apps.clear();
        if (packageNames != null) {
            apps.addAll(packageNames);
        }
    }

    public void removeApp(String packageName) {
        apps.remove(packageName);
    }
}
