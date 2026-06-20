package com.devicespooflab.hooks.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Persists the list of named per-app profiles in the companion app's private
 * SharedPreferences. Profiles are stored as a JSON array so that the structure
 * (name, generated property block, assigned apps) survives app restarts.
 */
public final class ProfileStore {

    private static final String PREFS_NAME = "spoof_profiles";
    private static final String KEY_PROFILES = "profiles";

    private static final String JSON_ID = "id";
    private static final String JSON_NAME = "name";
    private static final String JSON_PROPERTIES = "properties";
    private static final String JSON_APPS = "apps";

    private ProfileStore() {
    }

    public static List<SpoofProfile> load(Context context) {
        List<SpoofProfile> profiles = new ArrayList<>();
        String raw = preferences(context).getString(KEY_PROFILES, null);
        if (raw == null || raw.trim().isEmpty()) {
            return profiles;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                String id = object.optString(JSON_ID, "");
                if (id.trim().isEmpty()) {
                    continue;
                }
                String name = object.optString(JSON_NAME, id);
                String propertiesText = object.optString(JSON_PROPERTIES, "");
                LinkedHashSet<String> apps = new LinkedHashSet<>();
                JSONArray appsArray = object.optJSONArray(JSON_APPS);
                if (appsArray != null) {
                    for (int appIndex = 0; appIndex < appsArray.length(); appIndex++) {
                        String packageName = appsArray.optString(appIndex, "").trim();
                        if (!packageName.isEmpty()) {
                            apps.add(packageName);
                        }
                    }
                }
                profiles.add(new SpoofProfile(id, name, propertiesText, apps));
            }
        } catch (Exception ignored) {
        }
        return profiles;
    }

    public static void save(Context context, List<SpoofProfile> profiles) {
        JSONArray array = new JSONArray();
        if (profiles != null) {
            for (SpoofProfile profile : profiles) {
                if (profile == null) {
                    continue;
                }
                try {
                    JSONObject object = new JSONObject();
                    object.put(JSON_ID, profile.getId());
                    object.put(JSON_NAME, profile.getName());
                    object.put(JSON_PROPERTIES, profile.getPropertiesText());
                    JSONArray appsArray = new JSONArray();
                    for (String packageName : profile.getApps()) {
                        appsArray.put(packageName);
                    }
                    object.put(JSON_APPS, appsArray);
                    array.put(object);
                } catch (Exception ignored) {
                }
            }
        }
        preferences(context).edit().putString(KEY_PROFILES, array.toString()).apply();
    }

    public static String generateId() {
        return "profile_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
