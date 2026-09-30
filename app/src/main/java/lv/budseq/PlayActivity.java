package lv.budseq;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Запуск игры через EQ — без окна и без ожидания: сначала звук игры и низкая задержка
 * (сразу, в этом же процессе), потом сама игра. Сюда ведут кнопка «Играть» и ярлыки игр
 * на рабочем столе и в меню значка EQ.
 */
public class PlayActivity extends Activity {
    static final String ACTION = "lv.budseq.PLAY";
    static final String EXTRA_KEY = "key", EXTRA_NAME = "name", EXTRA_PKG = "pkg", EXTRA_SOURCE = "source";
    static final String STEAM_LINK = "com.valvesoftware.steamlink", MOONLIGHT = "com.limelight",
            XBOX = "com.microsoft.xboxone.smartglass";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        String key = i.getStringExtra(EXTRA_KEY);
        String name = i.getStringExtra(EXTRA_NAME);
        String pkg = i.getStringExtra(EXTRA_PKG);
        if (key != null && name != null && !launch(this, key, name, pkg, i.getStringExtra(EXTRA_SOURCE))
                && pkg == null) {
            // игра с ПК, а стриминга нет — показываем выбор в самом EQ
            Intent open = new Intent(this, MainActivity.class);
            open.putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_GAMES);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /**
     * Звук игры → игра. pkg — игра на телефоне; иначе игра с ПК: открываем стриминг
     * (Steam Link, Moonlight, Xbox) — звук с ПК пойдёт через телефон и EQ.
     * false — открыть нечего (игры больше нет или стриминг не установлен).
     */
    static boolean launch(Context c, String key, String name, String pkg, String source) {
        Intent game = pkg != null ? c.getPackageManager().getLaunchIntentForPackage(pkg) : streamingApp(c, source);
        if (game == null) {
            if (pkg != null) Toast.makeText(c, R.string.game_launch_fail, Toast.LENGTH_SHORT).show();
            return false;
        }
        Games.startSession(c, key, name);
        // звук и задержку — до запуска игры: пересборка эффектов не попадёт на её звук
        Games.applySound(c, Games.profileFor(c, key, name), name);
        EqService.poke(c);
        game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(game);
        } catch (Exception e) {
            Toast.makeText(c, R.string.game_launch_fail, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (pkg == null) Toast.makeText(c, R.string.game_started, Toast.LENGTH_SHORT).show();
        return true;
    }

    /** Приложение для игры с ПК на телефоне: для Steam — Steam Link, для Xbox — Xbox, иначе Moonlight. */
    static Intent streamingApp(Context c, String source) {
        String[] order = "Xbox".equals(source) ? new String[]{XBOX, STEAM_LINK, MOONLIGHT}
                : "Steam".equals(source) ? new String[]{STEAM_LINK, MOONLIGHT, XBOX}
                : new String[]{MOONLIGHT, STEAM_LINK, XBOX};
        for (String p : order) {
            Intent li = c.getPackageManager().getLaunchIntentForPackage(p);
            if (li != null) return li;
        }
        return null;
    }

    static Intent intent(Context c, String key, String name, String pkg, String source) {
        Intent i = new Intent(c, PlayActivity.class);
        i.setAction(ACTION);   // у ярлыков обязательно действие
        i.putExtra(EXTRA_KEY, key);
        i.putExtra(EXTRA_NAME, name);
        if (pkg != null) i.putExtra(EXTRA_PKG, pkg);
        if (source != null) i.putExtra(EXTRA_SOURCE, source);
        return i;
    }

    private static Icon icon(Context c, Drawable d, Bitmap cover) {
        if (cover != null) return Icon.createWithBitmap(square(cover));
        if (d == null) return Icon.createWithResource(c, R.mipmap.ic_launcher);
        Bitmap b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, 192, 192);
        d.draw(new Canvas(b));
        return Icon.createWithBitmap(b);
    }

    private static Bitmap square(Bitmap src) {
        int s = Math.min(src.getWidth(), src.getHeight());
        return Bitmap.createBitmap(src, (src.getWidth() - s) / 2, (src.getHeight() - s) / 2, s, s);
    }

    /** Ярлык «через EQ» на рабочий стол. false — рабочий стол не умеет. */
    static boolean pinShortcut(Context c, String key, String name, String pkg, String source, Drawable d, Bitmap cover) {
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) return false;
            ShortcutInfo si = new ShortcutInfo.Builder(c, "play_" + key)
                    .setShortLabel(name)
                    .setIcon(icon(c, d, cover))
                    .setIntent(intent(c, key, name, pkg, source))
                    .build();
            return sm.requestPinShortcut(si, null);
        } catch (Exception e) {
            return false;
        }
    }

    /** Меню значка EQ (долгое нажатие): любимые игры телефона — сразу в игру через EQ. */
    static void updateDynamic(Context c, List<Games.PhoneGame> games) {
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            List<ShortcutInfo> out = new ArrayList<>();
            int max = Math.min(4, sm.getMaxShortcutCountPerActivity());
            for (Games.PhoneGame g : games) {
                if (out.size() >= max || g.weekMs < 60000) break;
                Drawable d = null;
                try {
                    d = c.getPackageManager().getApplicationIcon(g.pkg);
                } catch (Exception ignored) {
                }
                out.add(new ShortcutInfo.Builder(c, "play_" + g.pkg)
                        .setShortLabel(g.label)
                        .setIcon(icon(c, d, null))
                        .setIntent(intent(c, g.pkg, g.label, g.pkg, null))
                        .setRank(out.size())
                        .build());
            }
            sm.setDynamicShortcuts(out);
        } catch (Exception ignored) {
            // лимит частоты обновлений или лаунчер без ярлыков — не страшно
        }
    }
}
