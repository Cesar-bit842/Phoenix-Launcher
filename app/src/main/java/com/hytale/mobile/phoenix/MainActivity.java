package com.hytale.mobile.phoenix;

import com.google.gson.Gson;
import com.hytale.mobile.phoenix.auth.HytaleAuthLibrary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_SELECT_VERSION_FOLDER = 4102;

    Gson gson = new Gson();
    private WebView webView;
    private Box64Runtime box64Runtime;
    private JavaRuntimeInstaller javaRuntimeInstaller;
    private HytaleVersionManager hytaleVersionManager;
    private final HytaleAuthLibrary hytaleAuth = new HytaleAuthLibrary("Phoenix");
    private boolean versionImportInProgress;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        box64Runtime = new Box64Runtime(getApplicationContext());
        javaRuntimeInstaller = new JavaRuntimeInstaller(getApplicationContext());
        hytaleVersionManager = new HytaleVersionManager(getApplicationContext());


        // Setup WebView shell
        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true); 
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(webView);
        hideSystemBars();

        // Load web app from assets folder: assets/www/index.html
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    public Process launchHytaleClient(
            String versionName,
            String playerName,
            String authMode,
            String sessionUuid,
            String identityToken,
            String sessionToken) throws IOException {
        List<String> arguments = buildGameLaunchArguments(
                versionName, playerName, authMode, sessionUuid, identityToken, sessionToken);
        if (!box64Runtime.isInstalled()) {
            throw new IllegalStateException("Instala Box64 antes de iniciar el juego.");
        }
        javaRuntimeInstaller.ensureJavaRuntimeInstalled(null);
        String executablePath = "Versions/" + versionName + "/Client/HytaleClient.exe";
        return box64Runtime.launchExeInAppStorage(executablePath, arguments);
    }

    public void launchHytaleClientWithDisplay(
            String versionName,
            String playerName,
            String authMode,
            String sessionUuid,
            String identityToken,
            String sessionToken) throws IOException {
        List<String> arguments = buildGameLaunchArguments(
                versionName, playerName, authMode, sessionUuid, identityToken, sessionToken);
        Intent intent = new Intent(this, GameDisplayActivity.class);
        intent.putExtra(GameDisplayActivity.EXTRA_EXECUTABLE_PATH,
                "Versions/" + versionName + "/Client/HytaleClient.exe");
        intent.putStringArrayListExtra(
                GameDisplayActivity.EXTRA_ARGUMENTS, new ArrayList<>(arguments));
        runOnUiThread(() -> startActivity(intent));
    }

    private List<String> buildGameLaunchArguments(
            String versionName,
            String playerName,
            String authMode,
            String sessionUuid,
            String identityToken,
            String sessionToken) throws IOException {
        HytaleVersionManager.validateVersionName(versionName);
        if (box64Runtime == null) {
            throw new IllegalStateException("El runtime de Box64 todavía no está inicializado.");
        }

        File versionDirectory = hytaleVersionManager.resolveVersionDirectory(versionName);
        File userDirectory = createUserDataDirectory(versionName, sessionUuid);
        return buildHytaleClientArguments(
                versionName,
                AppStoragePaths.toWinePath(versionDirectory),
                AppStoragePaths.toWinePath(userDirectory),
                javaRuntimeInstaller.getJavaExecutableWinePath(),
                playerName,
                authMode,
                sessionUuid,
                identityToken,
                sessionToken);
    }

    private File createUserDataDirectory(String versionName, String sessionUuid)
            throws IOException {
        requireLaunchArgument("sessionUuid", sessionUuid);
        try {
            UUID.fromString(sessionUuid);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("sessionUuid debe ser un UUID válido.", error);
        }

        File userDirectory = new File(
                new File(new File(getFilesDir(), "UserData"), versionName),
                sessionUuid);
        File canonicalRoot = getFilesDir().getCanonicalFile();
        File canonicalUserDirectory = userDirectory.getCanonicalFile();
        if (!canonicalUserDirectory.toPath().startsWith(canonicalRoot.toPath())) {
            throw new IOException("La carpeta de datos del usuario sale del almacenamiento privado.");
        }
        if (!canonicalUserDirectory.isDirectory() && !canonicalUserDirectory.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta de datos del usuario.");
        }
        return canonicalUserDirectory;
    }

    public List<String> getVersionDirectoryNames() {
        return hytaleVersionManager.getVersionDirectoryNames();
    }

    private static List<String> buildHytaleClientArguments(
            String versionName,
            String appDirectory,
            String userDirectory,
            String javaExecutable,
            String playerName,
            String authMode,
            String sessionUuid,
            String identityToken,
            String sessionToken) {
        HytaleVersionManager.validateVersionName(versionName);
        requireLaunchArgument("appDirectory", appDirectory);
        requireLaunchArgument("userDirectory", userDirectory);
        requireLaunchArgument("javaExecutable", javaExecutable);
        requireLaunchArgument("playerName", playerName);
        requireLaunchArgument("sessionUuid", sessionUuid);
        try {
            UUID.fromString(sessionUuid);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("sessionUuid debe ser un UUID válido.", error);
        }
        if (!"offline".equals(authMode) && !"authenticated".equals(authMode)) {
            throw new IllegalArgumentException("authMode debe ser 'offline' o 'authenticated'.");
        }
        if ("authenticated".equals(authMode)) {
            requireLaunchArgument("identityToken", identityToken);
            requireLaunchArgument("sessionToken", sessionToken);
        }

        List<String> arguments = new ArrayList<>();
        arguments.add("--app-dir");
        arguments.add(appDirectory);
        arguments.add("--user-dir");
        arguments.add(userDirectory);
        arguments.add("--java-exec");
        arguments.add(javaExecutable);
        arguments.add("--name");
        arguments.add(playerName);
        arguments.add("--auth-mode");
        arguments.add(authMode);
        arguments.add("--uuid");
        arguments.add(sessionUuid);
        if ("authenticated".equals(authMode)) {
            arguments.add("--identity-token");
            arguments.add(identityToken);
            arguments.add("--session-token");
            arguments.add(sessionToken);
        }
        return arguments;
    }

    private static void requireLaunchArgument(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " no puede estar vacío.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SELECT_VERSION_FOLDER) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            versionImportInProgress = false;
            notifyVersionImport(false, true, null, "Selección de carpeta cancelada.");
            return;
        }

        Uri sourceTreeUri = data.getData();
        int permissionFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (permissionFlags == 0
                || (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
            versionImportInProgress = false;
            notifyVersionImport(
                    false, false, null, "Android no concedió permiso persistente para la carpeta.");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(sourceTreeUri, permissionFlags);
        } catch (SecurityException exception) {
            versionImportInProgress = false;
            notifyVersionImport(
                    false, false, null, "No se pudo guardar el permiso para leer la carpeta seleccionada.");
            return;
        }

        new Thread(() -> {
            String copiedPath = null;
            String error = null;
            try {
                copiedPath = VersionImportUtils.copyVersion(getApplicationContext(), sourceTreeUri);
            } catch (IOException | RuntimeException exception) {
                error = exception.getMessage() == null
                        ? "Ocurrió un error al copiar la carpeta."
                        : exception.getMessage();
            } finally {
                try {
                    getContentResolver().releasePersistableUriPermission(
                            sourceTreeUri, permissionFlags);
                } catch (SecurityException exception) {
                    String releaseError = "No se pudo liberar el permiso de la carpeta seleccionada.";
                    error = error == null ? releaseError : error + " " + releaseError;
                }
            }

            String completedPath = copiedPath;
            String completedError = error;
            runOnUiThread(() -> {
                versionImportInProgress = false;
                if (completedPath == null) {
                    notifyVersionImport(false, false, null, completedError);
                } else {
                    notifyVersionImport(
                            true,
                            false,
                            completedPath,
                            completedError == null
                                    ? "Carpeta copiada correctamente."
                                    : "Carpeta copiada. " + completedError);
                }
            });
        }, "version-folder-import").start();
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void notifyVersionImport(
            boolean success,
            boolean cancelled,
            String path,
            String message) {
        String result = "{\"success\":" + success
                + ",\"cancelled\":" + cancelled
                + ",\"path\":" + (path == null ? "null" : JSONObject.quote(path))
                + ",\"message\":" + JSONObject.quote(message) + "}";
        webView.post(() -> webView.evaluateJavascript(
                "if (window.onVersionImportResult) window.onVersionImportResult(" + result + ");",
                null));
    }

    public boolean saveConfigJson(String jsonContent) {
        return InternalStorageUtils.writeFile(this, "config.json", jsonContent, false);
    }
    public String readConfigJson() {
        if (!InternalStorageUtils.exists(this, "config.json")) {
            InternalStorageUtils.createFile(this, "config.json");
            InternalStorageUtils.writeFile(this, "config.json", "{\"version\":\"1.0.0\",\"theme\":\"phoenix\",\"profile\":\"Default\"}", false);
        }
        return InternalStorageUtils.readFile(this, "config.json");
    }
    public boolean ConfigExist(){
        return InternalStorageUtils.exists(this, "config.json");
    }
 
    public class WebAppInterface {
        private final Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void saveConfig(String jsonContent) {
            boolean success = saveConfigJson(jsonContent);
        }

        @JavascriptInterface
        public boolean ConfigurationFileExists() { 
            return ConfigExist();
        }

        @JavascriptInterface
        public void AuthorizedHytaleAccount() {
            if (hytaleAuth.hasSavedSession(MainActivity.this)) {
                hytaleAuth.restoreSession(MainActivity.this, new HytaleAuthLibrary.Callback<HytaleAuthLibrary.AuthTokens>() {
                    @Override
                    public void onSuccess(HytaleAuthLibrary.AuthTokens tokens) {
                        webView.evaluateJavascript("window.onHytaleAuthResult(true)", null);
                    }

                    @Override
                    public void onError(Exception error) {
                        webView.evaluateJavascript("window.onHytaleAuthResult(false)", null);
                    }
                });
            } else {
                webView.evaluateJavascript("window.onHytaleAuthResult(false)", null);
            }
        }

        @JavascriptInterface
        public boolean hasSavedSession() {
            return hytaleAuth.hasSavedSession(MainActivity.this);
        }

        @JavascriptInterface
        public void loginHytale() {
            hytaleAuth.login(MainActivity.this, new HytaleAuthLibrary.Callback<HytaleAuthLibrary.AuthTokens>() {
                @Override
                public void onSuccess(HytaleAuthLibrary.AuthTokens tokens) {
                    System.out.println("token: " + tokens.getAccessToken());
                    webView.evaluateJavascript("window.onHytaleLoginResult(true)", null);
                }

                @Override
                public void onError(Exception error) {
                    System.out.println("error: " + error.getMessage());
                    webView.evaluateJavascript("window.onHytaleLoginResult(false)", null);
                }
            });
        }



        @JavascriptInterface
        public void selectAndCopyVersion() {
            installBox64();
            runOnUiThread(() -> {
                if (versionImportInProgress) {
                    notifyVersionImport(
                            false, false, null, "Ya hay una importación de carpeta en curso.");
                    return;
                }
                versionImportInProgress = true;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(intent, REQUEST_SELECT_VERSION_FOLDER);
                } catch (RuntimeException exception) {
                    versionImportInProgress = false;
                    String message = exception.getMessage() == null
                            ? "No se pudo abrir el selector de carpetas."
                            : exception.getMessage();
                    notifyVersionImport(false, false, null, message);
                }
            });
        }

        @JavascriptInterface
        public void installBox64() {
            try {
                box64Runtime.install();
                Toast.makeText(mContext, "Box64 instalado con éxito", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(mContext, "Error instalando Box64: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        @JavascriptInterface
        public void showToast(String toast) {
            Toast.makeText(mContext, toast, Toast.LENGTH_SHORT).show();
        }

        @JavascriptInterface
        public String getVersionDirectoryNames() {
            return gson.toJson(MainActivity.this.getVersionDirectoryNames());
        }

        @JavascriptInterface
        public String readConfig() {
            return readConfigJson();
        }

        @JavascriptInterface
        public void launchGame(
                String versionName,
                String playerName) {
            try {
                installBox64();
                launchHytaleClientWithDisplay(
                        versionName,
                        playerName,
                        "offline",
                        UUID.randomUUID().toString(),
                        "",
                        "");
            } catch (IOException | RuntimeException error) {
                String message = error.getMessage() == null
                        ? "No se pudo preparar el inicio del juego."
                        : error.getMessage();
                Log.e(TAG, "No se pudo iniciar Hytale.", error);
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    webView.evaluateJavascript(
                            "if (window.onGameLaunchError) window.onGameLaunchError("
                                    + JSONObject.quote(message) + ");",
                            null);
                });
            }
        }
    }
}
/*
@JavascriptInterface
public String checkBox64Installed() {
    try {
        return box64Runtime.runVersion();
    } catch (Exception e) {
        return "error execute action";
    }
}

@JavascriptInterface
public void installBox64() {
    try {
        box64Runtime.install();
        Toast.makeText(mContext, "Box64 instalado con éxito", Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
        Toast.makeText(mContext, "Error instalando Box64: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
}
@JavascriptInterface
public void showToast(String toast) {
    Toast.makeText(mContext, toast, Toast.LENGTH_SHORT).show();
} 
*/