package lv.budseq;

import android.service.notification.NotificationListenerService;

/**
 * Пустая служба доступа к уведомлениям. Android выдаёт список играющих плееров
 * (Spotify и др.) только приложениям с таким доступом — сами уведомления не читаются.
 */
public class MediaListener extends NotificationListenerService {
}
