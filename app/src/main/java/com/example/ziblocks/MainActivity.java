package com.example.ziblocks;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.text.LineBreaker;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private ActivityResultLauncher<String> storagePermissionLauncher;

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
    // ARQUIVOS ZIBLOCKS
    // =====================================================

    private static final String PASTA_PROGRAMAS = "Ziblocks";
    private static final String EXTENSAO = ".ziblocks";
    private static final String MIME_TYPE = "application/x-ziblocks";

    private String nomePendente;
    private String conteudoPendente;

    private enum AcaoArmazenamento {
        NENHUMA,
        SALVAR,
        LISTAR,
        ABRIR,
        COMPARTILHAR,
        EXCLUIR
    }

    private AcaoArmazenamento acaoArmazenamento =
            AcaoArmazenamento.NENHUMA;

    private String arquivoPendente;

    private static class Programa {
        String nome;
        String arquivo;
        Uri uri;

        Programa(String nome, String arquivo, Uri uri) {
            this.nome = nome;
            this.arquivo = arquivo;
            this.uri = uri;
        }
    }

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

        @JavascriptInterface
        public void salvarPrograma(String nome, String conteudo) {

            runOnUiThread(() -> solicitarSalvarPrograma(nome, conteudo));

        }

        @JavascriptInterface
        public void listarProgramas() {

            runOnUiThread(() -> solicitarListagemProgramas());

        }

        @JavascriptInterface
        public void abrirPrograma(String arquivo) {

            runOnUiThread(() -> solicitarAbrirPrograma(arquivo));

        }

        @JavascriptInterface
        public void compartilharPrograma(String arquivo) {

            runOnUiThread(() -> solicitarCompartilhamento(arquivo));

        }

        @JavascriptInterface
        public void excluirPrograma(String arquivo) {

            runOnUiThread(() -> solicitarExclusaoPrograma(arquivo));

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
        ImageButton btnMenu = findViewById(R.id.btnMenu);

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
        // PERMISSÃO DE ARMAZENAMENTO (ANDROID 9 OU ANTERIOR)
        // =================================================

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),

                granted -> {

                    if (granted) {

                        executarAcaoArmazenamento();

                    } else {

                        acaoArmazenamento = AcaoArmazenamento.NENHUMA;

                        Toast.makeText(
                                this,
                                "Permissão necessária para acessar a pasta Documentos.",
                                Toast.LENGTH_LONG
                        ).show();

                        if (nomePendente != null) {
                            notificarSalvamento(false, "Permissão de armazenamento negada.");
                        }

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
        // BOTÃO ANDROID DO MENU
        // =================================================

        btnMenu.setOnClickListener(v -> abrirMenu(v));

        // =================================================
        // CARREGAMENTO DO BLOCKLY
        // =================================================

        webView.loadUrl(
                "https://appassets.androidplatform.net/assets/blockly.html"
        );

    }

    // =====================================================
    // MENU DE OPÇÕES
    // =====================================================

    private void abrirMenu(View ancora) {

        // Fecha "Meus programas", caso esteja aberto.
        // O menu de opções continua sendo exibido normalmente.
        webView.evaluateJavascript(
                "fecharPainelProgramas()",
                null
        );

        View menuView = LayoutInflater.from(this)
                .inflate(R.layout.menu_popup, null);

        int larguraMenu = (int) (
                160 * getResources().getDisplayMetrics().density
        );

        PopupWindow popup = new PopupWindow(
                menuView,
                larguraMenu,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );

        popup.setBackgroundDrawable(
                new ColorDrawable(android.graphics.Color.TRANSPARENT)
        );

        popup.setOutsideTouchable(true);
        popup.setElevation(8f);

        TextView abrir = menuView.findViewById(R.id.menuAbrir);
        TextView salvar = menuView.findViewById(R.id.menuSalvar);
        TextView sobre = menuView.findViewById(R.id.menuSobre);

        abrir.setOnClickListener(v -> {

            popup.dismiss();

            webView.evaluateJavascript(
                    "abrirListaProgramas()",
                    null
            );

        });

        salvar.setOnClickListener(v -> {

            popup.dismiss();

            webView.evaluateJavascript(
                    "abrirJanelaSalvar()",
                    null
            );

        });

        sobre.setOnClickListener(v -> {
            popup.dismiss();
            mostrarSobre();
        });

        popup.showAsDropDown(
                ancora,
                0,
                4,
                Gravity.END
        );

    }

    // =====================================================
    // SOBRE O ZIBLOCKS
    // =====================================================



    private void mostrarSobre() {

        int padding = dp(24);

        // Cor padrão da identidade visual do Ziblocks.
        int azulZiblocks = android.graphics.Color.parseColor("#332B98");

        // Fundo amarelo com cantos arredondados.
        android.graphics.drawable.GradientDrawable fundoDialog =
                new android.graphics.drawable.GradientDrawable();

        fundoDialog.setColor(
                android.graphics.Color.parseColor("#F2FB94")
        );

        fundoDialog.setCornerRadius(dp(20));

        LinearLayout conteudo = new LinearLayout(this);
        conteudo.setOrientation(LinearLayout.VERTICAL);

        // O fundo fica transparente para respeitar os cantos do diálogo.
        conteudo.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        conteudo.setGravity(Gravity.CENTER_HORIZONTAL);
        conteudo.setPadding(padding, dp(12), padding, dp(8));

        // LOGO
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_ziblocks);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);

        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                dp(104),
                dp(104)
        );
        logoParams.bottomMargin = dp(8);
        conteudo.addView(logo, logoParams);


        // DESCRIÇÃO
        TextView descricao = new TextView(this);
        descricao.setText(
                "APRENDIZAGEM • PROGRAMAÇÃO • ROBÓTICA\n\n" +
                        "O Ziblocks é uma plataforma educacional que permite criar " +
                        "programas de forma visual, utilizando blocos de programação, " +
                        "e transferi-los sem fio, por meio dos flashes de luz do celular, " +
                        "diretamente para uma placa eletrônica que controla projetos de robótica."
        );
        descricao.setTextSize(15);
        descricao.setTextColor(azulZiblocks);
        descricao.setGravity(Gravity.CENTER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            descricao.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD);
        }
        descricao.setLineSpacing(dp(3), 1.0f);

        LinearLayout.LayoutParams descricaoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        descricaoParams.bottomMargin = dp(20);
        conteudo.addView(descricao, descricaoParams);

        // DESENVOLVEDOR
        TextView desenvolvedor = new TextView(this);
        desenvolvedor.setText("Desenvolvimento\nMichel Rodrigues");
        desenvolvedor.setTextSize(14);
        desenvolvedor.setTextColor(azulZiblocks);
        desenvolvedor.setGravity(Gravity.CENTER);
        desenvolvedor.setLineSpacing(dp(3), 1.0f);

        LinearLayout.LayoutParams desenvolvedorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        desenvolvedorParams.bottomMargin = dp(16);
        conteudo.addView(desenvolvedor, desenvolvedorParams);

        // VERSÃO E DIREITOS AUTORAIS
        TextView versao = new TextView(this);
        versao.setText(
                "Versão 1.0.0\n© 2026 — Todos os direitos reservados."
        );
        versao.setTextSize(12);
        versao.setTextColor(azulZiblocks);
        versao.setGravity(Gravity.CENTER);
        versao.setLineSpacing(dp(2), 1.0f);

        conteudo.addView(
                versao,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        // JANELA SOBRE
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(conteudo)
                .setPositiveButton(
                        "Fechar",
                        (d, which) -> d.dismiss()
                )
                .create();

        dialog.show();

        // Aplica o fundo arredondado à janela.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(fundoDialog);
        }

        // Cor do texto do botão Fechar.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(azulZiblocks);
    }



    private int dp(int valor) {
        return (int) (valor * getResources().getDisplayMetrics().density + 0.5f);
    }

    // =====================================================
    // PERMISSÃO DE ARMAZENAMENTO
    // =====================================================

    private boolean verificarPermissaoArmazenamento() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }

        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED;

    }

    private void solicitarPermissaoArmazenamento(
            AcaoArmazenamento acao) {

        acaoArmazenamento = acao;

        if (verificarPermissaoArmazenamento()) {

            executarAcaoArmazenamento();

        } else {

            storagePermissionLauncher.launch(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            );

        }

    }

    private void executarAcaoArmazenamento() {

        AcaoArmazenamento acao = acaoArmazenamento;

        acaoArmazenamento = AcaoArmazenamento.NENHUMA;

        switch (acao) {

            case SALVAR:
                salvarProgramaNoDispositivo(nomePendente, conteudoPendente);
                break;

            case LISTAR:
                listarProgramasDoDispositivo();
                break;

            case ABRIR:
                abrirProgramaDoDispositivo(arquivoPendente);
                break;

            case COMPARTILHAR:
                compartilharProgramaDoDispositivo(arquivoPendente);
                break;

            case EXCLUIR:
                confirmarExclusaoPrograma(arquivoPendente);
                break;

            default:
                break;

        }

    }

    // =====================================================
    // SALVAR PROGRAMA
    // =====================================================

    private void solicitarSalvarPrograma(
            String nome,
            String conteudo) {

        if (nome == null || conteudo == null || conteudo.trim().isEmpty()) {

            notificarSalvamento(false, "Não foi possível salvar o programa.");
            return;

        }

        String nomeLimpo = nome.trim();

        nomeLimpo = nomeLimpo.replaceAll(
                "[\\\\/:*?\"<>|]",
                "_"
        );

        if (nomeLimpo.isEmpty()) {

            notificarSalvamento(false, "Digite um nome válido.");
            return;

        }

        if (nomeLimpo.toLowerCase().endsWith(EXTENSAO)) {

            nomeLimpo = nomeLimpo.substring(
                    0,
                    nomeLimpo.length() - EXTENSAO.length()
            );

        }

        nomePendente = nomeLimpo;
        conteudoPendente = conteudo;

        solicitarPermissaoArmazenamento(
                AcaoArmazenamento.SALVAR
        );

    }

    private void salvarProgramaNoDispositivo(
            String nome,
            String conteudo) {

        if (nome == null || conteudo == null) {
            notificarSalvamento(false, "Dados inválidos.");
            return;
        }

        String arquivo = nome + EXTENSAO;

        try {

            Programa existente = localizarPrograma(arquivo);

            if (existente != null) {

                new AlertDialog.Builder(this)
                        .setTitle("Substituir programa?")
                        .setMessage(
                                "Já existe um programa chamado \"" +
                                        nome +
                                        "\". Deseja substituí-lo?"
                        )
                        .setNegativeButton("Cancelar", (dialog, which) -> {

                            notificarSalvamento(
                                    false,
                                    "Salvamento cancelado."
                            );

                        })
                        .setPositiveButton("Substituir", (dialog, which) -> {

                            gravarPrograma(existente, conteudo);

                        })
                        .show();

            } else {

                gravarPrograma(null, conteudo);

            }

        } catch (Exception e) {

            Log.e("ZIBLOCKS_FILES", "Erro ao verificar programa.", e);

            notificarSalvamento(
                    false,
                    "Não foi possível acessar os programas."
            );

        }

    }

    private void gravarPrograma(
            Programa existente,
            String conteudo) {

        Uri uri = null;
        boolean novoArquivo = existente == null;

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                if (existente != null) {

                    uri = existente.uri;

                    ContentValues valores = new ContentValues();
                    valores.put(MediaStore.MediaColumns.IS_PENDING, 1);

                    // Corrige também o nome de arquivos antigos.
                    valores.put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            nomePendente + EXTENSAO
                    );

                    valores.put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            MIME_TYPE
                    );

                    getContentResolver().update(
                            uri,
                            valores,
                            null,
                            null
                    );

                } else {

                    ContentValues valores = new ContentValues();

                    valores.put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            nomePendente + EXTENSAO
                    );

                    valores.put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            MIME_TYPE
                    );

                    valores.put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOCUMENTS + "/" +
                                    PASTA_PROGRAMAS + "/"
                    );

                    valores.put(
                            MediaStore.MediaColumns.IS_PENDING,
                            1
                    );

                    uri = getContentResolver().insert(
                            MediaStore.Files.getContentUri("external"),
                            valores
                    );

                    if (uri == null) {
                        throw new IOException("Não foi possível criar o arquivo.");
                    }

                }

                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {

                    if (output == null) {
                        throw new IOException("Não foi possível gravar o arquivo.");
                    }

                    output.write(conteudo.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                }

                ContentValues valoresFinal = new ContentValues();
                valoresFinal.put(MediaStore.MediaColumns.IS_PENDING, 0);

                getContentResolver().update(
                        uri,
                        valoresFinal,
                        null,
                        null
                );

            } else {

                File pasta = obterPastaLegada();

                if (!pasta.exists() && !pasta.mkdirs()) {
                    throw new IOException("Não foi possível criar a pasta.");
                }

                File destino = new File(pasta, nomePendente + EXTENSAO);

                try (OutputStream output = new java.io.FileOutputStream(destino, false)) {

                    output.write(conteudo.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                }

            }

            notificarSalvamento(
                    true,
                    "Programa salvo com sucesso!"
            );

        } catch (Exception e) {

            Log.e("ZIBLOCKS_FILES", "Erro ao salvar programa.", e);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {

                if (novoArquivo) {

                    getContentResolver().delete(uri, null, null);

                } else {

                    ContentValues valores = new ContentValues();
                    valores.put(MediaStore.MediaColumns.IS_PENDING, 0);

                    getContentResolver().update(
                            uri,
                            valores,
                            null,
                            null
                    );

                }

            }

            notificarSalvamento(
                    false,
                    "Não foi possível salvar o programa."
            );

        }

    }

    // =====================================================
    // LISTAR PROGRAMAS
    // =====================================================

    private void solicitarListagemProgramas() {

        solicitarPermissaoArmazenamento(
                AcaoArmazenamento.LISTAR
        );

    }

    private void listarProgramasDoDispositivo() {

        ArrayList<Programa> programas = new ArrayList<>();

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                Uri colecao = MediaStore.Files.getContentUri("external");

                String caminhoPasta =
                        Environment.DIRECTORY_DOCUMENTS + "/" +
                                PASTA_PROGRAMAS + "/";

                String selecao =
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " +
                                MediaStore.MediaColumns.IS_PENDING + " = 0";

                String[] argumentos = {
                        caminhoPasta + "%"
                };

                String[] colunas = {
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.RELATIVE_PATH
                };

                try (Cursor cursor = getContentResolver().query(
                        colecao,
                        colunas,
                        selecao,
                        argumentos,
                        MediaStore.MediaColumns.DISPLAY_NAME + " ASC"
                )) {

                    if (cursor != null) {

                        int indiceNome = cursor.getColumnIndexOrThrow(
                                MediaStore.MediaColumns.DISPLAY_NAME
                        );

                        int indiceId = cursor.getColumnIndexOrThrow(
                                MediaStore.MediaColumns._ID
                        );

                        int indiceCaminho = cursor.getColumnIndexOrThrow(
                                MediaStore.MediaColumns.RELATIVE_PATH
                        );

                        while (cursor.moveToNext()) {

                            String arquivo = cursor.getString(indiceNome);
                            String caminho = cursor.getString(indiceCaminho);

                            Log.d(
                                    "ZIBLOCKS_FILES",
                                    "Encontrado: " + arquivo +
                                            " | Pasta: " + caminho
                            );

                            if (!ehArquivoZiblocks(arquivo)) {
                                continue;
                            }

                            long id = cursor.getLong(indiceId);

                            Uri uri = Uri.withAppendedPath(
                                    colecao,
                                    String.valueOf(id)
                            );

                            String arquivoExibicao =
                                    normalizarArquivoLegado(arquivo);

                            String nomeExibicao =
                                    removerExtensao(arquivoExibicao);

                            // Tenta corrigir o nome físico dos arquivos antigos.
                            if (!arquivo.equals(arquivoExibicao)) {

                                if (renomearArquivo(uri, arquivoExibicao)) {

                                    Log.d(
                                            "ZIBLOCKS_FILES",
                                            "Arquivo antigo renomeado: " +
                                                    arquivo + " -> " +
                                                    arquivoExibicao
                                    );

                                    arquivo = arquivoExibicao;

                                } else {

                                    Log.w(
                                            "ZIBLOCKS_FILES",
                                            "Não foi possível renomear: " + arquivo
                                    );

                                }

                            }

                            programas.add(new Programa(
                                    nomeExibicao,
                                    arquivo,
                                    uri
                            ));

                        }

                        Log.d(
                                "ZIBLOCKS_FILES",
                                "Total de programas encontrados: " + programas.size()
                        );

                    } else {

                        Log.e(
                                "ZIBLOCKS_FILES",
                                "A consulta ao MediaStore retornou cursor nulo."
                        );

                    }

                }

            } else {

                File pasta = obterPastaLegada();

                File[] arquivos = pasta.listFiles();

                if (arquivos != null) {

                    for (File arquivo : arquivos) {

                        if (arquivo.isFile() &&
                                ehArquivoZiblocks(arquivo.getName())) {

                            String nomeNormalizado =
                                    normalizarArquivoLegado(arquivo.getName());

                            programas.add(new Programa(
                                    removerExtensao(nomeNormalizado),
                                    arquivo.getName(),
                                    Uri.fromFile(arquivo)
                            ));

                        }

                    }

                }

                programas.sort(
                        (a, b) -> a.nome.compareToIgnoreCase(b.nome)
                );

            }

            JSONArray resultado = new JSONArray();

            for (Programa programa : programas) {

                JSONObject objeto = new JSONObject();

                objeto.put("nome", programa.nome);
                objeto.put("arquivo", programa.arquivo);

                resultado.put(objeto);

            }

            webView.evaluateJavascript(
                    "ziblocksListaProgramas(" + resultado.toString() + ")",
                    null
            );

        } catch (Exception e) {

            Log.e("ZIBLOCKS_FILES", "Erro ao listar programas.", e);

            webView.evaluateJavascript(
                    "ziblocksListaProgramas([])",
                    null
            );

            Toast.makeText(
                    this,
                    "Não foi possível listar os programas.",
                    Toast.LENGTH_SHORT
            ).show();

        }

    }

    // =====================================================
    // RECONHECER E NORMALIZAR ARQUIVOS ANTIGOS
    // =====================================================

    private boolean ehArquivoZiblocks(String arquivo) {

        if (arquivo == null) {
            return false;
        }

        String nome = arquivo.toLowerCase();

        return nome.endsWith(EXTENSAO) ||
                nome.matches(".*\\.ziblocks(?: \\(\\d+\\))?\\.xml$");

    }

    private String normalizarArquivoLegado(String arquivo) {

        if (arquivo == null) {
            return null;
        }

        Pattern padrao = Pattern.compile(
                "(?i)\\.ziblocks(?: \\((\\d+)\\))?\\.xml$"
        );

        Matcher matcher = padrao.matcher(arquivo);

        if (!matcher.find()) {
            return arquivo;
        }

        String base = arquivo.substring(0, matcher.start());
        String numero = matcher.group(1);

        if (numero != null) {
            return base + " (" + numero + ")" + EXTENSAO;
        }

        return base + EXTENSAO;

    }

    private boolean renomearArquivo(Uri uri, String novoNome) {

        try {

            ContentValues valores = new ContentValues();

            valores.put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    novoNome
            );

            valores.put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    MIME_TYPE
            );

            int atualizados = getContentResolver().update(
                    uri,
                    valores,
                    null,
                    null
            );

            return atualizados > 0;

        } catch (Exception e) {

            Log.e(
                    "ZIBLOCKS_FILES",
                    "Erro ao renomear arquivo antigo.",
                    e
            );

            return false;

        }

    }

    // =====================================================
    // ABRIR PROGRAMA
    // =====================================================

    private void solicitarAbrirPrograma(String arquivo) {

        if (arquivo == null || arquivo.trim().isEmpty()) {
            return;
        }

        arquivoPendente = arquivo;

        solicitarPermissaoArmazenamento(
                AcaoArmazenamento.ABRIR
        );

    }

    private void abrirProgramaDoDispositivo(String arquivo) {

        try {

            Programa programa = localizarPrograma(arquivo);

            if (programa == null) {
                throw new IOException("Programa não encontrado.");
            }

            String conteudo;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                try (InputStream input = getContentResolver().openInputStream(programa.uri)) {

                    if (input == null) {
                        throw new IOException("Não foi possível ler o arquivo.");
                    }

                    conteudo = lerTexto(input);

                }

            } else {

                File file = new File(obterPastaLegada(), programa.arquivo);

                try (InputStream input = new java.io.FileInputStream(file)) {

                    conteudo = lerTexto(input);

                }

            }

            String nome = removerExtensao(
                    normalizarArquivoLegado(programa.arquivo)
            );

            webView.evaluateJavascript(
                    "ziblocksProgramaAberto(" +
                            JSONObject.quote(nome) + "," +
                            JSONObject.quote(conteudo) +
                            ")",
                    null
            );

        } catch (Exception e) {

            Log.e("ZIBLOCKS_FILES", "Erro ao abrir programa.", e);

            Toast.makeText(
                    this,
                    "Não foi possível abrir esse programa.",
                    Toast.LENGTH_SHORT
            ).show();

        }

    }

    // =====================================================
    // COMPARTILHAR PROGRAMA
    // =====================================================

    private void solicitarCompartilhamento(String arquivo) {

        if (arquivo == null || arquivo.trim().isEmpty()) {
            return;
        }

        arquivoPendente = arquivo;

        solicitarPermissaoArmazenamento(
                AcaoArmazenamento.COMPARTILHAR
        );

    }

    private void compartilharProgramaDoDispositivo(String arquivo) {

        try {

            Programa programa = localizarPrograma(arquivo);

            if (programa == null) {
                throw new IOException("Programa não encontrado.");
            }

            Uri uriCompartilhamento;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                uriCompartilhamento = programa.uri;

            } else {

                uriCompartilhamento = FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        new File(obterPastaLegada(), programa.arquivo)
                );

            }

            Intent compartilhar = new Intent(Intent.ACTION_SEND);

            compartilhar.setType(MIME_TYPE);

            compartilhar.putExtra(
                    Intent.EXTRA_STREAM,
                    uriCompartilhamento
            );

            compartilhar.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            compartilhar.setClipData(
                    android.content.ClipData.newUri(
                            getContentResolver(),
                            programa.arquivo,
                            uriCompartilhamento
                    )
            );

            startActivity(
                    Intent.createChooser(
                            compartilhar,
                            "Compartilhar programa"
                    )
            );

        } catch (Exception e) {

            Log.e("ZIBLOCKS_FILES", "Erro ao compartilhar programa.", e);

            Toast.makeText(
                    this,
                    "Não foi possível compartilhar esse programa.",
                    Toast.LENGTH_SHORT
            ).show();

        }

    }

    // =====================================================
    // EXCLUIR PROGRAMA
    // =====================================================

    private void solicitarExclusaoPrograma(String arquivo) {

        if (arquivo == null || arquivo.trim().isEmpty()) {
            return;
        }

        arquivoPendente = arquivo;

        solicitarPermissaoArmazenamento(
                AcaoArmazenamento.EXCLUIR
        );

    }

    private void confirmarExclusaoPrograma(String arquivo) {

        try {

            Programa programa = localizarPrograma(arquivo);

            if (programa == null) {

                Toast.makeText(
                        this,
                        "Programa não encontrado.",
                        Toast.LENGTH_SHORT
                ).show();

                listarProgramasDoDispositivo();
                return;

            }

            new AlertDialog.Builder(this)
                    .setTitle("Excluir programa?")
                    .setMessage(
                            "Deseja realmente excluir o programa \"" +
                                    programa.nome +
                                    "\"? Essa ação não poderá ser desfeita."
                    )
                    .setNegativeButton("Cancelar", (dialog, which) -> {
                        dialog.dismiss();
                    })
                    .setPositiveButton("Excluir", (dialog, which) -> {
                        excluirProgramaDoDispositivo(programa);
                    })
                    .show();

        } catch (Exception e) {

            Log.e(
                    "ZIBLOCKS_FILES",
                    "Erro ao confirmar exclusão.",
                    e
            );

            Toast.makeText(
                    this,
                    "Não foi possível excluir o programa.",
                    Toast.LENGTH_SHORT
            ).show();

        }

    }

    private void excluirProgramaDoDispositivo(Programa programa) {

        try {

            boolean excluido;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                int quantidade = getContentResolver().delete(
                        programa.uri,
                        null,
                        null
                );

                excluido = quantidade > 0;

            } else {

                File arquivo = new File(
                        obterPastaLegada(),
                        programa.arquivo
                );

                excluido = arquivo.exists() && arquivo.delete();

            }

            if (excluido) {

                Toast.makeText(
                        this,
                        "Programa excluído com sucesso!",
                        Toast.LENGTH_SHORT
                ).show();

                // Atualiza a lista após excluir o arquivo.
                listarProgramasDoDispositivo();

            } else {

                Toast.makeText(
                        this,
                        "Não foi possível excluir o programa.",
                        Toast.LENGTH_SHORT
                ).show();

            }

        } catch (Exception e) {

            Log.e(
                    "ZIBLOCKS_FILES",
                    "Erro ao excluir programa.",
                    e
            );

            Toast.makeText(
                    this,
                    "Não foi possível excluir o programa.",
                    Toast.LENGTH_SHORT
            ).show();

        }

    }

    // =====================================================
    // LOCALIZAR PROGRAMA
    // =====================================================

    private Programa localizarPrograma(String arquivo) {

        if (arquivo == null) {
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            Uri colecao = MediaStore.Files.getContentUri("external");

            String caminhoPasta =
                    Environment.DIRECTORY_DOCUMENTS + "/" +
                            PASTA_PROGRAMAS + "/";

            String selecao =
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " +
                            MediaStore.MediaColumns.DISPLAY_NAME + "=?";

            String[] colunas = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME
            };

            // Primeiro tenta localizar pelo nome exato.
            try (Cursor cursor = getContentResolver().query(
                    colecao,
                    colunas,
                    selecao,
                    new String[]{caminhoPasta, arquivo},
                    null
            )) {

                if (cursor != null && cursor.moveToFirst()) {

                    long id = cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                    MediaStore.MediaColumns._ID
                            )
                    );

                    String nomeReal = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                    MediaStore.MediaColumns.DISPLAY_NAME
                            )
                    );

                    Uri uri = Uri.withAppendedPath(
                            colecao,
                            String.valueOf(id)
                    );

                    return new Programa(
                            removerExtensao(
                                    normalizarArquivoLegado(nomeReal)
                            ),
                            nomeReal,
                            uri
                    );

                }

            }

            // Se não encontrou, procura também pelos nomes antigos.
            try (Cursor cursor = getContentResolver().query(
                    colecao,
                    colunas,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{caminhoPasta},
                    null
            )) {

                if (cursor != null) {

                    int indiceId = cursor.getColumnIndexOrThrow(
                            MediaStore.MediaColumns._ID
                    );

                    int indiceNome = cursor.getColumnIndexOrThrow(
                            MediaStore.MediaColumns.DISPLAY_NAME
                    );

                    String arquivoProcurado =
                            normalizarArquivoLegado(arquivo);

                    while (cursor.moveToNext()) {

                        String nomeReal = cursor.getString(indiceNome);

                        if (nomeReal == null) {
                            continue;
                        }

                        String nomeNormalizado =
                                normalizarArquivoLegado(nomeReal);

                        if (nomeNormalizado.equalsIgnoreCase(arquivoProcurado)) {

                            long id = cursor.getLong(indiceId);

                            Uri uri = Uri.withAppendedPath(
                                    colecao,
                                    String.valueOf(id)
                            );

                            return new Programa(
                                    removerExtensao(nomeNormalizado),
                                    nomeReal,
                                    uri
                            );

                        }

                    }

                }

            }

            return null;

        } else {

            File pasta = obterPastaLegada();

            File file = new File(pasta, arquivo);

            if (!file.exists() || !file.isFile()) {

                String procurado =
                        normalizarArquivoLegado(arquivo);

                File[] arquivos = pasta.listFiles();

                if (arquivos != null) {

                    for (File candidato : arquivos) {

                        if (candidato.isFile() &&
                                normalizarArquivoLegado(candidato.getName())
                                        .equalsIgnoreCase(procurado)) {

                            file = candidato;
                            break;

                        }

                    }

                }

            }

            if (!file.exists() || !file.isFile()) {
                return null;
            }

            return new Programa(
                    removerExtensao(
                            normalizarArquivoLegado(file.getName())
                    ),
                    file.getName(),
                    Uri.fromFile(file)
            );

        }

    }

    // =====================================================
    // UTILITÁRIOS DE ARQUIVOS
    // =====================================================

    private File obterPastaLegada() {

        File documentos = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
        );

        return new File(documentos, PASTA_PROGRAMAS);

    }

    private String removerExtensao(String arquivo) {

        if (arquivo != null &&
                arquivo.toLowerCase().endsWith(EXTENSAO)) {

            return arquivo.substring(
                    0,
                    arquivo.length() - EXTENSAO.length()
            );

        }

        return arquivo;

    }

    private String lerTexto(InputStream input) throws IOException {

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        byte[] dados = new byte[4096];

        int quantidade;

        while ((quantidade = input.read(dados)) != -1) {
            buffer.write(dados, 0, quantidade);
        }

        return buffer.toString("UTF-8");

    }

    private void notificarSalvamento(
            boolean sucesso,
            String mensagem) {

        if (webView == null) {
            return;
        }

        webView.evaluateJavascript(
                "ziblocksSalvamentoResultado(" +
                        sucesso + "," +
                        JSONObject.quote(mensagem) +
                        ")",
                null
        );

    }

    // =====================================================
    // SOLICITAR PERMISSÃO OU INICIAR TRANSMISSÃO
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

                esperarAte(
                        System.nanoTime() + TEMPO_INICIAL_NS
                );

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

                        esperarAte(prazo);

                        cameraManager.setTorchMode(
                                idCamera,
                                true
                        );

                        prazo += highNs;

                        esperarAte(prazo);

                        cameraManager.setTorchMode(
                                idCamera,
                                false
                        );

                        prazo += lowNs;

                        esperarAte(prazo);

                    }

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