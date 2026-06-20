package com.devicespooflab.hooks.proxy;

public final class ProxyConfig {
    public final String host;
    public final int port;
    public final String user;
    public final String password;

    public ProxyConfig(String host, int port, String user, String password) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
    }

    public boolean hasAuth() {
        return user != null && !user.isEmpty();
    }
}
