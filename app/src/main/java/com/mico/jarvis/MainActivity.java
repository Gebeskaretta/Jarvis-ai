package com.mico.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private ValueCallback<Uri[]> fileUploadCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int PERMISSIONS_REQUEST_CODE = 1002;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#02060d"));

        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);

        initTts();
        requestRequiredPermissions();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#02060d"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#02060d"));
        setupWebView();

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file:///android_asset/")) {
                    return false;
                }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                } catch (Exception ignored) {}
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    request.grant(request.getResources());
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Locale tr = new Locale("tr", "TR");
                int result = tts.setLanguage(tr);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }

                // Explicitly look for masculine voice in Turkish
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        Set<Voice> voices = tts.getVoices();
                        if (voices != null) {
                            Voice bestMaleVoice = null;
                            for (Voice v : voices) {
                                String name = v.getName().toLowerCase();
                                String lang = v.getLocale() != null ? v.getLocale().getLanguage().toLowerCase() : "";
                                if (lang.equals("tr") || name.contains("tr") || name.contains("tur")) {
                                    if (name.contains("male") || name.contains("erkek") || name.contains("dfz") || name.contains("ama")) {
                                        bestMaleVoice = v;
                                        break;
                                    }
                                }
                            }
                            if (bestMaleVoice != null) {
                                tts.setVoice(bestMaleVoice);
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                // Deep, masculine, resonant Jarvis tone
                tts.setPitch(0.78f);
                tts.setSpeechRate(0.97f);
                ttsReady = true;

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {}

                    @Override
                    public void onDone(String utteranceId) {
                        runOnUiThread(() -> webView.evaluateJavascript("window.onSpoken && window.onSpoken();", null));
                    }

                    @Override
                    public void onError(String utteranceId) {
                        runOnUiThread(() -> webView.evaluateJavascript("window.onSpoken && window.onSpoken();", null));
                    }
                });
            }
        });
    }

    private void requestRequiredPermissions() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
        };
        boolean needRequest = false;
        for (String p : permissions) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }
        if (needRequest) {
            requestPermissions(permissions, PERMISSIONS_REQUEST_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (fileUploadCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public String getApiKey() {
            String key = prefs.getString("key", "");
            if (!key.isEmpty()) return key;

            try {
                java.lang.reflect.Field f = BuildConfig.class.getField("GEMINI_API_KEY");
                String k = (String) f.get(null);
                if (k != null && !k.isEmpty()) return k;
            } catch (Throwable ignored) {}

            try {
                java.lang.reflect.Field f = BuildConfig.class.getField("GROQ_API_KEY");
                String k = (String) f.get(null);
                if (k != null && !k.isEmpty()) return k;
            } catch (Throwable ignored) {}

            return "";
        }

        @JavascriptInterface
        public void setApiKey(String key) {
            prefs.edit().putString("key", key).apply();
        }

        @JavascriptInterface
        public boolean hasTts() {
            return ttsReady;
        }

        @JavascriptInterface
        public void speak(String text) {
            if (ttsReady && text != null && !text.trim().isEmpty()) {
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime());
            }
        }

        @JavascriptInterface
        public void stopSpeak() {
            if (ttsReady) {
                tts.stop();
            }
        }

        @JavascriptInterface
        public boolean hasSpeechRecognizer() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface
        public void toggleMic() {
            runOnUiThread(() -> {
                if (listening) {
                    if (recognizer != null) recognizer.stopListening();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_CODE);
                    return;
                }
                startSpeechRecognition();
            });
        }

        @JavascriptInterface
        public int getBatteryLevel() {
            try {
                BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                }
            } catch (Exception ignored) {}
            return -1;
        }
    }

    private void startSpeechRecognition() {
        if (recognizer != null) {
            recognizer.destroy();
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            String lastRecognized = "";

            @Override
            public void onReadyForSpeech(Bundle params) {
                listening = true;
                runOnUiThread(() -> webView.evaluateJavascript("window.onSpeechState && window.onSpeechState(true);", null));
            }

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {}

            @Override
            public void onError(int error) {
                listening = false;
                runOnUiThread(() -> {
                    webView.evaluateJavascript("window.onSpeechState && window.onSpeechState(false);", null);
                });
                if (recognizer != null) {
                    recognizer.destroy();
                    recognizer = null;
                }
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                final String text = (matches != null && !matches.isEmpty()) ? matches.get(0) : lastRecognized;
                runOnUiThread(() -> {
                    String clean = text.replace("'", "\\'").replace("\n", " ");
                    webView.evaluateJavascript("window.onSpeechResult && window.onSpeechResult('" + clean + "', true);", null);
                });
                if (recognizer != null) {
                    recognizer.destroy();
                    recognizer = null;
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    lastRecognized = matches.get(0);
                    runOnUiThread(() -> {
                        String clean = lastRecognized.replace("'", "\\'").replace("\n", " ");
                        webView.evaluateJavascript("window.onSpeechResult && window.onSpeechResult('" + clean + "', false);", null);
                    });
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(intent);
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("if(document.getElementById('set').style.display==='flex'){document.getElementById('set').style.display='none'; true;} else { false; }", value -> {
                if (!"true".equals(value)) {
                    super.onBackPressed();
                }
            });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.shutdown();
        }
        if (recognizer != null) {
            recognizer.destroy();
        }
    }
}