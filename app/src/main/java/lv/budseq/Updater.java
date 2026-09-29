package lv.budseq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Автообновление с GitHub Releases:
 * последний релиз → сравнить версию → скачать APK → установить через PackageInstaller.
 * Релизы выкладывает publish.sh (сборка из Termux) или GitHub Actions (по тегу vX.Y).
 */
public final class Updater {
    private static final String TAG = "EQ-Update";

    /** Репозиторий с релизами. Он должен быть публичным — иначе GitHub не отдаст релизы без входа. */
    public static final String REPO = "gghub433/QE";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final String CHANNEL = "updates";
    private static final int NOTIF_UPDATE = 5;
    private static final long AUTO_EVERY_MS = 12L * 60 * 60 * 1000;

    public static final class Release {
        public String version = "";
        public String notes = "";
        public String apkUrl;
        public String page;
        public long size;
    }

    public interface CheckCallback {
        /** release != null — есть новее; оба null — установлена последняя версия. */
        void onChecked(Release newer, String error);
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        void onDone(File apk, String error);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;

    private Updater() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean autoEnabled(Context c) {
        return prefs(c).getBoolean("auto_update", true);
    }

    public static void setAutoEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto_update", on).apply();
    }

    public static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** Сравнение версий «5.0» / «v5.1» / «5.0.2»: >0 если a новее b. */
    static int compare(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p > q ? 1 : -1;
        }
        return 0;
    }

    private static int[] parts(String v) {
        if (v == null) return new int[0];
        String clean = v.replaceAll("[^0-9.]", "");
        if (clean.isEmpty()) return new int[0];
        String[] s = clean.split("\\.");
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            try {
                out[i] = Integer.parseInt(s[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    // =====================================================================
    // Проверка
    // =====================================================================

    public static void check(Context ctx, final CheckCallback cb) {
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                Release rel = null;
                String err = null;
                try {
                    rel = fetchLatest();
                    prefs(c).edit().putLong("update_checked", System.currentTimeMillis()).apply();
                    if (rel != null && compare(rel.version, currentVersion(c)) <= 0) rel = null;
                } catch (Exception e) {
                    Log.w(TAG, "check failed", e);
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final Release r = rel;
                final String e = err;
                main.post(new Runnable() {
                    public void run() { cb.onChecked(r, e); }
                });
            }
        }, "eq-update-check").start();
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection con = open(API);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        int code = con.getResponseCode();
        if (code == 404) throw new Exception("no releases (" + REPO + ")");
        if (code != 200) throw new Exception("HTTP " + code);
        String body = readAll(con.getInputStream());
        con.disconnect();

        JSONObject o = new JSONObject(body);
        Release r = new Release();
        r.version = o.optString("tag_name", "").replaceFirst("^[vV]", "");
        r.notes = o.optString("body", "").trim();
        r.page = o.optString("html_url", null);
        JSONArray assets = o.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name", "").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                r.apkUrl = a.optString("browser_download_url", null);
                r.size = a.optLong("size", 0);
                break;
            }
        }
        return r;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "EQ-Android");
        return con;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    // =====================================================================
    // Фоновая проверка (из службы): раз в 12 часов, при новой версии — уведомление
    // =====================================================================

    public static void autoCheck(final Context c) {
        if (!autoEnabled(c)) return;
        long last = prefs(c).getLong("update_checked", 0);
        if (System.currentTimeMillis() - last < AUTO_EVERY_MS) return;
        check(c, new CheckCallback() {
            public void onChecked(Release newer, String error) {
                if (newer != null && newer.apkUrl != null
                        && !newer.version.equals(prefs(c).getString("update_notified", ""))) {
                    prefs(c).edit().putString("update_notified", newer.version).apply();
                    notifyAvailable(c, newer.version);
                }
            }
        });
    }

    private static void notifyAvailable(Context c, String version) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                c.getString(R.string.ch_updates), NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS);
        open.putExtra(MainActivity.EXTRA_CHECK_UPDATE, true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 11, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTIF_UPDATE, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(c.getString(R.string.upd_notif_title, version))
                .setContentText(c.getString(R.string.upd_notif_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    // =====================================================================
    // Загрузка и установка
    // =====================================================================

    public static boolean isBusy() {
        return busy;
    }

    public static void download(Context ctx, final Release r, final DownloadCallback cb) {
        if (busy) return;
        busy = true;
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                File out = new File(new File(c.getCacheDir(), "update"), "EQ-" + r.version + ".apk");
                String err = null;
                try {
                    File dir = out.getParentFile();
                    if (dir != null && !dir.exists() && !dir.mkdirs()) throw new Exception("no cache dir");
                    File[] old = dir == null ? null : dir.listFiles();
                    if (old != null) {
                        for (File f : old) {
                            if (!f.delete()) Log.w(TAG, "can't delete " + f);
                        }
                    }
                    HttpURLConnection con = open(r.apkUrl);
                    int code = con.getResponseCode();
                    if (code != 200) throw new Exception("HTTP " + code);
                    long total = con.getContentLength() > 0 ? con.getContentLength() : r.size;
                    InputStream in = con.getInputStream();
                    OutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int lastPct = -1, n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        if (pct != lastPct) {
                            lastPct = pct;
                            main.post(new Runnable() {
                                public void run() { cb.onProgress(pct); }
                            });
                        }
                    }
                    os.close();
                    in.close();
                    con.disconnect();
                    if (total > 0 && done < total) throw new Exception("download interrupted");
                } catch (Exception e) {
                    Log.w(TAG, "download failed", e);
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                busy = false;
                final File f = err == null ? out : null;
                final String e = err;
                main.post(new Runnable() {
                    public void run() { cb.onDone(f, e); }
                });
            }
        }, "eq-update-download").start();
    }

    /** Может ли приложение ставить APK (Android: «Установка неизвестных приложений»). */
    public static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Передать APK системному установщику. Результат придёт в UpdateReceiver. */
    public static void install(Context ctx, File apk) throws Exception {
        Context c = ctx.getApplicationContext();
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream out = s.openWrite("base.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            s.fsync(out);
            out.close();

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            // Установщик дописывает в Intent статус — PendingIntent должен быть изменяемым (Android 12+)
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent status = PendingIntent.getBroadcast(c, id,
                    new Intent(c, UpdateReceiver.class), flags);
            s.commit(status.getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }
}
