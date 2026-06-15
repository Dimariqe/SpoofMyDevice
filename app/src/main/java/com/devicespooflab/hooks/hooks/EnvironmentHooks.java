package com.devicespooflab.hooks.hooks;

import android.app.Application;
import android.content.Context;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.Locale;
import java.util.TimeZone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class EnvironmentHooks {

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        // Применяем настройки окружения сразу при загрузке пакета
        applySpoofedEnvironment();

        // А также перехватываем запуск приложения, чтобы переопределить значения,
        // если Android framework попытается их сбросить на оригинальные.
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    applySpoofedEnvironment();
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void applySpoofedEnvironment() {
        try {
            String spoofedTz = ConfigManager.getSystemProperty("persist.sys.timezone", "America/Los_Angeles");
            if (spoofedTz != null) {
                TimeZone tz = TimeZone.getTimeZone(spoofedTz);
                if (tz != null) {
                    TimeZone.setDefault(tz);
                }
            }
        } catch (Throwable ignored) {}

        try {
            Locale.setDefault(Locale.US);
        } catch (Throwable ignored) {}
    }
}
