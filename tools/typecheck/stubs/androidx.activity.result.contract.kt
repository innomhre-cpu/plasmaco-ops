@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.activity.result.contract

import android.net.Uri
abstract class ActivityResultContract<I, O>
class ActivityResultContracts {
    class CreateDocument(mimeType: String) : ActivityResultContract<String, Uri?>()
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>()
    class RequestPermission : ActivityResultContract<String, Boolean>()
}
