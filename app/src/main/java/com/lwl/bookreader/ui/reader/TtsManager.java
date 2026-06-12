package com.lwl.bookreader.ui.reader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
    private final PowerManager.WakeLock wakeLock;
    private TextToSpeech tts;
    private boolean ready;

    private List<String> segments = new ArrayList<>();
    private int index;
    private boolean playing;
    /** 每次 playFrom 递增。onStart/onDone 仅响应当前轮次,丢弃 QUEUE_FLUSH 后残留的旧回调。 */
    private int playRound;

    public TtsManager(Context context, Callback callback) {
        this.callback = callback;
        PowerManager pm = (PowerManager) context.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = pm != null
                ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BookReader:tts")
                : null;
        if (lock != null) lock.setReferenceCounted(false);
        wakeLock = lock;
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
            int[] p = parse(utteranceId);
            final int round = p[0];
            final int i = p[1];
            main.post(() -> {
                if (round != playRound) return;   // 旧轮次残留,忽略
                callback.onSegmentStart(i);
            });
        }

        @Override
        public void onDone(String utteranceId) {
            int[] p = parse(utteranceId);
            final int round = p[0];
            final int i = p[1];
            main.post(() -> {
                if (!playing) return;
                if (round != playRound) return;   // 旧轮次残留,忽略
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
        playRound++;                                // 作废上一轮残留的 onDone/onStart
        playing = true;
        speakCurrent();
    }

    /** 从当前位置继续。 */
    public void resume() {
        if (!ready || segments.isEmpty()) return;
        playRound++;                                // 作废上一轮残留回调
        playing = true;
        speakCurrent();
    }

    public void pause() {
        playing = false;
        if (tts != null) tts.stop();
        releaseWakeLock();
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
        releaseWakeLock();
    }

    private void speakCurrent() {
        if (tts == null || index < 0 || index >= segments.size()) return;
        // 每句续期一次带超时的 PARTIAL_WAKE_LOCK:熄屏后 CPU 保持运行,
        // 章节切换的短暂间隙也仍在持锁;暂停/退出时主动释放,超时兜底防泄漏。
        if (wakeLock != null) wakeLock.acquire(10 * 60 * 1000L);
        // utteranceId 编码 "u<轮次>_<句索引>",供 onStart/onDone 按轮次过滤旧回调,
        // 防止 QUEUE_FLUSH 后旧 utterance 的 onDone 把 index 又往前推、回拖播放位置。
        tts.speak(segments.get(index), TextToSpeech.QUEUE_FLUSH, null,
                "u" + playRound + "_" + index);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    /** 解析 utteranceId,返回 [轮次, 句索引]。兼容历史 "uN" 格式(轮次当 0)。 */
    private static int[] parse(String utteranceId) {
        try {
            String body = utteranceId.substring(1); // 去掉 'u'
            int sep = body.indexOf('_');
            if (sep < 0) return new int[]{0, Integer.parseInt(body)};
            return new int[]{
                    Integer.parseInt(body.substring(0, sep)),
                    Integer.parseInt(body.substring(sep + 1))};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }
}
