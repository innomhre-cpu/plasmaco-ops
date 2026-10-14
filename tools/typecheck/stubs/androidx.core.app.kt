@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.core.app

import android.app.Notification
import android.content.Context
class NotificationCompat {
    class Builder(context: Context, channelId: String) {
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setAutoCancel(autoCancel: Boolean): Builder = this
        fun setPriority(pri: Int): Builder = this
        fun build(): Notification = TODO()
    }
}
