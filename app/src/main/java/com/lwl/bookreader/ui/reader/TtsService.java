package com.lwl.bookreader.ui.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.lwl.bookreader.R;

/**
 * TTS 朗读前台服务:保活进程使熄屏/切后台后继续播放,
 * 并通过 MediaSession + MediaStyle 通知提供锁屏/通知栏控制(上一页/播放暂停/下一页)。
 * 朗读本身仍由 ReaderActivity + TtsManager 驱动,本服务只负责保活与控制事件转发。
 */
public class TtsService extends Service {

    /** 控制事件回调,由 ReaderActivity 实现并注册。 */
    public interface Controller {
        void onPlay();
        void onPause();
        void onNextPage();
        void onPrevPage();
    }

    private static final String CHANNEL_ID = "tts_playback";
    private static final int NOTIFY_ID = 1001;

    private static final String ACTION_UPDATE = "com.lwl.bookreader.tts.UPDATE";
    private static final String ACTION_PLAY = "com.lwl.bookreader.tts.PLAY";
    private static final String ACTION_PAUSE = "com.lwl.bookreader.tts.PAUSE";
    private static final String ACTION_NEXT = "com.lwl.bookreader.tts.NEXT";
    private static final String ACTION_PREV = "com.lwl.bookreader.tts.PREV";

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_SUBTITLE = "subtitle";
    private static final String EXTRA_PLAYING = "playing";

    private static Controller controller;

    public static void setController(Controller c) {
        controller = c;
    }

    /** 刷新(必要时启动)前台服务,同步书名/章节/播放状态到通知与锁屏。 */
    public static void update(Context ctx, String title, String subtitle, boolean playing) {
        Intent i = new Intent(ctx, TtsService.class).setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
                .putExtra(EXTRA_PLAYING, playing);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, TtsService.class));
    }

    private MediaSessionCompat session;
    private String title = "";
    private String subtitle = "";
    private boolean playing;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        session = new MediaSessionCompat(this, "BookReaderTts");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { if (controller != null) controller.onPlay(); }
            @Override public void onPause() { if (controller != null) controller.onPause(); }
            @Override public void onSkipToNext() { if (controller != null) controller.onNextPage(); }
            @Override public void onSkipToPrevious() { if (controller != null) controller.onPrevPage(); }
            @Override public void onStop() { if (controller != null) controller.onPause(); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_UPDATE.equals(action)) {
            title = notNull(intent.getStringExtra(EXTRA_TITLE));
            subtitle = notNull(intent.getStringExtra(EXTRA_SUBTITLE));
            playing = intent.getBooleanExtra(EXTRA_PLAYING, false);
        } else if (ACTION_PLAY.equals(action)) {
            if (controller != null) controller.onPlay();
        } else if (ACTION_PAUSE.equals(action)) {
            if (controller != null) controller.onPause();
        } else if (ACTION_NEXT.equals(action)) {
            if (controller != null) controller.onNextPage();
        } else if (ACTION_PREV.equals(action)) {
            if (controller != null) controller.onPrevPage();
        }
        // 任何路径都保证 startForeground 被调用(startForegroundService 的 5 秒约束)
        refreshSessionAndNotification();
        return START_NOT_STICKY;
    }

    private void refreshSessionAndNotification() {
        if (session == null) return;
        session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .build());
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING
                                : PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        playing ? 1f : 0f)
                .build());

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    private Notification buildNotification() {
        // 点击通知回到阅读页(singleTask,直接拉回已有实例)
        Intent open = new Intent(this, ReaderActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Action playPause = playing
                ? new NotificationCompat.Action(R.drawable.ic_pause,
                        getString(R.string.tts_action_pause), servicePending(ACTION_PAUSE))
                : new NotificationCompat.Action(R.drawable.ic_play,
                        getString(R.string.tts_action_play), servicePending(ACTION_PLAY));

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(new NotificationCompat.Action(R.drawable.ic_skip_previous,
                        getString(R.string.tts_action_prev), servicePending(ACTION_PREV)))
                .addAction(playPause)
                .addAction(new NotificationCompat.Action(R.drawable.ic_skip_next,
                        getString(R.string.tts_action_next), servicePending(ACTION_NEXT)))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private PendingIntent servicePending(String action) {
        Intent i = new Intent(this, TtsService.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.tts_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private static String notNull(String s) {
        return s != null ? s : "";
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
