
package com.example.ziblocks;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ImageView splashOverlay;

    private CameraManager cameraManager;
    private String cameraId;

    private static final long TEMPO_MINIMO_SPLASH = 2000;

    private long inicioSplash;
    private boolean splashRemovida = false;

    // =====================================================
    // TRANSMISSÃO
    // =====================================================

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private volatile boolean transmitting = false;

    private ActivityResultLauncher<String> cameraPermissionLauncher;

    private String codigoPendente;
    private String acaoPendente;

    // Protocolo de transmissão: 30 ms por bit.

    private static final long BIT_1_HIGH_NS = 5_000_000L;
    private static final long BIT_1_LOW_NS = 25_000_000L;

    private static final long BIT_0_HIGH_NS = 15_000_000L;
    private static final long BIT_0_LOW_NS = 15_000_000L;

    private static final long TEMPO_INICIAL_NS = 400_000_000L;
    private static final long TEMPO_GRUPO_NS = 50_000_000L;


    // =====================================================
    // COMUNICAÇÃO JAVASCRIPT / ANDROID
    // =====================================================

    private class AndroidBridge {

        @JavascriptInterface
        public void showToast(String mensagem) {

            runOnUiThread(() -> Toast.makeText(
                    MainActivity.this,
                    mensagem,
                    Toast.LENGTH_SHORT
            ).show());

        }

        @JavascriptInterface
        public void transmitir(String codigo, String acao) {

            runOnUiThread(() ->
                    solicitarOuIniciarTransmissao(codigo, acao)
            );

        }

    }


    // =====================================================
    // INICIALIZAÇÃO
    // =====================================================

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        inicioSplash = System.currentTimeMillis();

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        splashOverlay = findViewById(R.id.splashOverlay);

        ImageButton btnToolbox = findViewById(R.id.btnToolbox);

        // Gerenciador da câmera.

        cameraManager = (CameraManager)
                getSystemService(Context.CAMERA_SERVICE);


        // =================================================
        // PERMISSÃO DA CÂMERA
        // =================================================

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),

                granted -> {

                    if (granted) {

                        if (prepararCamera()) {

                            if (codigoPendente != null) {

                                String codigo = codigoPendente;
                                String acao = acaoPendente;

                                codigoPendente = null;
                                acaoPendente = null;

                                iniciarTransmissao(codigo, acao);

                            }

                        } else {

                            notificarErro(
                                    acaoPendente,
                                    "Flash traseiro indisponível."
                            );

                            codigoPendente = null;
                            acaoPendente = null;

                        }

                    } else {

                        notificarErro(
                                acaoPendente,
                                "A permissão da câmera é necessária para transmitir."
                        );

                        codigoPendente = null;
                        acaoPendente = null;

                    }

                }
        );


        // =================================================
        // BOTÃO VOLTAR
        // =================================================

        getOnBackPressedDispatcher().addCallback(this,

                new OnBackPressedCallback(true) {

                    @Override
                    public void handleOnBackPressed() {

                        if (webView.canGoBack()) {

                            webView.goBack();

                        } else {

                            setEnabled(false);

                            getOnBackPressedDispatcher()
                                    .onBackPressed();

                        }

                    }

                }
        );


        // =================================================
        // WEBVIEW ASSET LOADER
        // =================================================

        WebViewAssetLoader assetLoader =
                new WebViewAssetLoader.Builder()
                        .addPathHandler(
                                "/assets/",
                                new WebViewAssetLoader.AssetsPathHandler(this)
                        )
                        .build();


        // =================================================
        // CONFIGURAÇÕES DO WEBVIEW
        // =================================================

        WebSettings webSettings = webView.getSettings();

        webSettings.setJavaScriptEnabled(true);

        webSettings.setMediaPlaybackRequiresUserGesture(false);


        // Disponibiliza AndroidBridge ao JavaScript.

        webView.addJavascriptInterface(
                new AndroidBridge(),
                "AndroidBridge"
        );


        // =================================================
        // CLIENTE DO WEBVIEW
        // =================================================

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request) {

                return assetLoader.shouldInterceptRequest(
                        request.getUrl()
                );

            }


            @Override
            public void onPageFinished(WebView view, String url) {

                super.onPageFinished(view, url);

                long tempoDecorrido =
                        System.currentTimeMillis() - inicioSplash;

                long tempoRestante = Math.max(
                        0,
                        TEMPO_MINIMO_SPLASH - tempoDecorrido
                );

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


        // =================================================
        // BOTÃO ANDROID DA TOOLBOX
        // =================================================

        btnToolbox.setOnClickListener(v -> {

            webView.evaluateJavascript(
                    "alternarToolbox()",
                    null
            );

        });


        // =================================================
        // CARREGAMENTO DO BLOCKLY
        // =================================================

        webView.loadUrl(
                "https://appassets.androidplatform.net/assets/blockly.html"
        );

    }


    // =====================================================
    // SOLICITAR PERMISSÃO OU INICIAR
    // =====================================================

    private void solicitarOuIniciarTransmissao(
            String codigo,
            String acao) {

        if (transmitting) {

            notificarErro(
                    acao,
                    "Já existe uma transmissão em andamento."
            );

            return;

        }

        if (!"conectar".equals(acao) &&
                !"carregar".equals(acao)) {

            notificarErro(acao, "Ação não reconhecida.");

            return;

        }

        if (codigo == null ||
                !codigo.matches("[01 ]+") ||
                codigo.trim().isEmpty()) {

            notificarErro(acao, "Sequência binária inválida.");

            return;

        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED) {

            codigoPendente = codigo;
            acaoPendente = acao;

            cameraPermissionLauncher.launch(
                    Manifest.permission.CAMERA
            );

            return;

        }

        if (!prepararCamera()) {

            notificarErro(
                    acao,
                    "Não foi encontrada uma câmera traseira com flash."
            );

            return;

        }

        iniciarTransmissao(codigo, acao);

    }


    // =====================================================
    // IDENTIFICAR CÂMERA TRASEIRA COM FLASH
    // =====================================================

    private boolean prepararCamera() {

        try {

            cameraId = null;

            for (String id : cameraManager.getCameraIdList()) {

                CameraCharacteristics characteristics =
                        cameraManager.getCameraCharacteristics(id);

                Boolean temFlash = characteristics.get(
                        CameraCharacteristics.FLASH_INFO_AVAILABLE
                );

                Integer orientacao = characteristics.get(
                        CameraCharacteristics.LENS_FACING
                );

                if (Boolean.TRUE.equals(temFlash)
                        && orientacao != null
                        && orientacao == CameraCharacteristics.LENS_FACING_BACK) {

                    cameraId = id;

                    return true;

                }

            }

        } catch (CameraAccessException e) {

            Log.e("CAMERA", "Erro ao identificar câmera.", e);

        }

        return false;

    }


    // =====================================================
    // INICIAR TRANSMISSÃO
    // =====================================================

    private void iniciarTransmissao(
            String codigo,
            String acao) {

        if (transmitting) return;

        final String[] grupos =
                codigo.trim().split("\\s+");

        final String idCamera = cameraId;

        transmitting = true;

        notificarJavaScript(
                "ziblocksTransmissaoIniciada",
                acao,
                null
        );


        executor.execute(() -> {

            String erro = null;

            try {

                // Espera inicial de 400 ms.

                esperarAte(
                        System.nanoTime() + TEMPO_INICIAL_NS
                );


                // Transmissão dos grupos.

                for (int g = 0; g < grupos.length; g++) {

                    String grupo = grupos[g];

                    long inicioGrupo = System.nanoTime();

                    long prazo = inicioGrupo;


                    for (int i = 0; i < grupo.length(); i++) {

                        char bit = grupo.charAt(i);

                        long highNs = bit == '1'
                                ? BIT_1_HIGH_NS
                                : BIT_0_HIGH_NS;

                        long lowNs = bit == '1'
                                ? BIT_1_LOW_NS
                                : BIT_0_LOW_NS;


                        // Início do pulso.

                        esperarAte(prazo);

                        cameraManager.setTorchMode(
                                idCamera,
                                true
                        );


                        // Final do HIGH.

                        prazo += highNs;

                        esperarAte(prazo);

                        cameraManager.setTorchMode(
                                idCamera,
                                false
                        );


                        // Final do período de 30 ms.

                        prazo += lowNs;

                        esperarAte(prazo);

                    }


                    // Pausa entre grupos.

                    if (g < grupos.length - 1) {

                        cameraManager.setTorchMode(
                                idCamera,
                                false
                        );

                        prazo += TEMPO_GRUPO_NS;

                        esperarAte(prazo);

                    }

                }


            } catch (CameraAccessException e) {

                erro = "Erro ao controlar o flash da câmera.";

                Log.e("FLASH", "Erro na transmissão.", e);

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                erro = "Transmissão interrompida.";

            } catch (RuntimeException e) {

                erro = "Falha inesperada durante a transmissão.";

                Log.e("FLASH", "Erro inesperado.", e);

            } finally {

                // Garantir flash desligado.

                try {

                    cameraManager.setTorchMode(
                            idCamera,
                            false
                    );

                } catch (Exception ignored) {
                }


                final String mensagemErro = erro;

                mainHandler.post(() -> {

                    transmitting = false;

                    if (mensagemErro == null) {

                        notificarJavaScript(
                                "ziblocksTransmissaoConcluida",
                                acao,
                                null
                        );

                    } else {

                        notificarErro(
                                acao,
                                mensagemErro
                        );

                    }

                });

            }

        });

    }


    // =====================================================
    // CONTROLE PRECISO DOS INTERVALOS
    // =====================================================

    private static void esperarAte(long prazoNs)
            throws InterruptedException {

        while (true) {

            if (Thread.currentThread().isInterrupted()) {

                throw new InterruptedException();

            }

            long restante =
                    prazoNs - System.nanoTime();

            if (restante <= 0) {

                return;

            }

            if (restante > 2_000_000L) {

                long dormirNs = restante - 1_000_000L;

                long ms = dormirNs / 1_000_000L;

                int ns = (int) (dormirNs % 1_000_000L);

                Thread.sleep(ms, ns);

            } else {

                LockSupport.parkNanos(restante);

            }

        }

    }


    // =====================================================
    // RETORNO PARA JAVASCRIPT
    // =====================================================

    private void notificarErro(
            String acao,
            String mensagem) {

        notificarJavaScript(
                "ziblocksTransmissaoErro",
                acao,
                mensagem
        );

    }


    private void notificarJavaScript(
            String funcao,
            String acao,
            String mensagem) {

        if (webView == null || acao == null) return;

        String javascript =
                funcao + "(" +
                        JSONObject.quote(acao) + "," +
                        JSONObject.quote(mensagem == null ? "" : mensagem) +
                        ")";

        webView.evaluateJavascript(
                javascript,
                null
        );

    }


    // =====================================================
    // ENCERRAMENTO
    // =====================================================

    @Override
    protected void onDestroy() {

        executor.shutdownNow();

        super.onDestroy();

    }

}
