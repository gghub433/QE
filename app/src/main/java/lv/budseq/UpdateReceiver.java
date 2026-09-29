package lv.budseq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Ответ системного установщика на обновление:
 * попросить подтверждение, сообщить об ошибке (например, другая подпись APK).
 */
public class UpdateReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "updates";
    private static final int NOTIF = 6;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context c = Lang.wrap(ctx);
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(confirm);
            } catch (Exception e) {
                // Android не дал открыть окно из фона — пусть пользователь нажмёт на уведомление
                PendingIntent pi = PendingIntent.getActivity(ctx, 12, confirm,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                notify(c, c.getString(R.string.upd_confirm), pi);
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return; // приложение уже перезапускается
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return; // пользователь нажал «Отмена»

        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        // CONFLICT = APK подписан другим ключом (например, собран на GitHub, а стоит сборка из Termux)
        String text = status == PackageInstaller.STATUS_FAILURE_CONFLICT
                ? c.getString(R.string.upd_conflict)
                : c.getString(R.string.upd_install_failed, msg == null ? String.valueOf(status) : msg);
        Toast.makeText(c, text, Toast.LENGTH_LONG).show();
        notify(c, text, null);
    }

    private static void notify(Context c, String text, PendingIntent pi) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                c.getString(R.string.ch_updates), NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(c.getString(R.string.upd_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        if (pi != null) b.setContentIntent(pi);
        nm.notify(NOTIF, b.build());
    }
}
