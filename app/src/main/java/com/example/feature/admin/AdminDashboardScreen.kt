package com.example.feature.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.data.*
import com.example.core.domain.PinValidator
import com.example.core.l10n.LocalL10n
import com.example.core.ui.AppUiState
import com.example.core.ui.OfferPhotoView
import com.example.core.ui.StatusPill
import com.example.core.ui.formatMad
import com.example.feature.auth.resolveAuthErrorMessage
import com.example.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminDashboardScreen(
    state: AppUiState,
    onApproveUser: (String) -> Unit,
    onRejectUser: (String, String) -> Unit,
    onResetUserPin: (String, String, (Result<String?>) -> Unit) -> Unit = { _, _, cb -> cb(Result.success(null)) },
    onAddProduct: (String, String, String, String, String, String) -> Unit,
    onSetReferencePrice: (String, String, String, Double) -> Unit,
    onApproveProposal: (ProductProposalEntity) -> Unit,
    onRejectProposal: (ProductProposalEntity) -> Unit,
    onResolveReport: (ReportEntity) -> Unit,
    onDismissError: () -> Unit = {}
) {
    val l10n = LocalL10n.current
    var selectedTab by remember { mutableStateOf(0) }
    var rejectTargetUser by remember { mutableStateOf<ProfileEntity?>(null) }
    var resetPinTargetUser by remember { mutableStateOf<ProfileEntity?>(null) }
    var resetPinBanner by remember { mutableStateOf<String?>(null) }
    var docPreviewProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    var showAddProductDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(WarmParchment)) {
        if (resetPinBanner != null) {
            Surface(
                color = SoftSageContainer,
                contentColor = OnSageContainer,
                modifier = Modifier.fillMaxWidth().testTag("admin_pin_reset_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(resetPinBanner!!, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { resetPinBanner = null }) {
                        Icon(Icons.Rounded.Close, contentDescription = l10n.get(R.string.close_btn))
                    }
                }
            }
        }
        if (state.syncErrorMessage != null) {
            Surface(
                color = ErrorContainerLight,
                contentColor = ErrorCrimson,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(state.syncErrorMessage, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = onDismissError) {
                        Icon(Icons.Rounded.Close, contentDescription = l10n.get(R.string.close_btn))
                    }
                }
            }
        }
        TabRow(selectedTabIndex = selectedTab, containerColor = CrispSurface) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(l10n.get(R.string.admin_tab_verifications)) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(l10n.get(R.string.admin_tab_catalog)) })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(l10n.get(R.string.admin_tab_prices)) })
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text(l10n.get(R.string.admin_tab_moderation)) })
        }

        when (selectedTab) {
            0 -> {
                val users = state.profiles.filter { it.role != UserRole.ADMIN }
                    .sortedBy { if (it.verificationStatus == VerificationStatus.PENDING) 0 else 1 }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(users, key = { it.id }) { profile ->
                        Card(colors = CardDefaults.cardColors(containerColor = CrispSurface), border = BorderStroke(1.dp, SubtleBorder)) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(profile.fullName, style = MaterialTheme.typography.titleMedium)
                                    val (lbl, bg, fg) = when (profile.verificationStatus) {
                                        VerificationStatus.VERIFIED -> Triple(l10n.get(R.string.status_verified), SoftSageContainer, OnSageContainer)
                                        VerificationStatus.REJECTED -> Triple(l10n.get(R.string.status_rejected), ErrorContainerLight, ErrorCrimson)
                                        else -> Triple(l10n.get(R.string.status_pending), SaffronContainer, OnSaffronContainer)
                                    }
                                    StatusPill(lbl, bg, fg)
                                }
                                Text("${profile.role} • ${profile.phone} • ${profile.city}", style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
                                if (profile.verificationDocPath.isNotBlank()) {
                                    OutlinedButton(onClick = { docPreviewProfile = profile }) {
                                        Icon(Icons.Rounded.Badge, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(l10n.get(R.string.view_verification_doc_btn))
                                    }
                                }
                                if (profile.verificationStatus != VerificationStatus.VERIFIED) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { onApproveUser(profile.id) },
                                            modifier = Modifier.testTag("approve_user_${profile.id}")
                                        ) {
                                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(l10n.get(R.string.approve_btn))
                                        }
                                        OutlinedButton(
                                            onClick = { rejectTargetUser = profile },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorCrimson)
                                        ) {
                                            Text(l10n.get(R.string.reject_btn))
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = { resetPinTargetUser = profile },
                                    modifier = Modifier.testTag("reset_pin_user_${profile.id}")
                                ) {
                                    Icon(Icons.Rounded.LockReset, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(l10n.get(R.string.reset_pin_btn))
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Button(onClick = { showAddProductDialog = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(l10n.get(R.string.add_product_btn))
                        }
                    }
                    if (state.productProposals.isNotEmpty()) {
                        item {
                            Text(l10n.get(R.string.proposed_products_section), style = MaterialTheme.typography.titleLarge)
                        }
                        items(state.productProposals, key = { it.id }) { prop ->
                            Card(colors = CardDefaults.cardColors(containerColor = CrispSurface), border = BorderStroke(1.dp, SubtleBorder)) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(prop.proposedName, style = MaterialTheme.typography.titleMedium)
                                        StatusPill(prop.status, SaffronContainer, OnSaffronContainer)
                                    }
                                    Text("${l10n.unitLabel(prop.suggestedUnit)} • ${prop.notes}", color = MutedMossText)
                                    if (prop.status == "pending") {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { onApproveProposal(prop) }) { Text(l10n.get(R.string.approve_btn)) }
                                            OutlinedButton(onClick = { onRejectProposal(prop) }) { Text(l10n.get(R.string.reject_btn)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(state.products, key = { it.id }) { prod ->
                        Card(colors = CardDefaults.cardColors(containerColor = CrispSurface), border = BorderStroke(1.dp, SubtleBorder)) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(l10n.localizedName(prod.nameDarija, prod.nameAr, prod.nameFr), style = MaterialTheme.typography.titleMedium)
                                    Text(prod.allowedUnitsCsv, style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
                                }
                                StatusPill(prod.categoryId.removePrefix("cat_"), SoftSageContainer, OnSageContainer)
                            }
                        }
                    }
                }
            }
            2 -> {
                var selProd by remember { mutableStateOf(state.products.firstOrNull()) }
                var priceInput by remember { mutableStateOf("") }
                val selUnit = selProd?.allowedUnitsCsv?.split(",")?.firstOrNull()?.trim() ?: "kg"
                val defaultRegionId = state.regions.firstOrNull { it.isDefault }?.id ?: "reg_souss_massa"

                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = CrispSurface), border = BorderStroke(1.dp, SubtleBorder)) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(l10n.get(R.string.set_ref_price_btn), style = MaterialTheme.typography.titleMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    state.products.take(8).forEach { p ->
                                        FilterChip(
                                            selected = selProd?.id == p.id,
                                            onClick = { selProd = p },
                                            label = { Text(l10n.localizedName(p.nameDarija, p.nameAr, p.nameFr)) }
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = priceInput,
                                    onValueChange = { priceInput = it },
                                    label = { Text("${l10n.get(R.string.wholesale_ref_price)} (${l10n.unitLabel(selUnit)})") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        val p = priceInput.replace(',', '.').toDoubleOrNull() ?: return@Button
                                        val prod = selProd ?: return@Button
                                        if (p > 0) {
                                            onSetReferencePrice(prod.id, defaultRegionId, selUnit, p)
                                            priceInput = ""
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(l10n.get(R.string.save_btn))
                                }
                            }
                        }
                    }
                    items(state.referencePrices.takeLast(12).reversed(), key = { it.id }) { ref ->
                        val prod = state.products.firstOrNull { it.id == ref.productId }
                        val prodName = prod?.let { l10n.localizedName(it.nameDarija, it.nameAr, it.nameFr) } ?: ref.productId
                        Card(colors = CardDefaults.cardColors(containerColor = CrispSurface), border = BorderStroke(1.dp, SubtleBorder)) {
                            Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(prodName, style = MaterialTheme.typography.titleMedium)
                                Text("${formatMad(ref.priceMad)} MAD / ${l10n.unitLabel(ref.unit)}", color = ForestGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            3 -> {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(l10n.get(R.string.reports_section), style = MaterialTheme.typography.titleLarge)
                    }
                    items(state.reports, key = { it.id }) { rep ->
                        Card(colors = CardDefaults.cardColors(containerColor = CrispSurface), border = BorderStroke(1.dp, SubtleBorder)) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${rep.targetType}: ${rep.targetId}", style = MaterialTheme.typography.titleMedium)
                                    StatusPill(rep.status, if (rep.status == "open") ErrorContainerLight else SoftSageContainer, if (rep.status == "open") ErrorCrimson else OnSageContainer)
                                }
                                Text(rep.reason)
                                if (rep.status == "open") {
                                    Button(onClick = { onResolveReport(rep) }) { Text(l10n.get(R.string.resolve_btn)) }
                                }
                            }
                        }
                    }
                    items(state.deals, key = { it.id }) { deal ->
                        val prod = state.products.firstOrNull { it.id == deal.productId }
                        Card(colors = CardDefaults.cardColors(containerColor = CrispSurface), border = BorderStroke(1.dp, SubtleBorder)) {
                            Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(prod?.let { l10n.localizedName(it.nameDarija, it.nameAr, it.nameFr) } ?: deal.productId)
                                Text("${deal.status} • ${formatMad(deal.agreedPrice ?: deal.proposedPrice)} MAD", color = ForestGreen)
                            }
                        }
                    }
                }
            }
        }
    }

    if (rejectTargetUser != null) {
        val target = rejectTargetUser!!
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectTargetUser = null },
            title = { Text(l10n.get(R.string.reject_btn)) },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(l10n.get(R.string.reject_reason_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRejectUser(target.id, reason.ifBlank { "Incomplete info" })
                    rejectTargetUser = null
                }) { Text(l10n.get(R.string.confirm_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { rejectTargetUser = null }) { Text(l10n.get(R.string.cancel_btn)) }
            }
        )
    }

    if (showAddProductDialog) {
        var nameDarija by remember { mutableStateOf("") }
        var nameFr by remember { mutableStateOf("") }
        var catId by remember { mutableStateOf(state.categories.firstOrNull()?.id ?: "cat_veg") }
        var units by remember { mutableStateOf("kg,crate") }
        AlertDialog(
            onDismissRequest = { showAddProductDialog = false },
            title = { Text(l10n.get(R.string.add_product_btn)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = nameDarija, onValueChange = { nameDarija = it }, label = { Text("الدارجة / العربية") }, singleLine = true)
                    OutlinedTextField(value = nameFr, onValueChange = { nameFr = it }, label = { Text("Français") }, singleLine = true)
                    OutlinedTextField(value = units, onValueChange = { units = it }, label = { Text("Units (kg,ton,liter,crate)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (nameDarija.isNotBlank()) {
                        onAddProduct(
                            catId,
                            nameDarija,
                            nameDarija,
                            nameFr.ifBlank { nameDarija },
                            units,
                            """[{"key":"grade","labelDarija":"الجودة","labelAr":"الجودة","labelFr":"Qualité","type":"select","required":true,"options":["A","B","C"]}]"""
                        )
                        showAddProductDialog = false
                    }
                }) { Text(l10n.get(R.string.save_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddProductDialog = false }) { Text(l10n.get(R.string.cancel_btn)) }
            }
        )
    }

    if (docPreviewProfile != null) {
        val target = docPreviewProfile!!
        AlertDialog(
            onDismissRequest = { docPreviewProfile = null },
            title = { Text(target.fullName) },
            text = {
                OfferPhotoView(
                    photoToken = target.verificationDocPath,
                    contentDescription = l10n.get(R.string.view_verification_doc_btn),
                    defaultBucket = "verification-docs",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(MaterialTheme.shapes.medium)
                )
            },
            confirmButton = {
                TextButton(onClick = { docPreviewProfile = null }) {
                    Text(l10n.get(R.string.close_btn))
                }
            }
        )
    }

    if (resetPinTargetUser != null) {
        val target = resetPinTargetUser!!
        var tempPin by remember { mutableStateOf("") }
        var pinDialogErrorKey by remember { mutableStateOf<String?>(null) }
        var isSubmitting by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSubmitting) resetPinTargetUser = null },
            title = { Text("${l10n.get(R.string.reset_pin_dialog_title)} — ${target.fullName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(l10n.get(R.string.reset_pin_call_warning), style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
                    OutlinedTextField(
                        value = tempPin,
                        onValueChange = { tempPin = it.filter { c -> c.isDigit() }.take(PinValidator.MAX_LENGTH); pinDialogErrorKey = null },
                        label = { Text(l10n.get(R.string.temp_pin_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("admin_temp_pin_input")
                    )
                    if (pinDialogErrorKey != null) {
                        Text(
                            text = resolveAuthErrorMessage(l10n, pinDialogErrorKey),
                            color = ErrorCrimson,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("admin_temp_pin_error")
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSubmitting = true
                        onResetUserPin(target.id, tempPin) { res ->
                            isSubmitting = false
                            if (res.isSuccess) {
                                resetPinTargetUser = null
                                resetPinBanner = "${l10n.get(R.string.pin_reset_success)} (${target.fullName})"
                            } else {
                                pinDialogErrorKey = res.exceptionOrNull()?.message ?: "error_generic"
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier.testTag("confirm_admin_reset_pin_button")
                ) {
                    Text(l10n.get(R.string.confirm_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetPinTargetUser = null }, enabled = !isSubmitting) {
                    Text(l10n.get(R.string.cancel_btn))
                }
            }
        )
    }
}
