package com.winlator.core;

public final class GPUHelper {
    static {
        System.loadLibrary("winlator");
    }

    private GPUHelper() {}

    public static native void setGlobalEGLContext();

    public static native long createOffscreenEGLContext(boolean sharedContext);

    public static native void destroyOffscreenEGLContext(long contextPtr);
}
