package app.tvdascriancas

import android.service.notification.NotificationListenerService

/**
 * Never reads notifications. Android only hands MediaSessionManager.getActiveSessions
 * (the "now playing" of other apps) to apps holding an enabled notification listener,
 * so this empty one is the key the watchdog needs. Enabled over ADB by
 * `./install.sh` (`cmd notification allow_listener`).
 */
class MediaListener : NotificationListenerService()
