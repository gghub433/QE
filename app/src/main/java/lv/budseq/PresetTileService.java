package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.ArrayList;
import java.util.List;

/** Плитка в шторке: каждое нажатие — следующий пресет. */
public class PresetTileService extends TileService {

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
        List<String> names = allNames();
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        int i = (p.getInt("tile_preset", -1) + 1) % names.size();
        p.edit().putInt("tile_preset", i).apply();
        int builtIn = EqEngine.PRESETS.length;
        if (i < builtIn) {
            eq.applyBuiltIn(i, names.get(i));
        } else {
            eq.loadPreset(names.get(i));
        }
        if (!eq.enabled) eq.setEnabled(true);
        EqTileService.ensureService(this);
        update();
    }

    private List<String> allNames() {
        List<String> out = new ArrayList<>();
        for (int res : EqEngine.PRESET_NAMES) out.add(getString(res));
        out.addAll(EqEngine.get(this).presetNames());
        return out;
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        EqEngine eq = EqEngine.get(this);
        t.setState(eq.enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            t.setLabel(getString(R.string.tile_preset));
            t.setSubtitle(eq.lastPreset.isEmpty() ? getString(R.string.custom) : eq.lastPreset);
        } else {
            t.setLabel(eq.lastPreset.isEmpty() ? getString(R.string.tile_preset) : eq.lastPreset);
        }
        t.updateTile();
    }
}
