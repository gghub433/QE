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
import android.text.InputType;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class MainActivity extends Activity {

    private static final int ACCENT = Color.rgb(0x3E, 0x7B, 0xFA);
    private static final int CARD = Color.rgb(0x1C, 0x1D, 0x21);
    private static final int CHIP = Color.rgb(0x3A, 0x3B, 0x40);
    private static final int DANGER = Color.rgb(0xE5, 0x48, 0x48);
    private static final int GREY = Color.rgb(0x80, 0x83, 0x8A);

    private static final int REQ_PERMS = 1, REQ_AUDIO = 2, REQ_SAVE = 10, REQ_OPEN = 11;

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

    // устройства
    private String selected;
    private FrameLayout devicePanel;
    private BudsView budsView;
    private DeviceView deviceView;
    private LinearLayout deviceChips;
    private HorizontalScrollView deviceChipsScroll;
    private Button devSettingsBtn, findBtn, popupBtn;
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
            ui.postDelayed(this, 1000);
        }
    };

    private final BudsLink.Listener budsListener = new BudsLink.Listener() {
        public void onBudsState(BudsLink.State s) {
            if (!s.connected && finding) setFinding(false);
            refreshDevices();
        }
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
        eq = EqEngine.get(this);
        settings = getSharedPreferences("settings", MODE_PRIVATE);

        ArrayList<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && !granted("android.permission.POST_NOTIFICATIONS")) {
            perms.add("android.permission.POST_NOTIFICATIONS");
        }
        if (Build.VERSION.SDK_INT >= 31 && !granted("android.permission.BLUETOOTH_CONNECT")) {
            perms.add("android.permission.BLUETOOTH_CONNECT");
        }
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
        EqTileService.ensureService(this);

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFitsSystemWindows(true); // Android 15+: не залезать под строку состояния
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root);

        buildHeader(root);
        buildDevices(root);
        buildNowPlaying(root);
        buildEqualizer(root);
        buildSound(root);
        buildPresets(root);
        buildAutomation(root);

        TextView tip = text(getString(R.string.tip_wearable), 12, GREY);
        tip.setPadding(dp(4), dp(20), dp(4), 0);
        root.addView(tip);

        setContentView(scroll);
        refreshEq();
        refreshDevices();
        handleEffectIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleEffectIntent(intent);
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
        updateStatus();
    }

    private void buildHeader(LinearLayout root) {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(getString(R.string.eq_title), 26, Color.WHITE),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton langBtn = new ImageButton(this);
        langBtn.setImageDrawable(icon(R.drawable.ic_language, Color.WHITE));
        langBtn.setBackground(round(CARD, 22));
        langBtn.setContentDescription(getString(R.string.language));
        langBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLanguageDialog(); }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(44), dp(44));
        llp.rightMargin = dp(12);
        head.addView(langBtn, llp);

        mainSwitch = styledSwitch();
        mainSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (!updating) eq.setEnabled(on);
            }
        });
        head.addView(mainSwitch);
        root.addView(head);
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
                    else eq.switchProfile("phone", getString(R.string.phone_speaker));
                }
                refreshEq();
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
    // Жизненный цикл
    // =====================================================================

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        BudsLink.get().addListener(budsListener);
        DeviceMonitor.get().addListener(deviceListener);
        eq.addListener(eqListener);
        DeviceMonitor.get().refresh();
        refreshDevices();
        refreshEq();
        boolean on = BudsPopup.allowed(this);
        popupBtn.setText(on ? R.string.popup_on : R.string.popup_enable);
        setChipIcon(popupBtn, on ? R.drawable.ic_check : R.drawable.ic_layers);
        ui.postDelayed(new Runnable() {
            public void run() { updateStatus(); }
        }, 600);
        if (spectrumWanted() && granted("android.permission.RECORD_AUDIO")) startSpectrum();
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
        DeviceMonitor.get().removeListener(deviceListener);
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
        budsView.setVisibility(budsMode ? View.VISIBLE : View.GONE);
        deviceView.setVisibility(budsMode ? View.GONE : View.VISIBLE);
        if (budsMode) budsView.setState(bs);
        else deviceView.setDevice(sel);

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
        autoSwitch.setChecked(eq.autoMode);
        perDeviceSwitch.setChecked(eq.perDevice);
        profileText.setVisibility(eq.perDevice ? View.VISIBLE : View.GONE);
        String pname = eq.profileName.isEmpty() ? getString(R.string.phone_speaker) : eq.profileName;
        profileText.setText(getString(R.string.profile_label, pname));
        specBtn.setBackground(round(spectrumWanted() ? ACCENT : CHIP, 24));
        updating = false;
        rebuildUserPresets();
        updateStatus();
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
