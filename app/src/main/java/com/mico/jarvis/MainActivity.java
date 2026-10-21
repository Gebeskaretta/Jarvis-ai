package com.mico.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import org.json.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

public class MainActivity extends Activity {
    static final String GROQ = "https://api.groq.com/openai/v1";
    static final String SYSTEM = "Sen Tony Stark'ın yapay zekası J.A.R.V.I.S.'sin. Kullanıcıya 'efendim' diye hitap et. "
            + "Türkçe, akıcı, doğal ve kısa konuş; sesli okunacağı için madde işareti, emoji ve markdown kullanma. "
            + "Hafif ince espri ve nezaket serbest. Eklenen dosya içeriğini dikkate alarak cevap ver.";
    static final String[] DEFAULT_MODELS = {
            "llama-3.3-70b-versatile", "llama-3.1-8b-instant",
            "openai/gpt-oss-120b", "openai/gpt-oss-20b",
            "meta-llama/llama-4-scout-17b-16e-instruct", "qwen/qwen3-32b"};

    SharedPreferences prefs;
    RingView ring;
    TextView clock, date, weather, status, you, reply;
    LinearLayout log;
    ScrollView logScroll;
    EditText input;
    Button micBtn;
    TextToSpeech tts;
    boolean ttsReady = false;
    final AtomicInteger speakQ = new AtomicInteger();
    volatile boolean busy = false, stopFlag = false;
    String pending = null, pendingName = null;
    final List<JSONObject> history = new ArrayList<>();
    SpeechRecognizer recognizer;
    boolean listening = false;
    final Timer timer = new Timer();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);

        // Preload API key from BuildConfig if available
        String currentKey = prefs.getString("key", "");
        if (currentKey.isEmpty()) {
            try {
                java.lang.reflect.Field f = BuildConfig.class.getField("GROQ_API_KEY");
                String envKey = (String) f.get(null);
                if (envKey != null && !envKey.isEmpty()) {
                    prefs.edit().putString("key", envKey).apply();
                    currentKey = envKey;
                }
            } catch (Throwable ignored) {}
        }

        buildUi();
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("tr", "TR"));
                tts.setPitch(.9f);
                ttsReady = true;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    public void onStart(String id) {}
                    public void onDone(String id) { spoken(); }
                    public void onError(String id) { spoken(); }
                });
            }
        });
        timer.schedule(new TimerTask() {
            public void run() { runOnUiThread(() -> tick()); }
        }, 0, 1000);
        loadWeather();
        if (prefs.getString("key", "").isEmpty()) showSettings();
    }

    /* ---------------- UI ---------------- */
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    TextView tv(String t, int sp, String color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(Color.parseColor(color));
        return v;
    }

    Button btn(String t) {
        Button x = new Button(this);
        x.setText(t);
        x.setTextColor(Color.parseColor("#3fd8ff"));
        x.setBackgroundColor(Color.parseColor("#14243a"));
        x.setMinWidth(dp(48));
        x.setMinimumWidth(dp(48));
        x.setMinHeight(dp(48));
        x.setMinimumHeight(dp(48));
        return x;
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#02060d"));
        root.setPadding(dp(14), dp(36), dp(14), dp(10));

        // Header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button gear = btn("⚙");
        gear.setOnClickListener(v -> showSettings());
        TextView title = tv("J.A.R.V.I.S", 20, "#3fd8ff");
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(.3f);
        head.addView(gear);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(head);

        // Clock + Weather
        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(6), 0, 0);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        clock = tv("--:--", 28, "#3fd8ff");
        date = tv("", 11, "#3fd8ff");
        left.addView(clock);
        left.addView(date);
        weather = tv("--° Yükleniyor", 13, "#4f7f9c");
        weather.setGravity(Gravity.END);
        info.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        info.addView(weather, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(info);

        // Ring
        ring = new RingView(this);
        root.addView(ring, new LinearLayout.LayoutParams(-1, 0, 1.2f));

        status = tv("HAZIR", 11, "#3fd8ff");
        status.setGravity(Gravity.CENTER);
        status.setLetterSpacing(.25f);
        root.addView(status);

        you = tv("", 12, "#4f7f9c");
        you.setGravity(Gravity.CENTER);
        root.addView(you);

        reply = tv("Emrinizdeyim, efendim.", 15, "#cdeeff");
        reply.setGravity(Gravity.CENTER);
        ScrollView rs = new ScrollView(this);
        rs.addView(reply);
        root.addView(rs, new LinearLayout.LayoutParams(-1, 0, .7f));

        // Control buttons
        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER);
        Button pause = btn("DURDUR"), clear = btn("TEMİZLE");
        clear.setTextColor(Color.parseColor("#ff5a5a"));
        pause.setOnClickListener(v -> {
            stopSpeak();
            if (!busy) setState(RingView.IDLE);
        });
        clear.setOnClickListener(v -> {
            history.clear();
            log.removeAllViews();
            reply.setText("Konuşma temizlendi, efendim.");
            you.setText("");
        });
        ctl.addView(pause);
        ctl.addView(clear);
        root.addView(ctl);

        // Log
        logScroll = new ScrollView(this);
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        logScroll.addView(log);
        logScroll.setBackgroundColor(Color.parseColor("#0a1828"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, .8f);
        lp.topMargin = dp(6);
        root.addView(logScroll, lp);

        // Input bar
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(6), 0, 0);
        Button attach = btn("📎");
        attach.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            startActivityForResult(i, 11);
        });
        input = new EditText(this);
        input.setHint("Mesaj yazınız…");
        input.setHintTextColor(Color.parseColor("#4f7f9c"));
        input.setTextColor(Color.parseColor("#cdeeff"));
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, a, e) -> {
            send(input.getText().toString());
            return true;
        });
        Button sendBtn = btn("➤");
        sendBtn.setOnClickListener(v -> send(input.getText().toString()));
        micBtn = btn("🎤");
        micBtn.setOnClickListener(v -> toggleMic());
        bar.addView(attach);
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(sendBtn);
        bar.addView(micBtn);
        root.addView(bar);

        setContentView(root);
    }

    void setState(int s) {
        runOnUiThread(() -> {
            ring.state = s;
            status.setText(new String[]{"HAZIR", "DİNLİYORUM", "DÜŞÜNÜYORUM", "KONUŞUYORUM"}[s]);
        });
    }

    TextView addMsg(boolean user, String t) {
        TextView v = tv((user ? "SİZ: " : "JARVIS: ") + t, 13, user ? "#4f7f9c" : "#cdeeff");
        v.setPadding(dp(8), dp(4), dp(8), dp(4));
        log.addView(v);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        return v;
    }

    void tick() {
        Date d = new Date();
        Locale tr = new Locale("tr", "TR");
        clock.setText(new SimpleDateFormat("HH:mm", tr).format(d));
        date.setText(new SimpleDateFormat("EEEE, d MMMM yyyy", tr).format(d));
    }

    /* ---------------- Weather ---------------- */
    void loadWeather() {
        new Thread(() -> {
            try {
                String city = prefs.getString("city", "");
                if (city.isEmpty()) city = "Istanbul";
                JSONObject g = new JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&language=tr&name="
                        + URLEncoder.encode(city, "UTF-8"), null));
                JSONObject r = g.getJSONArray("results").getJSONObject(0);
                JSONObject w = new JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=" + r.getDouble("latitude")
                        + "&longitude=" + r.getDouble("longitude") + "&current=temperature_2m,weather_code", null))
                        .getJSONObject("current");
                int code = w.getInt("weather_code");
                String desc = code == 0 ? "Açık" : code < 4 ? "Parçalı bulutlu" : code < 50 ? "Sisli"
                        : code < 70 ? "Yağmurlu" : code < 80 ? "Karlı" : "Fırtınalı";
                String txt = Math.round(w.getDouble("temperature_2m")) + "°  " + r.getString("name") + " • " + desc;
                runOnUiThread(() -> weather.setText(txt));
            } catch (Exception e) {
                runOnUiThread(() -> weather.setText("--° Hava durumu yok"));
            }
        }).start();
    }

    String httpGet(String u, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        if (key != null) c.setRequestProperty("Authorization", "Bearer " + key);
        if (c.getResponseCode() >= 400) throw new IOException("Hata " + c.getResponseCode());
        return readAll(c.getInputStream());
    }

    String readAll(InputStream in) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = br.readLine()) != null) sb.append(l).append('\n');
        return sb.toString();
    }

    /* ---------------- Speech out ---------------- */
    void say(String text) {
        text = text.trim();
        if (text.isEmpty() || !ttsReady) return;
        speakQ.incrementAndGet();
        setState(RingView.SPEAKING);
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime());
    }

    void spoken() {
        if (speakQ.decrementAndGet() <= 0 && !busy) setState(RingView.IDLE);
    }

    void stopSpeak() {
        if (ttsReady) tts.stop();
        speakQ.set(0);
    }

    /* ---------------- Chat (Groq) ---------------- */
    void send(String text) {
        text = text.trim();
        if (text.isEmpty() || busy) return;
        final String key = prefs.getString("key", "");
        if (key.isEmpty()) {
            showSettings();
            return;
        }
        final String model = prefs.getString("model", DEFAULT_MODELS[0]);
        stopSpeak();
        busy = true;
        setState(RingView.THINKING);
        you.setText(text);
        reply.setText("");
        input.setText("");
        addMsg(true, text);
        final TextView am = addMsg(false, "");

        String userContent = text;
        if (pending != null) {
            userContent = pending + "\n\n" + text;
            pending = null;
            pendingName = null;
        }
        final String uc = userContent, ut = text;

        new Thread(() -> {
            StringBuilder full = new StringBuilder(), buf = new StringBuilder();
            try {
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "system").put("content", SYSTEM));
                for (JSONObject h : history) msgs.put(h);
                msgs.put(new JSONObject().put("role", "user").put("content", uc));
                JSONObject body = new JSONObject().put("model", model).put("messages", msgs)
                        .put("stream", true).put("max_tokens", 1000);

                HttpURLConnection c = (HttpURLConnection) new URL(GROQ + "/chat/completions").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + key);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }

                int code = c.getResponseCode();
                if (code >= 400) {
                    String err = readAll(c.getErrorStream());
                    String m = "Hata " + code;
                    try {
                        m = new JSONObject(err).getJSONObject("error").getString("message");
                    } catch (Exception ignored) {}
                    throw new IOException(m);
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                Pattern sentence = Pattern.compile("^([\\s\\S]*?[.!?\\n])\\s");
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String d = line.substring(5).trim();
                    if (d.equals("[DONE]")) break;
                    try {
                        JSONObject j = new JSONObject(d);
                        JSONObject delta = j.getJSONArray("choices").getJSONObject(0).getJSONObject("delta");
                        String tx = delta.optString("content", "");
                        if (tx.isEmpty() || tx.equals("null")) continue;
                        full.append(tx);
                        buf.append(tx);
                        final String f = full.toString();
                        runOnUiThread(() -> {
                            reply.setText(f);
                            am.setText("JARVIS: " + f);
                            logScroll.fullScroll(View.FOCUS_DOWN);
                        });
                        Matcher m = sentence.matcher(buf);
                        if (m.find()) {
                            final String s = m.group(1);
                            buf.delete(0, m.end());
                            runOnUiThread(() -> say(s));
                        }
                    } catch (JSONException ignored) {}
                }
                if (buf.toString().trim().length() > 0) {
                    final String s = buf.toString();
                    runOnUiThread(() -> say(s));
                }
                history.add(new JSONObject().put("role", "user").put("content", ut + (uc.length() > ut.length() ? " [ek gönderildi]" : "")));
                history.add(new JSONObject().put("role", "assistant").put("content", full.toString()));
                while (history.size() > 20) history.remove(0);
            } catch (Exception e) {
                final String m = "Bir sorun var, efendim: " + e.getMessage();
                runOnUiThread(() -> {
                    reply.setText(m);
                    am.setText("JARVIS: " + m);
                });
            }
            busy = false;
            runOnUiThread(() -> {
                if (speakQ.get() <= 0) setState(RingView.IDLE);
            });
        }).start();
    }

    /* ---------------- Attach ---------------- */
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 11 || res != RESULT_OK || data == null) return;
        Uri u = data.getData();
        try (InputStream in = getContentResolver().openInputStream(u)) {
            String t = readAll(in);
            if (t.length() > 60000) t = t.substring(0, 60000);
            pendingName = u.getLastPathSegment();
            pending = "[Dosya: " + pendingName + "]\n" + t;
            reply.setText("📎 " + pendingName + " eklendi, göndermek için mesaj yazın.");
        } catch (Exception e) {
            reply.setText("Dosya okunamadı: " + e.getMessage());
        }
    }

    /* ---------------- Mic ---------------- */
    void toggleMic() {
        if (listening) {
            if (recognizer != null) recognizer.stopListening();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 5);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            reply.setText("Cihazda ses tanıma servisi yok, efendim.");
            return;
        }
        stopSpeak();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            String last = "";
            public void onReadyForSpeech(Bundle p) {
                listening = true;
                setState(RingView.LISTENING);
                micBtn.setTextColor(Color.WHITE);
            }
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float v) {}
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() {}
            public void onEvent(int t, Bundle b) {}
            public void onPartialResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) {
                    last = r.get(0);
                    you.setText(last);
                }
            }
            public void onResults(Bundle b) {
                listening = false;
                micBtn.setTextColor(Color.parseColor("#3fd8ff"));
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String t = (r != null && !r.isEmpty()) ? r.get(0) : last;
                if (t != null && !t.isEmpty()) send(t);
                else setState(RingView.IDLE);
                recognizer.destroy();
            }
            public void onError(int e) {
                listening = false;
                micBtn.setTextColor(Color.parseColor("#3fd8ff"));
                setState(RingView.IDLE);
                reply.setText("Sizi duyamadım, efendim.");
                recognizer.destroy();
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(i);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        if (rc == 5 && r.length > 0 && r[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) toggleMic();
    }

    /* ---------------- Settings (Groq key + model) ---------------- */
    void showSettings() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(10), dp(20), 0);

        TextView info = tv("Groq API anahtarınızı yapıştırın (console.groq.com/keys). Sadece bu cihazda saklanır.", 12, "#555555");
        final EditText key = new EditText(this);
        key.setHint("gsk_...");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(prefs.getString("key", ""));

        TextView ml = tv("Model", 12, "#555555");
        final Spinner sp = new Spinner(this);
        final List<String> models = new ArrayList<>(Arrays.asList(DEFAULT_MODELS));
        String cur = prefs.getString("model", DEFAULT_MODELS[0]);
        if (!models.contains(cur)) models.add(0, cur);
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, models);
        sp.setAdapter(ad);
        sp.setSelection(models.indexOf(cur));

        Button refresh = new Button(this);
        refresh.setText("Modelleri yenile");
        refresh.setOnClickListener(v -> {
            final String k = key.getText().toString().trim();
            if (k.isEmpty()) {
                Toast.makeText(this, "Önce API anahtarı girin", Toast.LENGTH_SHORT).show();
                return;
            }
            new Thread(() -> {
                try {
                    JSONArray arr = new JSONObject(httpGet(GROQ + "/models", k)).getJSONArray("data");
                    final List<String> ids = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String id = arr.getJSONObject(i).getString("id");
                        if (id.contains("whisper") || id.contains("guard") || id.contains("tts") || id.contains("orpheus")) continue;
                        ids.add(id);
                    }
                    Collections.sort(ids);
                    runOnUiThread(() -> {
                        String sel = (String) sp.getSelectedItem();
                        ad.clear();
                        ad.addAll(ids);
                        int idx = ids.indexOf(sel);
                        sp.setSelection(idx >= 0 ? idx : 0);
                        Toast.makeText(this, ids.size() + " model yüklendi", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Liste alınamadı: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });

        final EditText city = new EditText(this);
        city.setHint("Şehir (hava durumu için)");
        city.setText(prefs.getString("city", ""));

        l.addView(info);
        l.addView(key);
        l.addView(ml);
        l.addView(sp);
        l.addView(refresh);
        l.addView(city);

        new AlertDialog.Builder(this)
                .setTitle("Ayarlar")
                .setView(l)
                .setPositiveButton("Kaydet", (d, w) -> {
                    String m = (String) sp.getSelectedItem();
                    prefs.edit().putString("key", key.getText().toString().trim())
                            .putString("city", city.getText().toString().trim())
                            .putString("model", m == null ? DEFAULT_MODELS[0] : m).apply();
                    loadWeather();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timer.cancel();
        if (tts != null) tts.shutdown();
        if (recognizer != null) recognizer.destroy();
    }
}