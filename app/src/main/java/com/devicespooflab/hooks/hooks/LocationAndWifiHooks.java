package com.devicespooflab.hooks.hooks;

import android.location.Location;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class LocationAndWifiHooks {

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookWifiManager(lpparam);
        hookLocationManager(lpparam);
    }

    private static void hookWifiManager(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> wifiManager = XposedHelpers.findClassIfExists("android.net.wifi.WifiManager", lpparam.classLoader);
        if (wifiManager != null) {
            try {
                XposedHelpers.findAndHookMethod(wifiManager, "getScanResults", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!ConfigManager.shouldHookWifi()) return;
                        param.setResult(new ArrayList<ScanResult>());
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void hookLocationManager(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> locationManager = XposedHelpers.findClassIfExists("android.location.LocationManager", lpparam.classLoader);
        if (locationManager != null) {
            try {
                XposedHelpers.findAndHookMethod(locationManager, "getLastKnownLocation", String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!ConfigManager.shouldHookLocation()) return;
                        param.setResult(null);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }
}
