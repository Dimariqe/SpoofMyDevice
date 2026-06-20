package com.devicespooflab.hooks.proxy;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.devicespooflab.hooks.R;
import com.devicespooflab.hooks.data.ProfileStore;
import com.devicespooflab.hooks.data.SpoofProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProxyService extends Service {

    private static final String CHANNEL_ID = "spoofproxy_svc";
    private static final int NOTIF_ID = 0x5F000001;
    private static final String ACTION_RESET = "com.spoofmydevice.proxy.RESET";

    private final List<SocksRelayServer> relays = new ArrayList<>();
    private final Map<String, Integer> profileLocalPorts = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESET.equals(intent.getAction())) {
            stopRelays();
            IptablesManager.reset();
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: pass foreground service type matching manifest declaration
                // FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 0x80 (API 34); safe to pass on older as int
                @SuppressLint("InlinedApi")
                int fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                startForeground(NOTIF_ID, buildNotification(), fgsType);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
        } catch (Exception e) {
            android.util.Log.e("ProxyService", "startForeground failed, aborting", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        new Thread(this::applyProxy, "spoofproxy-apply").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRelays();
        IptablesManager.reset();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void applyProxy() {
        stopRelays();
        List<SpoofProfile> profiles = ProfileStore.load(this);

        for (SpoofProfile profile : profiles) {
            if (profile.getApps().isEmpty()) continue;
            ProxyConfig cfg = IptablesManager.extractProxyForProfile(profile);
            if (cfg == null) continue;

            int localPort = portForProfile(profile.getId());
            SocksRelayServer relay = new SocksRelayServer(profile.getId(), localPort, cfg);
            try {
                relay.start();
                relays.add(relay);
                profileLocalPorts.put(profile.getId(), relay.getLocalPort());
            } catch (IOException e) {
                android.util.Log.e("ProxyService", "Relay start failed for " + profile.getId(), e);
            }
        }

        if (profileLocalPorts.isEmpty()) {
            stopSelf();
            return;
        }

        IptablesManager.apply(this, profileLocalPorts);
    }

    private void stopRelays() {
        for (SocksRelayServer relay : relays) {
            relay.stop();
        }
        relays.clear();
        profileLocalPorts.clear();
    }

    private static int portForProfile(String profileId) {
        return 11000 + (Math.abs(profileId.hashCode()) % 4000);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.proxy_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.proxy_notif_channel_desc));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.proxy_notif_title))
            .setContentText(getString(R.string.proxy_notif_text))
            .setSmallIcon(R.drawable.ic_settings_wifi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build();
    }

    /** Start or restart the proxy service to re-read all profiles. */
    public static void startOrRestart(Context context) {
        context.startForegroundService(new Intent(context, ProxyService.class));
    }

    /** Reset iptables and stop the proxy service. */
    public static void resetAndStop(Context context) {
        Intent intent = new Intent(context, ProxyService.class);
        intent.setAction(ACTION_RESET);
        context.startForegroundService(intent);
    }

    public static boolean hasActiveProxyProfiles(Context context) {
        List<SpoofProfile> profiles = ProfileStore.load(context);
        for (SpoofProfile profile : profiles) {
            if (!profile.getApps().isEmpty()
                && IptablesManager.extractProxyForProfile(profile) != null) {
                return true;
            }
        }
        return false;
    }
}
