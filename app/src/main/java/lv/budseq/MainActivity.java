package lv.budseq;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
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
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
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
    static final int TAB_DEVICE = 0, TAB_EQ = 1, TAB_MUSIC = 2, TAB_SETTINGS = 3, TAB_COUNT = 4;
    static final String EXTRA_TAB = "tab", EXTRA_CHECK_UPDATE = "check_update";
    private static final int[] TAB_TITLES = {R.string.tab_device, R.string.tab_eq, R.string.tab_music, R.string.tab_settings};
    private static final int[] TAB_ICONS = {R.drawable.ic_headset, R.drawable.ic_equalizer, R.drawable.ic_music, R.drawable.ic_settings};

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
            TextView t = text(getString(TAB_TITLES[i]), 12, GREY);
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
        if (t == TAB_SETTINGS) refreshSettings();
        updateBackCallback();
    }

    /** «Назад» с любой вкладки ведёт на первую, с первой — выход. */
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return; // там работает onBackPressed()
        boolean need = tab != TAB_DEVICE;
        if (need && backCallback == null) {
            backCallback = Back33.register(this, new Runnable() {
                public void run() { selectTab(TAB_DEVICE, true); }
            });
        } else if (!need && backCallback != null) {
            Back33.unregister(this, backCallback);
            backCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
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
        root.addView(hscroll(actions));
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

        carStatus = text("", 14, Color.WHITE);
        carStatus.setPadding(dp(4), dp(10), dp(4), dp(6));
        carBox.addView(carStatus);

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
        carBox.addView(hscroll(modes));

        // где реально стоят динамики + проверка каналов
        carBox.addView(label(getString(R.string.car_speakers)));
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
        carBox.addView(hscroll(sp));
        root.addView(carBox);
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
        root.addView(section(getString(R.string.ph_title)));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(8));
        card.setBackground(round(CARD, 24));
        TextView name = text(ph.name, 17, Color.WHITE);
        name.getPaint().setFakeBoldText(true);
        Drawable ic = icon(R.drawable.ic_phone, Color.WHITE);
        ic.setBounds(0, 0, dp(22), dp(22));
        name.setCompoundDrawablesRelative(ic, null, null, null);
        name.setCompoundDrawablePadding(dp(10));
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
        root.addView(card);
    }

    private void refreshPhone() {
        if (phoneStatus == null) return;
        PhoneInfo ph = PhoneInfo.get(this);
        boolean free = PhoneInfo.batteryUnrestricted(this);
        String s = getString(eq.globalOk ? R.string.status_global : R.string.ph_eq_players)
                + "\n" + getString(ph.ble ? R.string.ph_ble_yes : R.string.ph_ble_no)
                + "\n" + getString(free ? R.string.ph_battery_ok : R.string.ph_battery_warn);
        phoneStatus.setText(s);
        phoneBatteryBtn.setVisibility(free ? View.GONE : View.VISIBLE);
    }

    private DeviceSettings carSettings() {
        return carAddress == null ? null : DeviceSettings.get(this, carAddress);
    }

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
        boolean show = dev != null && dev.type == DeviceInfo.T_CAR;
        carBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        DeviceSettings ds = DeviceSettings.get(this, dev.address);
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
            DeviceInfo primary = DeviceMonitor.get().primaryAudio();
            if (primary == null || !primary.address.equals(dev.address)) s += "\n" + getString(R.string.car_pending);
        }
        if (ds.carMono) s += "\n" + getString(R.string.car_mono);
        else if (ds.carSwap) s += "\n" + getString(R.string.car_swapped);
        carStatus.setText(s);
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
        DeviceMonitor.get().refresh();
        refreshDevices();
        refreshEq();
        refreshSettings();
        if (tab == TAB_MUSIC) refreshMusicTime();
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
        np.stop();
        ui.removeCallbacks(npTicker);
        BudsLink.get().removeListener(budsListener);
        AirPods.get().removeListener(airListener);
        DeviceMonitor.get().removeListener(deviceListener);
        // экран закрыт — AirPods слушаем в экономном режиме (служба сама остановит без них)
        DeviceInfo sel = DeviceMonitor.get().find(selected);
        if (sel != null && sel.isAirPods()) AirPods.get().start(this, sel, false);
        eq.removeListener(eqListener);
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
        wave.setColor(art != null ? waveColor(art) : 0);
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

    /** Яркий цвет из обложки — для волны. */
    private static int waveColor(Bitmap b) {
        try {
            Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
            float[] hsv = new float[3];
            Color.colorToHSV(one.getPixel(0, 0), hsv);
            if (hsv[1] < 0.2f) return 0; // серая обложка — берём акцент
            hsv[1] = Math.max(0.55f, hsv[1]);
            hsv[2] = Math.max(0.9f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0;
        }
    }

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
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

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
