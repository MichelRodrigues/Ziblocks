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
import android.widget.ImageView;
import android.widget.Toast;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ImageView splashOverlay;

    // Tempo mínimo de exibição da splash (2 segundos).
    private static final long TEMPO_MINIMO_SPLASH = 2000;

    private long inicioSplash;
    private boolean splashRemovida = false;

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

        super.onCreate(savedInstanceState);

        // Registra o início da splash.
        inicioSplash = System.currentTimeMillis();

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        splashOverlay = findViewById(R.id.splashOverlay);

        ImageButton btnToolbox = findViewById(R.id.btnToolbox);

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
            public void onPageFinished(WebView view, String url) {

                super.onPageFinished(view, url);

                // Calcula quanto tempo a splash já ficou visível.
                long tempoDecorrido =
                        System.currentTimeMillis() - inicioSplash;

                // Calcula quanto falta para completar 2 segundos.
                long tempoRestante = Math.max(
                        0,
                        TEMPO_MINIMO_SPLASH - tempoDecorrido
                );

                // Remove a splash após o tempo mínimo.
                splashOverlay.postDelayed(() -> {

                    if (!splashRemovida) {

                        splashRemovida = true;

                        splashOverlay.setVisibility(View.GONE);

                    }

                }, tempoRestante);

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
