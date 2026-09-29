package lv.budseq;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Загрузочный экран: анимированный логотип.
 * При первом запуске — выбор языка.
 */
public class SplashActivity extends Activity {
    private static final long SPLASH_MS = 1400;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout langBox;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        // сразу запускаем фоновую службу, пока идёт анимация
        startForegroundService(new Intent(this, EqService.class));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);

        BarsLogo logo = new BarsLogo(this);
        center.addView(logo, new LinearLayout.LayoutParams(dp(120), dp(120)));

        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(32);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(16), 0, 0);
        center.addView(name);

        TextView tag = new TextView(this);
        tag.setText(R.string.tagline);
        tag.setTextColor(Color.rgb(0xA0, 0xA3, 0xAA));
        tag.setTextSize(14);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(dp(24), dp(6), dp(24), 0);
        center.addView(tag);

        langBox = new LinearLayout(this);
        langBox.setOrientation(LinearLayout.VERTICAL);
        langBox.setPadding(dp(32), dp(32), dp(32), 0);
        langBox.setAlpha(0f);
        center.addView(langBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(center, clp);
        setContentView(root);

        // появление логотипа
        center.setAlpha(0f);
        center.setScaleX(0.9f);
        center.setScaleY(0.9f);
        center.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500).start();

        ui.postDelayed(new Runnable() {
            public void run() {
                if (Lang.chosen(SplashActivity.this)) openMain();
                else showLanguagePicker();
            }
        }, SPLASH_MS);
    }

    private void showLanguagePicker() {
        TextView title = new TextView(this);
        title.setText(R.string.choose_lang);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        langBox.addView(title);

        for (int i = 1; i < Lang.CODES.length; i++) {
            final String code = Lang.CODES[i];
            TextView btn = new TextView(this);
            btn.setText(Lang.NATIVE[i]);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(17);
            btn.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(0x2A, 0x2B, 0x30));
            bg.setCornerRadius(dp(26));
            btn.setBackground(bg);
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Lang.set(SplashActivity.this, code);
                    openMain();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.bottomMargin = dp(10);
            langBox.addView(btn, lp);
        }
        langBox.setTranslationY(dp(30));
        langBox.animate().alpha(1f).translationY(0).setDuration(400).start();
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Логотип: «прыгающие» полоски эквалайзера. */
    static class BarsLogo extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final long start = System.currentTimeMillis();

        BarsLogo(Context c) {
            super(c);
            bg.setColor(Color.rgb(0x1C, 0x1D, 0x21));
            bar.setColor(Theme.accent());
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            c.drawRoundRect(r, w * 0.28f, w * 0.28f, bg);
            float t = (System.currentTimeMillis() - start) / 1000f;
            int n = 5;
            float bw = w * 0.09f, gap = w * 0.06f;
            float total = n * bw + (n - 1) * gap;
            float x0 = (w - total) / 2f;
            float base = h * 0.76f, maxH = h * 0.52f;
            for (int i = 0; i < n; i++) {
                float k = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * 5.5f + i * 1.1f));
                float bh = maxH * k;
                float x = x0 + i * (bw + gap);
                r.set(x, base - bh, x + bw, base);
                c.drawRoundRect(r, bw / 2, bw / 2, bar);
            }
            postInvalidateOnAnimation();
        }
    }
}
