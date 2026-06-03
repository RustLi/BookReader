package com.lwl.bookreader.ui.reader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 封装系统 TextToSpeech:按句顺序朗读,onDone 驱动续读,章末回调。 */
public class TtsManager {

    public interface Callback {
        void onReady(boolean ok);
        void onSegmentStart(int index);
        void onChapterFinished();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private TextToSpeech tts;
    private boolean ready;

    private List<String> segments = new ArrayList<>();
    private int index;
    private boolean playing;

    public TtsManager(Context context, Callback callback) {
        this.callback = callback;
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                tts.setSpeechRate(1.0f);
                tts.setOnUtteranceProgressListener(progressListener);
                ready = true;
                main.post(() -> callback.onReady(true));
            } else {
                main.post(() -> callback.onReady(false));
            }
        });
    }

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            int i = parse(utteranceId);
            main.post(() -> callback.onSegmentStart(i));
        }

        @Override
        public void onDone(String utteranceId) {
            int i = parse(utteranceId);
            main.post(() -> {
                if (!playing) return;
                if (i + 1 < segments.size()) {
                    index = i + 1;
                    speakCurrent();
                } else {
                    playing = false;
                    callback.onChapterFinished();
                }
            });
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onError(String utteranceId) {
            // 跳过出错句,继续下一句
            onDone(utteranceId);
        }
    };

    public boolean isReady() {
        return ready;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setSegments(List<String> list) {
        segments = list != null ? list : new ArrayList<>();
        index = 0;
    }

    public boolean hasSegments() {
        return !segments.isEmpty();
    }

    /** 从指定句开始朗读。 */
    public void playFrom(int from) {
        if (!ready || segments.isEmpty()) return;
        index = Math.max(0, Math.min(from, segments.size() - 1));
        playing = true;
        speakCurrent();
    }

    /** 从当前位置继续。 */
    public void resume() {
        if (!ready || segments.isEmpty()) return;
        playing = true;
        speakCurrent();
    }

    public void pause() {
        playing = false;
        if (tts != null) tts.stop();
    }

    public void setSpeechRate(float rate) {
        if (tts != null) tts.setSpeechRate(rate);
    }

    public void shutdown() {
        playing = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    private void speakCurrent() {
        if (tts == null || index < 0 || index >= segments.size()) return;
        tts.speak(segments.get(index), TextToSpeech.QUEUE_FLUSH, null, "u" + index);
    }

    private static int parse(String utteranceId) {
        try {
            return Integer.parseInt(utteranceId.substring(1));
        } catch (Exception e) {
            return 0;
        }
    }
}
