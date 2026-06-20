package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ProxyHooks {

    private static final String TAG = "SpoofMyDevice-Proxy";
    private static String lastUser = null;
    private static String lastPass = null;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.ProxySelector",
                lpparam.classLoader,
                "select",
                URI.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!ConfigManager.isProxyEnabled()) {
                            return;
                        }

                        String host = ConfigManager.getProxyHost();
                        if (host == null || host.trim().isEmpty()) {
                            return;
                        }

                        int port = ConfigManager.getProxyPort();
                        String user = ConfigManager.getProxyUser();
                        String pass = ConfigManager.getProxyPassword();

                        if (user != null && !user.isEmpty()) {
                            if (!user.equals(lastUser) || !pass.equals(lastPass)) {
                                try {
                                    Authenticator.setDefault(new Authenticator() {
                                        @Override
                                        protected PasswordAuthentication getPasswordAuthentication() {
                                            return new PasswordAuthentication(user, pass != null ? pass.toCharArray() : new char[0]);
                                        }
                                    });
                                    lastUser = user;
                                    lastPass = pass;
                                    XposedBridge.log(TAG + ": Authenticator (re)set for user: " + user);
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Failed to set Authenticator: " + e.getMessage());
                                }
                            }
                        }

                        try {
                            Proxy socksProxy = new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port));
                            List<Proxy> result = new ArrayList<>();
                            result.add(socksProxy);
                            param.setResult(result);
                        } catch (Exception e) {
                            XposedBridge.log(TAG + ": Failed to create proxy: " + e.getMessage());
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook ProxySelector: " + t.getMessage());
        }
    }
}
