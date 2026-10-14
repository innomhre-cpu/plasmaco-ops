package rw.plasmaco.ops.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.domain.sync.SyncState
import rw.plasmaco.ops.ui.theme.Plasmaco

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(Plasmaco.shapes.circle).background(Plasmaco.colors.primaryTint), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Plasmaco.colors.primary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = Plasmaco.type.cardTitle, color = Plasmaco.colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(message, style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary, textAlign = TextAlign.Center)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(actionText, onAction, fillWidth = false)
        }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Plasmaco.colors.danger, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(message, style = Plasmaco.type.body, color = Plasmaco.colors.textPrimary, textAlign = TextAlign.Center)
        if (onRetry != null) { Spacer(Modifier.height(12.dp)); SecondaryButton("Try again", onRetry, fillWidth = false) }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = "Loading") {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Plasmaco.colors.primary, strokeWidth = 3.dp)
            Spacer(Modifier.height(8.dp))
            Text(label, style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
        }
    }
}

/** Shown under the top bar whenever the device has no connection; work continues locally. */
@Composable
fun OfflineBanner(pending: Int, modifier: Modifier = Modifier) {
    val t = toneColors(Tone.WARNING)
    Row(
        modifier.fillMaxWidth().background(t.container).padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CloudOff, null, tint = t.content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (pending > 0) "Offline — $pending change${if (pending == 1) "" else "s"} saved on this device will sync when you reconnect" else "Offline — you can keep working; changes are saved on this device",
            style = Plasmaco.type.label, color = t.content,
        )
    }
}

/** Per-record sync state with an icon and words (never colour alone). */
@Composable
fun SyncStatusPill(state: SyncState, modifier: Modifier = Modifier) {
    val (tone, icon) = when (state) {
        SyncState.SYNCED -> Tone.SUCCESS to Icons.Outlined.CloudDone
        SyncState.PENDING -> Tone.INFO to Icons.Outlined.CloudSync
        SyncState.LOCAL_ONLY -> Tone.NEUTRAL to Icons.Outlined.PhoneAndroid
        SyncState.CONFLICT -> Tone.WARNING to Icons.Outlined.SyncProblem
        SyncState.FAILED -> Tone.DANGER to Icons.Outlined.SyncProblem
    }
    StatusBadge(state.label, tone, modifier, icon)
}

/** Inline banner for validation summaries and notices inside forms. */
@Composable
fun NoticeBanner(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.ErrorOutline) {
    val t = toneColors(tone)
    Row(
        modifier.fillMaxWidth().clip(Plasmaco.shapes.inset).background(t.container).padding(12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = t.content, modifier = Modifier.size(18.dp))
        Text(text, style = Plasmaco.type.bodySmall, color = t.content)
    }
}
