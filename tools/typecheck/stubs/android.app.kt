@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package android.app

open class Application : android.content.ContextWrapper() {
    open fun onCreate() {}
}
open class Activity : android.content.ContextWrapper() {
    open fun onResume() {}
    open fun onUserInteraction() {}
}
class NotificationChannel(id: String, name: CharSequence, importance: Int) {
    var description: String? = null
}
class Notification
class NotificationManager {
    fun areNotificationsEnabled(): Boolean = TODO()
    fun createNotificationChannel(channel: NotificationChannel) {}
    fun notify(id: Int, notification: Notification) {}
    companion object { const val IMPORTANCE_DEFAULT = 3; const val IMPORTANCE_HIGH = 4 }
}
