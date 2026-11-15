package com.winlator;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.XClientConnectionHandler;
import com.winlator.xserver.XClientRequestHandler;
import com.winlator.xserver.XServer;
import com.winlator.widget.XServerView;

public final class WinlatorDisplay implements AutoCloseable {
    private static final int FIRST_X11_PORT = 6000;

    private final int displayNumber;
    private final ScreenInfo screenInfo;
    private final XServer xServer;
    private final XServerView view;
    private final XConnectorEpoll connector;
    private boolean started;
    private boolean closed;
    private boolean touchDown;

    public WinlatorDisplay(Context context, int width, int height) {
        this(context, width, height, 0);
    }

    public WinlatorDisplay(Context context, int width, int height, int displayNumber) {
        if (context == null) throw new IllegalArgumentException("context no puede ser null.");
        if (width < ScreenInfo.MIN_WIDTH || height < ScreenInfo.MIN_HEIGHT) {
            throw new IllegalArgumentException("La resolución del display es demasiado pequeña.");
        }
        if (displayNumber < 0 || displayNumber > 65535 - FIRST_X11_PORT) {
            throw new IllegalArgumentException("El número de display no es válido.");
        }

        this.displayNumber = displayNumber;
        this.screenInfo = new ScreenInfo(width, height);
        this.xServer = new XServer(screenInfo);
        this.view = new XServerView(context, xServer);
        this.connector = new XConnectorEpoll(
                UnixSocketConfig.tcpLoopback(FIRST_X11_PORT + displayNumber),
                new XClientConnectionHandler(xServer),
                new XClientRequestHandler());
        view.setOnTouchListener(this::handleTouch);
    }

    public XServerView getView() {
        ensureOpen();
        return view;
    }

    public String getDisplayAddress() {
        return "127.0.0.1:" + displayNumber;
    }

    public void start() {
        ensureOpen();
        if (started) return;
        connector.start();
        started = true;
    }

    public void onResume() {
        ensureOpen();
        view.onResume();
    }

    public void onPause() {
        if (!closed) view.onPause();
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        ensureOpen();
        return xServer.keyboard.onKeyEvent(event);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (touchDown) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
            touchDown = false;
        }
        connector.destroy();
        view.onPause();
    }

    private boolean handleTouch(android.view.View target, MotionEvent event) {
        if (closed || target.getWidth() == 0 || target.getHeight() == 0) return true;

        int x = Math.round(event.getX() * screenInfo.width / target.getWidth());
        int y = Math.round(event.getY() * screenInfo.height / target.getHeight());
        xServer.injectPointerMove(x, y);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDown = true;
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                return true;
            case MotionEvent.ACTION_MOVE:
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touchDown) {
                    touchDown = false;
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                }
                return true;
            default:
                return true;
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("El display ya está cerrado.");
    }
}
