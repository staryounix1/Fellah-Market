package com.example.feature.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.data.UserRole
import com.example.core.data.VerificationStatus
import com.example.core.domain.PhoneValidator
import com.example.core.domain.PinValidator
import com.example.core.l10n.AppLanguage
import com.example.core.l10n.LocalL10n
import com.example.core.media.ImageCompressor
import com.example.core.ui.*
import com.example.feature.auth.resolveAuthErrorMessage
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    state: AppUiState,
    onMarkAllRead: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val l10n = LocalL10n.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(l10n.get(R.string.notifications_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = l10n.get(R.string.back_btn))
                    }
                },
                actions = {
                    if (state.notifications.isNotEmpty()) {
                        TextButton(onClick = onMarkAllRead) {
                            Text(l10n.get(R.string.mark_all_read))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmParchment)
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.notifications.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Rounded.NotificationsNone,
                        title = l10n.get(R.string.empty_notifications)
                    )
                }
            } else {
                items(state.notifications, key = { it.id }) { notif ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (notif.isRead) CrispSurface else SoftSageContainer
                        ),
                        border = BorderStroke(1.dp, SubtleBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.NotificationsActive,
                                contentDescription = null,
                                tint = if (notif.isRead) MutedMossText else ForestGreen
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = l10n.localizedName(notif.titleDarija, notif.titleAr, notif.titleFr),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(notif.bodyText, style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    state: AppUiState,
    onSelectLanguage: (AppLanguage) -> Unit,
    onToggleSimulateOffline: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onRetryFailedSync: () -> Unit = {},
    onUploadVerificationDoc: (String) -> Unit = {},
    onChangePin: (String, String, String, (Result<String?>) -> Unit) -> Unit = { _, _, _, cb -> cb(Result.success(null)) },
    onOpenDiagnostics: () -> Unit = {},
    onSwitchDemoUser: (String) -> Unit,
    onLogout: () -> Unit
) {
    val l10n = LocalL10n.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = state.currentUser ?: return
    var showChangePinDialog by remember { mutableStateOf(false) }
    var pinChangedNotice by remember { mutableStateOf(false) }
    val docPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val compressed = ImageCompressor.compressAndSave(context, uri)
                if (compressed != null) onUploadVerificationDoc(compressed)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmParchment)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Profile Header Card
        Card(
            colors = CardDefaults.cardColors(containerColor = CrispSurface),
            border = BorderStroke(1.dp, SubtleBorder)
        ) {
            Row(
                modifier = Modifier.padding(18.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(62.dp).clip(CircleShape).background(SoftSageContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (user.role) {
                            UserRole.FARMER -> Icons.Rounded.Agriculture
                            UserRole.BUYER -> Icons.Rounded.Storefront
                            else -> Icons.Rounded.AdminPanelSettings
                        },
                        contentDescription = null,
                        tint = ForestGreen,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(user.fullName, style = MaterialTheme.typography.titleLarge)
                    Text("${user.phone} • ${user.city}", style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val (verLabel, verBg, verFg) = when (user.verificationStatus) {
                            VerificationStatus.VERIFIED -> Triple(l10n.get(R.string.status_verified), SoftSageContainer, OnSageContainer)
                            VerificationStatus.REJECTED -> Triple(l10n.get(R.string.status_rejected), ErrorContainerLight, ErrorCrimson)
                            else -> Triple(l10n.get(R.string.status_pending), SaffronContainer, OnSaffronContainer)
                        }
                        StatusPill(verLabel, verBg, verFg)
                        if (user.ratingCount > 0) {
                            StatusPill("★ ${String.format(java.util.Locale.US, "%.1f", user.ratingAvg)}", SaffronContainer, OnSaffronContainer)
                        }
                    }
                }
            }
        }

        if (user.verificationStatus != VerificationStatus.VERIFIED) {
            VerificationBanner(user)
            if (user.role != UserRole.ADMIN) {
                OutlinedButton(
                    onClick = { docPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth().testTag("profile_upload_doc_button")
                ) {
                    Icon(Icons.Rounded.Badge, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (user.verificationDocPath.isNotBlank()) l10n.get(R.string.verification_doc_uploaded)
                        else l10n.get(R.string.upload_verification_doc_btn)
                    )
                }
            }
        }

        // Language switcher (Instant live RTL/LTR switch)
        Card(
            colors = CardDefaults.cardColors(containerColor = CrispSurface),
            border = BorderStroke(1.dp, SubtleBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(l10n.get(R.string.choose_language), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppLanguage.DARIJA to R.string.lang_darija,
                        AppLanguage.ARABIC to R.string.lang_arabic,
                        AppLanguage.FRENCH to R.string.lang_french
                    ).forEach { (lang, resId) ->
                        FilterChip(
                            selected = state.language == lang,
                            onClick = { onSelectLanguage(lang) },
                            label = { Text(l10n.get(resId)) }
                        )
                    }
                }
            }
        }

        // Offline mode & sync controls
        Card(
            colors = CardDefaults.cardColors(containerColor = CrispSurface),
            border = BorderStroke(1.dp, SubtleBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(l10n.get(R.string.simulate_offline_toggle), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.simulateOffline,
                        onCheckedChange = onToggleSimulateOffline,
                        modifier = Modifier.testTag("offline_toggle_switch")
                    )
                }
                if (state.pendingSyncCount > 0) {
                    Button(
                        onClick = onSyncNow,
                        enabled = state.isEffectivelyOnline,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.CloudSync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(l10n.format(R.string.sync_now_btn, state.pendingSyncCount))
                    }
                }
                if (state.failedSyncItems.isNotEmpty()) {
                    HorizontalDivider(color = SubtleBorder)
                    Text(
                        text = l10n.format(R.string.sync_failed_banner, state.failedSyncItems.size),
                        color = ErrorCrimson,
                        style = MaterialTheme.typography.titleMedium
                    )
                    state.failedSyncItems.take(3).forEach { failed ->
                        val errDesc = failed.lastError.ifBlank { failed.entityType }
                        Text("• ${failed.entityType}: $errDesc", style = MaterialTheme.typography.bodySmall, color = MutedMossText)
                    }
                    OutlinedButton(
                        onClick = onRetryFailedSync,
                        enabled = state.isEffectivelyOnline,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorCrimson),
                        modifier = Modifier.fillMaxWidth().testTag("profile_retry_sync_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(l10n.get(R.string.retry_btn))
                    }
                }
            }
        }

        // Security & Diagnostics section
        if (pinChangedNotice) {
            Surface(
                color = SoftSageContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = l10n.get(R.string.pin_changed_success),
                    color = OnSageContainer,
                    modifier = Modifier.padding(12.dp).testTag("pin_changed_banner")
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = CrispSurface),
            border = BorderStroke(1.dp, SubtleBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { showChangePinDialog = true; pinChangedNotice = false },
                    modifier = Modifier.fillMaxWidth().testTag("change_pin_button")
                ) {
                    Icon(Icons.Rounded.Pin, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(l10n.get(R.string.change_pin_btn))
                }
                OutlinedButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth().testTag("diagnostics_button")
                ) {
                    Icon(Icons.Rounded.HealthAndSafety, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(l10n.get(R.string.diagnostics_btn))
                }
            }
        }

        if (state.isDemoMode) {
            // Quick role switch for testing
            Card(
                colors = CardDefaults.cardColors(containerColor = CrispSurface),
                border = BorderStroke(1.dp, SubtleBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(l10n.get(R.string.switch_account_btn), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { onSwitchDemoUser("farmer_lahcen") }, modifier = Modifier.weight(1f)) {
                            Text(l10n.get(R.string.role_farmer), maxLines = 1)
                        }
                        OutlinedButton(onClick = { onSwitchDemoUser("buyer_tafraout") }, modifier = Modifier.weight(1f)) {
                            Text(l10n.get(R.string.role_buyer), maxLines = 1)
                        }
                        OutlinedButton(onClick = { onSwitchDemoUser("admin_fellah") }, modifier = Modifier.weight(1f)) {
                            Text(l10n.get(R.string.role_admin), maxLines = 1)
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onLogout,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorCrimson),
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag("logout_button")
        ) {
            Icon(Icons.Rounded.Logout, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(l10n.get(R.string.logout_btn), style = MaterialTheme.typography.titleMedium)
        }
    }

    if (showChangePinDialog) {
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var dialogErrorKey by remember { mutableStateOf<String?>(null) }
        var isSaving by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSaving) showChangePinDialog = false },
            title = { Text(l10n.get(R.string.change_pin_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { currentPin = it.filter { c -> c.isDigit() }.take(PinValidator.MAX_LENGTH); dialogErrorKey = null },
                        label = { Text(l10n.get(R.string.current_pin_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("dialog_current_pin_input")
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(PinValidator.MAX_LENGTH); dialogErrorKey = null },
                        label = { Text(l10n.get(R.string.new_pin_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("dialog_new_pin_input")
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(PinValidator.MAX_LENGTH); dialogErrorKey = null },
                        label = { Text(l10n.get(R.string.confirm_new_pin_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("dialog_confirm_pin_input")
                    )
                    if (dialogErrorKey != null) {
                        Text(
                            text = resolveAuthErrorMessage(l10n, dialogErrorKey),
                            color = ErrorCrimson,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("change_pin_dialog_error")
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSaving = true
                        onChangePin(currentPin, newPin, confirmPin) { res ->
                            isSaving = false
                            if (res.isSuccess) {
                                showChangePinDialog = false
                                pinChangedNotice = true
                            } else {
                                dialogErrorKey = res.exceptionOrNull()?.message ?: "error_generic"
                            }
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier.testTag("save_new_pin_button")
                ) {
                    Text(l10n.get(R.string.save_btn))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showChangePinDialog = false },
                    enabled = !isSaving
                ) {
                    Text(l10n.get(R.string.cancel_btn))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: AppUiState,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val l10n = LocalL10n.current
    val maskedPhone = PhoneValidator.maskPhoneForDiagnostics(state.currentUser?.phone)
    val backendSummary = buildString {
        append(if (state.isRemoteConfigured) "Supabase HTTPS" else "Local Room")
        append(if (state.isDemoMode) " • DEMO_MODE" else " • PROD")
    }
    val sessionStatus = if (state.hasActiveSession) "Active (AES-256-GCM)" else "Local / None"
    val netStatus = if (state.isEffectivelyOnline) "Online" else "Offline"
    val syncStatus = "Pending: ${state.pendingSyncCount} • Failed: ${state.failedSyncItems.size}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(l10n.get(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("diagnostics_back_button")) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = l10n.get(R.string.back_btn))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmParchment)
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiagnosticRow(l10n.get(R.string.diag_phone_masked), maskedPhone, "diag_masked_phone")
            DiagnosticRow(l10n.get(R.string.diag_backend_mode), backendSummary, "diag_backend_mode")
            DiagnosticRow(l10n.get(R.string.diag_session_status), sessionStatus, "diag_session_status")
            DiagnosticRow(l10n.get(R.string.diag_network_status), netStatus, "diag_network_status")
            DiagnosticRow(l10n.get(R.string.diag_sync_status), syncStatus, "diag_sync_status")
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, tag: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CrispSurface),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MutedMossText)
            Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(tag))
        }
    }
}
