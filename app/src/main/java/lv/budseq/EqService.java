package lv.budseq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Работает в фоне:
 *  - держит эквалайзер (весь звук телефона + сессии плееров);
 *  - следит за Bluetooth-устройствами: всплывающее окно, заряд в уведомлении,
 *    предупреждение о низком заряде, громкость и приложение при подключении;
 *  - автовключение и свои настройки для каждого устройства, фокус звука в машине;
 *  - Music Time: считает, сколько и кого слушаешь;
 *  - раз в 12 часов проверяет обновления на GitHub.
 */
public class EqService extends Service {
    private static final String CHANNEL = "eq";
    private static final String CHANNEL_ALERT = "alerts";
    private static final String CHANNEL_TIPS = "tips";
    private static final int NOTIF_MAIN = 1, NOTIF_APP = 3, NOTIF_AUTOEQ = 4, NOTIF_SLEEP = 7, NOTIF_LOW_BASE = 100;
    private static final long STATS_TICK_MS = 15000, UPDATE_TICK_MS = 6L * 60 * 60 * 1000;

    private EqEngine eq;
    private BudsLink link;
    private AirPods air;
    private DeviceMonitor monitor;
    private BudsPopup popup;
    private NotificationManager nm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> lowWarned = new HashSet<>();
    private String pendingPopup;
    private NowPlaying np;
    private AudioManager am;
    private long lastStatsTick;

    private int widgetTicks;

    // автопресет по приложению
    private final AppPresets.Tracker appTracker = new AppPresets.Tracker();
    private boolean gameNow;

    private final AudioManager.AudioPlaybackCallback playbackCallback = new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(java.util.List<android.media.AudioPlaybackConfiguration> configs) {
            gameNow = AppPresets.gameActive(configs);
            checkAppPreset();
        }
    };

    private void checkAppPreset() {
        String pkg = np.active() && np.playing() ? np.packageName() : null;
        appTracker.update(this, pkg, gameNow);
    }

    /** Music Time: раз в 15 с — играет ли музыка и кто. Виджет — раз в минуту. */
    private final Runnable statsTick = new Runnable() {
        public void run() {
            checkAppPreset();
            tickStats();
            if (++widgetTicks % 4 == 0) updateWidget();
            main.postDelayed(this, STATS_TICK_MS);
        }
    };

    private final Runnable widgetTask = new Runnable() {
        public void run() { pushWidget(); }
    };

    private final Runnable updateTick = new Runnable() {
        public void run() {
            Updater.autoCheck(EqService.this);
            main.postDelayed(this, UPDATE_TICK_MS);
        }
    };

    private final Runnable showPending = new Runnable() {
        public void run() {
            String addr = pendingPopup;
            pendingPopup = null;
            showPopupFor(addr);
        }
    };

    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE);
            if (session <= 0) return;
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) {
                eq.attach(session);
            } else {
                eq.detach(session);
            }
        }
    };

    private final BudsLink.Listener budsListener = new BudsLink.Listener() {
        public void onBudsState(BudsLink.State s) { onBuds(s); }
    };

    private final AirPods.Listener airListener = new AirPods.Listener() {
        public void onAirPods(BudsLink.State s) { onBuds(s); }
    };

    private final DeviceMonitor.Listener deviceListener = new DeviceMonitor.Listener() {
        public void onDevicesChanged(DeviceInfo added) { onDevices(added); }
    };

    private final Runnable eqListener = new Runnable() {
        public void run() {
            updateNotification();
            updateWidget();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        eq = EqEngine.get(this);
        link = BudsLink.get();
        air = AirPods.get();
        monitor = DeviceMonitor.get();
        popup = new BudsPopup(this);
        nm = getSystemService(NotificationManager.class);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        np = new NowPlaying(this, new NowPlaying.Listener() {
            public void onNowPlayingChanged() {
                updateWidget();
                checkAppPreset();
            }
        });

        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.ch_eq), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL_ALERT, getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL_TIPS, getString(R.string.ch_tips), NotificationManager.IMPORTANCE_DEFAULT));

        Notification n = buildMain(getString(R.string.notif_tap));
        // 0x40000000 = FOREGROUND_SERVICE_TYPE_SPECIAL_USE (Android 14)
        if (Build.VERSION.SDK_INT >= 34 && getApplicationInfo().targetSdkVersion >= 34) {
            startForeground(NOTIF_MAIN, n, 0x40000000);
        } else {
            startForeground(NOTIF_MAIN, n);
        }

        eq.attach(0);

        IntentFilter f = new IntentFilter();
        f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, f, 0x2); // Context.RECEIVER_EXPORTED
        } else {
            registerReceiver(sessionReceiver, f);
        }

        link.addListener(budsListener);
        air.addListener(airListener);
        monitor.addListener(deviceListener);
        eq.addListener(eqListener);
        monitor.start(this);
        syncProfileAndAuto();
        updateNotification();

        np.start();
        // игры не создают MediaSession — узнаём их по типу звука (USAGE_GAME)
        am.registerAudioPlaybackCallback(playbackCallback, main);
        gameNow = AppPresets.gameActive(am);
        lastStatsTick = SystemClock.elapsedRealtime();
        main.postDelayed(statsTick, STATS_TICK_MS);
        main.postDelayed(updateTick, 20000); // не мешаем запуску
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_SLEEP.equals(intent.getAction())) {
            startSleep(intent.getIntExtra(EXTRA_MINUTES, 0));
            return START_STICKY;
        }
        monitor.refresh();
        return START_STICKY;
    }

    // =====================================================================
    // Таймер сна: за минуту до конца музыка плавно затихает, потом — пауза
    // =====================================================================

    static final String ACT_SLEEP = "lv.budseq.SLEEP", EXTRA_MINUTES = "minutes";
    private static final long SLEEP_FADE_MS = 60000;
    /** Когда уснуть (SystemClock.elapsedRealtime), 0 — таймер выключен. Читает экран. */
    static volatile long sleepEnd;
    private int sleepVolume = -1;

    /** Запустить (minutes > 0) или выключить (0) таймер сна. Вызывать с открытого экрана. */
    static void setSleep(Context c, int minutes) {
        Intent i = new Intent(c, EqService.class);
        i.setAction(ACT_SLEEP);
        i.putExtra(EXTRA_MINUTES, minutes);
        try {
            c.startService(i);
        } catch (Exception e) {
            try {
                c.startForegroundService(i);
            } catch (Exception ignored) {
            }
        }
    }

    /** Сколько минут осталось (округление вверх), 0 — выключен. */
    static int sleepMinutesLeft() {
        long left = sleepEnd - SystemClock.elapsedRealtime();
        return sleepEnd == 0 || left <= 0 ? 0 : (int) ((left + 59999) / 60000);
    }

    private final Runnable sleepTick = new Runnable() {
        public void run() {
            long left = sleepEnd - SystemClock.elapsedRealtime();
            if (sleepEnd == 0) return;
            if (left <= 0) {
                finishSleep();
                return;
            }
            if (left <= SLEEP_FADE_MS) {
                // плавное затухание громкости по кривой (ухо слышит громкость логарифмически)
                if (sleepVolume < 0) sleepVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                float k = left / (float) SLEEP_FADE_MS;
                int v = Math.round(sleepVolume * k * k);
                try {
                    if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != v) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, v), 0);
                    }
                } catch (Exception ignored) {
                }
            }
            if (left % 30000 < 1000) updateNotification();
            main.postDelayed(this, 1000);
        }
    };

    private void startSleep(int minutes) {
        main.removeCallbacks(sleepTick);
        restoreSleepVolume();
        if (minutes <= 0) {
            sleepEnd = 0;
        } else {
            sleepEnd = SystemClock.elapsedRealtime() + minutes * 60000L;
            main.post(sleepTick);
        }
        updateNotification();
    }

    private void finishSleep() {
        sleepEnd = 0;
        np.pause();
        // громкость возвращаем чуть позже, когда плеер уже встал на паузу
        main.postDelayed(new Runnable() {
            public void run() { restoreSleepVolume(); }
        }, 2500);
        nm.notify(NOTIF_SLEEP, new Notification.Builder(this, CHANNEL_TIPS)
                .setSmallIcon(R.drawable.ic_bedtime)
                .setContentTitle(getString(R.string.sleep_done))
                .setTimeoutAfter(10 * 60 * 1000)
                .setAutoCancel(true)
                .build());
        updateNotification();
    }

    private void restoreSleepVolume() {
        if (sleepVolume < 0) return;
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, sleepVolume, 0);
        } catch (Exception ignored) {
        }
        sleepVolume = -1;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(statsTick);
        main.removeCallbacks(updateTick);
        main.removeCallbacks(widgetTask);
        main.removeCallbacks(sleepTick);
        restoreSleepVolume();
        sleepEnd = 0;
        tickStats();
        ListenStats.get(this).saveNow();
        try { am.unregisterAudioPlaybackCallback(playbackCallback); } catch (Exception ignored) { }
        np.stop();
        try { unregisterReceiver(sessionReceiver); } catch (Exception ignored) { }
        link.removeListener(budsListener);
        air.removeListener(airListener);
        air.stop();
        monitor.removeListener(deviceListener);
        eq.removeListener(eqListener);
        monitor.stop();
        popup.hide();
        eq.releaseAll();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // =====================================================================
    // Устройства
    // =====================================================================

    private void onDevices(DeviceInfo added) {
        updateAirPods();
        syncProfileAndAuto();
        checkLowBattery();
        if (added != null && added.isAudio()) {
            onConnected(added);
        } else if (popup.isShown()) {
            DeviceInfo shown = monitor.find(popup.shownAddress());
            if (shown == null) popup.hide();
            else popup.update(shown, budsFor(shown));
        }
        updateNotification();
    }

    private void onConnected(final DeviceInfo info) {
        suggestAutoEq(info);
        final DeviceSettings ds = DeviceSettings.get(this, info.address);
        if (ds.volume >= 0) {
            main.postDelayed(new Runnable() {
                public void run() { setVolume(ds.volume); }
            }, 2500);
        }
        if (!ds.app.isEmpty()) {
            main.postDelayed(new Runnable() {
                public void run() { launchApp(ds.app); }
            }, 1500);
        }
        if (!ds.popup || MainActivity.visible) return;
        main.removeCallbacks(showPending);
        pendingPopup = info.address;
        // для Galaxy Buds и AirPods ждём данные о заряде L/R/кейса, но не дольше 4,5 с
        main.postDelayed(showPending, info.isGalaxyBuds() || info.isAirPods() ? 4500 : 1200);
    }

    private void showPopupFor(String addr) {
        DeviceInfo i = monitor.find(addr);
        if (i == null || MainActivity.visible) return;
        popup.show(i, budsFor(i));
    }

    private BudsLink.State budsFor(DeviceInfo info) {
        if (info != null && info.isAirPods()) {
            BudsLink.State a = air.state();
            return a.connected ? a : null;
        }
        BudsLink.State s = link.state();
        return info != null && info.isGalaxyBuds() && s.connected && info.address.equals(s.address) ? s : null;
    }

    /** Слушать эфир AirPods, пока они подключены (экономный режим; экран включает быстрый). */
    private void updateAirPods() {
        DeviceInfo pods = null;
        for (DeviceInfo i : monitor.list()) {
            if (i.isAirPods()) {
                pods = i;
                break;
            }
        }
        if (pods != null) air.start(this, pods, MainActivity.visible);
        else air.stop();
    }

    private void onBuds(BudsLink.State s) {
        if (s.connected && s.hasBattery() && s.address.equals(pendingPopup)) {
            main.removeCallbacks(showPending);
            pendingPopup = null;
            showPopupFor(s.address);
        } else if (popup.isShown() && s.address.equals(popup.shownAddress())) {
            popup.update(monitor.find(s.address), s);
        }

        String key = s.address + "#buds";
        if (!s.connected) {
            // отключились — при следующем подключении снова можно предупредить
            Iterator<String> it = lowWarned.iterator();
            while (it.hasNext()) {
                if (it.next().endsWith("#buds")) it.remove();
            }
        } else if (s.hasBattery() && !lowWarned.contains(key)) {
            boolean lowL = s.batL >= 0 && s.batL <= 15 && !s.chgL;
            boolean lowR = s.batR >= 0 && s.batR <= 15 && !s.chgR;
            if (lowL || lowR) {
                lowWarned.add(key);
                notifyAlert(NOTIF_LOW_BASE, getString(R.string.low_title),
                        getString(R.string.low_text, pct(s.batL), pct(s.batR)));
            }
        }
        updateNotification();
    }

    /** AutoEQ: при подключении наушников найти их модель в базе и предложить коррекцию. */
    private void suggestAutoEq(final DeviceInfo info) {
        final String addr = info.address;
        if (!info.isHeadphones() || AutoEq.load(this, addr) != null || AutoEq.dismissed(this, addr)
                || AutoEq.suggestion(this, addr) != null) {
            return;
        }
        // базу (~1 МБ) без спроса качаем только по Wi-Fi; дальше она лежит в памяти телефона
        if (!AutoEq.hasIndex(this)) {
            try {
                android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
                if (cm == null || cm.isActiveNetworkMetered()) return;
            } catch (Exception e) {
                return;
            }
        }
        AutoEq.loadIndex(this, new AutoEq.IndexCallback() {
            public void onIndex(java.util.List<AutoEq.Entry> all, String error) {
                if (all == null) return;
                AutoEq.Entry e = AutoEq.bestMatch(all, info.name);
                if (e == null) return;
                AutoEq.setSuggestion(EqService.this, addr, e);
                eq.notifyChanged(); // открытый экран покажет предложение
                if (MainActivity.visible) return;
                Intent open = new Intent(EqService.this, MainActivity.class);
                open.putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_EQ);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pi = PendingIntent.getActivity(EqService.this, 13, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                nm.notify(NOTIF_AUTOEQ, new Notification.Builder(EqService.this, CHANNEL_TIPS)
                        .setSmallIcon(R.drawable.ic_equalizer)
                        .setContentTitle(getString(R.string.ae_notif_title))
                        .setContentText(getString(R.string.ae_notif_text, e.name))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build());
            }
        });
    }

    /** Автовключение, профиль и фокус машины под текущее звуковое устройство. */
    private void syncProfileAndAuto() {
        DeviceInfo p = monitor.primaryAudio();
        if (eq.autoMode) eq.setEnabled(p != null);
        if (eq.perDevice) {
            if (p != null) eq.switchProfile(p.address, p.name);
            else eq.switchProfile("phone", PhoneInfo.get(this).speakerName(this));
        }
        CarFocusView.applyFocus(this);
        AutoEq.applyFor(this, p != null ? p.address : null);
    }

    // =====================================================================
    // Music Time
    // =====================================================================

    private void tickStats() {
        long now = SystemClock.elapsedRealtime();
        long dt = (now - lastStatsTick) / 1000;
        lastStatsTick = now;
        if (dt <= 0) return;
        // телефон спал — не засчитываем часы сна (плеер во время игры не даёт уснуть)
        dt = Math.min(dt, 2 * STATS_TICK_MS / 1000);

        if (!np.isStarted() && NowPlaying.hasAccess(this)) np.start(); // доступ выдали позже
        String pkg = null, artist = null, title = null;
        boolean playing;
        if (np.active()) {
            playing = np.playing();
            pkg = np.packageName();
            artist = np.artist();
            title = np.title();
        } else {
            // без доступа к уведомлениям — только общее время
            try {
                playing = am.isMusicActive();
            } catch (Exception e) {
                playing = false;
            }
        }
        if (!playing) return;
        // где и с каким звуком слушали — для итогов месяца и достижений
        DeviceInfo out = monitor.primaryAudio();
        String device = out != null ? out.name : getString(R.string.phone_speaker);
        String preset = eq.lastPreset.isEmpty() ? getString(R.string.custom) : eq.lastPreset;
        ListenStats.get(this).add(dt, pkg, artist, title, device, preset,
                out != null && out.isHeadphones() ? out.name : null);
    }

    private void checkLowBattery() {
        Set<String> connected = new HashSet<>();
        for (DeviceInfo i : monitor.list()) {
            connected.add(i.address);
            if (i.battery < 0) continue;
            int limit = DeviceSettings.get(this, i.address).lowBattery;
            if (i.battery <= limit && !lowWarned.contains(i.address)) {
                lowWarned.add(i.address);
                notifyAlert(NOTIF_LOW_BASE + 1 + (i.address.hashCode() & 0xFFF),
                        getString(R.string.low_title_dev, i.name), getString(R.string.low_text_dev, i.battery));
            } else if (i.battery > limit + 5) {
                lowWarned.remove(i.address); // зарядили — можно снова предупредить
            }
        }
        Iterator<String> it = lowWarned.iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (!k.endsWith("#buds") && !connected.contains(k)) it.remove();
        }
    }

    private void setVolume(int percent) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * percent / 100f), 0);
        } catch (Exception ignored) {
        }
    }

    private void launchApp(String pkg) {
        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) return;
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(li);
                return;
            } catch (Exception ignored) {
                // Android не дал открыть из фона — покажем уведомление
            }
        }
        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {
        }
        PendingIntent pi = PendingIntent.getActivity(this, 7, li,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTIF_APP, new Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_headset)
                .setContentTitle(getString(R.string.open_app, label))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    // =====================================================================
    // Уведомления
    // =====================================================================

    private Notification buildMain(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_headset)
                .setContentTitle(getString(eq != null && !eq.enabled ? R.string.notif_off : R.string.notif_running))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    // =====================================================================
    // Виджет
    // =====================================================================

    /** Собрать данные не чаще раза в секунду (плеер шлёт много событий подряд). */
    private void updateWidget() {
        main.removeCallbacks(widgetTask);
        main.postDelayed(widgetTask, 700);
    }

    private void pushWidget() {
        if (!EqWidget.exists(this)) return;
        EqWidget.Data w = new EqWidget.Data();
        w.title = np.title();
        w.artist = np.artist();
        w.art = np.art();
        // волна виджета — цвета обложки (если включено в «Оформлении»)
        int cc = Theme.coverEnabled() && w.art != null ? CoverColor.dominant(w.art) : 0;
        w.waveColor = cc != 0 ? cc : Theme.wave();
        w.playing = np.active() ? np.playing() : am.isMusicActive();
        w.eqOn = eq.enabled;
        w.eqStatus = !eq.enabled ? getString(R.string.off) : eq.lastPreset;
        w.todaySecs = ListenStats.get(this).today();
        DeviceInfo p = monitor.primaryAudio();
        if (p != null) {
            w.device = p.name;
            BudsLink.State b = budsFor(p);
            if (b != null && b.hasBattery()) {
                w.batL = b.batL;
                w.batR = b.batR;
                w.batCase = b.batCase;
            } else if (p.battery >= 0) {
                w.battery = p.battery + "%";
            }
        }
        EqWidget.push(this, w);
    }

    private void updateNotification() {
        DeviceInfo p = monitor.primaryAudio();
        if (p == null && !monitor.list().isEmpty()) p = monitor.list().get(0);
        String text;
        if (p == null) {
            text = getString(R.string.notif_tap);
        } else {
            BudsLink.State b = budsFor(p);
            if (b != null && b.hasBattery()) {
                text = p.name + ": " + getString(R.string.notif_battery, pct(b.batL), pct(b.batR), pct(b.batCase));
            } else if (p.battery >= 0) {
                text = p.name + " · " + p.battery + "%";
            } else {
                text = p.name;
            }
        }
        int sleep = sleepMinutesLeft();
        if (sleep > 0) text = getString(R.string.sleep_left, sleep) + " · " + text;
        nm.notify(NOTIF_MAIN, buildMain(text));
        updateWidget();
    }

    private void notifyAlert(int id, String title, String text) {
        nm.notify(id, new Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_battery_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build());
    }

    private static String pct(int v) {
        return v >= 0 && v <= 100 ? v + "%" : "—";
    }
}
