package com.mico.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Toast;

import java.util.ArrayList;

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

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();

        initSpeechRecognizer();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "J.A.R.V.I.S. Arka Plan Dinleme",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Hey Jarvis ses komutunu arka planda dinler");
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

        builder.setContentTitle("J.A.R.V.I.S. Arka Planda Aktif")
                .setContentText("'Hey Jarvis' veya 'Hey Friday' diyerek uyandırabilirsiniz")
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

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
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

                // Sessizlik ve timeout normaldir, küçük bir gecikmeyle döngüyü sürdür
                long delay = 350;
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                    delay = 1000;
                }
                scheduleRestart(delay);
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                if (results != null) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        for (String match : matches) {
                            String spokenText = match.toLowerCase();
                            if (spokenText.contains("hey jarvis") || spokenText.contains("hey carvis") ||
                                spokenText.contains("jarvis") || spokenText.contains("hey friday") ||
                                spokenText.contains("friday")) {

                                wakeUpApp();
                                break;
                            }
                        }
                    }
                }

                if (shouldRun) {
                    scheduleRestart(300);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (partialResults != null) {
                    ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String spokenText = matches.get(0).toLowerCase();
                        if (spokenText.contains("hey jarvis") || spokenText.contains("hey carvis") ||
                            spokenText.contains("jarvis") || spokenText.contains("hey friday") ||
                            spokenText.contains("friday")) {

                            wakeUpApp();
                        }
                    }
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void wakeUpApp() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            launchIntent.putExtra("WAKE_TRIGGER", true);
            startActivity(launchIntent);

            handler.post(() -> {
                Toast.makeText(WakeWordService.this, "Efendim, sizi dinliyorum.", Toast.LENGTH_SHORT).show();
            });
        }
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
                handler.postDelayed(this, 1200);
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shouldRun = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        shouldRun = true;
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
            handler.removeCallbacks(restartRunnable);
        }
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
