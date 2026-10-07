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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements RecognitionListener {

    private static final int REQ_AUDIO = 101;
    private static final int REQ_SAVE = 202;
    private static final String PREFS = "live_notes_prefs";
    private static final String KEY_DRAFT = "draft";
    private static final String NOTES_DIR = "notes";

    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText transcriptView;
    private TextView statusView;
    private TextView timerView;
    private TextView liveHintView;
    private Button startStopButton;
    private Button libraryButton;
    private Spinner languageSpinner;
    private Spinner delaySpinner;

    private boolean listeningRequested = false;
    private boolean recognizerRunning = false;
    private long startedAt = 0L;
    private long lastRecognizerActivityAt = 0L;
    private long lastCommittedAt = 0L;
    private int displayDelayMs = 250;
    private String languageCode = "th-TH";
    private String committedText = "";
    private String pendingPartial = "";
    private String lastCommittedSegment = "";
    private String currentNoteFileName = null;
    private String pendingExportText = null;
    private String pendingExportName = null;
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

    // Keeps the native Android recognizer alive for long lectures. Some devices end
    // a recognition session after silence or after an internal service timeout.
    private final Runnable continuousWatchdog = new Runnable() {
        @Override public void run() {
            if (!listeningRequested) return;
            long now = SystemClock.elapsedRealtime();
            long inactiveFor = now - lastRecognizerActivityAt;

            if (!recognizerRunning || inactiveFor > 8000L) {
                commitPendingPartial();
                try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) {}
                recognizerRunning = false;
                startRecognizerNow();
            }
            handler.postDelayed(this, 2000L);
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
        refreshLibraryCount();
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
        subtitle.setText("ถอดเสียงต่อเนื่องจนกว่าจะกดหยุด • ข้อความสะสมไม่หายระหว่างรอบฟัง");
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
        liveHintView.setText("โหมดต่อเนื่อง: ระบบจะต่อรอบฟังให้อัตโนมัติเมื่อ Android จบช่วงเสียง และสะสมข้อความไว้จนกว่าจะกดหยุด");
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

        LinearLayout fileActions = new LinearLayout(this);
        fileActions.setOrientation(LinearLayout.HORIZONTAL);
        fileActions.setPadding(0, dp(10), 0, 0);

        Button saveInside = smallButton("บันทึก");
        saveInside.setOnClickListener(v -> manualSaveCurrentNote());
        fileActions.addView(saveInside, new LinearLayout.LayoutParams(0, dp(48), 1f));

        libraryButton = smallButton("คลังบันทึก");
        libraryButton.setOnClickListener(v -> showNotesLibrary());
        LinearLayout.LayoutParams libraryParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        libraryParams.setMargins(dp(8), 0, dp(8), 0);
        fileActions.addView(libraryButton, libraryParams);

        Button newNote = smallButton("บันทึกใหม่");
        newNote.setOnClickListener(v -> createNewNote());
        fileActions.addView(newNote, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(fileActions);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button copy = smallButton("คัดลอก");
        copy.setOnClickListener(v -> copyText());
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button export = smallButton("ส่งออก .txt");
        export.setOnClickListener(v -> saveTextFile());
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        exportParams.setMargins(dp(8), 0, dp(8), 0);
        actions.addView(export, exportParams);

        Button clear = smallButton("ล้างหน้า");
        clear.setOnClickListener(v -> confirmClear());
        actions.addView(clear, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(actions);

        TextView privacy = new TextView(this);
        privacy.setText("ไฟล์ในคลังเก็บภายในพื้นที่ส่วนตัวของแอป • ลบเฉพาะรายการได้ • ส่งออกเป็น .txt ได้");
        privacy.setTextSize(11);
        privacy.setTextColor(Color.rgb(102,112,133));
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(0, dp(14), 0, dp(3));
        root.addView(privacy);

        TextView version = new TextView(this);
        version.setText("Version 1.2.0");
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
        lastRecognizerActivityAt = startedAt;
        timerView.setText("00:00:00");
        handler.removeCallbacks(timerTick);
        handler.removeCallbacks(continuousWatchdog);
        handler.post(timerTick);
        handler.postDelayed(continuousWatchdog, 2000L);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        startStopButton.setText("■ หยุดและบันทึก");
        startStopButton.setBackground(roundRect(Color.rgb(217,45,32), dp(14), Color.rgb(217,45,32)));
        statusView.setText("● กำลังฟังต่อเนื่อง…");
        statusView.setTextColor(Color.rgb(15,157,88));
        startRecognizerNow();
    }

    private void startRecognizerNow() {
        if (!listeningRequested || recognizer == null || recognizerRunning) return;
        recognizerIntent = buildRecognizerIntent();
        try {
            recognizerRunning = true;
            lastRecognizerActivityAt = SystemClock.elapsedRealtime();
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
        handler.removeCallbacks(continuousWatchdog);
        if (pendingPartialRender != null) handler.removeCallbacks(pendingPartialRender);
        commitPendingPartial();
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
        persistDraft();

        boolean saved = autoSaveCurrentNote(false);
        if (saved) {
            statusView.setText("● หยุดแล้ว • บันทึกเข้าไฟล์อัตโนมัติ");
            statusView.setTextColor(Color.rgb(15,157,88));
        } else {
            statusView.setText("● หยุดแล้ว — ไม่มีข้อความให้บันทึก");
            statusView.setTextColor(Color.rgb(102,112,133));
        }
    }

    private void restartRecognizer(long delayMs) {
        if (!listeningRequested) return;
        commitPendingPartial();
        try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) {}
        recognizerRunning = false;
        lastRecognizerActivityAt = SystemClock.elapsedRealtime();
        handler.postDelayed(this::startRecognizerNow, Math.max(60L, delayMs));
    }

    private void commitPendingPartial() {
        String p = pendingPartial == null ? "" : pendingPartial.trim();
        if (!p.isEmpty()) {
            appendFinal(p);
            pendingPartial = "";
        }
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
        long now = SystemClock.elapsedRealtime();
        if (s.equals(lastCommittedSegment) && (now - lastCommittedAt) < 1200L) return;
        lastCommittedSegment = s;
        lastCommittedAt = now;
        if (committedText == null || committedText.trim().isEmpty()) committedText = s;
        else committedText = committedText.trim() + " " + s;
        pendingPartial = "";
        renderCombinedText("");
        persistDraft();
        if (currentNoteFileName != null) autoSaveCurrentNote(false);
    }

    private void persistDraft() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_DRAFT, transcriptView.getText().toString()).apply();
    }

    private File getNotesDir() {
        File dir = new File(getFilesDir(), NOTES_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private boolean autoSaveCurrentNote(boolean showToast) {
        String text = transcriptView.getText().toString().trim();
        if (text.isEmpty()) {
            if (showToast) Toast.makeText(this, "ยังไม่มีข้อความให้บันทึก", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (currentNoteFileName == null) {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentNoteFileName = "lecture_" + ts + ".txt";
        }

        File file = new File(getNotesDir(), currentNoteFileName);
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            committedText = text;
            persistDraft();
            refreshLibraryCount();
            if (showToast) Toast.makeText(this, "บันทึกไฟล์แล้ว", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            if (showToast) Toast.makeText(this, "บันทึกไฟล์ไม่สำเร็จ", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void manualSaveCurrentNote() {
        if (listeningRequested) {
            committedText = transcriptView.getText().toString().trim();
        }
        if (autoSaveCurrentNote(true)) {
            statusView.setText("● บันทึกไฟล์แล้ว");
            statusView.setTextColor(Color.rgb(15,157,88));
        }
    }

    private void createNewNote() {
        if (listeningRequested) stopLiveMode();
        if (!transcriptView.getText().toString().trim().isEmpty() && currentNoteFileName == null) {
            autoSaveCurrentNote(false);
        }

        committedText = "";
        pendingPartial = "";
        lastCommittedSegment = "";
        currentNoteFileName = null;
        transcriptView.setText("");
        timerView.setText("00:00:00");
        startedAt = 0L;
        persistDraft();
        statusView.setText("● บันทึกใหม่ — พร้อมเริ่ม");
        statusView.setTextColor(Color.rgb(36,87,245));
    }

    private void refreshLibraryCount() {
        if (libraryButton == null) return;
        File[] files = listNoteFiles();
        libraryButton.setText("คลังบันทึก (" + files.length + ")");
    }

    private File[] listNoteFiles() {
        File[] files = getNotesDir().listFiles(file ->
                file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".txt"));
        if (files == null) files = new File[0];
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    private String readNoteFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = fis.read(buffer)) > 0) {
                bos.write(buffer, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private String noteDisplayTitle(File file) {
        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date(file.lastModified()));
        return "บันทึก " + date;
    }

    private String noteSnippet(String text) {
        if (text == null) return "";
        String clean = text.replace("\n", " ").replace("\r", " ").trim();
        return clean.length() > 80 ? clean.substring(0, 80) + "…" : clean;
    }

    private void showNotesLibrary() {
        File[] files = listNoteFiles();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        final AlertDialog[] holder = new AlertDialog[1];

        if (files.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("ยังไม่มีไฟล์บันทึก\nเมื่อกดหยุดการฟัง ระบบจะบันทึกเข้าในคลังอัตโนมัติ");
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(15);
            empty.setTextColor(Color.rgb(102,112,133));
            empty.setPadding(dp(18), dp(28), dp(18), dp(28));
            content.addView(empty);
        } else {
            for (File file : files) {
                String text = readNoteFile(file);

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(14), dp(12), dp(14), dp(12));
                card.setBackground(roundRect(Color.WHITE, dp(14), Color.rgb(228,231,236)));

                TextView cardTitle = new TextView(this);
                cardTitle.setText(noteDisplayTitle(file));
                cardTitle.setTextSize(15);
                cardTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                cardTitle.setTextColor(Color.rgb(17,24,39));
                card.addView(cardTitle);

                TextView snippet = new TextView(this);
                snippet.setText(noteSnippet(text));
                snippet.setTextSize(13);
                snippet.setTextColor(Color.rgb(102,112,133));
                snippet.setPadding(0, dp(5), 0, dp(9));
                card.addView(snippet);

                LinearLayout buttons = new LinearLayout(this);
                buttons.setOrientation(LinearLayout.HORIZONTAL);

                Button open = smallButton("เปิด");
                open.setOnClickListener(v -> {
                    openNote(file);
                    if (holder[0] != null) holder[0].dismiss();
                });
                buttons.addView(open, new LinearLayout.LayoutParams(0, dp(44), 1f));

                Button export = smallButton("ส่งออก");
                export.setOnClickListener(v -> {
                    exportNote(file);
                    if (holder[0] != null) holder[0].dismiss();
                });
                LinearLayout.LayoutParams exportP = new LinearLayout.LayoutParams(0, dp(44), 1f);
                exportP.setMargins(dp(7), 0, dp(7), 0);
                buttons.addView(export, exportP);

                Button delete = smallButton("ลบ");
                delete.setTextColor(Color.rgb(217,45,32));
                delete.setOnClickListener(v -> confirmDeleteNote(file, holder[0]));
                buttons.addView(delete, new LinearLayout.LayoutParams(0, dp(44), 1f));

                card.addView(buttons);

                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                cardParams.setMargins(0, 0, 0, dp(10));
                content.addView(card, cardParams);
            }
        }

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("คลังบันทึก • " + files.length + " ไฟล์")
                .setView(scroll)
                .setNegativeButton("ปิด", null)
                .create();
        holder[0].show();
    }

    private void openNote(File file) {
        if (listeningRequested) stopLiveMode();
        String text = readNoteFile(file);
        if (text == null) {
            Toast.makeText(this, "เปิดไฟล์ไม่สำเร็จ", Toast.LENGTH_LONG).show();
            return;
        }

        currentNoteFileName = file.getName();
        committedText = text.trim();
        pendingPartial = "";
        lastCommittedSegment = "";
        transcriptView.setText(text);
        transcriptView.setSelection(transcriptView.length());
        persistDraft();
        statusView.setText("● เปิด " + noteDisplayTitle(file));
        statusView.setTextColor(Color.rgb(36,87,245));
    }

    private void confirmDeleteNote(File file, AlertDialog libraryDialog) {
        new AlertDialog.Builder(this)
                .setTitle("ลบบันทึกนี้?")
                .setMessage(noteDisplayTitle(file) + "\n\nเมื่อลบแล้วจะไม่สามารถเรียกคืนจากแอปได้")
                .setNegativeButton("ยกเลิก", null)
                .setPositiveButton("ลบ", (dialog, which) -> {
                    boolean deleted = file.delete();
                    if (deleted) {
                        if (file.getName().equals(currentNoteFileName)) {
                            currentNoteFileName = null;
                        }
                        refreshLibraryCount();
                        Toast.makeText(this, "ลบไฟล์แล้ว", Toast.LENGTH_SHORT).show();
                        if (libraryDialog != null) libraryDialog.dismiss();
                        handler.postDelayed(this::showNotesLibrary, 120);
                    } else {
                        Toast.makeText(this, "ลบไฟล์ไม่สำเร็จ", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void exportNote(File file) {
        String text = readNoteFile(file);
        if (text == null) {
            Toast.makeText(this, "อ่านไฟล์ไม่สำเร็จ", Toast.LENGTH_LONG).show();
            return;
        }
        pendingExportText = text;
        pendingExportName = file.getName();
        launchExportDocument();
    }

    private void copyText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Live Lecture Notes", transcriptView.getText().toString()));
        Toast.makeText(this, "คัดลอกข้อความแล้ว", Toast.LENGTH_SHORT).show();
    }

    private void saveTextFile() {
        String text = transcriptView.getText().toString();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, "ยังไม่มีข้อความให้ส่งออก", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExportText = text;
        String ts = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        pendingExportName = currentNoteFileName != null ? currentNoteFileName : "lecture_notes_" + ts + ".txt";
        launchExportDocument();
    }

    private void launchExportDocument() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, pendingExportName == null ? "lecture_notes.txt" : pendingExportName);
        startActivityForResult(intent, REQ_SAVE);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("ล้างข้อความหน้านี้?")
                .setMessage("ไฟล์ที่บันทึกไว้ในคลังจะไม่ถูกลบ")
                .setNegativeButton("ยกเลิก", null)
                .setPositiveButton("ล้างหน้า", (dialog, which) -> {
                    if (listeningRequested) stopLiveMode();
                    committedText = "";
                    pendingPartial = "";
                    lastCommittedSegment = "";
                    currentNoteFileName = null;
                    transcriptView.setText("");
                    persistDraft();
                    statusView.setText("● ล้างหน้าปัจจุบันแล้ว");
                    statusView.setTextColor(Color.rgb(102,112,133));
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            String exportText = pendingExportText != null ? pendingExportText : transcriptView.getText().toString();
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    os.write(exportText.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "ส่งออกไฟล์แล้ว", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "ส่งออกไฟล์ไม่สำเร็จ", Toast.LENGTH_LONG).show();
            } finally {
                pendingExportText = null;
                pendingExportName = null;
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
        lastRecognizerActivityAt = SystemClock.elapsedRealtime();
        statusView.setText("● กำลังฟังต่อเนื่อง…");
        statusView.setTextColor(Color.rgb(15,157,88));
    }

    @Override public void onBeginningOfSpeech() {
        lastRecognizerActivityAt = SystemClock.elapsedRealtime();
        statusView.setText("● กำลังถอดคำต่อเนื่อง…");
    }

    @Override public void onRmsChanged(float rmsdB) {
        lastRecognizerActivityAt = SystemClock.elapsedRealtime();
    }

    @Override public void onBufferReceived(byte[] buffer) {
        lastRecognizerActivityAt = SystemClock.elapsedRealtime();
    }

    @Override public void onEndOfSpeech() {
        lastRecognizerActivityAt = SystemClock.elapsedRealtime();
        statusView.setText("● กำลังต่อรอบการฟัง…");
    }

    @Override
    public void onError(int error) {
        recognizerRunning = false;
        lastRecognizerActivityAt = SystemClock.elapsedRealtime();
        if (!listeningRequested) return;

        commitPendingPartial();
        long delay = 120;
        switch (error) {
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                statusView.setText("● กำลังต่อรอบการฟัง…");
                delay = 350;
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                statusView.setText("● สัญญาณไม่เสถียร — ลองใหม่อัตโนมัติ");
                delay = 800;
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                statusView.setText("● ไม่มีสิทธิ์ใช้ไมโครโฟน");
                stopLiveMode();
                return;
            case SpeechRecognizer.ERROR_AUDIO:
                statusView.setText("● ไมโครโฟนขัดข้อง — ลองใหม่");
                delay = 500;
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                statusView.setText("● กำลังฟังต่อเนื่อง…");
                delay = 80;
                break;
            default:
                statusView.setText("● กำลังเริ่มฟังใหม่…");
                delay = 250;
        }
        restartRecognizer(delay);
    }

    @Override
    public void onResults(Bundle results) {
        recognizerRunning = false;
        lastRecognizerActivityAt = SystemClock.elapsedRealtime();
        ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) appendFinal(matches.get(0));
        pendingPartial = "";
        if (listeningRequested) {
            statusView.setText("● กำลังฟังต่อเนื่อง…");
            handler.postDelayed(this::startRecognizerNow, 60L);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        lastRecognizerActivityAt = SystemClock.elapsedRealtime();
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
        if (currentNoteFileName != null && !transcriptView.getText().toString().trim().isEmpty()) {
            autoSaveCurrentNote(false);
        }
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
