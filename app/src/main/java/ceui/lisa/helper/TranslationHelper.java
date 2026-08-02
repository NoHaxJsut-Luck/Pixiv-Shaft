package ceui.lisa.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

import ceui.lisa.R;
import ceui.pixiv.translation.TranslationManager;

/** Lifecycle-aware bridge for legacy Java novel screens. */
public class TranslationHelper {

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

        void onTranslationFailed(Exception error);

        void onTranslationChunkReceived(int chunkIndex, String chunkResult, String partialResult);
    }

    public synchronized boolean translate(
            final String novelText,
            final String apiKey,
            final TranslationCallback callback
    ) {
        if (isRunning()) return false;

        cancelled.set(false);
        showTranslationProgress(true);
        workerThread = new Thread(() -> {
            try {
                String translatedText = TranslationManager.Companion.getInstance().translateForJava(
                        appContext,
                        novelText,
                        apiKey,
                        "Japanese",
                        "Chinese",
                        partialResult -> postToMain(() ->
                                callback.onTranslationChunkReceived(0, partialResult, partialResult)),
                        (completed, total, translatedChars, totalChars) -> postToMain(() ->
                                updateTranslationProgress(completed, total, translatedChars, totalChars))
                );

                postToMain(() -> {
                    showTranslationProgress(false);
                    callback.onTranslationComplete(translatedText);
                });
            } catch (Exception error) {
                if (!cancelled.get()) {
                    postToMain(() -> {
                        showTranslationProgress(false);
                        callback.onTranslationFailed(error);
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
        if (thread != null) thread.interrupt();
        workerThread = null;
        mainHandler.removeCallbacksAndMessages(null);
        showTranslationProgress(false);
        progressView = null;
    }

    private void showTranslationProgress(boolean show) {
        View view = progressView;
        if (view == null) return;
        view.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show) {
            TextView textView = view.findViewById(android.R.id.text1);
            if (textView != null) textView.setText(R.string.translation_in_progress);
        }
    }

    private void updateTranslationProgress(int current, int total, int translatedChars, int totalChars) {
        View view = progressView;
        if (view == null) return;

        ProgressBar progressBar = view.findViewById(android.R.id.progress);
        TextView progressText = view.findViewById(android.R.id.text1);
        if (progressBar == null || progressText == null) return;

        progressBar.setMax(Math.max(total, 1));
        progressBar.setProgress(current);
        int charPercentage = totalChars > 0
                ? Math.min(100, Math.round((float) translatedChars / totalChars * 100))
                : 0;
        progressText.setText(appContext.getString(
                R.string.translation_progress_detail,
                current,
                total,
                charPercentage
        ));
    }

    private void postToMain(Runnable runnable) {
        if (cancelled.get()) return;
        mainHandler.post(() -> {
            if (!cancelled.get() && progressView != null) runnable.run();
        });
    }
}
