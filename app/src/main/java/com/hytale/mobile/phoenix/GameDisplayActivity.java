package com.hytale.mobile.phoenix;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.winlator.WinlatorDisplay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class GameDisplayActivity extends Activity {
    static final String EXTRA_EXECUTABLE_PATH = "phoenix.executable_path";
    static final String EXTRA_ARGUMENTS = "phoenix.arguments";
    private static final String TAG = "GameDisplayActivity";

    private WinlatorDisplay display;
    private Process gameProcess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemBars();

        String executablePath = getIntent().getStringExtra(EXTRA_EXECUTABLE_PATH);
        ArrayList<String> arguments = getIntent().getStringArrayListExtra(EXTRA_ARGUMENTS);
        if (executablePath == null || arguments == null) {
            showLaunchError("Faltan datos para iniciar el juego.");
            return;
        }

        try {
            display = new WinlatorDisplay(this, 1280, 720);
            setContentView(display.getView());
            display.start();

            Box64Runtime runtime = new Box64Runtime(getApplicationContext());
            gameProcess = runtime.launchExeInAppStorage(
                    executablePath, arguments, display.getDisplayAddress());
            monitorGameProcess(gameProcess);
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "No se pudo iniciar el juego con el servidor X11.", error);
            showLaunchError(error.getMessage() == null
                    ? "No se pudo iniciar el juego."
                    : error.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (display != null) display.onResume();
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        if (display != null) display.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Process process = gameProcess;
        if (process != null && process.isAlive()) {
            Thread shutdownThread = new Thread(() -> {
                process.destroy();
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }, "phoenix-game-shutdown");
            shutdownThread.setDaemon(true);
            shutdownThread.start();
        }
        if (display != null) display.close();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (display != null && display.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private void monitorGameProcess(Process process) {
        Thread processWatcher = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    Log.e(TAG, "El proceso del juego terminó con código " + exitCode + ".");
                }
                runOnUiThread(() -> {
                    if (!isFinishing()) finish();
                });
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Se interrumpió la espera del proceso del juego.", error);
            }
        }, "phoenix-game-watch");
        processWatcher.setDaemon(true);
        processWatcher.start();
    }

    private void showLaunchError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
