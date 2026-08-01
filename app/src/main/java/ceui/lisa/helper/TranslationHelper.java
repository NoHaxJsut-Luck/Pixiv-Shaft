package ceui.lisa.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import ceui.pixiv.translation.TranslationManager;

/** Lifecycle-aware bridge for legacy Java novel screens. */
public class TranslationHelper {

    private static final String TAG = "TranslationHelper";

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile View progressView;
    private volatile Thread workerThread;

    public TranslationHelper(Context context, View progressView) {
        this.appContext = context.getApplicationContext();
        this.progressView = progressView;
    }

    public interface TranslationCallback {
        void onTranslationComplete(String result);

        void onTranslationFailed(Exception e);

        void onTranslationChunkReceived(int chunkIndex, String chunkResult, String partialResult);
    }

    public synchronized boolean translate(
            final String novelText,
            final String apiKey,
            final TranslationCallback callback
    ) {
        if (isRunning()) {
            return false;
        }

        cancelled.set(false);
        showTranslationProgress(true);
        final long startTime = System.currentTimeMillis();

        workerThread = new Thread(() -> {
            try {
                TranslationManager translationManager = TranslationManager.Companion.getInstance();
                String translatedText = translationManager.translateForJava(
                        appContext,
                        novelText,
                        apiKey,
                        "Japanese",
                        "Chinese",
                        partialResult -> postToMain(() ->
                                callback.onTranslationChunkReceived(0, partialResult, partialResult)),
                        (completed, total, translatedChars, totalChars) -> postToMain(() ->
                                updateTranslationProgress(
                                        completed,
                                        total,
                                        translatedChars,
                                        totalChars,
                                        startTime
                                ))
                );

                postToMain(() -> {
                    showTranslationProgress(false);
                    callback.onTranslationComplete(translatedText);
                });
            } catch (Exception e) {
                if (!cancelled.get()) {
                    Log.e(TAG, "Translation failed", e);
                    postToMain(() -> {
                        showTranslationProgress(false);
                        callback.onTranslationFailed(e);
                    });
                }
            } finally {
                synchronized (TranslationHelper.this) {
                    workerThread = null;
                }
            }
        }, "legacy-novel-translation");
        workerThread.start();
        return true;
    }

    public synchronized boolean isRunning() {
        return workerThread != null && workerThread.isAlive();
    }

    public synchronized void release() {
        cancelled.set(true);
        Thread thread = workerThread;
        if (thread != null) {
            thread.interrupt();
        }
        workerThread = null;
        mainHandler.removeCallbacksAndMessages(null);
        progressView = null;
    }

    private void showTranslationProgress(boolean show) {
        View view = progressView;
        if (view == null) return;

        view.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show) {
            TextView textView = view.findViewById(android.R.id.text1);
            if (textView != null) {
                textView.setText("翻译中...");
            }
        }
    }

    private void updateTranslationProgress(
            int current,
            int total,
            int translatedChars,
            int totalChars,
            long startTime
    ) {
        View view = progressView;
        if (view == null) return;

        int percentage = total > 0 ? (int) ((float) current / total * 100) : 0;
        int charPercentage = totalChars > 0 ? (int) ((float) translatedChars / totalChars * 100) : 0;
        ProgressBar progressBar = view.findViewById(android.R.id.progress);
        TextView progressText = view.findViewById(android.R.id.text1);

        if (progressBar == null || progressText == null) return;
        progressBar.setMax(Math.max(total, 1));
        progressBar.setProgress(current);

        long elapsedTime = System.currentTimeMillis() - startTime;
        String speedInfo = "";
        String etaInfo = "";
        if (elapsedTime > 1000 && translatedChars > 0) {
            double speed = (double) translatedChars / (elapsedTime / 1000.0);
            speedInfo = String.format(Locale.getDefault(), " | %.0f字/秒", speed);
            if (speed > 0) {
                long etaSeconds = (long) ((totalChars - translatedChars) / speed);
                etaInfo = etaSeconds < 60
                        ? String.format(Locale.getDefault(), " | 剩余~%d秒", etaSeconds)
                        : String.format(Locale.getDefault(), " | 剩余~%d分钟", etaSeconds / 60);
            }
        }

        progressText.setText(String.format(
                Locale.getDefault(),
                "翻译中: %d/%d块 (%d%%) | %d/%d字 (%d%%)%s%s",
                current,
                total,
                percentage,
                translatedChars,
                totalChars,
                charPercentage,
                speedInfo,
                etaInfo
        ));
    }

    private void postToMain(Runnable runnable) {
        if (cancelled.get()) return;
        mainHandler.post(() -> {
            if (!cancelled.get() && progressView != null) {
                runnable.run();
            }
        });
    }
}
