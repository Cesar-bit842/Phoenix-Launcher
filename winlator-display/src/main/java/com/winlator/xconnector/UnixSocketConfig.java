// Modified by Phoenix Launcher on 2026-11-14: added loopback TCP display endpoints.
package com.winlator.xconnector;

public class UnixSocketConfig {
    public static final String SYSVSHM_SERVER_PATH = "/tmp/.sysvshm/SM0";
    public static final String ALSA_SERVER_PATH = "/tmp/.sound/AS0";
    public static final String PULSE_SERVER_PATH = "/tmp/.sound/PS0";
    public static final String XSERVER_PATH = "/tmp/.X11-unix/X0";
    public static final String VIRGL_SERVER_PATH = "/tmp/.virgl/V0";
    public static final String VORTEK_SERVER_PATH = "/tmp/.vortek/V0";
    public final String path;

    private UnixSocketConfig(String path) {
        this.path = path;
    }

    public static UnixSocketConfig tcpLoopback(int port) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("El puerto debe estar entre 1024 y 65535.");
        }
        return new UnixSocketConfig("tcp:" + port);
    }
}
