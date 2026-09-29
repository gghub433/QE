package lv.budseq;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Плитка в шторке: включить / выключить эквалайзер. */
public class EqTileService extends TileService {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        EqEngine eq = EqEngine.get(this);
        eq.setEnabled(!eq.enabled);
        ensureService(this);
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        EqEngine eq = EqEngine.get(this);
        t.setState(eq.enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= 29) {
            String sub = eq.enabled ? getString(R.string.on) : getString(R.string.off);
            if (eq.enabled && !eq.lastPreset.isEmpty()) sub = eq.lastPreset;
            t.setSubtitle(sub);
        }
        t.updateTile();
    }

    static void ensureService(Context c) {
        try {
            c.startForegroundService(new Intent(c, EqService.class));
        } catch (Exception ignored) {
            // Android может не разрешить запуск из фона — служба и так обычно работает
        }
    }
}
