package com.devicespooflab.hooks.proxy;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.devicespooflab.hooks.data.ProfileStore;
import com.devicespooflab.hooks.data.SpoofProfile;
import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class IptablesManager {

    private static final String TAG = "SpoofProxy";
    private static final String CHAIN = "SPOOFMYD";

    private IptablesManager() {}

    /** Remove all custom iptables chains created by this manager. */
    public static void reset() {
        List<String> cmds = new ArrayList<>();
        // Loop to remove all jump references (multiple starts may have inserted multiple times)
        cmds.add("while iptables -t nat -D OUTPUT -j " + CHAIN + " 2>/dev/null; do true; done");
        cmds.add("while iptables -D OUTPUT -j " + CHAIN + " 2>/dev/null; do true; done");
        cmds.add("iptables -t nat -F " + CHAIN + " 2>/dev/null; true");
        cmds.add("iptables -t nat -X " + CHAIN + " 2>/dev/null; true");
        cmds.add("iptables -F " + CHAIN + " 2>/dev/null; true");
        cmds.add("iptables -X " + CHAIN + " 2>/dev/null; true");
        runAsRoot(cmds);
    }

    /**
     * Rebuild iptables chains for all profiles that have a proxy configured.
     * profileLocalPorts maps profile ID → the local port where the relay listens.
     */
    public static void apply(Context context, Map<String, Integer> profileLocalPorts) {
        List<SpoofProfile> profiles = ProfileStore.load(context);
        PackageManager pm = context.getPackageManager();

        // Collect UIDs per local port (multiple profiles with same port aren't expected
        // but the structure handles it cleanly)
        Map<Integer, List<Integer>> portToUids = new HashMap<>();
        for (SpoofProfile profile : profiles) {
            if (profile.getApps().isEmpty()) continue;
            Integer localPort = profileLocalPorts.get(profile.getId());
            if (localPort == null) continue;

            List<Integer> uids = portToUids.computeIfAbsent(localPort, k -> new ArrayList<>());
            for (String pkg : profile.getApps()) {
                try {
                    int uid = pm.getApplicationInfo(pkg, 0).uid;
                    uids.add(uid);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }

        List<String> cmds = new ArrayList<>();

        // Always reset first for a clean slate
        cmds.add("while iptables -t nat -D OUTPUT -j " + CHAIN + " 2>/dev/null; do true; done");
        cmds.add("while iptables -D OUTPUT -j " + CHAIN + " 2>/dev/null; do true; done");
        cmds.add("iptables -t nat -F " + CHAIN + " 2>/dev/null; true");
        cmds.add("iptables -t nat -X " + CHAIN + " 2>/dev/null; true");
        cmds.add("iptables -F " + CHAIN + " 2>/dev/null; true");
        cmds.add("iptables -X " + CHAIN + " 2>/dev/null; true");

        boolean hasAny = false;
        for (List<Integer> uids : portToUids.values()) {
            if (!uids.isEmpty()) { hasAny = true; break; }
        }
        if (!hasAny) {
            runAsRoot(cmds);
            return;
        }

        cmds.add("iptables -t nat -N " + CHAIN);
        cmds.add("iptables -N " + CHAIN);
        cmds.add("iptables -t nat -I OUTPUT -j " + CHAIN);
        cmds.add("iptables -I OUTPUT -j " + CHAIN);

        // Bypass private/local ranges before UID rules (nat chain)
        cmds.add("iptables -t nat -A " + CHAIN + " -d 127.0.0.0/8    -j RETURN");
        cmds.add("iptables -t nat -A " + CHAIN + " -d 10.0.0.0/8     -j RETURN");
        cmds.add("iptables -t nat -A " + CHAIN + " -d 172.16.0.0/12  -j RETURN");
        cmds.add("iptables -t nat -A " + CHAIN + " -d 192.168.0.0/16 -j RETURN");
        cmds.add("iptables -t nat -A " + CHAIN + " -d 169.254.0.0/16 -j RETURN");
        cmds.add("iptables -t nat -A " + CHAIN + " -d 224.0.0.0/4    -j RETURN");
        cmds.add("iptables -t nat -A " + CHAIN + " -d 240.0.0.0/4    -j RETURN");

        // Per-UID rules
        for (Map.Entry<Integer, List<Integer>> entry : portToUids.entrySet()) {
            int localPort = entry.getKey();
            for (int uid : entry.getValue()) {
                // Redirect TCP to local relay
                cmds.add("iptables -t nat -A " + CHAIN
                    + " -p tcp -m owner --uid-owner " + uid
                    + " -j REDIRECT --to-ports " + localPort);
                // Block UDP
                cmds.add("iptables -A " + CHAIN
                    + " -p udp -m owner --uid-owner " + uid
                    + " -j REJECT");
            }
        }

        runAsRoot(cmds);
    }

    public static ProxyConfig extractProxyForProfile(SpoofProfile profile) {
        String text = profile.getPropertiesText();
        if (text == null || text.isEmpty()) return null;
        Map<String, String> props = new HashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                props.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        String enabled = props.get(ConfigManager.KEY_PROXY_ENABLED);
        if (!"true".equalsIgnoreCase(enabled) && !"1".equals(enabled)) return null;
        String host = props.get(ConfigManager.KEY_PROXY_HOST);
        if (host == null || host.trim().isEmpty()) return null;
        int port = 1080;
        try {
            port = Integer.parseInt(props.get(ConfigManager.KEY_PROXY_PORT));
        } catch (Exception ignored) {
        }
        return new ProxyConfig(
            host.trim(), port,
            props.get(ConfigManager.KEY_PROXY_USER),
            props.get(ConfigManager.KEY_PROXY_PASSWORD)
        );
    }

    private static void runAsRoot(List<String> commands) {
        if (commands.isEmpty()) return;
        StringBuilder script = new StringBuilder();
        for (String cmd : commands) {
            script.append(cmd).append('\n');
        }
        try {
            Process process = new ProcessBuilder("su", "-c", script.toString())
                .redirectErrorStream(true)
                .start();
            int exit = process.waitFor();
            if (exit != 0) {
                Log.w(TAG, "iptables script exited with " + exit);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to run iptables commands", e);
        }
    }
}
