
package com.example.ziblocks;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.JavascriptInterface;
import android.widget.ImageButton;
import android.widget.Toast;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;
import androidx.core.splashscreen.SplashScreen;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ImageButton btnToolbox;

    // Interface para comunicação entre JavaScript e Android.
    private class AndroidBridge {

        @JavascriptInterface
        public void showToast(String mensagem) {

            runOnUiThread(() -> {

                Toast.makeText(
                        MainActivity.this,
                        mensagem,
                        Toast.LENGTH_SHORT
                ).show();

            });

        }

    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        btnToolbox = findViewById(R.id.btnToolbox);

        // Configura o carregamento dos arquivos locais por HTTPS.
        WebViewAssetLoader assetLoader =
                new WebViewAssetLoader.Builder()
                        .addPathHandler(
                                "/assets/",
                                new WebViewAssetLoader.AssetsPathHandler(this)
                        )
                        .build();

        // Configurações do WebView.
        WebSettings webSettings = webView.getSettings();

        webSettings.setJavaScriptEnabled(true);

        webSettings.setMediaPlaybackRequiresUserGesture(false);

        // Disponibiliza a interface Android para o JavaScript.
        webView.addJavascriptInterface(
                new AndroidBridge(),
                "AndroidBridge"
        );

        // Cliente do WebView.
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request) {

                return assetLoader.shouldInterceptRequest(
                        request.getUrl()
                );
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error) {

                super.onReceivedError(view, request, error);

                Log.e(
                        "WEBVIEW",
                        request.getUrl().toString()
                                + " -> "
                                + error.getDescription()
                );
            }

        });

        // Botão para abrir e fechar a Toolbox.
        btnToolbox.setOnClickListener(v -> {

            webView.evaluateJavascript(
                    "alternarToolbox()",
                    null
            );

        });

        // Carrega o Blockly utilizando HTTPS virtual.
        webView.loadUrl(
                "https://appassets.androidplatform.net/assets/blockly.html"
        );

    }

    @Override
    public void onBackPressed() {

        if (webView.canGoBack()) {

            webView.goBack();

        } else {

            super.onBackPressed();

        }

    }

}
