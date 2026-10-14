package rw.plasmaco.ops.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.plasmaco.ops.ui.components.AppTextField
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.PlasmacoCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.SecondaryButton
import rw.plasmaco.ops.ui.components.TextLink
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Logo
import rw.plasmaco.ops.ui.theme.Plasmaco

@Composable
private fun AuthFrame(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Plasmaco.colors.background).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Plasmaco.dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Plasmaco.dimens.cardGap),
        ) {
            Spacer(Modifier.height(24.dp))
            Logo()
            content()
        }
    }
}

@Composable
fun SignInScreen(vm: AuthViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    // The PIN is deliberately not saved across process death.
    var secret by remember { mutableStateOf("") }
    AuthFrame {
        PlasmacoCard(padding = Plasmaco.dimens.headerCardPadding) {
            Text("Sign in", style = Plasmaco.type.screenTitle, color = Plasmaco.colors.textPrimary, modifier = Modifier.semantics { heading() })
            Text(
                if (state.serverConfigured) "Connected to ${state.serverUrl}. You can also sign in offline on a device you have used before."
                else "This device works on its own; accounts are managed by its administrator.",
                style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            AppTextField(username, { username = it; vm.clearError() }, "Username", required = true)
            Spacer(Modifier.height(12.dp))
            AppTextField(
                secret, { secret = it; vm.clearError() }, "PIN or password", required = true, isPassword = true,
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
            )
            state.error?.let { Spacer(Modifier.height(12.dp)); NoticeBanner(it, Tone.DANGER) }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Sign in", { vm.signIn(username, secret); secret = "" }, icon = Icons.AutoMirrored.Outlined.Login, loading = state.working)
        }
        if (state.serverConfigured && !state.hasUsers) {
            TextLink("Use a different server address", vm::clearServer)
        }
    }
}

/** First run: connect to the company server, or make this device standalone with its own administrator. */
@Composable
fun SetupScreen(vm: AuthViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var server by rememberSaveable { mutableStateOf("https://") }
    var username by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AuthFrame {
        PlasmacoCard(padding = Plasmaco.dimens.headerCardPadding) {
            Text("Set up this device", style = Plasmaco.type.screenTitle, color = Plasmaco.colors.textPrimary, modifier = Modifier.semantics { heading() })
            Text(
                "Daily production reports, stock and approvals for PLASMACO. Choose how this device works.",
                style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary,
            )
        }
        state.error?.let { NoticeBanner(it, Tone.DANGER) }
        PlasmacoCard {
            Text("Connect to the company server", style = Plasmaco.type.sectionTitle, color = Plasmaco.colors.textPrimary)
            Text("Recommended when several devices share data. Your administrator gives you the address.", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
            Spacer(Modifier.height(12.dp))
            AppTextField(server, { server = it; vm.clearError() }, "Server address", keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Use this server", { vm.saveServer(server) }, icon = Icons.Outlined.Cloud)
        }
        PlasmacoCard {
            Text("Use this device on its own", style = Plasmaco.type.sectionTitle, color = Plasmaco.colors.textPrimary)
            Text(
                "Create the first administrator. They then add production lines, shifts, materials, products and the other users.",
                style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            AppTextField(username, { username = it; vm.clearError() }, "Username", required = true)
            Spacer(Modifier.height(12.dp))
            AppTextField(name, { name = it; vm.clearError() }, "Full name", required = true)
            Spacer(Modifier.height(12.dp))
            AppTextField(secret, { secret = it; vm.clearError() }, "PIN or password", required = true, isPassword = true, keyboardType = KeyboardType.Password)
            FieldHint("At least 6 characters; avoid repeated or common PINs.")
            Spacer(Modifier.height(12.dp))
            AppTextField(confirm, { confirm = it; vm.clearError() }, "Repeat PIN or password", required = true, isPassword = true, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                "Create administrator", { vm.createAdministrator(username, name, secret, confirm) },
                icon = Icons.Outlined.AdminPanelSettings, loading = state.working,
            )
        }
    }
}
