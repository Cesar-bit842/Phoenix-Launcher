package com.hytale.mobile.phoenix;

import com.google.gson.Gson;
import com.hytale.mobile.phoenix.auth.HytaleAuthLibrary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends Activity {
    Gson gson = new Gson();
    private WebView webView;
    private Box64Runtime box64Runtime;
    private final HytaleAuthLibrary hytaleAuth = new HytaleAuthLibrary("Phoenix");

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        box64Runtime = new Box64Runtime(getApplicationContext());


        // Setup WebView shell
        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        // Add JavaScript Interface (Electron-like IPC Bridge)
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        FrameLayout content = new FrameLayout(this);
        content.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
                androidx.core.graphics.Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        setContentView(content);
        ViewCompat.requestApplyInsets(content);

        // Load web app from assets folder: assets/www/index.html
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    /**
     * Guarda el contenido JSON en "config.json" usando InternalStorageUtils.
     */
    public boolean saveConfigJson(String jsonContent) {
        return InternalStorageUtils.writeFile(this, "config.json", jsonContent, false);
    }

    /**
     * Lee el contenido JSON de "config.json" usando InternalStorageUtils.
     * Si no existe, crea uno por defecto.
     */
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

    // JavaScript Bridge Class (Electron IPC equivalent)
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
            System.out.println("is exist?: " + ConfigExist());
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
            hytaleAuth.login(MainActivity.this,
                    new HytaleAuthLibrary.Callback<HytaleAuthLibrary.AuthTokens>() {
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
        public String readConfig() {
            return readConfigJson();
        }

        @JavascriptInterface
        public void showToast(String toast) {
            Toast.makeText(mContext, toast, Toast.LENGTH_SHORT).show();
        }

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
    }
}
