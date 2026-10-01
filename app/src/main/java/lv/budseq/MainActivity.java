package lv.budseq;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class MainActivity extends Activity {

    /** Акцент из темы (задаётся в onCreate; после смены цвета экран пересоздаётся). */
    private static int ACCENT = Theme.DEFAULT_ACCENT;
    private static final int CARD = Color.rgb(0x1C, 0x1D, 0x21);
    private static final int CHIP = Color.rgb(0x3A, 0x3B, 0x40);
    private static final int DANGER = Color.rgb(0xE5, 0x48, 0x48);
    private static final int GREY = Color.rgb(0x80, 0x83, 0x8A);

    private static final int REQ_PERMS = 1, REQ_AUDIO = 2, REQ_AIRPODS = 3, REQ_SAVE = 10, REQ_OPEN = 11;

    /** Вкладки: Устройство / Эквалайзер / Музыка / Настройки. */
    static final int TAB_DEVICE = 0, TAB_EQ = 1, TAB_GAMES = 2, TAB_MUSIC = 3, TAB_SETTINGS = 4, TAB_COUNT = 5;
    static final String EXTRA_TAB = "tab", EXTRA_CHECK_UPDATE = "check_update", EXTRA_CAR = "car";
    private static final int[] TAB_TITLES = {R.string.tab_device, R.string.tab_eq, R.string.tab_games, R.string.tab_music,
            R.string.tab_settings};
    private static final int[] TAB_ICONS = {R.drawable.ic_headset, R.drawable.ic_equalizer, R.drawable.ic_gamepad,
            R.drawable.ic_music, R.drawable.ic_settings};

    /** Популярные музыкальные приложения — показываются первыми в выборе. */
    private static final List<String> MUSIC_APPS = Arrays.asList(
            "com.spotify.music", "com.google.android.apps.youtube.music", "com.google.android.youtube",
            "ru.yandex.music", "com.vkontakte.android", "com.soundcloud.android", "deezer.android.app",
            "com.apple.android.music", "com.amazon.mp3", "com.aspiro.tidal", "com.maxmpz.audioplayer",
            "org.videolan.vlc", "com.sec.android.app.music", "com.miui.player");

    static volatile boolean visible;

    private EqEngine eq;
    private SharedPreferences settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Spectrum spectrum = new Spectrum();
    private boolean updating;
    private String pendingExport;

    // вкладки
    private final ScrollView[] pages = new ScrollView[TAB_COUNT];
    private final ImageView[] navIcons = new ImageView[TAB_COUNT];
    private final TextView[] navLabels = new TextView[TAB_COUNT];
    private TextView headerTitle;
    private int tab = TAB_DEVICE;
    private Object backCallback;   // OnBackInvokedCallback (Android 13+), хранится как Object

    // машина
    private LinearLayout carBox;
    private CarFocusView carView;
    private TextView carStatus;
    private final Button[] carModeBtns = new Button[3];
    private Button carRhdBtn, carOffBtn, carEditBtn;
    private boolean carEdit;
    /** Вкладки машины как в магнитоле: EQ, объёмный звук, Bass Boost, ZONE, фильтр баса. */
    private static final int CAR_TAB_EQ = 0, CAR_TAB_SURROUND = 1, CAR_TAB_BASS = 2, CAR_TAB_ZONE = 3,
            CAR_TAB_FILTER = 4, CAR_TABS = 5;
    private static final int[] CAR_TAB_TITLES = {R.string.car_tab_eq, R.string.car_tab_surround,
            R.string.car_tab_bass, R.string.car_tab_zone, R.string.car_tab_filter};
    private static final int[] CAR_TAB_ICONS = {R.drawable.ic_equalizer, R.drawable.ic_surround,
            R.drawable.ic_bass, R.drawable.ic_car, R.drawable.ic_filter};
    private static final int[] CAR_BASS_HZ = {60, 80, 100, 120};
    private static final int[] CAR_HP_HZ = {0, 40, 60, 80, 100, 120, 150, 200};
    /** Места, как в списке магнитолы: весь салон, водитель, пассажир, сзади слева, сзади справа, спереди по центру. */
    private static final int SEAT_ALL = 4, SEAT_DRIVER = -10, SEAT_PASSENGER = -11;
    private static final int[] CAR_SEATS = {SEAT_ALL, SEAT_DRIVER, SEAT_PASSENGER, 3, 5, 1};
    private final Button[] carSeatBtns = new Button[CAR_SEATS.length];
    private final LinearLayout[] carPanels = new LinearLayout[CAR_TABS];
    private final ImageView[] carTabIcons = new ImageView[CAR_TABS];
    private final TextView[] carTabLabels = new TextView[CAR_TABS];
    private int carTab = CAR_TAB_ZONE;
    private boolean carUpdating;
    private EqGraphView carEqGraph;
    private Switch carSurSwitch;
    private SeekBar carSurBar, carBassBar;
    private TextView carSurVal, carSurNa, carBassVal;
    private final Button[] carBassHzBtns = new Button[CAR_BASS_HZ.length];
    private final Button[] carHpBtns = new Button[CAR_HP_HZ.length];
    private String carAddress;

    // Music Time
    private MusicTimeView mtView;
    private TextView mtToday, mtTodayLbl, mtWeek, mtHint;
    private Button mtWeekBtn, mtMonthBtn;
    private FlowLayout mtAchBox;
    private LinearLayout mtTopBox;
    private int mtTicks;
    private WaveView wave;
    private Button sleepBtn;

    // AutoEQ
    private TextView aeStatus, aeSuggestText;
    private Switch aeSwitch;
    private LinearLayout aeSuggestBox;
    private Button aeRemoveBtn;

    // настройки
    private Button langBtn, popupBtn2;
    private Switch autostartSwitch, updAutoSwitch;
    private TextView updStatus, carNote;
    private Button updCheckBtn, updInstallBtn;
    private Updater.Release updRelease;

    // устройства
    private String selected;
    private FrameLayout devicePanel;
    private BudsView budsView;
    private DeviceView deviceView;
    private LinearLayout deviceChips;
    private HorizontalScrollView deviceChipsScroll;
    private Button devSettingsBtn, findBtn, popupBtn, airPermBtn;
    private LinearLayout budsBox;
    private Button ncOff, ncAnc, ncAmb, touchBtn, fwEqBtn;
    private boolean finding;

    // эквалайзер
    private TextView status, profileText;
    private EqGraphView graph;
    private LinearLayout bandRow;
    private Button specBtn;
    private SeekBar preBar, punchBar, boostBar, balanceBar;
    private TextView preVal, punchVal, boostVal, balanceVal;
    private Switch mainSwitch, levelSwitch, autoSwitch, perDeviceSwitch;
    private LinearLayout userBox;

    // «Сейчас играет» / Spotify
    private NowPlaying np;
    private LinearLayout npCard, npAccessBox;
    private ImageView npArt;
    private TextView npTitle, npArtist, npApp, npTime;
    private SeekBar npSeek;
    private ImageButton npPlay;
    private Button spOpenBtn;
    private boolean npSeeking;
    private Bitmap npLastArt;

    private final Runnable npTicker = new Runnable() {
        public void run() {
            updateProgress();
            if (tab == TAB_MUSIC && ++mtTicks % 20 == 0) refreshMusicTime();
            if (tab == TAB_MUSIC && mtTicks % 5 == 0) refreshSleep();
            ui.postDelayed(this, 1000);
        }
    };

    private final BudsLink.Listener budsListener = new BudsLink.Listener() {
        public void onBudsState(BudsLink.State s) {
            if (!s.connected && finding) setFinding(false);
            refreshDevices();
        }
    };

    private final AirPods.Listener airListener = new AirPods.Listener() {
        public void onAirPods(BudsLink.State s) { refreshDevices(); }
    };

    private final DeviceMonitor.Listener deviceListener = new DeviceMonitor.Listener() {
        public void onDevicesChanged(DeviceInfo added) {
            if (added != null) selected = added.address;
            refreshDevices();
        }
    };

    private final Runnable eqListener = new Runnable() {
        public void run() { refreshEq(); }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    // =====================================================================
    // Построение экрана
    // =====================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ACCENT = Theme.accent();
        eq = EqEngine.get(this);
        settings = getSharedPreferences("settings", MODE_PRIVATE);

        ArrayList<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && !granted("android.permission.POST_NOTIFICATIONS")) {
            perms.add("android.permission.POST_NOTIFICATIONS");
        }
        if (Build.VERSION.SDK_INT >= 31 && !granted("android.permission.BLUETOOTH_CONNECT")) {
            perms.add("android.permission.BLUETOOTH_CONNECT");
        }
        // «Устройства поблизости» одним окном: подключение + поиск (заряд AirPods)
        if (Build.VERSION.SDK_INT >= 31 && !granted("android.permission.BLUETOOTH_SCAN")) {
            perms.add("android.permission.BLUETOOTH_SCAN");
        }
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
        EqTileService.ensureService(this);

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(NAV_BG);

        // экран: заголовок + 4 вкладки + нижняя панель
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(Color.BLACK);
        frame.setFitsSystemWindows(true); // Android 15+: не залезать под строку состояния и навигацию

        buildHeader(frame);

        FrameLayout pagesBox = new FrameLayout(this);
        LinearLayout[] roots = new LinearLayout[TAB_COUNT];
        for (int i = 0; i < TAB_COUNT; i++) {
            ScrollView sv = new ScrollView(this);
            sv.setVisibility(View.GONE);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(16), dp(4), dp(16), dp(28));
            sv.addView(root);
            pagesBox.addView(sv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pages[i] = sv;
            roots[i] = root;
        }
        // «Сценарии» — отдельный экран из настроек (не вкладка): «Назад» возвращает в настройки
        scnPage = new ScrollView(this);
        scnPage.setVisibility(View.GONE);
        scnRoot = new LinearLayout(this);
        scnRoot.setOrientation(LinearLayout.VERTICAL);
        scnRoot.setPadding(dp(16), dp(4), dp(16), dp(28));
        scnPage.addView(scnRoot);
        pagesBox.addView(scnPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildDevices(roots[TAB_DEVICE]);
        buildCar(roots[TAB_DEVICE]);
        buildPhone(roots[TAB_DEVICE]);

        buildEqualizer(roots[TAB_EQ]);
        buildAutoEq(roots[TAB_EQ]);
        buildSound(roots[TAB_EQ]);
        buildPresets(roots[TAB_EQ]);
        buildAppPresets(roots[TAB_EQ]);
        TextView tip = text(getString(R.string.tip_wearable), 12, GREY);
        tip.setPadding(dp(4), dp(20), dp(4), 0);
        roots[TAB_EQ].addView(tip);

        buildGames(roots[TAB_GAMES]);

        buildNowPlaying(roots[TAB_MUSIC]);
        buildMusicTime(roots[TAB_MUSIC]);

        buildSettings(roots[TAB_SETTINGS]);

        frame.addView(pagesBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        frame.addView(buildNav());
        setContentView(frame);

        refreshEq();
        refreshDevices();
        selectTab(getIntent().getIntExtra(EXTRA_TAB, settings.getInt("tab", TAB_DEVICE)), false);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra(EXTRA_TAB)) selectTab(intent.getIntExtra(EXTRA_TAB, tab), true);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        handleEffectIntent(i);
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            String link = i.getDataString();
            i.setAction(Intent.ACTION_MAIN);   // чтобы не показать ещё раз после пересоздания
            i.setData(null);
            selectTab(TAB_EQ, false);
            PresetCode.Preset p = PresetCode.decode(link);
            if (p != null) showPresetPreview(p);
            else Toast.makeText(this, R.string.code_bad, Toast.LENGTH_LONG).show();
        }
        if (i.getBooleanExtra(EXTRA_CAR, false)) {
            // сценарий «Машина»: экран машины крупно — прокручиваем к ней
            i.removeExtra(EXTRA_CAR);
            selectTab(TAB_DEVICE, false);
            ui.postDelayed(new Runnable() {
                public void run() {
                    refreshCar();
                    if (carBox.getVisibility() == View.VISIBLE) pages[TAB_DEVICE].smoothScrollTo(0, carBox.getTop());
                }
            }, 400);
        }
        if (i.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) {
            i.removeExtra(EXTRA_CHECK_UPDATE);
            selectTab(TAB_SETTINGS, false);
            checkUpdates(true);
        }
    }

    /** Spotify (или другой плеер) → «Эквалайзер»: подключаемся прямо к его звуку. */
    private void handleEffectIntent(Intent i) {
        if (i == null || !AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL.equals(i.getAction())) return;
        int session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE);
        String pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        EqTileService.ensureService(this);
        if (session > 0) eq.attach(session);
        String who = pkg == null || pkg.isEmpty() ? getString(R.string.player_generic) : appLabel(pkg);
        Toast.makeText(this, getString(R.string.eq_attached, who), Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        selectTab(TAB_EQ, false);
        updateStatus();
    }

    private void buildHeader(LinearLayout root) {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(12), dp(16), dp(8));
        headerTitle = text(getString(R.string.tab_device), 26, Color.WHITE);
        headerTitle.getPaint().setFakeBoldText(true);
        head.addView(headerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView eqLabel = text(getString(R.string.app_name), 14, GREY);
        eqLabel.setPadding(0, 0, dp(6), 0);
        head.addView(eqLabel);

        mainSwitch = styledSwitch();
        mainSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (!updating) eq.setEnabled(on);
            }
        });
        head.addView(mainSwitch);
        root.addView(head);
    }

    // =====================================================================
    // Вкладки
    // =====================================================================

    // как фон экрана: на Android 15+ под панелью навигации видно фон окна — без «ступеньки»
    private static final int NAV_BG = Color.BLACK;

    private View buildNav() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        View line = new View(this);
        line.setBackgroundColor(CARD);
        box.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(NAV_BG);
        nav.setPadding(dp(4), dp(8), dp(4), dp(8));
        for (int i = 0; i < TAB_COUNT; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setContentDescription(getString(TAB_TITLES[i]));
            ImageView ic = new ImageView(this);
            ic.setScaleType(ImageView.ScaleType.CENTER);
            item.addView(ic, new LinearLayout.LayoutParams(dp(60), dp(32)));
            TextView t = text(getString(TAB_TITLES[i]), 11, GREY);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(2), dp(4), dp(2), 0);
            item.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectTab(index, true); }
            });
            navIcons[i] = ic;
            navLabels[i] = t;
            nav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(nav);
        return box;
    }

    private void selectTab(int t, boolean animate) {
        if (t < 0 || t >= TAB_COUNT) t = TAB_DEVICE;
        int prev = tab;
        tab = t;
        if (scnOpen) {
            scnOpen = false;
            scnPage.setVisibility(View.GONE);
            prev = -1;   // возврат со «Сценариев» — с анимацией
        }
        for (int i = 0; i < TAB_COUNT; i++) {
            boolean on = i == t;
            pages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            navIcons[i].setImageDrawable(icon(TAB_ICONS[i], on ? Color.WHITE : GREY));
            navIcons[i].setBackground(on ? round(ACCENT, 16) : null);
            navLabels[i].setTextColor(on ? Color.WHITE : GREY);
            navLabels[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        headerTitle.setText(TAB_TITLES[t]);
        settings.edit().putInt("tab", t).apply();
        if (animate && prev != t) {
            pages[t].setAlpha(0f);
            pages[t].setTranslationY(dp(10));
            pages[t].animate().alpha(1f).translationY(0).setDuration(180).start();
        }
        // спектр нужен только на вкладке эквалайзера
        if (t == TAB_EQ) {
            if (visible && spectrumWanted() && granted("android.permission.RECORD_AUDIO")) startSpectrum();
        } else {
            spectrum.stop();
            graph.setSpectrum(null);
        }
        if (t == TAB_MUSIC) refreshMusicTime();
        if (t == TAB_GAMES) refreshGames(true);
        if (t == TAB_SETTINGS) refreshSettings();
        updateBackCallback();
    }

    /** «Назад» со «Сценариев» — в настройки, с любой вкладки — на первую, с первой — выход. */
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return; // там работает onBackPressed()
        boolean need = tab != TAB_DEVICE || scnOpen;
        if (need && backCallback == null) {
            backCallback = Back33.register(this, new Runnable() {
                public void run() { selectTab(scnOpen ? TAB_SETTINGS : TAB_DEVICE, true); }
            });
        } else if (!need && backCallback != null) {
            Back33.unregister(this, backCallback);
            backCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (scnOpen) {
            selectTab(TAB_SETTINGS, true);
            return;
        }
        if (tab != TAB_DEVICE) {
            selectTab(TAB_DEVICE, true);
            return;
        }
        super.onBackPressed();
    }

    /**
     * Android 13+: с targetSdk 36 onBackPressed() больше не вызывается — нужен OnBackInvokedCallback.
     * Отдельный класс, чтобы на Android 9–12 не грузились классы, которых там нет.
     */
    private static final class Back33 {
        static Object register(Activity a, final Runnable r) {
            android.window.OnBackInvokedCallback cb = new android.window.OnBackInvokedCallback() {
                public void onBackInvoked() { r.run(); }
            };
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            return cb;
        }

        static void unregister(Activity a, Object cb) {
            a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) cb);
        }
    }

    private void buildDevices(LinearLayout root) {
        devicePanel = new FrameLayout(this);
        devicePanel.setBackground(round(CARD, 24));
        budsView = new BudsView(this);
        deviceView = new DeviceView(this);
        devicePanel.addView(budsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        devicePanel.addView(deviceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(250));
        plp.topMargin = dp(12);
        root.addView(devicePanel, plp);

        // выбор устройства, если подключено несколько
        deviceChipsScroll = new HorizontalScrollView(this);
        deviceChipsScroll.setHorizontalScrollBarEnabled(false);
        deviceChips = new LinearLayout(this);
        deviceChips.setPadding(0, dp(10), 0, 0);
        deviceChipsScroll.addView(deviceChips);
        root.addView(deviceChipsScroll);

        // действия
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(10), 0, 0);
        devSettingsBtn = chip(getString(R.string.device_settings), R.drawable.ic_settings, CHIP, new View.OnClickListener() {
            public void onClick(View v) { showDeviceSettings(); }
        });
        actions.addView(devSettingsBtn);
        findBtn = chip(getString(R.string.find_buds), R.drawable.ic_bell, CHIP, new View.OnClickListener() {
            public void onClick(View v) { onFindClicked(); }
        });
        actions.addView(findBtn);
        popupBtn = chip("", R.drawable.ic_layers, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        actions.addView(popupBtn);
        airPermBtn = chip(getString(R.string.air_permission), R.drawable.ic_bluetooth, ACCENT, new View.OnClickListener() {
            public void onClick(View v) { askAirPodsPermission(); }
        });
        actions.addView(airPermBtn);
        root.addView(hscroll(actions));

        // Galaxy Buds: шумоподавление, сенсор, встроенный EQ
        budsBox = new LinearLayout(this);
        budsBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nc = new LinearLayout(this);
        ncOff = chip(getString(R.string.nc_off), 0, CHIP, ncClick(BudsLink.NC_OFF));
        ncAnc = chip(getString(R.string.nc_anc), 0, CHIP, ncClick(BudsLink.NC_ANC));
        ncAmb = chip(getString(R.string.nc_ambient), 0, CHIP, ncClick(BudsLink.NC_AMBIENT));
        nc.addView(ncOff);
        nc.addView(ncAnc);
        nc.addView(ncAmb);
        budsBox.addView(hscroll(nc));
        LinearLayout more = new LinearLayout(this);
        touchBtn = chip("", R.drawable.ic_lock_open, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                BudsLink.get().setTouchLock(BudsLink.get().state().touchLocked != 1);
            }
        });
        more.addView(touchBtn);
        fwEqBtn = chip("", R.drawable.ic_equalizer, CHIP, new View.OnClickListener() {
            public void onClick(View v) { showFirmwareEqDialog(); }
        });
        more.addView(fwEqBtn);
        budsBox.addView(hscroll(more));
        root.addView(budsBox);
    }

    private View.OnClickListener ncClick(final int mode) {
        return new View.OnClickListener() {
            public void onClick(View v) { BudsLink.get().setNoiseControl(mode); }
        };
    }

    private void buildEqualizer(LinearLayout root) {
        status = text("", 13, Color.rgb(0xA0, 0xA3, 0xAA));
        status.setPadding(dp(4), dp(12), 0, dp(4));
        status.setCompoundDrawablePadding(dp(6));
        root.addView(status);
        profileText = text("", 13, GREY);
        profileText.setPadding(dp(4), 0, 0, dp(8));
        root.addView(profileText);

        // число полос + спектр
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = text(getString(R.string.bands), 14, Color.WHITE);
        lbl.setGravity(Gravity.CENTER_VERTICAL);
        lbl.setPadding(dp(4), 0, dp(10), 0);
        LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lblp.bottomMargin = dp(8);
        row.addView(lbl, lblp);
        bandRow = new LinearLayout(this);
        for (final int n : EqEngine.BAND_COUNTS) {
            Button bb = chip(String.valueOf(n), 0, CHIP, new View.OnClickListener() {
                public void onClick(View v) {
                    int prev = eq.bandCount();
                    boolean wasOk = eq.globalOk;
                    eq.setBandCount(n);
                    if (wasOk && !eq.globalOk && n != prev) {
                        // звуковой движок телефона не принял столько полос — возвращаем как было
                        eq.setBandCount(prev);
                        Toast.makeText(MainActivity.this, R.string.bands_unsupported, Toast.LENGTH_LONG).show();
                    }
                    refreshEq();
                    restartSpectrum();
                }
            });
            bb.setMinWidth(dp(52));
            bb.setMinimumWidth(dp(52));
            bandRow.addView(bb);
        }
        row.addView(bandRow);
        specBtn = chip(getString(R.string.spectrum), R.drawable.ic_waves, CHIP, new View.OnClickListener() {
            public void onClick(View v) { toggleSpectrum(); }
        });
        row.addView(specBtn);
        root.addView(hscroll(row));

        graph = new EqGraphView(this);
        graph.setBackground(round(CARD, 24));
        graph.setListener(new EqGraphView.Listener() {
            public void onBandChanged(int band, float db) { eq.setGain(band, db); }
        });
        root.addView(graph, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        buildAbButton(root);

        // запас громкости (preamp)
        preBar = new SeekBar(this);
        preBar.setMax(24);
        preVal = text("", 14, Color.WHITE);
        root.addView(sliderRow(getString(R.string.preamp), preBar, preVal));
        preBar.setOnSeekBarChangeListener(new Seek() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float db = -p / 2f;
                preVal.setText(String.format(Locale.US, "%.1f dB", db));
                if (fromUser) eq.setPreamp(db);
            }
        });
        TextView hint = text(getString(R.string.preamp_hint), 12, GREY);
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        root.addView(hint);
    }

    // =====================================================================
    // A/B: пока держишь — оригинал без EQ той же громкости
    // =====================================================================

    private LinearLayout abBtn;
    private TextView abLabel;
    private GradientDrawable abIdleBg, abHeldBg;

    private void buildAbButton(LinearLayout root) {
        abIdleBg = round(CHIP, 28);
        abHeldBg = round(ACCENT, 28);
        // иконка рядом с текстом, оба по центру широкой кнопки
        abBtn = new LinearLayout(this);
        abBtn.setGravity(Gravity.CENTER);
        abBtn.setBackground(abIdleBg);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_compare);
        abBtn.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
        abLabel = text(getString(R.string.ab_hold), 16, Color.WHITE);
        abLabel.setPadding(dp(10), 0, 0, 0);
        abBtn.addView(abLabel);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(12);
        root.addView(abBtn, lp);
        abBtn.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);   // прокрутка не отнимет палец
                        if (!eq.enabled) {
                            Toast.makeText(MainActivity.this, R.string.ab_off, Toast.LENGTH_SHORT).show();
                            return true;
                        }
                        if (eq.bypass() == EqEngine.BYPASS_TEST) return true;   // идёт тест динамиков
                        setAbHeld(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        setAbHeld(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
        root.addView(hintText(getString(R.string.ab_hint)));
    }

    private void setAbHeld(boolean held) {
        boolean now = eq.bypass() == EqEngine.BYPASS_AB;
        if (held == now) return;
        eq.setBypass(held ? EqEngine.BYPASS_AB : EqEngine.BYPASS_NONE);
        abLabel.setText(held ? R.string.ab_original : R.string.ab_hold);
        abBtn.setBackground(held ? abHeldBg : abIdleBg);
        graph.animate().alpha(held ? 0.35f : 1f).setDuration(150).start();
        tick(held);
    }

    /** Лёгкий «щелчок» вибрацией: при нажатии чуть сильнее, при отпускании — тише. */
    private void tick(boolean press) {
        Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib == null || !vib.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                vib.vibrate(VibrationEffect.createPredefined(press ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
            } else {
                vib.vibrate(VibrationEffect.createOneShot(press ? 20 : 10, press ? 120 : 60));
            }
        } catch (RuntimeException ignored) {
            // без вибрации сравнение всё равно работает
        }
    }

    private void buildSound(LinearLayout root) {
        root.addView(section(getString(R.string.sec_sound)));

        punchBar = new SeekBar(this);
        punchBar.setMax(100);
        punchVal = text("", 14, Color.WHITE);
        root.addView(sliderRow(getString(R.string.punch), punchBar, punchVal));
        root.addView(hintText(getString(R.string.punch_hint)));
        punchBar.setOnSeekBarChangeListener(new Seek() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                punchVal.setText(p + "%");
                if (fromUser) eq.setPunch(p / 100f);
            }
        });

        boostBar = new SeekBar(this);
        boostBar.setMax((int) (EqEngine.MAX_BOOST * 2));
        boostVal = text("", 14, Color.WHITE);
        root.addView(sliderRow(getString(R.string.boost), boostBar, boostVal));
        boostBar.setOnSeekBarChangeListener(new Seek() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                boostVal.setText(String.format(Locale.US, "+%.1f dB", p / 2f));
                if (fromUser) eq.setBoost(p / 2f);
            }

            public void onStopTrackingTouch(SeekBar s) {
                if (s.getProgress() > 0 && !settings.getBoolean("boost_warned", false)) {
                    settings.edit().putBoolean("boost_warned", true).apply();
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.boost_warn_title)
                            .setMessage(R.string.boost_warn)
                            .setPositiveButton(R.string.ok, null)
                            .show();
                }
            }
        });

        balanceBar = new SeekBar(this);
        balanceBar.setMax(200);
        balanceVal = text("", 14, Color.WHITE);
        root.addView(sliderRow(getString(R.string.balance), balanceBar, balanceVal));
        balanceBar.setOnSeekBarChangeListener(new Seek() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p - 100;
                if (fromUser && Math.abs(v) <= 4) { // «прилипает» к центру
                    v = 0;
                    s.setProgress(100);
                }
                balanceVal.setText(balanceLabel(v));
                if (fromUser) eq.setBalance(v / 100f);
            }
        });
        carNote = hintText("");
        root.addView(carNote);

        levelSwitch = styledSwitch();
        root.addView(switchRow(getString(R.string.leveling), getString(R.string.leveling_hint), levelSwitch));
        levelSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (!updating) eq.setLeveling(on);
            }
        });

        // моно — только через системные настройки
        LinearLayout mono = new LinearLayout(this);
        mono.setOrientation(LinearLayout.VERTICAL);
        mono.setPadding(dp(4), dp(14), dp(4), 0);
        mono.addView(text(getString(R.string.mono), 15, Color.WHITE));
        TextView mh = text(getString(R.string.mono_hint), 12, GREY);
        mh.setPadding(0, dp(2), 0, dp(8));
        mono.addView(mh);
        mono.addView(chip(getString(R.string.open_settings), R.drawable.ic_settings, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));
        root.addView(mono);
    }

    private void buildPresets(LinearLayout root) {
        root.addView(section(getString(R.string.presets)));
        LinearLayout presetRow = new LinearLayout(this);
        for (int i = 0; i < EqEngine.PRESETS.length; i++) {
            final int index = i;
            presetRow.addView(chip(getString(EqEngine.PRESET_NAMES[i]), 0, CHIP, new View.OnClickListener() {
                public void onClick(View v) {
                    eq.applyBuiltIn(index, getString(EqEngine.PRESET_NAMES[index]));
                    refreshEq();
                }
            }));
        }
        root.addView(hscroll(presetRow));

        root.addView(section(getString(R.string.my_presets)));
        userBox = new LinearLayout(this);
        userBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(userBox);

        LinearLayout actions = new LinearLayout(this);
        actions.addView(chip(getString(R.string.save_current), R.drawable.ic_add, ACCENT, new View.OnClickListener() {
            public void onClick(View v) { askPresetName(); }
        }));
        actions.addView(chip(getString(R.string.import_), R.drawable.ic_upload, CHIP, new View.OnClickListener() {
            public void onClick(View v) { showImportDialog(); }
        }));
        actions.addView(chip(getString(R.string.share), R.drawable.ic_share, CHIP, new View.OnClickListener() {
            public void onClick(View v) { shareText(eq.exportText(null)); }
        }));
        actions.addView(chip(getString(R.string.preset_code), R.drawable.ic_qr, CHIP, new View.OnClickListener() {
            public void onClick(View v) { showPresetCode(); }
        }));
        actions.addView(chip(getString(R.string.enter_code), R.drawable.ic_keyboard, CHIP, new View.OnClickListener() {
            public void onClick(View v) { askPresetCode(); }
        }));
        root.addView(hscroll(actions));
    }

    // =====================================================================
    // Пресет кодом и QR
    // =====================================================================

    private void showPresetCode() {
        final String code = PresetCode.encode(PresetCode.current(eq));
        final String link = PresetCode.link(code);
        boolean[][] m = QrCode.encode(link);
        if (m == null) {
            Toast.makeText(this, R.string.code_too_long, Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrBitmap(m, dp(232)));
        qr.setBackground(round(Color.WHITE, 16));
        qr.setClipToOutline(true);
        box.addView(qr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView c = text(code, 16, Color.WHITE);
        c.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        c.setGravity(Gravity.CENTER);
        c.setTextIsSelectable(true);
        c.setPadding(0, dp(14), 0, dp(6));
        box.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView h = text(getString(R.string.code_hint), 12, GREY);
        h.setGravity(Gravity.CENTER);
        box.addView(h);
        new AlertDialog.Builder(this)
                .setTitle(R.string.code_title)
                .setView(box)
                .setPositiveButton(R.string.share, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { shareText(getString(R.string.code_share_text, code, link)); }
                })
                .setNeutralButton(R.string.copy, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("EQ", code));
                        // Android 13+ сам показывает, что скопировано
                        if (Build.VERSION.SDK_INT < 33) {
                            Toast.makeText(MainActivity.this, R.string.code_copied, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Чёрные модули на белом, с тихой зоной 4 модуля; целый масштаб — без размытия. */
    private static Bitmap qrBitmap(boolean[][] m, int px) {
        int n = m.length + 8;
        int scale = Math.max(1, px / n);
        int w = n * scale;
        int[] pixels = new int[w * w];
        Arrays.fill(pixels, Color.WHITE);
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m.length; x++) {
                if (!m[y][x]) continue;
                for (int dy = 0; dy < scale; dy++) {
                    Arrays.fill(pixels, ((y + 4) * scale + dy) * w + (x + 4) * scale, ((y + 4) * scale + dy) * w + (x + 5) * scale, Color.BLACK);
                }
            }
        }
        return Bitmap.createBitmap(pixels, w, w, Bitmap.Config.ARGB_8888);
    }

    private void askPresetCode() {
        final EditText input = new EditText(this);
        input.setHint(R.string.code_input_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setTypeface(Typeface.MONOSPACE);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.enter_code)
                .setView(padded(input))
                .setPositiveButton(R.string.show, null)
                .setNegativeButton(R.string.cancel, null)
                .show();
        // своя кнопка: при опечатке окно остаётся открытым
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                PresetCode.Preset p = PresetCode.decode(input.getText().toString());
                if (p == null) {
                    input.setError(getString(R.string.code_bad));
                    return;
                }
                dlg.dismiss();
                showPresetPreview(p);
            }
        });
    }

    /** Превью кривой из кода и «Применить». */
    private void showPresetPreview(final PresetCode.Preset p) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        EqGraphView g = new EqGraphView(this);   // без слушателя — только смотреть
        g.setBackground(round(CARD, 24));
        g.setBands(EqEngine.freqs(p.bands), p.gains);
        box.addView(g, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        String info = getString(R.string.punch) + ": " + Math.round(p.punch * 100) + "%\n"
                + getString(R.string.boost) + ": " + String.format(Locale.US, "+%.1f dB", p.boost) + "\n"
                + getString(R.string.balance) + ": " + balanceLabel(Math.round(p.balance * 100)) + "\n"
                + getString(R.string.preamp) + ": " + String.format(Locale.US, "%.1f dB", p.preamp) + "\n"
                + getString(R.string.leveling) + ": " + getString(p.leveling ? R.string.on : R.string.off);
        TextView t = text(info, 13, Color.rgb(0xA0, 0xA3, 0xAA));
        t.setPadding(dp(4), dp(10), dp(4), 0);
        box.addView(t);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(R.string.code_preview_title)
                .setView(sv)
                .setPositiveButton(R.string.apply, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { applyCodePreset(p); }
                })
                .setNeutralButton(R.string.save_as, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        applyCodePreset(p);
                        askPresetName();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applyCodePreset(PresetCode.Preset p) {
        PresetCode.apply(eq, p);
        refreshEq();
        Toast.makeText(this, R.string.code_applied, Toast.LENGTH_SHORT).show();
    }

    // =====================================================================
    // Сценарии «когда → что» (Настройки → Сценарии)
    // =====================================================================

    private ScrollView scnPage;
    private LinearLayout scnRoot;
    private boolean scnOpen;
    private TextView scnSummary;

    private void openScenarios() {
        scnOpen = true;
        for (ScrollView p : pages) p.setVisibility(View.GONE);
        scnPage.setVisibility(View.VISIBLE);
        scnPage.scrollTo(0, 0);
        headerTitle.setText(R.string.scn_title);
        buildScenarios();
        scnPage.setAlpha(0f);
        scnPage.setTranslationY(dp(10));
        scnPage.animate().alpha(1f).translationY(0).setDuration(180).start();
        updateBackCallback();
    }

    private void buildScenarios() {
        scnRoot.removeAllViews();
        final Scenarios.Config k = Scenarios.load(this);
        TextView intro = hintText(getString(R.string.scn_intro));
        intro.setPadding(dp(4), dp(12), dp(4), dp(4));
        scnRoot.addView(intro);
        TextView now = text(eq.scnLabel.isEmpty() ? getString(R.string.scn_none)
                : getString(R.string.scn_active, eq.scnLabel), 14, Color.WHITE);
        now.setPadding(dp(4), dp(8), dp(4), dp(4));
        scnRoot.addView(now);

        // машина
        LinearLayout car = scnCard(R.drawable.ic_car, R.string.scn_car, R.string.scn_car_when, k.carOn,
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        k.carOn = on;
                        saveScn(k);
                    }
                });
        LinearLayout carRow = new LinearLayout(this);
        String preset = k.carPreset.isEmpty() ? getString(R.string.scn_keep) : AppPresets.presetLabel(this, k.carPreset);
        carRow.addView(chip(getString(R.string.scn_car_preset, preset), R.drawable.ic_equalizer, CHIP,
                new View.OnClickListener() {
                    public void onClick(View v) { chooseCarScnPreset(k); }
                }));
        carRow.addView(chip(getString(R.string.scn_car_open), R.drawable.ic_car, k.carOpen ? ACCENT : CHIP,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        k.carOpen = !k.carOpen;
                        saveScn(k);
                        buildScenarios();
                    }
                }));
        car.addView(hscroll(carRow));
        scnRoot.addView(car, topGap(12));

        // ночь
        LinearLayout night = scnCard(R.drawable.ic_bedtime, R.string.scn_night, R.string.scn_night_when, k.nightOn,
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        k.nightOn = on;
                        saveScn(k);
                    }
                });
        LinearLayout nightRow = new LinearLayout(this);
        nightRow.addView(chip(getString(R.string.scn_from, hhmm(k.nightFrom)), 0, CHIP, new View.OnClickListener() {
            public void onClick(View v) { pickScnTime(k, true); }
        }));
        nightRow.addView(chip(getString(R.string.scn_to, hhmm(k.nightTo)), 0, CHIP, new View.OnClickListener() {
            public void onClick(View v) { pickScnTime(k, false); }
        }));
        night.addView(hscroll(nightRow));
        scnRoot.addView(night, topGap(12));

        // тренировка
        LinearLayout sport = scnCard(R.drawable.ic_bolt, R.string.scn_sport, R.string.scn_sport_when, k.sportOn,
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        k.sportOn = on;
                        saveScn(k);
                    }
                });
        StringBuilder apps = new StringBuilder();
        for (int i = 0; i < k.sportApps.size() && i < 2; i++) {
            if (i > 0) apps.append(", ");
            apps.append(appLabel(k.sportApps.get(i)));
        }
        if (k.sportApps.size() > 2) apps.append(" +").append(k.sportApps.size() - 2);
        LinearLayout sportRow = new LinearLayout(this);
        sportRow.addView(chip(getString(R.string.scn_sport_apps,
                apps.length() > 0 ? apps.toString() : getString(R.string.scn_sport_none)), R.drawable.ic_add, CHIP,
                new View.OnClickListener() {
                    public void onClick(View v) { chooseSportApps(k); }
                }));
        sport.addView(hscroll(sportRow));
        TextView sh = text(getString(R.string.scn_sport_hint), 12, GREY);
        sh.setPadding(0, 0, 0, dp(6));
        sport.addView(sh);
        scnRoot.addView(sport, topGap(12));
    }

    /** Карточка правила: значок, название, «когда → что» и переключатель. */
    private LinearLayout scnCard(int iconRes, int title, int when, boolean on,
                                 CompoundButton.OnCheckedChangeListener l) {
        LinearLayout c = gameCard();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(10));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(icon(iconRes, Theme.liveAccent()));
        head.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);
        TextView t = text(getString(title), 16, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);
        TextView w = text(getString(when), 12, GREY);
        w.setPadding(0, dp(2), 0, 0);
        texts.addView(w);
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = styledSwitch();
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(l);
        head.addView(sw);
        c.addView(head);
        return c;
    }

    private void saveScn(Scenarios.Config k) {
        Scenarios.save(this, k);
        EqService.poke(this);
    }

    private static String hhmm(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void pickScnTime(final Scenarios.Config k, final boolean from) {
        int m = from ? k.nightFrom : k.nightTo;
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            public void onTimeSet(TimePicker v, int h, int min) {
                if (from) k.nightFrom = h * 60 + min;
                else k.nightTo = h * 60 + min;
                saveScn(k);
                buildScenarios();
            }
        }, m / 60, m % 60, android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void chooseCarScnPreset(final Scenarios.Config k) {
        final List<String> vals = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        vals.add("");
        labels.add(getString(R.string.scn_keep));
        for (int i = 0; i < EqEngine.PRESET_NAMES.length; i++) {
            vals.add(AppPresets.BUILTIN + i);
            labels.add(getString(EqEngine.PRESET_NAMES[i]));
        }
        for (String n : eq.presetNames()) {
            vals.add(AppPresets.USER + n);
            labels.add(n);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.scn_car)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        k.carPreset = vals.get(which);
                        saveScn(k);
                        buildScenarios();
                    }
                })
                .show();
    }

    private void chooseSportApps(final Scenarios.Config k) {
        final List<String[]> rows = launcherApps();
        String[] labels = new String[rows.size()];
        final boolean[] checked = new boolean[rows.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = rows.get(i)[1];
            checked[i] = k.sportApps.contains(rows.get(i)[0]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.scn_sport_pick)
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean on) { checked[which] = on; }
                })
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        k.sportApps.clear();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) k.sportApps.add(rows.get(i)[0]);
                        saveScn(k);
                        buildScenarios();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Приложения с ярлыком на рабочем столе: [пакет, название], по алфавиту. */
    private List<String[]> launcherApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<String[]> rows = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || !seen.add(p)) continue;
            rows.add(new String[]{p, ri.loadLabel(pm).toString()});
        }
        Collections.sort(rows, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return a[1].compareToIgnoreCase(b[1]); }
        });
        return rows;
    }

    // =====================================================================
    // Игры: звук под игру, игры телефона и ПК (Steam), игровое время
    // =====================================================================

    private static final int[] GAME_PROFILE_ORDER = {Games.P_STEPS, Games.P_RICH, Games.P_VOICE, Games.P_NONE};

    private TextView gameStatus;
    private Button gameEndBtn, gameTryBtn, gamesPhoneBtn, gamesPcBtn;
    private final Button[] gameDefBtns = new Button[GAME_PROFILE_ORDER.length];
    private SeekBar gameStrengthBar;
    private TextView gameStrengthVal;
    private Switch gameAutoSwitch, gameLatencySwitch;
    private LinearLayout gamesBox;
    private boolean gamesPc;
    private int steamShown = 60;
    private int gamesGen;   // номер перестройки списка: поздние ответы для старого списка не рисуем

    /** Игра для окна «Играть»: с телефона, из Steam или добавленная вручную. */
    private static final class GameRef {
        String key, name, source = "", pkg;
        int appid, minutes, minutes2w;
        long weekMs;
        boolean pc, manual;
    }

    private void buildGames(LinearLayout root) {
        gamesPc = settings.getBoolean("games_pc", false);
        root.addView(section(getString(R.string.games_sound)));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 24));
        card.setPadding(dp(16), dp(14), dp(16), dp(6));
        gameStatus = text("", 14, Color.WHITE);
        gameStatus.setGravity(Gravity.CENTER_VERTICAL);
        gameStatus.setCompoundDrawablePadding(dp(10));
        card.addView(gameStatus);
        LinearLayout acts = new LinearLayout(this);
        acts.setPadding(0, dp(12), 0, 0);
        gameEndBtn = chip(getString(R.string.game_end), R.drawable.ic_stop, ACCENT, new View.OnClickListener() {
            public void onClick(View v) {
                Games.stopSession(MainActivity.this);
                EqService.poke(MainActivity.this);
                refreshGames(true);
            }
        });
        acts.addView(gameEndBtn);
        gameTryBtn = chip(getString(R.string.game_try), R.drawable.ic_play, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                if (Games.TRY.equals(Games.sessionKey(MainActivity.this))) {
                    Games.stopSession(MainActivity.this);
                } else {
                    Games.startSession(MainActivity.this, Games.TRY, getString(R.string.game_try_label));
                }
                EqService.poke(MainActivity.this);
                refreshGames(false);
            }
        });
        acts.addView(gameTryBtn);
        card.addView(hscroll(acts));
        root.addView(card);

        root.addView(label(getString(R.string.games_default)));
        LinearLayout defs = new LinearLayout(this);
        for (int i = 0; i < GAME_PROFILE_ORDER.length; i++) {
            final int prof = GAME_PROFILE_ORDER[i];
            gameDefBtns[i] = chip(getString(Games.PROFILE_NAMES[prof]), 0, CHIP, new View.OnClickListener() {
                public void onClick(View v) {
                    Games.setDefaultProfile(MainActivity.this, prof);
                    EqService.poke(MainActivity.this);
                    refreshGames(false);
                }
            });
            defs.addView(gameDefBtns[i]);
        }
        root.addView(hscroll(defs));
        root.addView(hintText(getString(R.string.games_profiles_hint)));

        gameStrengthBar = new SeekBar(this);
        gameStrengthBar.setMax(100);
        gameStrengthVal = text("", 14, Color.WHITE);
        root.addView(sliderRow(getString(R.string.game_strength), gameStrengthBar, gameStrengthVal));
        gameStrengthBar.setOnSeekBarChangeListener(new Seek() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                gameStrengthVal.setText(p + "%");
                if (fromUser) Games.setStrength(MainActivity.this, p / 100f);
            }

            public void onStopTrackingTouch(SeekBar s) {
                EqService.poke(MainActivity.this);
            }
        });

        gameAutoSwitch = styledSwitch();
        root.addView(switchRow(getString(R.string.game_auto), getString(R.string.game_auto_hint), gameAutoSwitch));
        gameAutoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (updating) return;
                Games.setAuto(MainActivity.this, on);
                EqService.poke(MainActivity.this);
            }
        });

        gameLatencySwitch = styledSwitch();
        root.addView(switchRow(getString(R.string.game_lowlat), getString(R.string.game_lowlat_hint), gameLatencySwitch));
        gameLatencySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (updating) return;
                Games.setLowLatency(MainActivity.this, on);
                EqService.poke(MainActivity.this);   // служба применит заново, если игра идёт
            }
        });

        // Телефон | ПК · Steam
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(0, dp(20), 0, 0);
        gamesPhoneBtn = chip(getString(R.string.games_phone), R.drawable.ic_phone, CHIP, new View.OnClickListener() {
            public void onClick(View v) { setGamesPc(false); }
        });
        gamesPcBtn = chip(getString(R.string.games_pc), R.drawable.ic_laptop, CHIP, new View.OnClickListener() {
            public void onClick(View v) { setGamesPc(true); }
        });
        seg.addView(gamesPhoneBtn);
        seg.addView(gamesPcBtn);
        root.addView(hscroll(seg));
        gamesBox = new LinearLayout(this);
        gamesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(gamesBox);
    }

    private void setGamesPc(boolean pc) {
        if (pc == gamesPc) return;
        gamesPc = pc;
        settings.edit().putBoolean("games_pc", pc).apply();
        refreshGames(true);
    }

    /** rebuild — перестроить список игр; иначе только статус и кнопки. */
    private void refreshGames(boolean rebuild) {
        if (gameStatus == null) return;
        refreshGameStatus();
        int def = Games.defaultProfile(this);
        for (int i = 0; i < gameDefBtns.length; i++) {
            gameDefBtns[i].setBackground(round(GAME_PROFILE_ORDER[i] == def ? ACCENT : CHIP, 24));
        }
        int st = Math.round(Games.strength(this) * 100);
        gameStrengthBar.setProgress(st);
        gameStrengthVal.setText(st + "%");
        updating = true;
        gameAutoSwitch.setChecked(Games.autoOn(this));
        gameLatencySwitch.setChecked(Games.lowLatency(this));
        updating = false;
        gamesPhoneBtn.setBackground(round(gamesPc ? CHIP : ACCENT, 24));
        gamesPcBtn.setBackground(round(gamesPc ? ACCENT : CHIP, 24));
        if (!rebuild) return;
        if (gamesPc) buildPcGames();
        else buildPhoneGames();
    }

    private void refreshGameStatus() {
        if (gameStatus == null) return;
        String session = Games.sessionKey(this);
        boolean on = eq.gameSoundOn();
        gameStatus.setText(on ? getString(R.string.game_now, eq.gameLabel) : getString(R.string.game_idle));
        gameStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_gamepad, on ? Theme.liveAccent() : GREY), null, null, null);
        gameEndBtn.setVisibility(session != null && !Games.TRY.equals(session) ? View.VISIBLE : View.GONE);
        gameTryBtn.setBackground(round(Games.TRY.equals(session) ? ACCENT : CHIP, 24));
    }

    private LinearLayout gameCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 24));
        c.setPadding(dp(16), dp(14), dp(16), dp(8));
        return c;
    }

    private LinearLayout.LayoutParams topGap(int gapDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(gapDp);
        return lp;
    }

    /** Сетка плиток по N в ряд: плитка, название и строка под ним. */
    private final class GameGrid {
        final LinearLayout box = new LinearLayout(MainActivity.this);
        final int cols;
        LinearLayout row;
        int count;

        GameGrid(int cols) {
            this.cols = cols;
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, dp(6), 0, 0);
        }

        CoverView add(String name, String sub, float aspect, View.OnClickListener click) {
            if (count % cols == 0) {
                row = new LinearLayout(MainActivity.this);
                row.setWeightSum(cols);
                box.addView(row);
            }
            count++;
            LinearLayout cell = new LinearLayout(MainActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(4), dp(4), dp(4), dp(12));
            CoverView cv = new CoverView(MainActivity.this);
            cv.setAspect(aspect);
            cell.addView(cv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView t = text(name, 13, Color.WHITE);
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(2), dp(6), dp(2), 0);
            cell.addView(t);
            if (sub != null && !sub.isEmpty()) {
                TextView st = text(sub, 12, GREY);
                st.setSingleLine(true);
                st.setEllipsize(android.text.TextUtils.TruncateAt.END);
                st.setPadding(dp(2), dp(2), dp(2), 0);
                cell.addView(st);
            }
            cell.setOnClickListener(click);
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return cv;
        }
    }

    // ---------- телефон ----------

    private void buildPhoneGames() {
        final int gen = ++gamesGen;
        gamesBox.removeAllViews();
        if (!Games.hasUsageAccess(this)) {
            LinearLayout c = gameCard();
            c.addView(text(getString(R.string.games_usage_hint), 14, Color.WHITE));
            LinearLayout r = new LinearLayout(this);
            r.setPadding(0, dp(10), 0, 0);
            r.addView(chip(getString(R.string.games_allow), R.drawable.ic_lock_open, ACCENT, new View.OnClickListener() {
                public void onClick(View v) { openUsageAccess(); }
            }));
            c.addView(hscroll(r));
            gamesBox.addView(c, topGap(8));
        }
        final TextView loading = hintText(getString(R.string.games_loading));
        loading.setPadding(dp(4), dp(12), dp(4), 0);
        gamesBox.addView(loading);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final List<Games.PhoneGame> list = Games.phoneGames(app);
                PlayActivity.updateDynamic(app, list);
                ui.post(new Runnable() {
                    public void run() {
                        if (gen != gamesGen || gamesPc || isFinishing()) return;
                        gamesBox.removeView(loading);
                        showPhoneGames(list);
                    }
                });
            }
        }).start();
    }

    private void showPhoneGames(List<Games.PhoneGame> list) {
        boolean usage = Games.hasUsageAccess(this);
        if (usage && !list.isEmpty()) {
            long week = 0, today = 0;
            for (Games.PhoneGame g : list) {
                week += g.weekMs;
                today += g.todayMs;
            }
            TextView sum = text(getString(R.string.games_week, duration(week / 1000), duration(today / 1000)),
                    14, Color.WHITE);
            sum.setPadding(dp(4), dp(14), dp(4), dp(2));
            gamesBox.addView(sum);
        }
        if (list.isEmpty()) {
            TextView e = hintText(getString(R.string.games_phone_empty));
            e.setPadding(dp(4), dp(12), dp(4), 0);
            gamesBox.addView(e);
        }
        PackageManager pm = getPackageManager();
        String session = Games.sessionKey(this);
        GameGrid grid = new GameGrid(4);
        for (final Games.PhoneGame g : list) {
            String sub = usage && g.weekMs >= 60000 ? duration(g.weekMs / 1000)
                    : getString(Games.PROFILE_NAMES[Games.profileFor(this, g.pkg, g.label)]);
            CoverView cv = grid.add(g.label, sub, 1f, new View.OnClickListener() {
                public void onClick(View v) {
                    GameRef r = new GameRef();
                    r.key = g.pkg;
                    r.pkg = g.pkg;
                    r.name = g.label;
                    r.weekMs = g.weekMs;
                    showGameDialog(r);
                }
            });
            try {
                cv.setIcon(pm.getApplicationIcon(g.pkg));
            } catch (Exception ignored) {
            }
            cv.setTitle(g.label, "");
            cv.setPlaying(g.pkg.equals(session) || eq.gameSoundOn() && g.label.equals(eq.gameLabel));
        }
        gamesBox.addView(grid.box);
        LinearLayout r = new LinearLayout(this);
        r.addView(chip(getString(R.string.games_add), R.drawable.ic_add, CHIP, new View.OnClickListener() {
            public void onClick(View v) { pickPhoneGame(); }
        }));
        gamesBox.addView(hscroll(r));
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    /** Android не знает, что это игра, — человек отмечает сам. */
    private void pickPhoneGame() {
        final List<String[]> rows = launcherApps();
        String[] labels = new String[rows.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = rows.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle(R.string.games_pick_app)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Games.addExtraGame(MainActivity.this, rows.get(which)[0]);
                        EqService.poke(MainActivity.this);
                        refreshGames(true);
                    }
                })
                .show();
    }

    // ---------- ПК: Steam и игры из других магазинов ----------

    private void buildPcGames() {
        final int gen = ++gamesGen;
        gamesBox.removeAllViews();
        TextView about = hintText(getString(R.string.games_pc_about));
        about.setPadding(dp(4), dp(12), dp(4), dp(4));
        gamesBox.addView(about);
        LinearLayout steamBox = new LinearLayout(this);
        steamBox.setOrientation(LinearLayout.VERTICAL);
        gamesBox.addView(steamBox);
        if (!Steam.connected(this)) {
            buildSteamForm(steamBox);
        } else {
            Steam.Library lib = Steam.cached(this);
            if (lib != null) {
                showSteam(steamBox, lib);
            } else {
                final TextView msg = hintText(getString(R.string.steam_connecting));
                msg.setPadding(dp(4), dp(12), dp(4), 0);
                steamBox.addView(msg);
                Steam.refresh(this, new Steam.Callback() {
                    public void onResult(Steam.Library l, String error) {
                        if (gen != gamesGen || !gamesPc) return;
                        if (l != null) refreshGames(true);
                        else msg.setText(error);
                    }
                });
            }
        }
        buildOtherPcGames();
    }

    private void buildSteamForm(LinearLayout into) {
        LinearLayout c = gameCard();
        c.addView(text(getString(R.string.games_pc), 16, Color.WHITE));
        final EditText prof = new EditText(this);
        prof.setHint(R.string.steam_profile_hint);
        prof.setSingleLine(true);
        prof.setText(Steam.profileInput(this));
        prof.setTextColor(Color.WHITE);
        prof.setHintTextColor(GREY);
        c.addView(prof);
        final EditText key = new EditText(this);
        key.setHint(R.string.steam_key_hint);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTypeface(Typeface.MONOSPACE);
        key.setTextColor(Color.WHITE);
        key.setHintTextColor(GREY);
        c.addView(key);
        final TextView msg = text("", 13, Color.rgb(0xFF, 0xB3, 0x40));
        msg.setPadding(dp(2), dp(6), 0, 0);
        c.addView(msg);
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(8), 0, 0);
        r.addView(chip(getString(R.string.steam_connect), R.drawable.ic_check, ACCENT, new View.OnClickListener() {
            public void onClick(View v) {
                msg.setTextColor(GREY);
                msg.setText(R.string.steam_connecting);
                Steam.connect(MainActivity.this, prof.getText().toString(), key.getText().toString(),
                        new Steam.Callback() {
                            public void onResult(Steam.Library l, String error) {
                                if (l != null) {
                                    refreshGames(true);
                                } else {
                                    msg.setTextColor(Color.rgb(0xFF, 0xB3, 0x40));
                                    msg.setText(error);
                                }
                            }
                        });
            }
        }));
        r.addView(chip(getString(R.string.steam_get_key), R.drawable.ic_open, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Steam.KEY_PAGE)));
                } catch (Exception ignored) {
                }
            }
        }));
        c.addView(hscroll(r));
        TextView help = text(getString(R.string.steam_key_help), 12, GREY);
        help.setPadding(0, dp(2), 0, dp(6));
        c.addView(help);
        into.addView(c, topGap(8));
    }

    private void showSteam(LinearLayout into, Steam.Library lib) {
        TextView head = text(getString(R.string.steam_header, lib.name.isEmpty() ? "Steam" : lib.name,
                lib.games.size(), ListenStats.hours(this, lib.totalMinutes() * 60L)), 14, Color.WHITE);
        head.setPadding(dp(4), dp(10), dp(4), dp(2));
        into.addView(head);
        LinearLayout r = new LinearLayout(this);
        r.addView(chip(getString(R.string.steam_refresh), R.drawable.ic_download, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, R.string.steam_connecting, Toast.LENGTH_SHORT).show();
                Steam.refresh(MainActivity.this, new Steam.Callback() {
                    public void onResult(Steam.Library l, String error) {
                        if (l != null) refreshGames(true);
                        else Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }));
        r.addView(chip(getString(R.string.steam_disconnect), 0, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(R.string.steam_disconnect)
                        .setPositiveButton(R.string.steam_disconnect, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                Steam.disconnect(MainActivity.this);
                                refreshGames(true);
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        }));
        into.addView(hscroll(r));

        String session = Games.sessionKey(this);
        GameGrid grid = new GameGrid(3);
        int n = Math.min(steamShown, lib.games.size());
        for (int i = 0; i < n; i++) {
            final Steam.Game g = lib.games.get(i);
            String sub = g.minutes > 0 ? ListenStats.hours(this, g.minutes * 60L) : "";
            final CoverView cv = grid.add(g.name, sub, 1.5f, new View.OnClickListener() {
                public void onClick(View v) {
                    GameRef ref = new GameRef();
                    ref.key = g.key();
                    ref.name = g.name;
                    ref.appid = g.appid;
                    ref.minutes = g.minutes;
                    ref.minutes2w = g.minutes2w;
                    ref.pc = true;
                    ref.source = "Steam";
                    showGameDialog(ref);
                }
            });
            cv.setTitle(g.name, "Steam");
            cv.setPlaying(g.key().equals(session));
            setSteamCover(cv, g.appid);
        }
        into.addView(grid.box);
        if (lib.games.size() > n) {
            LinearLayout m = new LinearLayout(this);
            m.addView(chip(getString(R.string.steam_more), 0, CHIP, new View.OnClickListener() {
                public void onClick(View v) {
                    steamShown += 60;
                    refreshGames(true);
                }
            }));
            into.addView(hscroll(m));
        }
    }

    private void setSteamCover(final CoverView cv, int appid) {
        if (appid <= 0) return;
        Bitmap b = Steam.coverNow(appid);
        if (b != null) {
            cv.setCover(b);
            return;
        }
        Steam.loadCover(this, appid, new Steam.CoverCallback() {
            public void onCover(Bitmap bmp) {
                if (bmp != null) cv.setCover(bmp);
            }
        });
    }

    private void buildOtherPcGames() {
        gamesBox.addView(section(getString(R.string.games_other_pc)));
        String session = Games.sessionKey(this);
        GameGrid grid = new GameGrid(3);
        for (final Games.PcGame g : Games.pcGames(this)) {
            long secs = Games.playedSecs(this, g.key);
            final CoverView cv = grid.add(g.name, secs >= 60 ? duration(secs) : g.source, 1.5f,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            GameRef ref = new GameRef();
                            ref.key = g.key;
                            ref.name = g.name;
                            ref.appid = g.coverAppId;
                            ref.pc = true;
                            ref.manual = true;
                            ref.source = g.source;
                            showGameDialog(ref);
                        }
                    });
            cv.setTitle(g.name, g.source);
            cv.setPlaying(g.key.equals(session));
            setSteamCover(cv, g.coverAppId);
        }
        gamesBox.addView(grid.box);
        LinearLayout r = new LinearLayout(this);
        r.addView(chip(getString(R.string.games_add), R.drawable.ic_add, CHIP, new View.OnClickListener() {
            public void onClick(View v) { askPcGame(); }
        }));
        gamesBox.addView(hscroll(r));
    }

    private void askPcGame() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText name = new EditText(this);
        name.setHint(R.string.pc_add_hint);
        name.setSingleLine(true);
        box.addView(name);
        box.addView(label(getString(R.string.game_source)));
        final String[] src = {Games.PC_SOURCES[1]};
        final LinearLayout row = new LinearLayout(this);
        final List<Button> btns = new ArrayList<>();
        for (final String s : Games.PC_SOURCES) {
            Button b = chip(s, 0, s.equals(src[0]) ? ACCENT : CHIP, null);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    src[0] = s;
                    for (Button x : btns) x.setBackground(round(x == v ? ACCENT : CHIP, 24));
                }
            });
            btns.add(b);
            row.addView(b);
        }
        box.addView(hscroll(row));
        new AlertDialog.Builder(this)
                .setTitle(R.string.pc_add_title)
                .setView(padded(box))
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String n = name.getText().toString().trim();
                        if (n.isEmpty()) return;
                        final String source = src[0];
                        Games.putPcGame(MainActivity.this, n, source, 0);
                        refreshGames(true);
                        // обложку ищем в магазине Steam: многие игры Epic и GOG есть и там
                        Steam.findApp(n, new Steam.FindCallback() {
                            public void onFound(int appid) {
                                if (appid == 0) return;
                                Games.putPcGame(MainActivity.this, n, source, appid);
                                if (gamesPc) refreshGames(true);
                            }
                        });
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------- окно игры: откуда, звук, «Играть» / «Не играть» ----------

    private void showGameDialog(final GameRef g) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), 0);

        LinearLayout top = new LinearLayout(this);
        CoverView cv = new CoverView(this);
        cv.setAspect(g.pc ? 1.5f : 1f);
        cv.setTitle(g.name, g.source);
        if (g.pkg != null) {
            try {
                cv.setIcon(getPackageManager().getApplicationIcon(g.pkg));
            } catch (Exception ignored) {
            }
        } else {
            setSteamCover(cv, g.appid);
        }
        top.addView(cv, new LinearLayout.LayoutParams(dp(g.pc ? 84 : 72), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(14), 0, 0, 0);
        TextView title = text(g.name, 18, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(title);
        List<String> lines = new ArrayList<>();
        if (g.pc && !g.manual && g.minutes > 0) lines.add(getString(R.string.steam_hours, ListenStats.hours(this, g.minutes * 60L)));
        if (g.minutes2w > 0) lines.add(getString(R.string.steam_2w, duration(g.minutes2w * 60L)));
        if (g.weekMs >= 60000) lines.add(getString(R.string.games_week_short, duration(g.weekMs / 1000)));
        long eqSecs = Games.playedSecs(this, g.key);
        if (eqSecs >= 60) lines.add(getString(R.string.game_eq_time, duration(eqSecs)));
        for (String l : lines) {
            TextView t = text(l, 13, GREY);
            t.setPadding(0, dp(4), 0, 0);
            info.addView(t);
        }
        top.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(top);

        // откуда игра (ПК): Steam, Epic, GOG…
        final String[] src = {g.pc ? Games.sourceFor(this, g.key, g.source) : ""};
        if (g.pc) {
            box.addView(label(getString(R.string.game_source)));
            LinearLayout row = new LinearLayout(this);
            final List<Button> btns = new ArrayList<>();
            for (final String s : Games.PC_SOURCES) {
                Button b = chip(s, 0, s.equals(src[0]) ? ACCENT : CHIP, null);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        src[0] = s;
                        Games.setSource(MainActivity.this, g.key, s);
                        if (g.manual) Games.putPcGame(MainActivity.this, g.name, s, 0);
                        for (Button x : btns) x.setBackground(round(x == v ? ACCENT : CHIP, 24));
                    }
                });
                btns.add(b);
                row.addView(b);
            }
            box.addView(hscroll(row));
        }

        // звук в этой игре
        box.addView(label(getString(R.string.game_sound_for)));
        final int[] prof = {Games.profileFor(this, g.key, g.name)};
        final TextView hint = hintText("");
        LinearLayout pr = new LinearLayout(this);
        final List<Button> pbtns = new ArrayList<>();
        for (final int p : GAME_PROFILE_ORDER) {
            Button b = chip(getString(Games.PROFILE_NAMES[p]), 0, p == prof[0] ? ACCENT : CHIP, null);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prof[0] = p;
                    Games.setProfile(MainActivity.this, g.key, p);
                    EqService.poke(MainActivity.this);
                    for (Button x : pbtns) x.setBackground(round(x == v ? ACCENT : CHIP, 24));
                    setPlayHint(hint, g, p);
                }
            });
            pbtns.add(b);
            pr.addView(b);
        }
        box.addView(hscroll(pr));
        setPlayHint(hint, g, prof[0]);
        box.addView(hint);
        LinearLayout sc = new LinearLayout(this);
        sc.setPadding(0, dp(6), 0, 0);
        sc.addView(chip(getString(R.string.game_shortcut), R.drawable.ic_add, CHIP, new View.OnClickListener() {
            public void onClick(View v) { pinGame(g, src[0]); }
        }));
        box.addView(hscroll(sc));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setView(sv)
                .setPositiveButton(R.string.game_play, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { playGame(g, src[0], prof[0]); }
                })
                .setNegativeButton(R.string.game_no_play, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (g.key.equals(Games.sessionKey(MainActivity.this))) {
                            Games.stopSession(MainActivity.this);
                            EqService.poke(MainActivity.this);
                            refreshGames(true);
                        }
                    }
                });
        if (g.pc) {
            b.setNeutralButton(R.string.game_pc_sound, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { showApo(g.name, prof[0]); }
            });
        }
        b.show();
    }

    /** Ярлык игры на рабочий стол: нажали — EQ включил звук и сразу открыл игру. */
    private void pinGame(GameRef g, String source) {
        Drawable d = null;
        if (g.pkg != null) {
            try {
                d = getPackageManager().getApplicationIcon(g.pkg);
            } catch (Exception ignored) {
            }
        }
        Bitmap cover = g.appid > 0 ? Steam.coverNow(g.appid) : null;
        boolean ok = PlayActivity.pinShortcut(this, g.key, g.name, g.pkg, g.pc ? source : null, d, cover);
        Toast.makeText(this, ok ? R.string.game_shortcut_added : R.string.game_shortcut_fail, Toast.LENGTH_LONG).show();
    }

    private void setPlayHint(TextView hint, GameRef g, int profile) {
        hint.setVisibility(profile == Games.P_NONE ? View.GONE : View.VISIBLE);
        String name = getString(Games.PROFILE_NAMES[profile]);
        hint.setText(getString(g.pc ? R.string.game_pc_play_hint : R.string.game_phone_play_hint, name));
        hint.setPadding(dp(4), dp(10), dp(4), dp(4));
    }

    private void playGame(GameRef g, String source, int profile) {
        // звук игры и низкая задержка включаются сразу, до запуска — без пауз и щелчков в игре
        if (PlayActivity.launch(this, g.key, g.name, g.pkg, g.pc ? source : null)) {
            refreshGames(true);
            return;
        }
        if (g.pkg != null) return;   // игру удалили — сообщение уже показано
        final String name = g.name;
        final int prof = profile;
        new AlertDialog.Builder(this)
                .setMessage(R.string.game_no_stream)
                .setPositiveButton(R.string.steam_link, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { openStore(PlayActivity.STEAM_LINK); }
                })
                .setNeutralButton(R.string.game_pc_sound, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { showApo(name, prof); }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openStore(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
            } catch (Exception ignored) {
            }
        }
    }

    /** Тот же звук для Windows: текст для Equalizer APO. */
    private void showApo(String name, int profile) {
        final String cfg = Games.apoConfig(profile == Games.P_NONE ? Games.defaultProfile(this) : profile,
                Games.strength(this), name);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(text(getString(R.string.game_apo_hint), 13, GREY));
        TextView t = text(cfg, 12, Color.WHITE);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setBackground(round(Color.BLACK, 16));
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(t, topGap(10));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(R.string.game_apo_title)
                .setView(sv)
                .setPositiveButton(R.string.copy, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Equalizer APO", cfg));
                        if (Build.VERSION.SDK_INT < 33) {
                            Toast.makeText(MainActivity.this, R.string.text_copied, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // =====================================================================
    // Автопресет по приложению
    // =====================================================================

    private LinearLayout appRulesBox;
    private Switch appPresetSwitch;

    private void buildAppPresets(LinearLayout root) {
        root.addView(section(getString(R.string.ap_title)));
        appPresetSwitch = styledSwitch();
        LinearLayout sw = switchRow(getString(R.string.ap_switch), getString(R.string.ap_hint), appPresetSwitch);
        sw.setPadding(dp(4), 0, dp(4), dp(6));
        root.addView(sw);
        appPresetSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (!updating) AppPresets.setEnabled(MainActivity.this, on);
            }
        });
        appRulesBox = new LinearLayout(this);
        appRulesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(appRulesBox);
        LinearLayout add = new LinearLayout(this);
        add.setPadding(0, dp(6), 0, 0);
        add.addView(chip(getString(R.string.ap_add), R.drawable.ic_add, CHIP, new View.OnClickListener() {
            public void onClick(View v) { chooseRuleApp(); }
        }));
        root.addView(hscroll(add));
        refreshAppPresets();
    }

    private String ruleAppLabel(String app) {
        return AppPresets.GAME.equals(app) ? getString(R.string.ap_games) : appLabel(app);
    }

    private void refreshAppPresets() {
        if (appRulesBox == null) return;
        updating = true;
        appPresetSwitch.setChecked(AppPresets.enabled(this));
        updating = false;
        appRulesBox.removeAllViews();
        LinearLayout flow = new LinearLayout(this);
        for (final AppPresets.Rule r : AppPresets.rules(this)) {
            Button b = chip(ruleAppLabel(r.app) + "  →  " + AppPresets.presetLabel(this, r.preset),
                    AppPresets.GAME.equals(r.app) ? R.drawable.ic_gamepad : R.drawable.ic_music, CHIP,
                    new View.OnClickListener() {
                        public void onClick(View v) { chooseRulePreset(r.app); }
                    });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    AppPresets.remove(MainActivity.this, r.app);
                    refreshAppPresets();
                    return true;
                }
            });
            flow.addView(b);
        }
        if (flow.getChildCount() > 0) appRulesBox.addView(hscroll(flow));
    }

    /** Выбор приложения: «Игры», потом музыкальные, потом остальные по алфавиту. */
    private void chooseRuleApp() {
        final PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<String[]> rows = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName())) continue;
            boolean dup = false;
            for (String[] r : rows) if (r[0].equals(p)) dup = true;
            if (!dup) rows.add(new String[]{p, ri.loadLabel(pm).toString()});
        }
        Collections.sort(rows, new Comparator<String[]>() {
            public int compare(String[] a, String[] c) {
                int ia = MUSIC_APPS.indexOf(a[0]), ic = MUSIC_APPS.indexOf(c[0]);
                if (ia >= 0 || ic >= 0) {
                    if (ia < 0) return 1;
                    if (ic < 0) return -1;
                    return ia - ic;
                }
                return a[1].compareToIgnoreCase(c[1]);
            }
        });
        final List<String> pkgs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        pkgs.add(AppPresets.GAME);
        labels.add(getString(R.string.ap_games));
        for (String[] r : rows) {
            pkgs.add(r[0]);
            labels.add(r[1]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ap_choose_app)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { chooseRulePreset(pkgs.get(which)); }
                })
                .show();
    }

    private void chooseRulePreset(final String app) {
        final List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < EqEngine.PRESET_NAMES.length; i++) {
            ids.add(AppPresets.BUILTIN + i);
            labels.add(getString(EqEngine.PRESET_NAMES[i]));
        }
        for (String n : eq.presetNames()) {
            ids.add(AppPresets.USER + n);
            labels.add(n);
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ap_choose_preset, ruleAppLabel(app)))
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        AppPresets.put(MainActivity.this, app, ids.get(which));
                        refreshAppPresets();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void buildAutomation(LinearLayout root) {
        root.addView(section(getString(R.string.sec_auto)));

        autoSwitch = styledSwitch();
        root.addView(switchRow(getString(R.string.auto_mode), getString(R.string.auto_mode_hint), autoSwitch));
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (updating) return;
                eq.setAutoMode(on);
                if (on) eq.setEnabled(DeviceMonitor.get().primaryAudio() != null);
                refreshEq();
            }
        });

        perDeviceSwitch = styledSwitch();
        root.addView(switchRow(getString(R.string.per_device), getString(R.string.per_device_hint), perDeviceSwitch));
        perDeviceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (updating) return;
                eq.setPerDevice(on);
                if (on) {
                    DeviceInfo p = DeviceMonitor.get().primaryAudio();
                    if (p != null) eq.switchProfile(p.address, p.name);
                    else eq.switchProfile("phone", PhoneInfo.get(MainActivity.this).speakerName(MainActivity.this));
                }
                refreshEq();
            }
        });

        autostartSwitch = styledSwitch();
        root.addView(switchRow(getString(R.string.autostart), getString(R.string.autostart_hint), autostartSwitch));
        autostartSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (!updating) BootReceiver.setEnabled(MainActivity.this, on);
            }
        });

        LinearLayout tiles = new LinearLayout(this);
        tiles.setPadding(0, dp(12), 0, 0);
        tiles.addView(chip(getString(R.string.add_tile), R.drawable.ic_tiles, CHIP, new View.OnClickListener() {
            public void onClick(View v) { addTiles(); }
        }));
        root.addView(hscroll(tiles));
    }

    // =====================================================================
    // AutoEQ: коррекция наушников из открытой базы AutoEq
    // =====================================================================

    private void buildAutoEq(LinearLayout root) {
        root.addView(section(getString(R.string.ae_title)));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(6));
        card.setBackground(round(CARD, 24));

        aeStatus = text("", 14, Color.WHITE);
        card.addView(aeStatus);
        TextView hint = text(getString(R.string.ae_hint), 12, GREY);
        hint.setPadding(0, dp(4), 0, 0);
        card.addView(hint);

        // предложение, найденное при подключении наушников
        aeSuggestBox = new LinearLayout(this);
        aeSuggestBox.setOrientation(LinearLayout.VERTICAL);
        aeSuggestBox.setPadding(dp(12), dp(10), dp(12), dp(4));
        aeSuggestBox.setBackground(round(Color.rgb(0x2A, 0x2C, 0x33), 18));
        aeSuggestText = text("", 14, Color.WHITE);
        aeSuggestBox.addView(aeSuggestText);
        LinearLayout sb = new LinearLayout(this);
        sb.setPadding(0, dp(8), 0, 0);
        sb.addView(chip(getString(R.string.ae_apply), R.drawable.ic_check, ACCENT, new View.OnClickListener() {
            public void onClick(View v) {
                DeviceInfo dev = DeviceMonitor.get().primaryAudio();
                AutoEq.Entry e = dev == null ? null : AutoEq.suggestionEntry(MainActivity.this, dev.address);
                if (e != null) applyAutoEq(dev, e);
            }
        }));
        sb.addView(chip(getString(R.string.ae_no), 0, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                DeviceInfo dev = DeviceMonitor.get().primaryAudio();
                if (dev != null) AutoEq.setDismissed(MainActivity.this, dev.address);
                refreshAutoEq();
            }
        }));
        aeSuggestBox.addView(hscroll(sb));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        card.addView(aeSuggestBox, slp);

        aeSwitch = styledSwitch();
        LinearLayout sw = switchRow(getString(R.string.ae_on), null, aeSwitch);
        sw.setPadding(0, dp(8), 0, 0);
        card.addView(sw);
        aeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (updating) return;
                DeviceInfo dev = DeviceMonitor.get().primaryAudio();
                AutoEq.Correction c = dev == null ? null : AutoEq.load(MainActivity.this, dev.address);
                if (c == null) return;
                c.on = on;
                AutoEq.save(MainActivity.this, dev.address, c);
                AutoEq.applyFor(MainActivity.this, dev.address);
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(10), 0, 0);
        actions.addView(chip(getString(R.string.ae_find), R.drawable.ic_search, CHIP, new View.OnClickListener() {
            public void onClick(View v) { showAutoEqSearch(); }
        }));
        aeRemoveBtn = chip(getString(R.string.ae_remove), R.drawable.ic_stop, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                DeviceInfo dev = DeviceMonitor.get().primaryAudio();
                if (dev == null) return;
                AutoEq.remove(MainActivity.this, dev.address);
                AutoEq.applyFor(MainActivity.this, dev.address);
            }
        });
        actions.addView(aeRemoveBtn);
        card.addView(hscroll(actions));
        root.addView(card);
    }

    private void refreshAutoEq() {
        if (aeStatus == null) return;
        DeviceInfo dev = DeviceMonitor.get().primaryAudio();
        AutoEq.Correction c = dev == null ? null : AutoEq.load(this, dev.address);
        if (dev == null) {
            aeStatus.setText(R.string.ae_need_device);
        } else if (c == null) {
            aeStatus.setText(getString(R.string.ae_none, dev.name));
        } else {
            aeStatus.setText(getString(R.string.ae_current, c.name, c.source));
        }
        updating = true;
        aeSwitch.setChecked(c != null && c.on);
        updating = false;
        aeSwitch.setEnabled(c != null);
        aeRemoveBtn.setVisibility(c != null ? View.VISIBLE : View.GONE);
        AutoEq.Entry sug = dev == null || c != null ? null : AutoEq.suggestionEntry(this, dev.address);
        aeSuggestBox.setVisibility(sug != null ? View.VISIBLE : View.GONE);
        if (sug != null) aeSuggestText.setText(getString(R.string.ae_suggest, sug.name, sug.source));
    }

    private void showAutoEqSearch() {
        final DeviceInfo dev = DeviceMonitor.get().primaryAudio();
        if (dev == null) {
            Toast.makeText(this, R.string.ae_need_device, Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText q = new EditText(this);
        q.setSingleLine(true);
        q.setHint(R.string.ae_search_hint);
        q.setText(dev.name);
        box.addView(q);
        final TextView status = text(getString(R.string.ae_loading), 13, GREY);
        status.setPadding(dp(4), dp(8), dp(4), dp(4));
        box.addView(status);
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(results);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.ae_title)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .show();

        final java.util.List<AutoEq.Entry>[] all = new java.util.List[1];
        final Runnable search = new Runnable() {
            public void run() {
                if (all[0] == null) return;
                results.removeAllViews();
                java.util.List<AutoEq.Entry> found = AutoEq.search(all[0], q.getText().toString(), 40);
                status.setText(found.isEmpty() ? getString(R.string.ae_nothing) : "");
                status.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
                for (final AutoEq.Entry e : found) {
                    TextView row = text(e.name, 15, Color.WHITE);
                    row.setPadding(dp(12), dp(10), dp(12), dp(4));
                    TextView src = text(e.source, 12, GREY);
                    src.setPadding(dp(12), 0, dp(12), dp(10));
                    LinearLayout item = new LinearLayout(MainActivity.this);
                    item.setOrientation(LinearLayout.VERTICAL);
                    item.setBackground(round(Color.rgb(0x2A, 0x2C, 0x33), 14));
                    item.addView(row);
                    item.addView(src);
                    item.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            dlg.dismiss();
                            applyAutoEq(dev, e);
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = dp(6);
                    results.addView(item, lp);
                }
            }
        };
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(search);
                ui.postDelayed(search, 250);
            }
        });
        AutoEq.loadIndex(this, new AutoEq.IndexCallback() {
            public void onIndex(java.util.List<AutoEq.Entry> list, String error) {
                if (list == null) {
                    status.setText(getString(R.string.ae_index_failed, error));
                    return;
                }
                all[0] = list;
                search.run();
            }
        });
    }

    private void applyAutoEq(final DeviceInfo dev, final AutoEq.Entry e) {
        Toast.makeText(this, R.string.ae_loading, Toast.LENGTH_SHORT).show();
        AutoEq.fetch(this, e, new AutoEq.CorrectionCallback() {
            public void onCorrection(AutoEq.Correction c, String error) {
                if (c == null) {
                    Toast.makeText(MainActivity.this, getString(R.string.ae_fetch_failed, error), Toast.LENGTH_LONG).show();
                    return;
                }
                AutoEq.save(MainActivity.this, dev.address, c);
                AutoEq.setSuggestion(MainActivity.this, dev.address, null);
                DeviceInfo primary = DeviceMonitor.get().primaryAudio();
                if (primary != null && primary.address.equals(dev.address)) {
                    AutoEq.applyFor(MainActivity.this, dev.address);
                }
                Toast.makeText(MainActivity.this, getString(R.string.ae_applied, c.name), Toast.LENGTH_SHORT).show();
                refreshAutoEq();
            }
        });
    }

    // =====================================================================
    // Машина: фокус звука
    // =====================================================================

    private void buildCar(LinearLayout root) {
        carBox = new LinearLayout(this);
        carBox.setOrientation(LinearLayout.VERTICAL);
        carBox.addView(section(getString(R.string.car_title)));
        carBox.addView(hintText(getString(R.string.car_hint)));

        carView = new CarFocusView(this);
        carView.setBackground(round(CARD, 24));
        carView.setListener(new CarFocusView.Listener() {
            public void onFocusChanged(int point) {
                DeviceSettings ds = carSettings();
                if (ds == null) return;
                ds.carFocus = point;
                ds.save(MainActivity.this);
                CarFocusView.applyFocus(MainActivity.this);
                refreshCar();
            }

            public void onSpeakersChanged(float[] speakers) {
                DeviceSettings ds = carSettings();
                if (ds == null) return;
                ds.carSpk = speakers;
                ds.save(MainActivity.this);
                CarFocusView.applyFocus(MainActivity.this);
                refreshCar();
            }
        });
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(440));
        vlp.topMargin = dp(8);
        carBox.addView(carView, vlp);

        // вкладки как на экране магнитолы: иконка, под ней подпись
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackground(round(CARD, 24));
        tabs.setPadding(dp(4), dp(8), dp(4), dp(8));
        for (int i = 0; i < CAR_TABS; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setContentDescription(getString(CAR_TAB_TITLES[i]));
            ImageView ic = new ImageView(this);
            ic.setScaleType(ImageView.ScaleType.CENTER);
            item.addView(ic, new LinearLayout.LayoutParams(dp(52), dp(32)));
            TextView t = text(getString(CAR_TAB_TITLES[i]), 11, GREY);
            t.setMaxLines(2);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(1), dp(4), dp(1), 0);
            item.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectCarTab(index); }
            });
            carTabIcons[i] = ic;
            carTabLabels[i] = t;
            tabs.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8);
        carBox.addView(tabs, tlp);
        for (int i = 0; i < CAR_TABS; i++) {
            carPanels[i] = new LinearLayout(this);
            carPanels[i].setOrientation(LinearLayout.VERTICAL);
            carBox.addView(carPanels[i]);
        }
        buildCarEqPanel(carPanels[CAR_TAB_EQ]);
        buildCarSurroundPanel(carPanels[CAR_TAB_SURROUND]);
        buildCarBassPanel(carPanels[CAR_TAB_BASS]);
        buildCarFilterPanel(carPanels[CAR_TAB_FILTER]);
        LinearLayout zone = carPanels[CAR_TAB_ZONE];

        carStatus = text("", 14, Color.WHITE);
        carStatus.setPadding(dp(4), dp(10), dp(4), dp(6));
        zone.addView(carStatus);

        LinearLayout modes = new LinearLayout(this);
        carOffBtn = chip(getString(R.string.off), 0, CHIP, new View.OnClickListener() {
            public void onClick(View v) { setCarFocus(-1, -1); }
        });
        modes.addView(carOffBtn);
        int[] names = {R.string.car_soft, R.string.car_normal, R.string.car_strong};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            carModeBtns[i] = chip(getString(names[i]), 0, CHIP, new View.OnClickListener() {
                public void onClick(View v) { setCarFocus(-2, mode); }
            });
            modes.addView(carModeBtns[i]);
        }
        carRhdBtn = chip(getString(R.string.car_rhd), R.drawable.ic_car, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                DeviceSettings ds = carSettings();
                if (ds == null) return;
                ds.carRhd = !ds.carRhd;
                ds.save(MainActivity.this);
                refreshCar();
            }
        });
        modes.addView(carRhdBtn);
        zone.addView(hscroll(modes));

        // где реально стоят динамики + проверка каналов
        zone.addView(label(getString(R.string.car_speakers)));
        LinearLayout sp = new LinearLayout(this);
        carEditBtn = chip(getString(R.string.car_edit), R.drawable.ic_speaker, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                carEdit = !carEdit;
                carView.setEditMode(carEdit);
                refreshCar();
            }
        });
        sp.addView(carEditBtn);
        sp.addView(chip(getString(R.string.car_layout), R.drawable.ic_car, CHIP, new View.OnClickListener() {
            public void onClick(View v) { chooseCarLayout(); }
        }));
        sp.addView(chip(getString(R.string.car_test), R.drawable.ic_volume_up, CHIP, new View.OnClickListener() {
            public void onClick(View v) { testCarSpeakers(); }
        }));
        zone.addView(hscroll(sp));
        root.addView(carBox);
        selectCarTab(settings.getInt("car_tab", CAR_TAB_ZONE));
    }

    /** EQ: кривая, которую сейчас слышно в машине (эквалайзер + Bass Boost + фильтр баса). */
    private void buildCarEqPanel(LinearLayout p) {
        carEqGraph = new EqGraphView(this);   // без слушателя — только смотреть
        carEqGraph.setBackground(round(CARD, 24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        lp.topMargin = dp(10);
        p.addView(carEqGraph, lp);
        TextView note = hintText(getString(R.string.car_eq_note));
        note.setPadding(dp(4), dp(10), dp(4), dp(8));
        p.addView(note);
        LinearLayout presets = new LinearLayout(this);
        for (int i = 0; i < EqEngine.PRESETS.length; i++) {
            final int index = i;
            presets.addView(chip(getString(EqEngine.PRESET_NAMES[i]), 0, CHIP, new View.OnClickListener() {
                public void onClick(View v) {
                    eq.applyBuiltIn(index, getString(EqEngine.PRESET_NAMES[index]));
                    refreshEq();
                }
            }));
        }
        p.addView(hscroll(presets));
        LinearLayout b = new LinearLayout(this);
        b.addView(chip(getString(R.string.car_eq_open), R.drawable.ic_equalizer, CHIP, new View.OnClickListener() {
            public void onClick(View v) { selectTab(TAB_EQ, true); }
        }));
        p.addView(hscroll(b));
    }

    /** Объёмный звук: системный Virtualizer на весь звук, сила 1…100. */
    private void buildCarSurroundPanel(LinearLayout p) {
        p.addView(label(getString(R.string.car_seat)));
        LinearLayout seats = new LinearLayout(this);
        for (int i = 0; i < CAR_SEATS.length; i++) {
            final int seat = CAR_SEATS[i];
            carSeatBtns[i] = chip("", 0, CHIP, new View.OnClickListener() {
                public void onClick(View v) {
                    DeviceSettings ds = carSettings();
                    if (ds == null) return;
                    setCarFocus(seatPoint(seat, ds.carRhd), -1);
                }
            });
            seats.addView(carSeatBtns[i]);
        }
        p.addView(hscroll(seats));
        p.addView(hintText(getString(R.string.car_delay_note)));
        carSurSwitch = styledSwitch();
        p.addView(switchRow(getString(R.string.car_tab_surround), getString(R.string.car_surround_note), carSurSwitch));
        carSurSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (carUpdating) return;
                DeviceSettings ds = carSettings();
                if (ds == null) return;
                ds.carSurround = on ? settings.getInt("car_sur_last", 50) : 0;
                saveCarSound(ds);
            }
        });
        carSurBar = new SeekBar(this);
        carSurBar.setMax(100);
        carSurVal = text("", 15, Color.WHITE);
        carSurBar.setOnSeekBarChangeListener(new Seek() {
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (!user || carUpdating) return;
                DeviceSettings ds = carSettings();
                if (ds == null) return;
                ds.carSurround = Math.max(1, v);
                settings.edit().putInt("car_sur_last", ds.carSurround).apply();
                saveCarSound(ds);
            }
        });
        p.addView(sliderRow(getString(R.string.car_surround_level), carSurBar, carSurVal));
        carSurNa = text(getString(R.string.car_surround_na), 13, Color.rgb(0xFF, 0xB0, 0x40));
        carSurNa.setPadding(dp(4), dp(4), dp(4), dp(4));
        p.addView(carSurNa);
    }

    /** Bass Boost: подъём низа 0…12 дБ до выбранной частоты. */
    private void buildCarBassPanel(LinearLayout p) {
        carBassBar = new SeekBar(this);
        carBassBar.setMax(12);
        carBassVal = text("", 15, Color.WHITE);
        carBassBar.setOnSeekBarChangeListener(new Seek() {
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (!user || carUpdating) return;
                DeviceSettings ds = carSettings();
                if (ds == null) return;
                ds.carBass = v;
                saveCarSound(ds);
            }
        });
        p.addView(sliderRow(getString(R.string.car_tab_bass), carBassBar, carBassVal));
        p.addView(label(getString(R.string.car_bass_upto)));
        LinearLayout hz = new LinearLayout(this);
        for (int i = 0; i < CAR_BASS_HZ.length; i++) {
            final int f = CAR_BASS_HZ[i];
            carBassHzBtns[i] = chip(getString(R.string.car_hz, f), 0, CHIP, new View.OnClickListener() {
                public void onClick(View v) {
                    DeviceSettings ds = carSettings();
                    if (ds == null) return;
                    ds.carBassHz = f;
                    if (ds.carBass == 0) ds.carBass = 6;   // выбрали частоту — сразу слышно
                    saveCarSound(ds);
                }
            });
            hz.addView(carBassHzBtns[i]);
        }
        p.addView(hscroll(hz));
        p.addView(hintText(getString(R.string.car_bass_note)));
    }

    /** Фильтр баса: срез ниже частоты (выкл, 40…120 Гц). */
    private void buildCarFilterPanel(LinearLayout p) {
        p.addView(label(getString(R.string.car_tab_filter)));
        LinearLayout hz = new LinearLayout(this);
        for (int i = 0; i < CAR_HP_HZ.length; i++) {
            final int f = CAR_HP_HZ[i];
            carHpBtns[i] = chip(f == 0 ? getString(R.string.off) : getString(R.string.car_hz, f), 0, CHIP,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            DeviceSettings ds = carSettings();
                            if (ds == null) return;
                            ds.carHp = f;
                            saveCarSound(ds);
                        }
                    });
            hz.addView(carHpBtns[i]);
        }
        p.addView(hscroll(hz));
        p.addView(hintText(getString(R.string.car_filter_note)));
    }

    /** Точка CarFocusView для места из списка (водитель и пассажир — по стороне руля). */
    private static int seatPoint(int seat, boolean rhd) {
        if (seat == SEAT_DRIVER) return rhd ? 2 : 0;
        if (seat == SEAT_PASSENGER) return rhd ? 0 : 2;
        return seat;
    }

    private void saveCarSound(DeviceSettings ds) {
        ds.save(this);
        CarFocusView.applyFocus(this);
        refreshCar();
    }

    private void selectCarTab(int t) {
        if (t < 0 || t >= CAR_TABS) t = CAR_TAB_ZONE;
        carTab = t;
        settings.edit().putInt("car_tab", t).apply();
        for (int i = 0; i < CAR_TABS; i++) {
            boolean on = i == t;
            carPanels[i].setVisibility(on ? View.VISIBLE : View.GONE);
            carTabIcons[i].setImageDrawable(icon(CAR_TAB_ICONS[i], on ? Color.WHITE : GREY));
            carTabIcons[i].setBackground(on ? round(ACCENT, 16) : null);
            carTabLabels[i].setTextColor(on ? Color.WHITE : GREY);
            carTabLabels[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        // «Объёмный звук» (как Apkārtējā skaņa в магнитоле): у динамиков числа задержки;
        // расстановка динамиков — только на ZONE
        carView.setShowDelays(t == CAR_TAB_SURROUND);
        if (t != CAR_TAB_ZONE && carEdit) {
            carEdit = false;
            carView.setEditMode(false);
        }
        if (carKeyShown != null) refreshCar();
    }

    /** Мини-график EQ машины: кривая эквалайзера + Bass Boost и фильтр баса этой машины. */
    private void refreshCarEq() {
        if (carEqGraph == null || carTab != CAR_TAB_EQ) return;
        DeviceSettings ds = carSettings();
        if (ds == null) return;
        float[] f = eq.freqs();
        float[] g = eq.curveWithoutCar();
        for (int i = 0; i < g.length && i < f.length; i++) {
            g[i] = Math.max(-24f, Math.min(24f, g[i] + EqEngine.carLayer(f[i], ds.carBass, ds.carBassHz, ds.carHp)));
        }
        carEqGraph.setBands(f, g);
    }

    private void chooseCarLayout() {
        final DeviceSettings ds = carSettings();
        if (ds == null) return;
        int[] names = {R.string.car_layout_2, R.string.car_layout_4, R.string.car_layout_6,
                R.string.car_layout_sub, R.string.car_layout_center};
        String[] items = new String[names.length];
        for (int i = 0; i < names.length; i++) items[i] = getString(names[i]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.car_layout)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        ds.carSpk = CarFocusView.LAYOUTS[which].clone();
                        ds.save(MainActivity.this);
                        CarFocusView.applyFocus(MainActivity.this);
                        refreshCar();
                    }
                })
                .show();
    }

    /** Три сигнала слева, потом три справа — человек говорит, откуда пришёл первый. */
    private void testCarSpeakers() {
        if (carSettings() == null || SpeakerTest.isPlaying()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.car_test)
                .setMessage(R.string.car_test_msg)
                .setPositiveButton(R.string.car_test_play, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { playCarTest(); }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void playCarTest() {
        SpeakerTest.play(this, new SpeakerTest.Done() {
            public void onDone() {
                if (isFinishing()) return;
                String[] items = {getString(R.string.car_test_left), getString(R.string.car_test_right),
                        getString(R.string.car_test_both), getString(R.string.car_test_again)};
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.car_test_q)
                        .setItems(items, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                if (which == 3) {
                                    playCarTest();
                                    return;
                                }
                                DeviceSettings ds = carSettings();
                                if (ds == null) return;
                                ds.carSwap = which == 1;
                                ds.carMono = which == 2;
                                ds.save(MainActivity.this);
                                CarFocusView.applyFocus(MainActivity.this);
                                Toast.makeText(MainActivity.this, which == 0 ? R.string.car_test_ok
                                        : which == 1 ? R.string.car_swapped : R.string.car_mono, Toast.LENGTH_LONG).show();
                                refreshCar();
                            }
                        })
                        .show();
            }
        });
    }

    // =====================================================================
    // Этот телефон: название, Android, что умеет и что мешает
    // =====================================================================

    private TextView phoneStatus;
    private Button phoneBatteryBtn;

    private void buildPhone(LinearLayout root) {
        PhoneInfo ph = PhoneInfo.get(this);
        phoneSection = section(getString(R.string.ph_title));
        root.addView(phoneSection);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(8));
        card.setBackground(round(CARD, 24));
        TextView name = text(ph.name, 17, Color.WHITE);
        name.getPaint().setFakeBoldText(true);
        name.setCompoundDrawablePadding(dp(10));
        phoneName = name;
        card.addView(name);
        TextView os = text(getString(R.string.ph_android, ph.android, ph.sdk) + " · " + ph.brand + " " + ph.model, 12, GREY);
        os.setPadding(dp(32), dp(2), 0, dp(8));
        card.addView(os);
        phoneStatus = text("", 13, Color.rgb(0xC8, 0xCA, 0xD0));
        card.addView(phoneStatus);
        if (ph.tipRes() != 0) {
            TextView tip = text(getString(ph.tipRes()), 12, GREY);
            tip.setPadding(0, dp(8), 0, 0);
            card.addView(tip);
        }
        LinearLayout b = new LinearLayout(this);
        b.setPadding(0, dp(10), 0, 0);
        phoneBatteryBtn = chip(getString(R.string.ph_battery_btn), R.drawable.ic_battery_alert, ACCENT, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            }
        });
        b.addView(phoneBatteryBtn);
        card.addView(hscroll(b));

        // где стоит EQ: телефон или магнитола (автоматически или вручную)
        card.addView(label(getString(R.string.hu_kind)));
        LinearLayout kinds = new LinearLayout(this);
        int[] names = {R.string.hu_auto, R.string.t_phone, R.string.hu_unit};
        for (int i = 0; i < kindBtns.length; i++) {
            final int k = i;
            kindBtns[i] = chip(getString(names[i]), 0, CHIP, new View.OnClickListener() {
                public void onClick(View v) {
                    PhoneInfo.setKind(MainActivity.this, k);
                    CarFocusView.applyFocus(MainActivity.this);
                    EqService.poke(MainActivity.this);   // профиль «динамики машины» и автовключение
                    refreshPhone();
                    refreshCar();
                }
            });
            kinds.addView(kindBtns[i]);
        }
        card.addView(hscroll(kinds));
        phoneWhy = text("", 12, GREY);
        phoneWhy.setPadding(0, 0, 0, dp(6));
        card.addView(phoneWhy);
        root.addView(card);
    }

    private TextView phoneSection, phoneName, phoneWhy;
    private final Button[] kindBtns = new Button[3];

    private void refreshPhone() {
        if (phoneStatus == null) return;
        PhoneInfo ph = PhoneInfo.get(this);
        boolean hu = PhoneInfo.headUnit(this);
        phoneSection.setText(hu ? R.string.hu_title : R.string.ph_title);
        Drawable ic = icon(hu ? R.drawable.ic_car : R.drawable.ic_phone, Color.WHITE);
        ic.setBounds(0, 0, dp(22), dp(22));
        phoneName.setCompoundDrawablesRelative(ic, null, null, null);
        int kind = PhoneInfo.kind(this);
        for (int i = 0; i < kindBtns.length; i++) kindBtns[i].setBackground(round(i == kind ? ACCENT : CHIP, 24));
        String why = ph.headUnitWhy != 0 ? getString(ph.headUnitWhy) : getString(R.string.hu_why_phone);
        phoneWhy.setText(getString(R.string.hu_auto_says, why) + (hu ? "\n" + getString(R.string.hu_note) : ""));
        boolean free = PhoneInfo.batteryUnrestricted(this);
        String s = getString(eq.globalOk ? R.string.status_global : R.string.ph_eq_players)
                + "\n" + getString(ph.ble ? R.string.ph_ble_yes : R.string.ph_ble_no)
                + "\n" + getString(free ? R.string.ph_battery_ok : R.string.ph_battery_warn);
        phoneStatus.setText(s);
        phoneBatteryBtn.setVisibility(free ? View.GONE : View.VISIBLE);
    }

    private DeviceSettings carSettings() {
        return carKeyShown == null ? null : DeviceSettings.get(this, carKeyShown);
    }

    /** Чьи настройки машины на экране: адрес Bluetooth-машины или CarFocusView.HEAD_UNIT. */
    private String carKeyShown;

    /** point: -1 выключить, -2 не менять; mode: -1 не менять. */
    private void setCarFocus(int point, int mode) {
        DeviceSettings ds = carSettings();
        if (ds == null) return;
        if (point != -2) ds.carFocus = point;
        if (mode >= 0) {
            ds.carMode = mode;
            if (ds.carFocus < 0) ds.carFocus = ds.carRhd ? 2 : 0; // сила без точки — фокус на водителя
        }
        ds.save(this);
        CarFocusView.applyFocus(this);
        refreshCar();
    }

    private void refreshCar() {
        DeviceInfo dev = DeviceMonitor.get().find(carAddress);
        boolean btCar = dev != null && dev.type == DeviceInfo.T_CAR;
        // EQ на магнитоле: салон и динамики — её собственные
        boolean headUnit = !btCar && PhoneInfo.headUnit(this);
        boolean show = btCar || headUnit;
        carBox.setVisibility(show ? View.VISIBLE : View.GONE);
        carKeyShown = btCar ? dev.address : headUnit ? CarFocusView.HEAD_UNIT : null;
        if (!show) return;
        DeviceSettings ds = DeviceSettings.get(this, carKeyShown);
        carView.setState(ds.carFocus, ds.carMode, ds.carRhd);
        carView.setSpeakers(ds.speakers());
        float bal = CarFocusView.balanceFor(ds.carFocus, ds.carMode, ds.speakers(), ds.carSwap, ds.carMono);
        carView.setBalance(ds.carSwap ? -bal : bal);   // на картинке — реальные стороны
        carEditBtn.setBackground(round(carEdit ? ACCENT : CHIP, 24));
        carEditBtn.setText(carEdit ? R.string.car_edit_done : R.string.car_edit);
        carOffBtn.setBackground(round(ds.carFocus < 0 ? ACCENT : CHIP, 24));
        for (int i = 0; i < carModeBtns.length; i++) {
            carModeBtns[i].setBackground(round(ds.carFocus >= 0 && ds.carMode == i ? ACCENT : CHIP, 24));
        }
        carRhdBtn.setBackground(round(ds.carRhd ? ACCENT : CHIP, 24));
        String s;
        if (carEdit) {
            s = getString(R.string.car_edit_hint);
        } else if (ds.carFocus < 0) {
            s = getString(R.string.car_status_off);
        } else {
            // подпись — по реальным сторонам салона (с учётом перепутанных каналов)
            float phys = ds.carSwap ? -bal : bal;
            s = getString(R.string.car_status,
                    getString(CarFocusView.labelRes(ds.carFocus, ds.carRhd)), balanceLabel(Math.round(phys * 100)));
            if (Math.abs(bal) > 0.005f) {
                s += " · " + getString(R.string.car_db, Math.abs(CarFocusView.balanceDb(bal)));
            }
            if (!carKeyShown.equals(CarFocusView.carKey(this))) s += "\n" + getString(R.string.car_pending);
        }
        if (ds.carMono) s += "\n" + getString(R.string.car_mono);
        else if (ds.carSwap) s += "\n" + getString(R.string.car_swapped);
        carStatus.setText(s);

        for (int i = 0; i < carSeatBtns.length; i++) {
            int pt = seatPoint(CAR_SEATS[i], ds.carRhd);
            carSeatBtns[i].setText(CarFocusView.labelRes(pt, ds.carRhd));
            carSeatBtns[i].setBackground(round(pt == ds.carFocus ? ACCENT : CHIP, 24));
        }
        carUpdating = true;
        boolean sur = ds.carSurround > 0;
        carSurSwitch.setChecked(sur);
        carSurBar.setEnabled(sur);
        carSurBar.setProgress(sur ? ds.carSurround : settings.getInt("car_sur_last", 50));
        carSurVal.setText(sur ? ds.carSurround + "%" : getString(R.string.off));
        carSurNa.setVisibility(sur && !eq.surroundSupported ? View.VISIBLE : View.GONE);
        carBassBar.setProgress(ds.carBass);
        carBassVal.setText(ds.carBass > 0 ? String.format(Locale.US, "+%d dB", ds.carBass) : getString(R.string.off));
        for (int i = 0; i < carBassHzBtns.length; i++) {
            carBassHzBtns[i].setBackground(round(CAR_BASS_HZ[i] == ds.carBassHz ? ACCENT : CHIP, 24));
        }
        for (int i = 0; i < carHpBtns.length; i++) {
            carHpBtns[i].setBackground(round(CAR_HP_HZ[i] == ds.carHp ? ACCENT : CHIP, 24));
        }
        carUpdating = false;
        refreshCarEq();
    }

    // =====================================================================
    // Music Time
    // =====================================================================

    private void buildMusicTime(LinearLayout root) {
        root.addView(section(getString(R.string.music_time)));

        // период: неделя / месяц
        LinearLayout period = new LinearLayout(this);
        mtWeekBtn = chip(getString(R.string.mt_period_week), 0, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                settings.edit().putBoolean("mt_month", false).apply();
                refreshMusicTime();
            }
        });
        mtMonthBtn = chip(getString(R.string.mt_period_month), 0, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                settings.edit().putBoolean("mt_month", true).apply();
                refreshMusicTime();
            }
        });
        period.addView(mtWeekBtn);
        period.addView(mtMonthBtn);
        root.addView(hscroll(period));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(CARD, 24));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        mtToday = text("", 28, Color.WHITE);
        mtToday.getPaint().setFakeBoldText(true);
        head.addView(mtToday);
        mtTodayLbl = text(getString(R.string.mt_today), 14, GREY);
        mtTodayLbl.setPadding(dp(8), 0, 0, dp(5));
        head.addView(mtTodayLbl);
        card.addView(head);
        mtWeek = text("", 13, Color.rgb(0xC8, 0xCA, 0xD0));
        mtWeek.setPadding(0, dp(2), 0, 0);
        card.addView(mtWeek);

        mtView = new MusicTimeView(this);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
        vlp.topMargin = dp(8);
        card.addView(mtView, vlp);

        mtTopBox = new LinearLayout(this);
        mtTopBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(mtTopBox);
        root.addView(card);

        // достижения
        root.addView(section(getString(R.string.ach_title)));
        mtAchBox = new FlowLayout(this);
        root.addView(mtAchBox);

        mtHint = hintText("");
        mtHint.setPadding(dp(4), dp(8), dp(4), 0);
        root.addView(mtHint);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(chip(getString(R.string.sc_button), R.drawable.ic_share, ACCENT, new View.OnClickListener() {
            public void onClick(View v) { shareMusicTime(); }
        }));
        actions.addView(chip(getString(R.string.mt_reset), R.drawable.ic_stop, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.mt_reset_q)
                        .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                ListenStats.get(MainActivity.this).reset();
                                refreshMusicTime();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        }));
        root.addView(hscroll(actions));
    }

    private String duration(long s) {
        return ListenStats.format(this, s);
    }

    private void refreshMusicTime() {
        if (mtView == null) return;
        ListenStats st = ListenStats.get(this);
        boolean month = settings.getBoolean("mt_month", false);
        mtWeekBtn.setBackground(round(!month ? ACCENT : CHIP, 24));
        mtMonthBtn.setBackground(round(month ? ACCENT : CHIP, 24));
        Locale loc = getResources().getConfiguration().getLocales().get(0);
        ListenStats.Summary sum;
        if (!month) {
            long[] days = st.lastDays(7);
            String[] labels = new String[7];
            SimpleDateFormat fmt = new SimpleDateFormat("EE", loc);
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -6);
            long week = 0;
            for (int i = 0; i < 7; i++) {
                labels[i] = fmt.format(cal.getTime());
                cal.add(Calendar.DAY_OF_YEAR, 1);
                week += days[i];
            }
            mtView.setData(days, labels);
            mtToday.setText(duration(days[6]));
            mtTodayLbl.setText(R.string.mt_today);
            mtWeek.setText(getString(R.string.mt_week, duration(week), duration(week / 7)));
            sum = st.summary(7);
        } else {
            long[] days = st.monthDays();
            String[] labels = new String[days.length];
            for (int i = 0; i < days.length; i++) {
                int day = i + 1;
                labels[i] = day == 1 || day % 5 == 0 || i == days.length - 1 ? String.valueOf(day) : "";
            }
            mtView.setData(days, labels);
            sum = st.summary(0);
            mtToday.setText(ListenStats.hours(this, sum.total));
            String name = new SimpleDateFormat("LLLL", loc).format(Calendar.getInstance().getTime());
            mtTodayLbl.setText(name.isEmpty() ? name : name.substring(0, 1).toUpperCase(loc) + name.substring(1));
            mtWeek.setText(getString(R.string.mt_month_sub, sum.daysWithMusic, duration(sum.total / Math.max(1, days.length))));
        }

        mtTopBox.removeAllViews();
        if (month && sum.total > 0) {
            // итоги месяца
            mtTopBox.addView(label(getString(R.string.mt_summary)));
            if (sum.top(sum.devices) != null) mtTopBox.addView(summaryLine(R.drawable.ic_headset, getString(R.string.mt_fav_device, sum.top(sum.devices))));
            if (sum.top(sum.presets) != null) mtTopBox.addView(summaryLine(R.drawable.ic_equalizer, getString(R.string.mt_fav_preset, sum.top(sum.presets))));
            if (sum.top(sum.artists) != null) mtTopBox.addView(summaryLine(R.drawable.ic_favorite, getString(R.string.mt_fav_artist, sum.top(sum.artists))));
            if (sum.night > 0) mtTopBox.addView(summaryLine(R.drawable.ic_bedtime, getString(R.string.mt_night, duration(sum.night))));
        }
        addTop(R.string.mt_top_artists, ListenStats.head(sum.artists, 5));
        addTop(R.string.mt_top_tracks, ListenStats.head(sum.tracks, 5));
        List<ListenStats.Entry> apps = ListenStats.head(sum.apps, 3);
        if (!apps.isEmpty()) {
            mtTopBox.addView(label(getString(R.string.mt_top_apps)));
            long max = apps.get(0).seconds;
            for (ListenStats.Entry e : apps) {
                Drawable ic = null;
                try {
                    ic = getPackageManager().getApplicationIcon(e.name);
                } catch (Exception ignored) {
                }
                mtTopBox.addView(statRow(appLabel(e.name), e.seconds, max, ic));
            }
        }
        if (st.isEmpty()) {
            mtHint.setText(R.string.mt_empty);
        } else if (!NowPlaying.hasAccess(this)) {
            mtHint.setText(R.string.mt_no_access);
        } else {
            mtHint.setText(R.string.mt_hint);
        }
        refreshAchievements(st.achievements());
    }

    /** Карточка итогов 1080×1920 → кэш → «Поделиться». Рисуем в фоне, чтобы экран не замирал. */
    private void shareMusicTime() {
        final ShareCard.Data d = ShareCard.collect(this, settings.getBoolean("mt_month", false));
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                boolean ok;
                try {
                    ShareCard.render(app, d);
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
                final boolean done = ok;
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (done) ShareCard.share(MainActivity.this);
                        else Toast.makeText(MainActivity.this, R.string.sc_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "eq-share-card").start();
    }

    private void addTop(int titleRes, List<ListenStats.Entry> list) {
        if (list.isEmpty()) return;
        mtTopBox.addView(label(getString(titleRes)));
        long max = list.get(0).seconds;
        for (int i = 0; i < list.size(); i++) {
            ListenStats.Entry e = list.get(i);
            mtTopBox.addView(statRow((i + 1) + ".  " + e.name, e.seconds, max, null));
        }
    }

    private View summaryLine(int iconRes, String s) {
        TextView t = text(s, 14, Color.WHITE);
        Drawable ic = icon(iconRes, GREY);
        ic.setBounds(0, 0, dp(18), dp(18));
        t.setCompoundDrawablesRelative(ic, null, null, null);
        t.setCompoundDrawablePadding(dp(10));
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    /** Значки достижений: полученные — акцентом, остальные — серые (цель видна заранее). */
    private void refreshAchievements(boolean[] got) {
        mtAchBox.removeAllViews();
        int[] names = {R.string.ach_10h, R.string.ach_100h, R.string.ach_500h, R.string.ach_night, R.string.ach_loyal};
        int[] icons = {R.drawable.ic_trophy, R.drawable.ic_trophy, R.drawable.ic_trophy, R.drawable.ic_bedtime, R.drawable.ic_headset};
        for (int i = 0; i < names.length; i++) {
            Button b = chip(getString(names[i]), icons[i], got[i] ? ACCENT : Color.rgb(0x26, 0x28, 0x2E), null);
            if (!got[i]) {
                b.setTextColor(GREY);
                Drawable d = icon(icons[i], Color.rgb(0x6A, 0x6E, 0x78));
                d.setBounds(0, 0, dp(18), dp(18));
                b.setCompoundDrawablesRelative(d, null, null, null);
            }
            mtAchBox.addView(b);
        }
    }

    /** Строка «имя … время» с полоской доли от лидера. */
    private View statRow(String name, long secs, long max, Drawable ic) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(4));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(name, 14, Color.WHITE);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (ic != null) {
            ic.setBounds(0, 0, dp(18), dp(18));
            n.setCompoundDrawablesRelative(ic, null, null, null);
            n.setCompoundDrawablePadding(dp(8));
        }
        row.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView t = text(duration(secs), 13, GREY);
        t.setPadding(dp(8), 0, 0, 0);
        row.addView(t);
        box.addView(row);
        View bar = new View(this);
        bar.setBackground(round(Color.rgb(0x4A, 0x5E, 0x8C), 2));
        LinearLayout barRow = new LinearLayout(this);
        barRow.setPadding(0, dp(4), 0, 0);
        float part = max > 0 ? Math.max(0.03f, secs / (float) max) : 0f;
        barRow.addView(bar, new LinearLayout.LayoutParams(0, dp(3), part));
        barRow.addView(new View(this), new LinearLayout.LayoutParams(0, dp(3), 1f - part + 0.0001f));
        box.addView(barRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)));
        return box;
    }

    // =====================================================================
    // Настройки: язык, автоматизация, всплывающее окно, обновления
    // =====================================================================

    private void buildSettings(LinearLayout root) {
        root.addView(section(getString(R.string.language)));
        langBtn = chip("", R.drawable.ic_language, CHIP, new View.OnClickListener() {
            public void onClick(View v) { showLanguageDialog(); }
        });
        LinearLayout lang = new LinearLayout(this);
        lang.addView(langBtn);
        root.addView(hscroll(lang));

        buildTheme(root);

        buildAutomation(root);

        root.addView(section(getString(R.string.scn_title)));
        scnSummary = hintText("");
        root.addView(scnSummary);
        LinearLayout scn = new LinearLayout(this);
        scn.setPadding(0, dp(8), 0, 0);
        scn.addView(chip(getString(R.string.scn_open), R.drawable.ic_layers, CHIP, new View.OnClickListener() {
            public void onClick(View v) { openScenarios(); }
        }));
        root.addView(hscroll(scn));

        root.addView(section(getString(R.string.popup_on)));
        root.addView(hintText(getString(R.string.popup_hint)));
        LinearLayout pop = new LinearLayout(this);
        pop.setPadding(0, dp(8), 0, 0);
        popupBtn2 = chip("", R.drawable.ic_layers, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        pop.addView(popupBtn2);
        root.addView(hscroll(pop));

        // обновления
        root.addView(section(getString(R.string.upd_title)));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(6));
        card.setBackground(round(CARD, 24));
        TextView ver = text(getString(R.string.upd_version, Updater.currentVersion(this)), 16, Color.WHITE);
        ver.getPaint().setFakeBoldText(true);
        card.addView(ver);
        updStatus = text(getString(R.string.upd_hint), 13, Color.rgb(0xC8, 0xCA, 0xD0));
        updStatus.setPadding(0, dp(4), 0, dp(10));
        card.addView(updStatus);
        LinearLayout ub = new LinearLayout(this);
        updCheckBtn = chip(getString(R.string.upd_check), R.drawable.ic_download, CHIP, new View.OnClickListener() {
            public void onClick(View v) { checkUpdates(true); }
        });
        ub.addView(updCheckBtn);
        updInstallBtn = chip(getString(R.string.upd_install), R.drawable.ic_check, ACCENT, new View.OnClickListener() {
            public void onClick(View v) {
                if (updRelease != null) startUpdate(updRelease);
            }
        });
        updInstallBtn.setVisibility(View.GONE);
        ub.addView(updInstallBtn);
        card.addView(hscroll(ub));
        updAutoSwitch = styledSwitch();
        LinearLayout autoRow = switchRow(getString(R.string.upd_auto), getString(R.string.upd_auto_hint), updAutoSwitch);
        autoRow.setPadding(0, dp(4), 0, dp(8));
        card.addView(autoRow);
        updAutoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (!updating) Updater.setAutoEnabled(MainActivity.this, on);
            }
        });
        root.addView(card);

        root.addView(section(getString(R.string.sec_about)));
        root.addView(hintText(getString(R.string.tagline) + " · " + getString(R.string.upd_version,
                Updater.currentVersion(this))));
    }

    // ---------- оформление ----------

    private void buildTheme(LinearLayout root) {
        root.addView(section(getString(R.string.theme_title)));
        root.addView(label(getString(R.string.theme_accent)));
        FlowLayout accents = new FlowLayout(this);
        for (final int color : Theme.PALETTE) {
            accents.addView(swatch(color, color == Theme.accent(), new View.OnClickListener() {
                public void onClick(View v) {
                    if (color == Theme.accent()) return;
                    Theme.setAccent(MainActivity.this, color);
                    recreate();
                }
            }));
        }
        root.addView(accents);

        root.addView(label(getString(R.string.theme_wave)));
        FlowLayout waves = new FlowLayout(this);
        Button asAccent = chip(getString(R.string.theme_wave_accent), 0,
                Theme.waveSetting() == Theme.WAVE_AS_ACCENT ? ACCENT : CHIP, new View.OnClickListener() {
                    public void onClick(View v) {
                        Theme.setWave(MainActivity.this, Theme.WAVE_AS_ACCENT);
                        recreate();
                    }
                });
        waves.addView(asAccent);
        for (final int color : Theme.PALETTE) {
            waves.addView(swatch(color, color == Theme.waveSetting(), new View.OnClickListener() {
                public void onClick(View v) {
                    Theme.setWave(MainActivity.this, color);
                    recreate();
                }
            }));
        }
        root.addView(waves);

        final Switch coverSwitch = styledSwitch();
        coverSwitch.setChecked(Theme.coverEnabled());
        root.addView(switchRow(getString(R.string.theme_cover), getString(R.string.theme_cover_hint), coverSwitch));
        coverSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                Theme.setCoverEnabled(MainActivity.this, on);
            }
        });
    }

    /** Кружок цвета; выбранный — с белой обводкой. */
    private View swatch(int color, boolean selected, View.OnClickListener click) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (selected) g.setStroke(dp(3), Color.WHITE);
        v.setBackground(g);
        v.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMargins(0, 0, dp(10), dp(10));
        v.setLayoutParams(lp);
        return v;
    }

    private void refreshSettings() {
        if (langBtn == null) return;
        if (scnSummary != null) {
            scnSummary.setText(getString(R.string.scn_settings_hint) + " "
                    + getString(R.string.scn_count, Scenarios.enabledCount(Scenarios.load(this))));
        }
        String cur = Lang.get(this);
        String name = getString(R.string.lang_system);
        for (int i = 1; i < Lang.CODES.length; i++) {
            if (Lang.CODES[i].equals(cur)) name = Lang.NATIVE[i];
        }
        langBtn.setText(name);
        updatePopupButtons();
        updating = true;
        autostartSwitch.setChecked(BootReceiver.enabled(this));
        updAutoSwitch.setChecked(Updater.autoEnabled(this));
        updating = false;
    }

    private void updatePopupButtons() {
        boolean on = BudsPopup.allowed(this);
        popupBtn.setText(on ? R.string.popup_on : R.string.popup_enable);
        setChipIcon(popupBtn, on ? R.drawable.ic_check : R.drawable.ic_layers);
        popupBtn2.setText(on ? R.string.popup_on : R.string.popup_enable);
        setChipIcon(popupBtn2, on ? R.drawable.ic_check : R.drawable.ic_layers);
        popupBtn2.setBackground(round(on ? CHIP : ACCENT, 24));
    }

    // ---------- обновления ----------

    private void checkUpdates(final boolean showDialog) {
        updStatus.setText(R.string.upd_checking);
        updCheckBtn.setEnabled(false);
        Updater.check(this, new Updater.CheckCallback() {
            public void onChecked(Updater.Release newer, String error) {
                if (isFinishing() || isDestroyed()) return;
                updCheckBtn.setEnabled(true);
                updRelease = newer;
                updInstallBtn.setVisibility(newer != null && newer.apkUrl != null ? View.VISIBLE : View.GONE);
                if (error != null) {
                    updStatus.setText(getString(R.string.upd_failed, error));
                } else if (newer == null) {
                    updStatus.setText(R.string.upd_latest);
                } else if (newer.apkUrl == null) {
                    updStatus.setText(getString(R.string.upd_no_apk, newer.version));
                } else {
                    updStatus.setText(getString(R.string.upd_available, newer.version));
                    if (showDialog) showUpdateDialog(newer);
                }
            }
        });
    }

    private void showUpdateDialog(final Updater.Release r) {
        String notes = r.notes.length() > 1500 ? r.notes.substring(0, 1500) + "…" : r.notes;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.upd_available, r.version))
                .setMessage(notes.isEmpty() ? getString(R.string.upd_no_notes) : notes)
                .setPositiveButton(R.string.upd_install, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { startUpdate(r); }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startUpdate(final Updater.Release r) {
        if (Updater.isBusy()) return;
        if (!Updater.canInstall(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.upd_title)
                    .setMessage(R.string.upd_permission)
                    .setPositiveButton(R.string.open_settings, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        updInstallBtn.setEnabled(false);
        updStatus.setText(getString(R.string.upd_downloading, 0));
        Updater.download(this, r, new Updater.DownloadCallback() {
            public void onProgress(int percent) {
                updStatus.setText(getString(R.string.upd_downloading, percent));
            }

            public void onDone(File apk, String error) {
                if (isFinishing() || isDestroyed()) return;
                updInstallBtn.setEnabled(true);
                if (error != null) {
                    updStatus.setText(getString(R.string.upd_failed, error));
                    return;
                }
                updStatus.setText(R.string.upd_installing);
                ListenStats.get(MainActivity.this).saveNow(); // установка перезапустит приложение
                try {
                    Updater.install(MainActivity.this, apk);
                } catch (Exception e) {
                    updStatus.setText(getString(R.string.upd_failed, String.valueOf(e.getMessage())));
                }
            }
        });
    }

    // =====================================================================
    // Жизненный цикл
    // =====================================================================

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        BudsLink.get().addListener(budsListener);
        AirPods.get().addListener(airListener);
        DeviceMonitor.get().addListener(deviceListener);
        eq.addListener(eqListener);
        Theme.addListener(liveListener);
        DeviceMonitor.get().refresh();
        refreshDevices();
        refreshEq();
        refreshSettings();
        if (tab == TAB_MUSIC) refreshMusicTime();
        if (tab == TAB_GAMES) refreshGames(true);   // вернулись из настроек доступа — список и время заново
        ui.postDelayed(new Runnable() {
            public void run() { updateStatus(); }
        }, 600);
        if (tab == TAB_EQ && spectrumWanted() && granted("android.permission.RECORD_AUDIO")) startSpectrum();
        np.start();
        refreshNowPlaying();
        ui.removeCallbacks(npTicker);
        ui.post(npTicker);
    }

    @Override
    protected void onPause() {
        visible = false;
        if (abBtn != null) setAbHeld(false);   // ушли с экрана с пальцем на кнопке — вернуть EQ
        np.stop();
        ui.removeCallbacks(npTicker);
        BudsLink.get().removeListener(budsListener);
        AirPods.get().removeListener(airListener);
        DeviceMonitor.get().removeListener(deviceListener);
        // экран закрыт — AirPods слушаем в экономном режиме (служба сама остановит без них)
        DeviceInfo sel = DeviceMonitor.get().find(selected);
        if (sel != null && sel.isAirPods()) AirPods.get().start(this, sel, false);
        eq.removeListener(eqListener);
        Theme.removeListener(liveListener);
        spectrum.stop();
        graph.setSpectrum(null);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_AUDIO) {
            if (granted("android.permission.RECORD_AUDIO")) startSpectrum();
            else setSpectrumWanted(false);
        } else if (code == REQ_AIRPODS) {
            refreshDevices();
        } else {
            DeviceMonitor.get().refresh();
        }
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    // =====================================================================
    // Устройства
    // =====================================================================

    private void refreshDevices() {
        List<DeviceInfo> list = DeviceMonitor.get().list();
        DeviceInfo sel = DeviceMonitor.get().find(selected);
        if (sel == null) {
            sel = list.isEmpty() ? null : list.get(0);
            selected = sel == null ? null : sel.address;
        }

        deviceChips.removeAllViews();
        for (final DeviceInfo i : list) {
            String name = i.name.length() > 18 ? i.name.substring(0, 17) + "…" : i.name;
            if (i.battery >= 0) name += "  " + i.battery + "%";
            deviceChips.addView(chip(name, DeviceInfo.iconRes(i.type), i == sel ? ACCENT : CHIP, new View.OnClickListener() {
                public void onClick(View v) {
                    selected = i.address;
                    refreshDevices();
                }
            }));
        }
        deviceChipsScroll.setVisibility(list.size() > 1 ? View.VISIBLE : View.GONE);

        BudsLink.State bs = BudsLink.get().state();
        boolean budsMode = sel != null && sel.isGalaxyBuds() && bs.connected && sel.address.equals(bs.address);
        // AirPods: заряд из BLE-рекламы, пока экран открыт — быстрый поиск
        boolean pods = sel != null && sel.isAirPods();
        if (pods && visible) AirPods.get().start(this, sel, true);
        BudsLink.State as = AirPods.get().state();
        boolean airMode = pods && as.connected;
        budsView.setVisibility(budsMode || airMode ? View.VISIBLE : View.GONE);
        deviceView.setVisibility(budsMode || airMode ? View.GONE : View.VISIBLE);
        if (budsMode) {
            budsView.setStyle(BudsView.styleFor(sel));
            budsView.setState(bs);
        } else if (airMode) {
            budsView.setStyle(BudsView.STYLE_AIRPODS);
            budsView.setState(as);
        } else {
            deviceView.setDevice(sel);
        }
        airPermBtn.setVisibility(pods && !AirPods.hasPermission(this) ? View.VISIBLE : View.GONE);

        devSettingsBtn.setVisibility(sel != null ? View.VISIBLE : View.GONE);
        findBtn.setVisibility(budsMode ? View.VISIBLE : View.GONE);

        boolean nc = budsMode && BudsLink.hasNoiseControl(bs);
        budsBox.setVisibility(budsMode ? View.VISIBLE : View.GONE);
        ncOff.setVisibility(nc ? View.VISIBLE : View.GONE);
        ncAnc.setVisibility(nc ? View.VISIBLE : View.GONE);
        ncAmb.setVisibility(nc && !bs.name.toLowerCase(Locale.ROOT).contains("live") ? View.VISIBLE : View.GONE);
        if (nc) {
            ncOff.setBackground(round(bs.noise == BudsLink.NC_OFF ? ACCENT : CHIP, 24));
            ncAnc.setBackground(round(bs.noise == BudsLink.NC_ANC ? ACCENT : CHIP, 24));
            ncAmb.setBackground(round(bs.noise == BudsLink.NC_AMBIENT ? ACCENT : CHIP, 24));
        }
        boolean locked = bs.touchLocked == 1;
        touchBtn.setText(locked ? R.string.touch_locked : R.string.touch_unlocked);
        setChipIcon(touchBtn, locked ? R.drawable.ic_lock : R.drawable.ic_lock_open);
        touchBtn.setBackground(round(locked ? ACCENT : CHIP, 24));
        String fw = bs.fwEq >= 0 && bs.fwEq <= 5 ? getString(fwEqName(bs.fwEq)) : "—";
        fwEqBtn.setText(getString(R.string.fw_eq, fw));

        // кнопка разрешения окна — только пока его нет (всегда есть на вкладке «Настройки»)
        popupBtn.setVisibility(BudsPopup.allowed(this) ? View.GONE : View.VISIBLE);
        carAddress = sel == null ? null : sel.address;
        refreshCar();
        refreshPhone();
        refreshAutoEq();
    }

    /** До Android 12 BLE-поиск требует геолокацию — объясняем, зачем. */
    private void askAirPodsPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{AirPods.permission()}, REQ_AIRPODS);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.air_permission)
                .setMessage(R.string.air_permission_old)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        requestPermissions(new String[]{AirPods.permission()}, REQ_AIRPODS);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static int fwEqName(int i) {
        switch (i) {
            case 1: return R.string.fw_eq_1;
            case 2: return R.string.fw_eq_2;
            case 3: return R.string.fw_eq_3;
            case 4: return R.string.fw_eq_4;
            case 5: return R.string.fw_eq_5;
            default: return R.string.fw_eq_0;
        }
    }

    private void showFirmwareEqDialog() {
        String[] items = new String[6];
        for (int i = 0; i < 6; i++) items[i] = getString(fwEqName(i));
        int cur = Math.max(0, BudsLink.get().state().fwEq);
        new AlertDialog.Builder(this)
                .setTitle(R.string.fw_eq_title)
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        BudsLink.get().setFirmwareEq(which);
                        d.dismiss();
                    }
                })
                .show();
    }

    private void onFindClicked() {
        if (!BudsLink.get().state().connected) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.not_connected_msg)
                    .setPositiveButton(R.string.ok, null).show();
            return;
        }
        if (finding) {
            setFinding(false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.find_title)
                .setMessage(R.string.find_msg)
                .setPositiveButton(R.string.beep, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { setFinding(true); }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void setFinding(boolean on) {
        finding = on;
        if (on) BudsLink.get().findStart(); else BudsLink.get().findStop();
        findBtn.setText(on ? R.string.stop_beep : R.string.find_buds);
        setChipIcon(findBtn, on ? R.drawable.ic_stop : R.drawable.ic_bell);
        findBtn.setBackground(round(on ? DANGER : CHIP, 24));
    }

    // ---------- настройки конкретного устройства ----------

    /** Подпись «звука при подключении»: свой выбор или «Басы (сам для машины)». */
    private String connectSoundLabel(DeviceInfo dev, DeviceSettings ds) {
        if (ds.sound == null) {
            String auto = ds.soundFor(dev.type);
            return auto.isEmpty() ? getString(R.string.ds_sound_keep)
                    : getString(R.string.ds_sound_auto, AppPresets.presetLabel(this, auto));
        }
        return ds.sound.isEmpty() ? getString(R.string.ds_sound_keep) : AppPresets.presetLabel(this, ds.sound);
    }

    private void chooseConnectSound(final Button btn, final DeviceInfo dev, final DeviceSettings ds) {
        final List<String> vals = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        vals.add("");
        labels.add(getString(R.string.ds_sound_keep));
        for (int i = 0; i < EqEngine.PRESET_NAMES.length; i++) {
            vals.add(AppPresets.BUILTIN + i);
            labels.add(getString(EqEngine.PRESET_NAMES[i]));
        }
        for (String n : eq.presetNames()) {
            vals.add(AppPresets.USER + n);
            labels.add(n);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ds_sound)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        ds.sound = vals.get(which);
                        btn.setText(connectSoundLabel(dev, ds));
                    }
                })
                .show();
    }

    private void showDeviceSettings() {
        final DeviceInfo dev = DeviceMonitor.get().find(selected);
        if (dev == null) return;
        final DeviceSettings ds = DeviceSettings.get(this, dev.address);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        // как выглядит
        box.addView(label(getString(R.string.ds_look)));
        final Button lookBtn = chip("", DeviceInfo.iconRes(dev.type), CHIP, null);
        updateLookButton(lookBtn, dev, ds);
        lookBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseLook(lookBtn, dev, ds); }
        });
        box.addView(lookBtn);

        // звук при подключении: подключили — EQ сам включается с этим звуком
        box.addView(label(getString(R.string.ds_sound)));
        final Button soundBtn = chip(connectSoundLabel(dev, ds), R.drawable.ic_equalizer, CHIP, null);
        soundBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseConnectSound(soundBtn, dev, ds); }
        });
        box.addView(soundBtn);
        TextView soundNote = text(getString(R.string.ds_sound_note), 13, GREY);
        soundNote.setPadding(0, dp(6), 0, 0);
        box.addView(soundNote);

        // громкость при подключении
        box.addView(label(getString(R.string.ds_volume)));
        final Switch volSwitch = styledSwitch();
        final SeekBar volBar = new SeekBar(this);
        volBar.setMax(100);
        final TextView volVal = text("", 14, Color.WHITE);
        volSwitch.setChecked(ds.volume >= 0);
        volBar.setProgress(ds.volume >= 0 ? ds.volume : 40);
        volBar.setEnabled(ds.volume >= 0);
        volVal.setText(ds.volume >= 0 ? ds.volume + "%" : getString(R.string.ds_volume_off));
        tint(volBar);
        volSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                volBar.setEnabled(on);
                ds.volume = on ? volBar.getProgress() : -1;
                volVal.setText(on ? volBar.getProgress() + "%" : getString(R.string.ds_volume_off));
            }
        });
        volBar.setOnSeekBarChangeListener(new Seek() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (volSwitch.isChecked()) {
                    ds.volume = p;
                    volVal.setText(p + "%");
                }
            }
        });
        LinearLayout volRow = new LinearLayout(this);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.addView(volSwitch);
        volRow.addView(volBar, new LinearLayout.LayoutParams(0, dp(40), 1));
        volVal.setMinWidth(dp(70));
        volVal.setGravity(Gravity.END);
        volRow.addView(volVal);
        box.addView(volRow);

        // всплывающее окно
        final Switch popSwitch = styledSwitch();
        popSwitch.setChecked(ds.popup);
        popSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) { ds.popup = on; }
        });
        box.addView(switchRow(getString(R.string.ds_popup), null, popSwitch));

        // приложение при подключении
        box.addView(label(getString(R.string.ds_app)));
        final Button appBtn = chip(appLabel(ds.app), R.drawable.ic_equalizer, CHIP, null);
        appBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseApp(appBtn, ds); }
        });
        box.addView(appBtn);

        // порог заряда
        box.addView(label(getString(R.string.ds_low)));
        final Button lowBtn = chip(getString(R.string.ds_low_value, ds.lowBattery), R.drawable.ic_battery_alert, CHIP, null);
        lowBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final int[] values = {10, 15, 20, 30};
                String[] items = new String[values.length];
                int cur = 1;
                for (int i = 0; i < values.length; i++) {
                    items[i] = getString(R.string.ds_low_value, values[i]);
                    if (values[i] == ds.lowBattery) cur = i;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                ds.lowBattery = values[which];
                                lowBtn.setText(getString(R.string.ds_low_value, ds.lowBattery));
                                d.dismiss();
                            }
                        }).show();
            }
        });
        box.addView(lowBtn);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(dev.name)
                .setView(sv)
                .setPositiveButton(R.string.done, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ds.save(MainActivity.this);
                        DeviceMonitor.get().applyOverride(dev.address);
                    }
                })
                .show();
    }

    private void updateLookButton(Button b, DeviceInfo dev, DeviceSettings ds) {
        int t = ds.typeOverride >= 0 ? ds.typeOverride : dev.detectedType;
        String txt = ds.typeOverride >= 0
                ? getString(DeviceInfo.labelRes(t))
                : getString(R.string.ds_auto, getString(DeviceInfo.labelRes(dev.detectedType)));
        b.setText(txt);
        setChipIcon(b, DeviceInfo.iconRes(t));
    }

    private void chooseLook(final Button b, final DeviceInfo dev, final DeviceSettings ds) {
        final int[] types = {-1, DeviceInfo.T_EARBUDS, DeviceInfo.T_HEADPHONES, DeviceInfo.T_SPEAKER,
                DeviceInfo.T_CAR, DeviceInfo.T_HEADSET, DeviceInfo.T_TV, DeviceInfo.T_WATCH,
                DeviceInfo.T_KEYBOARD, DeviceInfo.T_MOUSE, DeviceInfo.T_GAMEPAD, DeviceInfo.T_COMPUTER,
                DeviceInfo.T_PHONE, DeviceInfo.T_OTHER};
        String[] items = new String[types.length];
        int cur = 0;
        for (int i = 0; i < types.length; i++) {
            items[i] = types[i] < 0
                    ? getString(R.string.ds_auto, getString(DeviceInfo.labelRes(dev.detectedType)))
                    : getString(DeviceInfo.labelRes(types[i]));
            if (types[i] == ds.typeOverride) cur = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ds_look)
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        ds.typeOverride = types[which];
                        updateLookButton(b, dev, ds);
                        d.dismiss();
                    }
                })
                .show();
    }

    private String appLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return getString(R.string.ds_app_none);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void chooseApp(final Button b, final DeviceSettings ds) {
        final PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        final List<String> pkgs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName())) continue;
            boolean dup = false;
            for (String[] r : rows) if (r[0].equals(p)) dup = true;
            if (!dup) rows.add(new String[]{p, ri.loadLabel(pm).toString()});
        }
        Collections.sort(rows, new Comparator<String[]>() {
            public int compare(String[] a, String[] c) {
                int ia = MUSIC_APPS.indexOf(a[0]), ic = MUSIC_APPS.indexOf(c[0]);
                if (ia >= 0 || ic >= 0) {
                    if (ia < 0) return 1;
                    if (ic < 0) return -1;
                    return ia - ic;
                }
                return a[1].compareToIgnoreCase(c[1]);
            }
        });
        pkgs.add("");
        labels.add(getString(R.string.ds_app_none));
        for (String[] r : rows) {
            pkgs.add(r[0]);
            labels.add(r[1]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ds_app)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        ds.app = pkgs.get(which);
                        b.setText(labels.get(which));
                    }
                })
                .show();
    }

    // =====================================================================
    // Эквалайзер
    // =====================================================================

    // =====================================================================
    // «Сейчас играет» + Spotify
    // =====================================================================

    private void buildNowPlaying(LinearLayout root) {
        np = new NowPlaying(this, new NowPlaying.Listener() {
            public void onNowPlayingChanged() { refreshNowPlaying(); }
        });

        root.addView(section(getString(R.string.now_playing)));
        npCard = new LinearLayout(this);
        npCard.setOrientation(LinearLayout.VERTICAL);
        npCard.setPadding(dp(14), dp(14), dp(14), dp(10));
        npCard.setBackground(round(CARD, 24));

        // обложка + название
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setClipToOutline(true);
        npArt.setBackground(round(Color.rgb(0x2A, 0x2C, 0x33), 12));
        top.addView(npArt, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);
        npTitle = text("", 17, Color.WHITE);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        npTitle.getPaint().setFakeBoldText(true);
        texts.addView(npTitle);
        npArtist = text("", 14, Color.rgb(0xC8, 0xCA, 0xD0));
        npArtist.setSingleLine(true);
        npArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(npArtist);
        npApp = text("", 12, Color.rgb(0xA0, 0xA3, 0xAA));
        npApp.setPadding(0, dp(4), 0, 0);
        npApp.setCompoundDrawablePadding(dp(6));
        texts.addView(npApp);
        top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        npCard.addView(top);

        wave = new WaveView(this);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        wlp.topMargin = dp(8);
        npCard.addView(wave, wlp);

        // прогресс
        npSeek = new SeekBar(this);
        tint(npSeek);
        npSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) npTime.setText(fmtTime(p) + " / " + fmtTime(np.duration()));
            }

            public void onStartTrackingTouch(SeekBar s) { npSeeking = true; }

            public void onStopTrackingTouch(SeekBar s) {
                npSeeking = false;
                np.seekTo(s.getProgress());
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        slp.topMargin = dp(8);
        npCard.addView(npSeek, slp);
        npTime = text("", 12, Color.rgb(0xA0, 0xA3, 0xAA));
        npTime.setGravity(Gravity.END);
        npTime.setPadding(0, 0, dp(8), 0);
        npCard.addView(npTime, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // кнопки управления
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.addView(roundButton(R.drawable.ic_skip_previous, 48, CHIP, R.string.prev_track, new View.OnClickListener() {
            public void onClick(View v) { np.previous(); }
        }));
        npPlay = roundButton(R.drawable.ic_play, 60, ACCENT, R.string.play_pause, new View.OnClickListener() {
            public void onClick(View v) { np.playPause(); }
        });
        controls.addView(npPlay);
        controls.addView(roundButton(R.drawable.ic_skip_next, 48, CHIP, R.string.next_track, new View.OnClickListener() {
            public void onClick(View v) { np.next(); }
        }));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(4);
        npCard.addView(controls, clp);

        // без доступа к уведомлениям
        npAccessBox = new LinearLayout(this);
        npAccessBox.setOrientation(LinearLayout.VERTICAL);
        npAccessBox.setPadding(0, dp(10), 0, 0);
        npAccessBox.addView(text(getString(R.string.np_access), 13, Color.rgb(0xC8, 0xCA, 0xD0)));
        LinearLayout ab = new LinearLayout(this);
        ab.setPadding(0, dp(8), 0, 0);
        ab.addView(chip(getString(R.string.np_grant), R.drawable.ic_check, ACCENT, new View.OnClickListener() {
            public void onClick(View v) { openListenerSettings(); }
        }));
        ab.addView(chip(getString(R.string.app_info), R.drawable.ic_settings, CHIP, new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        }));
        npAccessBox.addView(hscroll(ab));
        TextView restricted = text(getString(R.string.np_restricted), 12, GREY);
        npAccessBox.addView(restricted);
        npCard.addView(npAccessBox);

        root.addView(npCard);

        // быстрые кнопки Spotify
        LinearLayout sp = new LinearLayout(this);
        sp.setPadding(0, dp(10), 0, 0);
        sp.addView(chip(getString(R.string.sp_liked), R.drawable.ic_favorite, CHIP, new View.OnClickListener() {
            public void onClick(View v) { openSpotify("spotify:collection:tracks"); }
        }));
        sp.addView(chip(getString(R.string.sp_search), R.drawable.ic_search, CHIP, new View.OnClickListener() {
            public void onClick(View v) { openSpotify("spotify:search"); }
        }));
        spOpenBtn = chip(getString(R.string.sp_open), R.drawable.ic_open, CHIP, new View.OnClickListener() {
            public void onClick(View v) { openSpotify(null); }
        });
        sp.addView(spOpenBtn);
        root.addView(hscroll(sp));
        root.addView(hintText(getString(R.string.sp_hint_eq)));

        // таймер сна
        LinearLayout sl = new LinearLayout(this);
        sl.setPadding(0, dp(10), 0, 0);
        sleepBtn = chip(getString(R.string.sleep_timer), R.drawable.ic_bedtime, CHIP, new View.OnClickListener() {
            public void onClick(View v) { showSleepDialog(); }
        });
        sl.addView(sleepBtn);
        root.addView(hscroll(sl));
    }

    private void showSleepDialog() {
        final int[] minutes = {15, 30, 45, 60, 90, 120};
        boolean on = EqService.sleepMinutesLeft() > 0;
        String[] items = new String[minutes.length + (on ? 1 : 0)];
        for (int i = 0; i < minutes.length; i++) items[i] = getString(R.string.time_m, (long) minutes[i]);
        if (on) items[minutes.length] = getString(R.string.sleep_off);
        new AlertDialog.Builder(this)
                .setTitle(R.string.sleep_timer)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        EqService.setSleep(MainActivity.this, which < minutes.length ? minutes[which] : 0);
                        ui.postDelayed(new Runnable() {
                            public void run() { refreshSleep(); }
                        }, 300);
                    }
                })
                .show();
        Toast.makeText(this, R.string.sleep_hint, Toast.LENGTH_SHORT).show();
    }

    private void refreshSleep() {
        if (sleepBtn == null) return;
        int left = EqService.sleepMinutesLeft();
        sleepBtn.setText(left > 0 ? getString(R.string.sleep_left, left) : getString(R.string.sleep_timer));
        sleepBtn.setBackground(round(left > 0 ? ACCENT : CHIP, 24));
    }

    private void openListenerSettings() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    NowPlaying.component(this).flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private boolean spotifyInstalled() {
        return getPackageManager().getLaunchIntentForPackage(NowPlaying.SPOTIFY) != null;
    }

    /** Открыть Spotify (uri = раздел, null = просто приложение). Если не установлен — Google Play. */
    private void openSpotify(String uri) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(NowPlaying.SPOTIFY);
        if (launch == null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + NowPlaying.SPOTIFY)));
            } catch (Exception e) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + NowPlaying.SPOTIFY)));
            }
            return;
        }
        if (uri == null) {
            startActivity(launch);
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.setPackage(NowPlaying.SPOTIFY);
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(launch);
        }
    }

    private void refreshNowPlaying() {
        if (np == null) return;
        boolean access = NowPlaying.hasAccess(this);
        npAccessBox.setVisibility(access ? View.GONE : View.VISIBLE);

        String title = np.title();
        String artist = np.artist();
        npTitle.setText(title != null && !title.isEmpty() ? title
                : getString(access ? R.string.nothing_playing : R.string.now_playing));
        npArtist.setText(artist != null ? artist : "");
        npArtist.setVisibility(artist != null && !artist.isEmpty() ? View.VISIBLE : View.GONE);

        String pkg = np.packageName();
        if (pkg != null) {
            npApp.setText(appLabel(pkg));
            npApp.setVisibility(View.VISIBLE);
            try {
                Drawable ic = getPackageManager().getApplicationIcon(pkg);
                ic.setBounds(0, 0, dp(16), dp(16));
                npApp.setCompoundDrawablesRelative(ic, null, null, null);
            } catch (Exception e) {
                npApp.setCompoundDrawablesRelative(null, null, null, null);
            }
        } else {
            npApp.setVisibility(View.GONE);
        }

        Bitmap art = np.art();
        if (art != null) {
            npArt.setImageBitmap(art);
            npArt.setPadding(0, 0, 0, 0);
            if (art != npLastArt) {
                npLastArt = art;
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{dominantColor(art), CARD});
                bg.setCornerRadius(dp(24));
                npCard.setBackground(bg);
            }
        } else {
            npLastArt = null;
            npArt.setImageDrawable(icon(R.drawable.ic_music, Color.rgb(0x8A, 0x8E, 0x98)));
            npArt.setPadding(dp(18), dp(18), dp(18), dp(18));
            npCard.setBackground(round(CARD, 24));
        }

        npPlay.setImageDrawable(icon(np.playing() ? R.drawable.ic_pause : R.drawable.ic_play, Color.WHITE));
        wave.setPlaying(np.playing());
        if (art != coverArt) {
            coverArt = art;
            Theme.setCover(art != null ? CoverColor.dominant(art) : 0);
        }
        long dur = np.duration();
        npSeek.setVisibility(dur > 0 ? View.VISIBLE : View.GONE);
        npTime.setVisibility(dur > 0 ? View.VISIBLE : View.GONE);
        spOpenBtn.setText(spotifyInstalled() ? R.string.sp_open : R.string.sp_install);
        updateProgress();
    }

    private void updateProgress() {
        if (np == null || npSeeking) return;
        long dur = np.duration();
        if (dur <= 0) return;
        long pos = np.position();
        npSeek.setMax((int) dur);
        npSeek.setProgress((int) pos);
        npTime.setText(fmtTime(pos) + " / " + fmtTime(dur));
    }

    private static String fmtTime(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** Средний цвет обложки, затемнённый — для фона карточки. */
    private static int dominantColor(Bitmap b) {
        try {
            Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
            float[] hsv = new float[3];
            Color.colorToHSV(one.getPixel(0, 0), hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.15f);
            hsv[2] = Math.min(hsv[2], 0.42f);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return CARD;
        }
    }

    private Bitmap coverArt;

    private ImageButton roundButton(int iconRes, int sizeDp, int bg, int descRes, View.OnClickListener click) {
        ImageButton b = new ImageButton(this);
        b.setImageDrawable(icon(iconRes, Color.WHITE));
        b.setBackground(round(bg, sizeDp / 2));
        b.setContentDescription(getString(descRes));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMargins(dp(10), 0, dp(10), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshEq() {
        updating = true;
        mainSwitch.setChecked(eq.enabled);
        graph.setBands(eq.freqs(), eq.gains);
        graph.setCorrection(eq.correctionBands());
        for (int i = 0; i < bandRow.getChildCount(); i++) {
            bandRow.getChildAt(i).setBackground(round(EqEngine.BAND_COUNTS[i] == eq.bandCount() ? ACCENT : CHIP, 24));
        }
        preBar.setProgress(Math.round(-eq.preamp * 2));
        preVal.setText(String.format(Locale.US, "%.1f dB", eq.preamp));
        punchBar.setProgress(Math.round(eq.punch * 100));
        punchVal.setText(Math.round(eq.punch * 100) + "%");
        boostBar.setProgress(Math.round(eq.boost * 2));
        boostVal.setText(String.format(Locale.US, "+%.1f dB", eq.boost));
        balanceBar.setProgress(Math.round(eq.balance * 100) + 100);
        balanceVal.setText(balanceLabel(Math.round(eq.balance * 100)));
        levelSwitch.setChecked(eq.leveling);
        refreshCarEq();
        int car = Math.round(eq.carBalance * 100);
        carNote.setVisibility(car != 0 ? View.VISIBLE : View.GONE);
        carNote.setText(getString(R.string.car_note, balanceLabel(car)));
        autoSwitch.setChecked(eq.autoMode);
        perDeviceSwitch.setChecked(eq.perDevice);
        profileText.setVisibility(eq.perDevice ? View.VISIBLE : View.GONE);
        String pname = eq.profileName.isEmpty() ? getString(R.string.phone_speaker) : eq.profileName;
        profileText.setText(getString(R.string.profile_label, pname));
        specBtn.setBackground(round(spectrumWanted() ? ACCENT : CHIP, 24));
        updating = false;
        rebuildUserPresets();
        updateStatus();
        refreshAutoEq();
        refreshGameStatus();
    }

    private String balanceLabel(int v) {
        if (v == 0) return getString(R.string.balance_center);
        return v < 0 ? getString(R.string.balance_left, -v) : getString(R.string.balance_right, v);
    }

    private void updateStatus() {
        int color;
        if (!eq.enabled) {
            status.setText(R.string.notif_off);
            color = GREY;
        } else if (eq.globalOk) {
            status.setText(R.string.status_global);
            color = Color.rgb(0x4C, 0xD9, 0x64);
        } else {
            status.setText(getString(R.string.status_players, eq.playerSessions()));
            color = Color.rgb(0xFF, 0xB3, 0x40);
        }
        if (eq.gameSoundOn()) status.append(" · " + getString(R.string.game_status, eq.gameLabel));
        status.setTextColor(color);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setSize(dp(8), dp(8));
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
    }

    // ---------- спектр ----------

    private boolean spectrumWanted() {
        return settings.getBoolean("spectrum", false);
    }

    private void setSpectrumWanted(boolean on) {
        settings.edit().putBoolean("spectrum", on).apply();
        specBtn.setBackground(round(on ? ACCENT : CHIP, 24));
    }

    private void toggleSpectrum() {
        if (spectrumWanted()) {
            setSpectrumWanted(false);
            spectrum.stop();
            graph.setSpectrum(null);
            return;
        }
        setSpectrumWanted(true);
        if (granted("android.permission.RECORD_AUDIO")) {
            startSpectrum();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.spectrum)
                    .setMessage(R.string.vis_perm)
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, REQ_AUDIO);
                        }
                    })
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { setSpectrumWanted(false); }
                    })
                    .show();
        }
    }

    private void startSpectrum() {
        spectrum.setBands(eq.freqs());
        boolean ok = spectrum.start(new Spectrum.Listener() {
            public void onLevels(final float[] levels) {
                ui.post(new Runnable() {
                    public void run() { graph.setSpectrum(levels); }
                });
            }
        });
        if (!ok) {
            setSpectrumWanted(false);
            Toast.makeText(this, R.string.vis_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void restartSpectrum() {
        if (spectrumWanted() && granted("android.permission.RECORD_AUDIO")) {
            graph.setSpectrum(null);
            startSpectrum();
        }
    }

    // ---------- пресеты ----------

    private void rebuildUserPresets() {
        userBox.removeAllViews();
        List<String> names = eq.presetNames();
        for (final String name : names) {
            Button b = chip(name, R.drawable.ic_equalizer, name.equals(eq.lastPreset) ? ACCENT : CHIP,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            eq.loadPreset(name);
                            refreshEq();
                        }
                    });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    presetActions(name);
                    return true;
                }
            });
            userBox.addView(b);
        }
        if (names.isEmpty()) {
            TextView empty = text(getString(R.string.presets_empty), 13, GREY);
            empty.setPadding(dp(4), 0, 0, dp(8));
            userBox.addView(empty);
        }
    }

    private void presetActions(final String name) {
        String[] items = {getString(R.string.apply), getString(R.string.share),
                getString(R.string.save_file), getString(R.string.delete)};
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                eq.loadPreset(name);
                                refreshEq();
                                break;
                            case 1:
                                shareText(eq.exportText(name));
                                break;
                            case 2:
                                saveToFile(name, eq.exportText(name));
                                break;
                            default:
                                confirmDelete(name);
                                break;
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final String name) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_q, name))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        eq.deletePreset(name);
                        rebuildUserPresets();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void askPresetName() {
        final EditText input = new EditText(this);
        input.setHint(R.string.name_hint);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.save_preset)
                .setView(padded(input))
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString().trim();
                        if (!name.isEmpty()) {
                            eq.savePreset(name);
                            eq.lastPreset = name;
                            rebuildUserPresets();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void shareText(String text) {
        if (text == null || text.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, getString(R.string.share)));
    }

    private void saveToFile(String name, String text) {
        pendingExport = text;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json");
        startActivityForResult(i, REQ_SAVE);
    }

    private void showImportDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.import_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle(R.string.import_title)
                .setView(padded(input))
                .setPositiveButton(R.string.import_, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { importDone(eq.importText(input.getText().toString())); }
                })
                .setNeutralButton(R.string.import_file, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        startActivityForResult(i, REQ_OPEN);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void importDone(String name) {
        if (name == null) {
            Toast.makeText(this, R.string.import_fail, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.import_ok, name), Toast.LENGTH_SHORT).show();
            eq.loadPreset(name);
            refreshEq();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_SAVE && pendingExport != null) {
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os != null) {
                    os.write(pendingExport.getBytes("UTF-8"));
                    os.close();
                }
                pendingExport = null;
                Toast.makeText(this, R.string.saved_file, Toast.LENGTH_SHORT).show();
            } else if (req == REQ_OPEN) {
                InputStream is = getContentResolver().openInputStream(uri);
                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null && sb.length() < 20000) sb.append(line).append('\n');
                    br.close();
                }
                importDone(eq.importText(sb.toString()));
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_fail, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- плитки ----------

    private void addTiles() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                final StatusBarManager sbm = getSystemService(StatusBarManager.class);
                final Consumer<Integer> ignore = new Consumer<Integer>() {
                    public void accept(Integer r) { }
                };
                // второй запрос — только после ответа на первый (два окна сразу Android не покажет)
                sbm.requestAddTileService(new ComponentName(this, EqTileService.class),
                        getString(R.string.app_name), Icon.createWithResource(this, R.drawable.ic_equalizer),
                        getMainExecutor(), new Consumer<Integer>() {
                            public void accept(Integer r) {
                                sbm.requestAddTileService(new ComponentName(MainActivity.this, PresetTileService.class),
                                        getString(R.string.tile_preset),
                                        Icon.createWithResource(MainActivity.this, R.drawable.ic_equalizer),
                                        getMainExecutor(), ignore);
                            }
                        });
                return;
            } catch (Exception ignored) {
                // покажем подсказку ниже
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_tile)
                .setMessage(R.string.tile_hint)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    // ---------- язык ----------

    private void showLanguageDialog() {
        String[] items = new String[Lang.CODES.length];
        int checked = 0;
        String cur = Lang.get(this);
        for (int i = 0; i < Lang.CODES.length; i++) {
            items[i] = i == 0 ? getString(R.string.lang_system) : Lang.NATIVE[i];
            if (Lang.CODES[i].equals(cur)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        Lang.set(MainActivity.this, Lang.CODES[which]);
                        // перезапуск службы, чтобы уведомление сменило язык
                        stopService(new Intent(MainActivity.this, EqService.class));
                        EqTileService.ensureService(MainActivity.this);
                        recreate();
                    }
                })
                .show();
    }

    // =====================================================================
    // Помощники для интерфейса
    // =====================================================================

    /** SeekBar-слушатель с пустыми методами по умолчанию. */
    private abstract static class Seek implements SeekBar.OnSeekBarChangeListener {
        public void onStartTrackingTouch(SeekBar s) { }
        public void onStopTrackingTouch(SeekBar s) { }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 17, Color.WHITE);
        t.setPadding(dp(4), dp(24), 0, dp(8));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14, Color.rgb(0xA0, 0xA3, 0xAA));
        t.setPadding(dp(2), dp(14), 0, dp(6));
        return t;
    }

    private TextView hintText(String s) {
        TextView t = text(s, 12, GREY);
        t.setPadding(dp(4), 0, dp(4), dp(4));
        return t;
    }

    private View padded(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(v);
        return f;
    }

    /** Ряд кнопок с переносом на новую строку (раньше была горизонтальная прокрутка — кнопки обрезались). */
    private View hscroll(LinearLayout content) {
        FlowLayout flow = new FlowLayout(this);
        flow.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                content.getPaddingRight(), content.getPaddingBottom());
        while (content.getChildCount() > 0) {
            View ch = content.getChildAt(0);
            content.removeViewAt(0);
            flow.addView(ch);
        }
        return flow;
    }

    private void tint(SeekBar bar) {
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
    }

    private LinearLayout sliderRow(String title, SeekBar bar, TextView value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(12), dp(4), 0);
        box.addView(text(title, 15, Color.WHITE));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        tint(bar);
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(40), 1));
        value.setMinWidth(dp(72));
        value.setGravity(Gravity.END);
        row.addView(value);
        box.addView(row);
        return box;
    }

    private LinearLayout switchRow(String title, String hint, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(14), dp(4), 0);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 15, Color.WHITE));
        if (hint != null) {
            TextView h = text(hint, 12, GREY);
            h.setPadding(0, dp(2), dp(8), 0);
            texts.addView(h);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(sw);
        return row;
    }

    private Switch styledSwitch() {
        Switch sw = new Switch(this);
        sw.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        sw.setTrackTintList(ColorStateList.valueOf(ACCENT));
        return sw;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        if (color == ACCENT) {
            // акцентный фон (выбранный чип, вкладка) — перекрашивается «живым» цветом обложки
            g.setColor(Theme.liveAccent());
            accentBgs.add(new java.lang.ref.WeakReference<>(g));
            if (accentBgs.size() > 400) pruneAccentBgs();
        } else {
            g.setColor(color);
        }
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** Все акцентные фоны экрана (слабые ссылки: заменённые фоны уходят сами). */
    private final List<java.lang.ref.WeakReference<GradientDrawable>> accentBgs = new ArrayList<>();

    private void pruneAccentBgs() {
        for (int i = accentBgs.size() - 1; i >= 0; i--) {
            if (accentBgs.get(i).get() == null) accentBgs.remove(i);
        }
    }

    /** Цвет обложки плавно перекрашивает волну, выбранные чипы и свечение машины. */
    private final Theme.LiveListener liveListener = new Theme.LiveListener() {
        public void onLiveColor(int accentColor, int waveColor) {
            for (java.lang.ref.WeakReference<GradientDrawable> r : accentBgs) {
                GradientDrawable g = r.get();
                if (g != null) g.setColor(accentColor);
            }
            if (wave != null) wave.setColor(waveColor);
            if (carView != null) carView.setGlow(accentColor);
        }
    };

    private Drawable icon(int res, int color) {
        Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    private void setChipIcon(Button b, int res) {
        if (res == 0) {
            b.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        Drawable d = icon(res, Color.WHITE);
        d.setBounds(0, 0, dp(18), dp(18));
        b.setCompoundDrawablesRelative(d, null, null, null);
    }

    private Button chip(String label, int iconRes, int bg, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setBackground(round(bg, 24));
        b.setPadding(dp(iconRes != 0 ? 16 : 20), 0, dp(20), 0);
        b.setCompoundDrawablePadding(dp(8));
        setChipIcon(b, iconRes);
        if (click != null) b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.setMargins(0, 0, dp(8), dp(8));
        b.setLayoutParams(lp);
        return b;
    }
}
