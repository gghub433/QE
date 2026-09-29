package lv.budseq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Автозапуск: после включения телефона и после обновления приложения
 * снова поднимает фоновую службу — эквалайзер работает без открытия EQ.
 */
public class BootReceiver extends BroadcastReceiver {

    public static boolean enabled(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("autostart", true);
    }

    public static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("autostart", on).apply();
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!enabled(c)) return;
        EqTileService.ensureService(c);
    }
}
