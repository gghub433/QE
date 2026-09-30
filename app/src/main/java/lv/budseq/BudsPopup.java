package lv.budseq;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Всплывающая карточка поверх экрана при подключении устройства (как у AirPods).
 * Для Galaxy Buds — анимированный кейс, для остальных — картинка по типу устройства.
 */
public final class BudsPopup {
    private static final long SHOW_MS = 8000;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout card;
    private FrameLayout content;
    private BudsView budsView;
    private DeviceView deviceView;
    private WaveView wave;
    private String address;

    private final Runnable autoHide = new Runnable() {
        public void run() { hide(); }
    };

    public BudsPopup(Context c) {
        ctx = c; // контекст службы — уже с выбранным языком
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public static boolean allowed(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public boolean isShown() { return card != null; }

    public String shownAddress() { return address; }

    /** Показать (или обновить) карточку устройства. buds != null — подробный режим Galaxy Buds. */
    public void show(DeviceInfo info, BudsLink.State buds) {
        if (!allowed(ctx) || info == null) return;
        boolean fresh = card == null;
        if (card == null && !create()) return;
        if (fresh) wave.breathe();
        address = info.address;
        setContent(info, buds);
        restartTimer();
    }

    public void update(DeviceInfo info, BudsLink.State buds) {
        if (card == null || info == null || !info.address.equals(address)) return;
        setContent(info, buds);
    }

    private void setContent(DeviceInfo info, BudsLink.State buds) {
        boolean useBuds = buds != null && buds.connected && buds.hasBattery();
        if (useBuds) {
            if (deviceView != null) deviceView.setVisibility(View.GONE);
            budsView.setVisibility(View.VISIBLE);
            budsView.setStyle(BudsView.styleFor(info));
            budsView.setState(buds);
        } else {
            budsView.setVisibility(View.GONE);
            deviceView.setVisibility(View.VISIBLE);
            deviceView.setDevice(info);
        }
    }

    private boolean create() {
        float d = ctx.getResources().getDisplayMetrics().density;

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) (12 * d), (int) (14 * d), (int) (12 * d), (int) (12 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(0x1C, 0x1D, 0x21));
        bg.setCornerRadius(32 * d);
        card.setBackground(bg);
        card.setElevation(16 * d);

        View handle = new View(ctx);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(Color.rgb(0x55, 0x58, 0x60));
        hb.setCornerRadius(3 * d);
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams((int) (40 * d), (int) (5 * d));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(handle, hlp);

        // фирменная волна: «вдох» при появлении окна
        wave = new WaveView(ctx);
        wave.setLevel(0.6f);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (34 * d));
        wlp.topMargin = (int) (6 * d);
        card.addView(wave, wlp);

        content = new FrameLayout(ctx);
        budsView = new BudsView(ctx);
        deviceView = new DeviceView(ctx);
        content.addView(budsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(deviceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (250 * d)));

        TextView open = new TextView(ctx);
        open.setText(R.string.open_eq);
        open.setTextColor(Color.WHITE);
        open.setTextSize(16);
        open.setGravity(Gravity.CENTER);
        Drawable eqIcon = ctx.getDrawable(R.drawable.ic_equalizer).mutate();
        eqIcon.setTint(Color.WHITE);
        eqIcon.setBounds(0, 0, (int) (20 * d), (int) (20 * d));
        open.setCompoundDrawablesRelative(eqIcon, null, null, null);
        open.setCompoundDrawablePadding((int) (8 * d));
        open.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
        GradientDrawable ob = new GradientDrawable();
        ob.setColor(Theme.accent());
        ob.setCornerRadius(24 * d);
        open.setBackground(ob);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(ctx, MainActivity.class);
                i.putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_EQ);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                hide();
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * d));
        olp.topMargin = (int) (8 * d);
        card.addView(open, olp);

        // смахивание вниз — закрыть
        card.setOnTouchListener(new View.OnTouchListener() {
            float startY;

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = e.getRawY();
                        main.removeCallbacks(autoHide);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        v.setTranslationY(Math.max(0, e.getRawY() - startY));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (v.getTranslationY() > v.getHeight() * 0.25f) {
                            hide();
                        } else {
                            v.animate().translationY(0).setDuration(200).start();
                            restartTimer();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.y = (int) (16 * d);
        lp.horizontalMargin = 0.03f;

        try {
            wm.addView(card, lp);
        } catch (Exception e) {
            card = null;
            return false;
        }
        card.setTranslationY(500 * d);
        card.setAlpha(0f);
        card.animate().translationY(0).alpha(1f).setDuration(450)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        return true;
    }

    private void restartTimer() {
        main.removeCallbacks(autoHide);
        main.postDelayed(autoHide, SHOW_MS);
    }

    public void hide() {
        main.removeCallbacks(autoHide);
        final View v = card;
        if (v == null) return;
        card = null;
        address = null;
        float d = ctx.getResources().getDisplayMetrics().density;
        v.animate().translationY(500 * d).alpha(0f).setDuration(300).withEndAction(new Runnable() {
            public void run() {
                try {
                    wm.removeView(v);
                } catch (Exception ignored) {
                }
            }
        }).start();
    }
}
