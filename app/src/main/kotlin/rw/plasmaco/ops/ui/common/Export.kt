package rw.plasmaco.ops.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import rw.plasmaco.ops.data.repository.ExportRepository
import rw.plasmaco.ops.domain.export.ExportFormat
import rw.plasmaco.ops.domain.export.ExportResult
import rw.plasmaco.ops.domain.reports.ReportDocument

/**
 * Export flow shared by every screen that produces a file: build the document, render PDF or Excel, then either ask
 * where to save it (Storage Access Framework, so the person chooses the folder) or open the Android share sheet.
 */
abstract class ExportingViewModel(messages: UserMessages, protected val exports: ExportRepository) : PlasmacoViewModel(messages) {
    private var pending: ExportResult? = null
    private val saveRequests = Channel<ExportResult>(Channel.BUFFERED)
    private val shareRequests = Channel<Intent>(Channel.BUFFERED)
    val saveEvents: Flow<ExportResult> = saveRequests.receiveAsFlow()
    val shareEvents: Flow<Intent> = shareRequests.receiveAsFlow()

    protected fun export(format: ExportFormat, share: Boolean, build: suspend () -> ReportDocument) = action {
        val result = exports.render(build(), format)
        if (share) {
            shareRequests.send(exports.shareIntent(result))
        } else {
            pending = result
            saveRequests.send(result)
        }
    }

    /** Result of the "save as" picker; null when the person cancelled. */
    fun saveTo(uri: Uri?) = action {
        val result = pending ?: return@action
        pending = null
        if (uri == null) return@action
        exports.saveTo(uri, result)
        messages.show("Saved ${result.fileName}")
    }
}

@Composable
fun ExportEffects(vm: ExportingViewModel) {
    val context = LocalContext.current
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PDF.mimeType)) { vm.saveTo(it) }
    val xlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.XLSX.mimeType)) { vm.saveTo(it) }
    LaunchedEffect(vm) {
        vm.saveEvents.collect { r -> if (r.mimeType == ExportFormat.PDF.mimeType) pdf.launch(r.fileName) else xlsx.launch(r.fileName) }
    }
    LaunchedEffect(vm) {
        vm.shareEvents.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // No app can receive the file; the person can still save it.
            }
        }
    }
}
