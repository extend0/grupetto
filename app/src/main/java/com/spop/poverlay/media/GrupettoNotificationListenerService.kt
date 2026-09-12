package com.spop.poverlay.media

import android.service.notification.NotificationListenerService

/**
 * Exists only so that [MediaSessionStrategy] has a ComponentName to hand to
 * MediaSessionManager.getActiveSessions, which requires either the MEDIA_CONTENT_CONTROL
 * signature permission or an enabled notification listener.
 *
 * It deliberately reads nothing. Notification contents are none of this app's business - the
 * grant is a means of reaching the media session, and nothing more.
 */
class GrupettoNotificationListenerService : NotificationListenerService()
