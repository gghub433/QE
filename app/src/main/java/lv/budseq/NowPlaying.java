package lv.budseq;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.List;

/**
 * «Сейчас играет»: трек, обложка и управление плеером (в первую очередь Spotify).
 * С доступом к уведомлениям видно трек и обложку; без него работают только кнопки.
 */
public final class NowPlaying {
    public static final String SPOTIFY = "com.spotify.music";

    public interface Listener {
        void onNowPlayingChanged();
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController controller;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                public void onActiveSessionsChanged(List<MediaController> list) {
                    pick(list);
                }
            };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            fire();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            fire();
        }

        @Override
        public void onSessionDestroyed() {
            setController(null);
            refreshSessions();
        }
    };

    public NowPlaying(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    public static ComponentName component(Context c) {
        return new ComponentName(c, MediaListener.class);
    }

    public static boolean hasAccess(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            return nm.isNotificationListenerAccessGranted(component(c));
        } catch (Throwable t) {
            return false;
        }
    }

    public void start() {
        if (started) {
            refreshSessions();
            return;
        }
        if (!hasAccess(ctx)) {
            fire();
            return;
        }
        try {
            msm = ctx.getSystemService(MediaSessionManager.class);
            msm.addOnActiveSessionsChangedListener(sessionsListener, component(ctx), main);
            started = true;
            refreshSessions();
        } catch (SecurityException e) {
            started = false;
            fire();
        }
    }

    public boolean isStarted() {
        return started;
    }

    public void stop() {
        if (msm != null && started) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception ignored) {
            }
        }
        started = false;
        setController(null);
    }

    private void refreshSessions() {
        if (msm == null) return;
        try {
            pick(msm.getActiveSessions(component(ctx)));
        } catch (SecurityException e) {
            setController(null);
        }
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState st = c.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    /** Приоритет: играющий Spotify → любой играющий → Spotify → первый. */
    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null && !list.isEmpty()) {
            int bestScore = -1;
            for (MediaController c : list) {
                int score = (isPlaying(c) ? 2 : 0) + (SPOTIFY.equals(c.getPackageName()) ? 1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
        }
        setController(best);
    }

    private void setController(MediaController c) {
        boolean same = c != null && controller != null
                && c.getSessionToken().equals(controller.getSessionToken());
        if (!same) {
            if (controller != null) {
                try {
                    controller.unregisterCallback(callback);
                } catch (Exception ignored) {
                }
            }
            if (c != null) c.registerCallback(callback, main);
        }
        controller = c;
        fire();
    }

    private void fire() {
        main.post(new Runnable() {
            public void run() {
                listener.onNowPlayingChanged();
            }
        });
    }

    // ---------- данные ----------

    public boolean active() {
        return controller != null;
    }

    public String packageName() {
        return controller == null ? null : controller.getPackageName();
    }

    private MediaMetadata meta() {
        return controller == null ? null : controller.getMetadata();
    }

    public String title() {
        MediaMetadata m = meta();
        return m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
    }

    public String artist() {
        MediaMetadata m = meta();
        if (m == null) return null;
        String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        return a != null ? a : m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
    }

    public Bitmap art() {
        MediaMetadata m = meta();
        if (m == null) return null;
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    public long duration() {
        MediaMetadata m = meta();
        return m == null ? 0 : Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
    }

    public long position() {
        PlaybackState st = controller == null ? null : controller.getPlaybackState();
        if (st == null) return 0;
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        long dur = duration();
        if (dur > 0) pos = Math.min(pos, dur);
        return Math.max(0, pos);
    }

    public boolean playing() {
        return controller != null && isPlaying(controller);
    }

    // ---------- управление ----------

    public void playPause() {
        if (controller != null) {
            if (playing()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        } else {
            key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    public void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
        else key(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
        else key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    public void seekTo(long ms) {
        if (controller != null) controller.getTransportControls().seekTo(ms);
    }

    /** Кнопка «медиа» — работает и без доступа к уведомлениям (для последнего плеера). */
    private void key(int code) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        } catch (Exception ignored) {
        }
    }
}
