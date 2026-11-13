package com.hytale.mobile.phoenix;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {

    private WebView webView;
    private Box64Runtime box64Runtime;

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

        // Add JavaScript Interface (Electron-like IPC Bridge)
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        setContentView(webView);

        // Load web app from assets folder: assets/www/index.html
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    // JavaScript Bridge Class (Electron IPC equivalent)
    public class WebAppInterface {
        private final Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
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
