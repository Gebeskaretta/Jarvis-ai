package com.mico.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class WakeWordService extends Service {
    public static final String ACTION_START = "com.mico.jarvis.START_WAKE_SERVICE";
    public static final String ACTION_STOP = "com.mico.jarvis.STOP_WAKE_SERVICE";
    private static final String CHANNEL_ID = "jarvis_wake_channel";
    private static final int NOTIFICATION_ID = 2001;

    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private Handler handler;
    private boolean isListening = false;
    private boolean shouldRun = true;
    private int consecutiveErrors = 0;
    private long lastResultTime = 0;

    private PowerManager.WakeLock wakeLock;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SharedPreferences prefs;

    // Sabit varsayılan algılama kalıpları
    private static final Set<String> DEFAULT_PATTERNS = new HashSet<>(Arrays.asList(
            "hey jarvis", "jarvis", "carvis", "hey carvis", "ey jarvis", "ey carvis",
            "hey cuma", "hey servis", "hey cemis", "hey camis", "çarvis", "canvis",
            "friday", "hey friday", "fraydey", "fraydi", "patron", "hey patron", "alo jarvis"
    ));

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);

        // CPU'nun uykuya geçmesini önlemek için WakeLock
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:WakeWordLock");
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}

        // Anında sesli onay için TTS ("Evet patron")
        initTts();

        createNotificationChannel();
        initSpeechRecognizer();
        startWatchdog();
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int res = tts.setLanguage(new Locale("tr", "TR"));
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsReady = true;
                    tts.setPitch(0.95f);
                    tts.setSpeechRate(1.05f);
                }
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "J.A.R.V.I.S. Sabit Dinleme",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Hey Jarvis ses komutunu arka planda kesintisiz dinler");
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pendingIntent = null;
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("J.A.R.V.I.S. Sabit Dinliyor 🎙")
                .setContentText("'Hey Jarvis', 'Patron' veya eğittiğiniz kelimeleri dinliyor...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true);

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent);
        }

        return builder.build();
    }

    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf();
            return;
        }

        try {
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
            }
        } catch (Exception ignored) {}

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                consecutiveErrors = 0;
            }

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
            }

            @Override
            public void onError(int error) {
                isListening = false;
                if (!shouldRun) return;

                consecutiveErrors++;

                // Sürekli hatalarda veya istemci kilitlenmelerinde recognizer'ı tazeleyerek yeniden kur
                if (consecutiveErrors >= 3 || error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    consecutiveErrors = 0;
                    handler.postDelayed(() -> recreateRecognizer(), 500);
                    return;
                }

                // Normal sessizlik (ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT) -> anında yeniden başlat
                scheduleRestart(200);
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                lastResultTime = System.currentTimeMillis();

                if (results != null) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null) {
                        for (String match : matches) {
                            if (checkMatch(match)) {
                                wakeUpApp(match);
                                return;
                            }
                        }
                    }
                }

                if (shouldRun) {
                    scheduleRestart(180);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (partialResults != null) {
                    ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null) {
                        for (String match : matches) {
                            if (checkMatch(match)) {
                                wakeUpApp(match);
                                return;
                            }
                        }
                    }
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void recreateRecognizer() {
        if (!shouldRun) return;
        try {
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
        } catch (Exception ignored) {}
        initSpeechRecognizer();
        scheduleRestart(250);
    }

    // Akıllı ve Eğitilebilir Eşleşme Kontrolü
    private boolean checkMatch(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return false;
        String text = rawText.toLowerCase().trim();

        // 1. Varsayılan kalıplar
        for (String def : DEFAULT_PATTERNS) {
            if (text.contains(def)) return true;
        }

        // 2. Kullanıcının Eğittiği Özel Kelimeler (SharedPreferences)
        String customStr = prefs.getString("custom_wake_words", "");
        if (!customStr.isEmpty()) {
            String[] customArr = customStr.split(",");
            for (String cWord : customArr) {
                String trimmed = cWord.trim().toLowerCase();
                if (!trimmed.isEmpty() && text.contains(trimmed)) {
                    return true;
                }
            }
        }

        // 3. Fonetik Benzerlik (Ses benzeşimi: "arvis", "ervis", "arwis", "carvis", "patron")
        if (text.contains("arvis") || text.contains("ervis") || text.contains("arwis") ||
            text.contains("carvis") || text.contains("servis") || text.contains("patron") ||
            text.contains("fraydey") || text.contains("cumartesi") && text.contains("hey")) {
            return true;
        }

        return false;
    }

    // Uyandırma tetiklendiğinde: HEMEN "Evet patron!" de ve uygulamayı öne getir
    private synchronized void wakeUpApp(String detectedWord) {
        // Çift tetiklemeyi önle
        lastResultTime = System.currentTimeMillis();

        // 1. SESLİ GERİ BİLDİRİM: "Evet patron!"
        if (ttsReady && tts != null) {
            tts.speak("Evet patron!", TextToSpeech.QUEUE_FLUSH, null, "wake_ack");
        }

        // 2. Toast Bildirimi
        handler.post(() -> {
            Toast.makeText(WakeWordService.this, "Evet patron! (" + detectedWord + ")", Toast.LENGTH_SHORT).show();
        });

        // 3. MainActivity'yi öne getir
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            launchIntent.putExtra("WAKE_TRIGGER", true);
            launchIntent.putExtra("WAKE_WORD", detectedWord);
            startActivity(launchIntent);
        }

        // Kısa bir beklemeden sonra arka planda dinlemeyi tekrar başlat
        scheduleRestart(2500);
    }

    private void scheduleRestart(long delayMs) {
        if (!shouldRun) return;
        handler.removeCallbacks(restartRunnable);
        handler.postDelayed(restartRunnable, delayMs);
    }

    private final Runnable restartRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shouldRun) return;
            try {
                if (speechRecognizer != null && !isListening) {
                    speechRecognizer.startListening(speechRecognizerIntent);
                    isListening = true;
                }
            } catch (Exception e) {
                recreateRecognizer();
            }
        }
    };

    // Watchdog: Dinleme donarsa veya recognizer takılırsa otomatik uyandırır (Sabit Dinleme Garantisi)
    private void startWatchdog() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!shouldRun) return;
                long now = System.currentTimeMillis();
                if (!isListening && (now - lastResultTime > 5000)) {
                    recreateRecognizer();
                }
                handler.postDelayed(this, 5000);
            }
        }, 5000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shouldRun = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        shouldRun = true;
        lastResultTime = System.currentTimeMillis();

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        scheduleRestart(200);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shouldRun = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {}
            tts = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
