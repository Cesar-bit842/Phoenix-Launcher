package com.hytale.mobile.phoenix;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
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
    private volatile Process gameProcess;
    private volatile boolean destroyed;
    private TextView launchStatus;
    private LinearLayout launchProgress;

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
            FrameLayout root = new FrameLayout(this);
            root.addView(display.getView());
            launchProgress = createLaunchProgress();
            FrameLayout.LayoutParams progressLayout = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            root.addView(launchProgress, progressLayout);
            setContentView(root);
            display.start();
        } catch (RuntimeException error) {
            Log.e(TAG, "No se pudo iniciar la pantalla del juego.", error);
            showLaunchError(error.getMessage() == null
                    ? "No se pudo iniciar la pantalla del juego."
                    : error.getMessage());
            return;
        }

        Box64Runtime runtime = new Box64Runtime(getApplicationContext());
        JavaRuntimeInstaller javaRuntimeInstaller =
                new JavaRuntimeInstaller(getApplicationContext());
        Thread launchThread = new Thread(() -> {
            try {
                if (!runtime.isInstalled()) {
                    throw new IllegalStateException("Instala Box64 antes de iniciar el juego.");
                }
                javaRuntimeInstaller.ensureJavaRuntimeInstalled(this::updateLaunchStatus);
                if (destroyed) return;
                Process process = runtime.launchExeInAppStorage(
                        executablePath, arguments, display.getDisplayAddress());
                gameProcess = process;
                if (destroyed) {
                    process.destroy();
                    return;
                }
                runOnUiThread(() -> {
                    if (launchProgress != null) launchProgress.setVisibility(View.GONE);
                });
                monitorGameProcess(process);
            } catch (IOException | RuntimeException error) {
                Log.e(TAG, "No se pudo preparar Java o iniciar el juego.", error);
                runOnUiThread(() -> {
                    if (!destroyed) {
                        showLaunchError(error.getMessage() == null
                                ? "No se pudo preparar Java o iniciar el juego."
                                : error.getMessage());
                    }
                });
            }
        }, "phoenix-game-launch");
        launchThread.setDaemon(true);
        launchThread.start();
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
        destroyed = true;
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

    private LinearLayout createLaunchProgress() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(25, 28, 35));
        background.setCornerRadius(16 * getResources().getDisplayMetrics().density);
        container.setBackground(background);

        ProgressBar progressBar = new ProgressBar(this);
        container.addView(progressBar);

        launchStatus = new TextView(this);
        launchStatus.setText("Preparando Java para Wine...");
        launchStatus.setTextColor(Color.WHITE);
        launchStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLayout.topMargin = Math.round(16 * getResources().getDisplayMetrics().density);
        container.addView(launchStatus, statusLayout);
        return container;
    }

    private void updateLaunchStatus(String status) {
        runOnUiThread(() -> {
            if (!destroyed && launchStatus != null) launchStatus.setText(status);
        });
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
