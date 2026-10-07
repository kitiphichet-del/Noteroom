package com.kitiphichet.livelecturenotes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements RecognitionListener {

    private static final int REQ_AUDIO = 101;
    private static final int REQ_SAVE = 202;
    private static final String PREFS = "live_notes_prefs";
    private static final String KEY_DRAFT = "draft";

    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText transcriptView;
    private TextView statusView;
    private TextView timerView;
    private TextView liveHintView;
    private Button startStopButton;
    private Spinner languageSpinner;
    private Spinner delaySpinner;

    private boolean listeningRequested = false;
    private boolean recognizerRunning = false;
    private long startedAt = 0L;
    private int displayDelayMs = 250;
    private String languageCode = "th-TH";
    private String committedText = "";
    private String pendingPartial = "";
    private String lastCommittedSegment = "";
    private Runnable pendingPartialRender;

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (!listeningRequested || startedAt == 0L) return;
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            long sec = elapsed / 1000;
            timerView.setText(String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        committedText = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DRAFT, "");
        transcriptView.setText(committedText);
        transcriptView.setSelection(transcriptView.length());
        setupRecognizer();
    }

    private void buildUi() {
        int pad = dp(18);
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setFillViewport(true);
        rootScroll.setBackgroundColor(Color.rgb(246, 247, 251));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(18), pad, dp(18));
        rootScroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Live Lecture Notes");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(17, 24, 39));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("จดคำบรรยายจากเสียงเป็นข้อความแบบสด • ค่อย ๆ แสดงระหว่างพูด");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.rgb(102, 112, 133));
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusRow.setBackground(roundRect(Color.WHITE, dp(14), Color.rgb(228,231,236)));

        statusView = new TextView(this);
        statusView.setText("● พร้อมใช้งาน");
        statusView.setTextSize(14);
        statusView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusView.setTextColor(Color.rgb(15, 157, 88));
        statusRow.addView(statusView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        timerView = new TextView(this);
        timerView.setText("00:00:00");
        timerView.setTextSize(14);
        timerView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timerView.setTextColor(Color.rgb(17, 24, 39));
        statusRow.addView(timerView);
        root.addView(statusRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(0, dp(12), 0, dp(10));

        languageSpinner = new Spinner(this);
        String[] langs = {"ไทย", "English", "中文简体"};
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, langs);
        languageSpinner.setAdapter(langAdapter);
        languageSpinner.setSelection(0);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                languageCode = position == 1 ? "en-US" : (position == 2 ? "zh-CN" : "th-TH");
                if (listeningRequested) restartRecognizer(250);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        controls.addView(languageSpinner, new LinearLayout.LayoutParams(0, dp(48), 1f));

        delaySpinner = new Spinner(this);
        String[] delays = {"ทันที", "หน่วง 0.25 วิ", "หน่วง 0.5 วิ"};
        ArrayAdapter<String> delayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, delays);
        delaySpinner.setAdapter(delayAdapter);
        delaySpinner.setSelection(1);
        delaySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                displayDelayMs = position == 0 ? 0 : (position == 2 ? 500 : 250);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        LinearLayout.LayoutParams delayParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        delayParams.setMargins(dp(8), 0, 0, 0);
        controls.addView(delaySpinner, delayParams);
        root.addView(controls);

        TextView transcriptLabel = new TextView(this);
        transcriptLabel.setText("บันทึกคำบรรยาย");
        transcriptLabel.setTextSize(15);
        transcriptLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        transcriptLabel.setTextColor(Color.rgb(17, 24, 39));
        transcriptLabel.setPadding(0, dp(3), 0, dp(8));
        root.addView(transcriptLabel);

        transcriptView = new EditText(this);
        transcriptView.setMinHeight(dp(330));
        transcriptView.setGravity(Gravity.TOP | Gravity.START);
        transcriptView.setTextSize(18);
        transcriptView.setTextColor(Color.rgb(17, 24, 39));
        transcriptView.setHint("ข้อความจากเสียงจะค่อย ๆ ปรากฏที่นี่…");
        transcriptView.setHintTextColor(Color.rgb(152, 162, 179));
        transcriptView.setPadding(dp(16), dp(16), dp(16), dp(16));
        transcriptView.setBackground(roundRect(Color.WHITE, dp(16), Color.rgb(228,231,236)));
        transcriptView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(transcriptView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        liveHintView = new TextView(this);
        liveHintView.setText("โหมดสดจะแสดงผลลัพธ์ชั่วคราวระหว่างพูด และอาจแก้คำล่าสุดเองเมื่อได้ยินชัดขึ้น");
        liveHintView.setTextSize(12);
        liveHintView.setTextColor(Color.rgb(102, 112, 133));
        liveHintView.setPadding(dp(2), dp(8), dp(2), dp(10));
        root.addView(liveHintView);

        startStopButton = new Button(this);
        startStopButton.setText("● เริ่มจดบันทึกสด");
        startStopButton.setAllCaps(false);
        startStopButton.setTextSize(17);
        startStopButton.setTextColor(Color.WHITE);
        startStopButton.setBackground(roundRect(Color.rgb(36, 87, 245), dp(14), Color.rgb(36,87,245)));
        startStopButton.setOnClickListener(v -> {
            if (listeningRequested) stopLiveMode(); else startLiveMode();
        });
        root.addView(startStopButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);

        Button copy = smallButton("คัดลอก");
        copy.setOnClickListener(v -> copyText());
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button save = smallButton("บันทึก .txt");
        save.setOnClickListener(v -> saveTextFile());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        saveParams.setMargins(dp(8), 0, dp(8), 0);
        actions.addView(save, saveParams);

        Button clear = smallButton("ล้าง");
        clear.setOnClickListener(v -> confirmClear());
        actions.addView(clear, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(actions);

        TextView privacy = new TextView(this);
        privacy.setText("หมายเหตุ: การรู้จำเสียงขึ้นกับ Speech Recognition Service ของเครื่อง และแพ็กภาษาในอุปกรณ์");
        privacy.setTextSize(11);
        privacy.setTextColor(Color.rgb(102,112,133));
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(0, dp(14), 0, dp(3));
        root.addView(privacy);

        TextView version = new TextView(this);
        version.setText("Version 1.0.0");
        version.setTextSize(11);
        version.setTextColor(Color.rgb(152,162,179));
        version.setGravity(Gravity.CENTER);
        root.addView(version);

        setContentView(rootScroll);
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.rgb(17,24,39));
        b.setBackground(roundRect(Color.WHITE, dp(12), Color.rgb(228,231,236)));
        return b;
    }

    private GradientDrawable roundRect(int fillColor, int radiusPx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private void setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.setText("● ไม่พบระบบรู้จำเสียง");
            statusView.setTextColor(Color.rgb(217,45,32));
            startStopButton.setEnabled(false);
            return;
        }
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
    }

    private Intent buildRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        return intent;
    }

    private void startLiveMode() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }

        committedText = transcriptView.getText().toString().trim();
        pendingPartial = "";
        lastCommittedSegment = "";
        transcriptView.setFocusable(false);
        transcriptView.setFocusableInTouchMode(false);
        listeningRequested = true;
        startedAt = SystemClock.elapsedRealtime();
        timerView.setText("00:00:00");
        handler.removeCallbacks(timerTick);
        handler.post(timerTick);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        startStopButton.setText("■ หยุดจดบันทึก");
        startStopButton.setBackground(roundRect(Color.rgb(217,45,32), dp(14), Color.rgb(217,45,32)));
        statusView.setText("● กำลังฟัง…");
        statusView.setTextColor(Color.rgb(15,157,88));
        startRecognizerNow();
    }

    private void startRecognizerNow() {
        if (!listeningRequested || recognizer == null || recognizerRunning) return;
        recognizerIntent = buildRecognizerIntent();
        try {
            recognizerRunning = true;
            recognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            recognizerRunning = false;
            statusView.setText("● กำลังเชื่อมระบบเสียงใหม่…");
            restartRecognizer(700);
        }
    }

    private void stopLiveMode() {
        listeningRequested = false;
        handler.removeCallbacks(timerTick);
        if (pendingPartialRender != null) handler.removeCallbacks(pendingPartialRender);
        pendingPartial = "";

        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) {}
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        recognizerRunning = false;

        committedText = transcriptView.getText().toString().trim();
        transcriptView.setFocusableInTouchMode(true);
        transcriptView.setFocusable(true);
        transcriptView.requestFocus();
        transcriptView.setSelection(transcriptView.length());
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        startStopButton.setText("● เริ่มจดบันทึกสด");
        startStopButton.setBackground(roundRect(Color.rgb(36,87,245), dp(14), Color.rgb(36,87,245)));
        statusView.setText("● หยุดแล้ว — แก้ไขข้อความได้");
        statusView.setTextColor(Color.rgb(102,112,133));
        persistDraft();
    }

    private void restartRecognizer(long delayMs) {
        if (!listeningRequested) return;
        try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) {}
        recognizerRunning = false;
        handler.postDelayed(this::startRecognizerNow, delayMs);
    }

    private void renderCombinedText(String partial) {
        String base = committedText == null ? "" : committedText.trim();
        String p = partial == null ? "" : partial.trim();
        String combined;
        if (base.isEmpty()) combined = p;
        else if (p.isEmpty()) combined = base;
        else combined = base + " " + p;
        transcriptView.setText(combined);
        transcriptView.setSelection(transcriptView.length());
    }

    private void appendFinal(String segment) {
        if (segment == null) return;
        String s = segment.trim();
        if (s.isEmpty()) return;
        if (s.equals(lastCommittedSegment)) return;
        lastCommittedSegment = s;
        if (committedText == null || committedText.trim().isEmpty()) committedText = s;
        else committedText = committedText.trim() + " " + s;
        pendingPartial = "";
        renderCombinedText("");
        persistDraft();
    }

    private void persistDraft() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_DRAFT, transcriptView.getText().toString()).apply();
    }

    private void copyText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Live Lecture Notes", transcriptView.getText().toString()));
        Toast.makeText(this, "คัดลอกข้อความแล้ว", Toast.LENGTH_SHORT).show();
    }

    private void saveTextFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        String ts = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "lecture_notes_" + ts + ".txt");
        startActivityForResult(intent, REQ_SAVE);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("ล้างข้อความทั้งหมด?")
                .setMessage("ข้อความที่ยังไม่ได้บันทึกเป็นไฟล์จะถูกลบ")
                .setNegativeButton("ยกเลิก", null)
                .setPositiveButton("ล้าง", (dialog, which) -> {
                    committedText = "";
                    pendingPartial = "";
                    lastCommittedSegment = "";
                    transcriptView.setText("");
                    persistDraft();
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    os.write(transcriptView.getText().toString().getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "บันทึกไฟล์แล้ว", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "บันทึกไฟล์ไม่สำเร็จ", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLiveMode();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("ต้องใช้ไมโครโฟน")
                        .setMessage("แอปต้องได้รับสิทธิ์ไมโครโฟนเพื่อแปลงเสียงเป็นข้อความ")
                        .setNegativeButton("ยกเลิก", null)
                        .setPositiveButton("เปิดการตั้งค่า", (d, w) -> {
                            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        }).show();
            }
        }
    }

    @Override public void onReadyForSpeech(Bundle params) {
        statusView.setText("● พร้อมรับเสียง");
        statusView.setTextColor(Color.rgb(15,157,88));
    }

    @Override public void onBeginningOfSpeech() {
        statusView.setText("● กำลังถอดคำ…");
    }

    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}

    @Override public void onEndOfSpeech() {
        statusView.setText("● ประมวลผลช่วงล่าสุด…");
    }

    @Override
    public void onError(int error) {
        recognizerRunning = false;
        if (!listeningRequested) return;

        long delay = 350;
        switch (error) {
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                statusView.setText("● กำลังต่อรอบการฟัง…");
                delay = 800;
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                statusView.setText("● สัญญาณไม่เสถียร — ลองใหม่อัตโนมัติ");
                delay = 1200;
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                statusView.setText("● ไม่มีสิทธิ์ใช้ไมโครโฟน");
                stopLiveMode();
                return;
            case SpeechRecognizer.ERROR_AUDIO:
                statusView.setText("● ไมโครโฟนขัดข้อง — ลองใหม่");
                delay = 900;
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                statusView.setText("● กำลังฟัง…");
                delay = 250;
                break;
            default:
                statusView.setText("● กำลังเริ่มฟังใหม่…");
                delay = 600;
        }
        restartRecognizer(delay);
    }

    @Override
    public void onResults(Bundle results) {
        recognizerRunning = false;
        ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) appendFinal(matches.get(0));
        if (listeningRequested) {
            statusView.setText("● กำลังฟังต่อ…");
            handler.postDelayed(this::startRecognizerNow, 180);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults == null ? null : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;
        pendingPartial = matches.get(0);
        if (pendingPartialRender != null) handler.removeCallbacks(pendingPartialRender);
        pendingPartialRender = () -> {
            if (listeningRequested) renderCombinedText(pendingPartial);
        };
        handler.postDelayed(pendingPartialRender, displayDelayMs);
    }

    @Override public void onEvent(int eventType, Bundle params) {}

    @Override
    protected void onPause() {
        super.onPause();
        persistDraft();
    }

    @Override
    protected void onDestroy() {
        listeningRequested = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            recognizer.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
