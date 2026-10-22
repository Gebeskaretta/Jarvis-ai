package com.mico.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
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

import java.io.File;
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
    private static final int AUDIO_PICKER_REQUEST_CODE = 1003;
    private SharedPreferences prefs;

    // Real device audio player
    private MediaPlayer mediaPlayer;
    private boolean isAudioPlaying = false;

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
                runOnUiThread(() -> request.grant(request.getResources()));
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

                // Natural, crisp, calm Jarvis tone
                tts.setPitch(0.95f);
                tts.setSpeechRate(1.0f);
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
        ArrayList<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.CAMERA);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        boolean needRequest = false;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }
        if (needRequest) {
            requestPermissions(perms.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
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
        } else if (requestCode == AUDIO_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri audioUri = data.getData();
            if (audioUri != null) {
                playUriAudio(audioUri, "Manifest - Hileli (Seçilen Parça)");
            }
        }
    }

    private void playUriAudio(Uri uri, String title) {
        try {
            stopAudio();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.prepare();
            mediaPlayer.start();
            isAudioPlaying = true;

            mediaPlayer.setOnCompletionListener(mp -> {
                isAudioPlaying = false;
                runOnUiThread(() -> webView.evaluateJavascript("window.onAudioFinished && window.onAudioFinished();", null));
            });

            runOnUiThread(() -> webView.evaluateJavascript("window.onTrackLoaded && window.onTrackLoaded('" + title + "', true);", null));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void playFileAudio(File file, String title) {
        playUriAudio(Uri.fromFile(file), title);
    }

    public void stopAudio() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
            isAudioPlaying = false;
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
        public void setVoiceSettings(float pitch, float rate) {
            runOnUiThread(() -> {
                if (ttsReady && tts != null) {
                    tts.setPitch(pitch);
                    tts.setSpeechRate(rate);
                }
            });
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

        // --- REAL AUDIO SEARCH & PLAYBACK (Manifest - Hileli) ---
        @JavascriptInterface
        public boolean searchAndPlayManifest() {
            // Search MediaStore first
            try {
                ContentResolver cr = getContentResolver();
                Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] projection = {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.DISPLAY_NAME
                };
                String selection = "LOWER(" + MediaStore.Audio.Media.TITLE + ") LIKE ? OR LOWER(" + MediaStore.Audio.Media.DISPLAY_NAME + ") LIKE ?";
                String[] args = new String[]{"%manifest%", "%manifest%"};

                Cursor c = cr.query(uri, projection, selection, args, null);
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                    String title = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                    c.close();
                    Uri contentUri = Uri.withAppendedPath(uri, String.valueOf(id));
                    runOnUiThread(() -> playUriAudio(contentUri, title != null ? title : "Manifest - Hileli"));
                    return true;
                }
                if (c != null) c.close();
            } catch (Exception ignored) {}

            // Search local device directories (Music, Download)
            try {
                File[] searchDirs = {
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        new File("/storage/emulated/0/Music"),
                        new File("/storage/emulated/0/Download")
                };
                for (File dir : searchDirs) {
                    if (dir != null && dir.exists() && dir.isDirectory()) {
                        File found = findManifestFile(dir, 0);
                        if (found != null) {
                            runOnUiThread(() -> playFileAudio(found, found.getName()));
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}

            return false;
        }

        @JavascriptInterface
        public void pickAudioFile() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("audio/*");
                startActivityForResult(Intent.createChooser(intent, "Manifest Dosyasını Seçin"), AUDIO_PICKER_REQUEST_CODE);
            });
        }

        @JavascriptInterface
        public void pauseAudio() {
            runOnUiThread(() -> {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    isAudioPlaying = false;
                }
            });
        }

        @JavascriptInterface
        public void resumeAudio() {
            runOnUiThread(() -> {
                if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                    isAudioPlaying = true;
                }
            });
        }

        @JavascriptInterface
        public void stopAudio() {
            runOnUiThread(MainActivity.this::stopAudio);
        }

        @JavascriptInterface
        public boolean isRealAudioPlaying() {
            return isAudioPlaying && mediaPlayer != null && mediaPlayer.isPlaying();
        }
    }

    private File findManifestFile(File dir, int depth) {
        if (depth > 2 || dir == null || !dir.canRead()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (f.isFile() && (name.contains("manifest") || name.contains("hileli")) &&
                    (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".wav") || name.endsWith(".aac"))) {
                return f;
            }
            if (f.isDirectory() && !name.startsWith(".")) {
                File sub = findManifestFile(f, depth + 1);
                if (sub != null) return sub;
            }
        }
        return null;
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
        stopAudio();
    }
}