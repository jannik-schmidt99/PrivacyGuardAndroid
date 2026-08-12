package com.example.privacyguard;

public final class HevTunnel {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private HevTunnel() {}

    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
