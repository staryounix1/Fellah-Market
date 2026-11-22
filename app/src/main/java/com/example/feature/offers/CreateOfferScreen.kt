package com.example.feature.offers

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.data.*
import com.example.core.domain.*
import com.example.core.l10n.LocalL10n
import com.example.core.media.VoiceNoteManager
import com.example.core.media.VoiceUiState
import com.example.core.ui.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CreateOfferScreen(
    state: AppUiState,
    editingOffer: OfferEntity?,
    voiceState: VoiceUiState,
    voiceManager: VoiceNoteManager,
    onCompressPhoto: suspend (android.net.Uri) -> String?,
    onGetMarketSummary: (String, String?, String?) -> ProductMarketSummary,
    onSaveOffer: (
        editingOfferId: String?,
        product: ProductEntity,
        city: String,
        regionId: String,
        quantity: Double,
        unit: String,
        askingPrice: Double,
        readyDateEpochDay: Long,
        attributesJson: String,
        photoPaths: List<String>,
        voicePath: String,
        voiceDuration: Int
    ) -> Unit,
    onSubmitProposal: (String, String, String, String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val l10n = LocalL10n.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = state.currentUser ?: return

    var selectedCategoryId by remember {
        mutableStateOf(editingOffer?.categoryId ?: state.categories.firstOrNull()?.id ?: "cat_veg")
    }
    val categoryProducts = remember(selectedCategoryId, state.products) {
        state.products.filter { it.categoryId == selectedCategoryId }
    }
    var selectedProductId by remember {
        mutableStateOf(editingOffer?.productId ?: categoryProducts.firstOrNull()?.id ?: state.products.firstOrNull()?.id.orEmpty())
    }
    val selectedProduct = state.products.firstOrNull { it.id == selectedProductId } ?: categoryProducts.firstOrNull()

    val allowedUnits = remember(selectedProduct) {
        selectedProduct?.allowedUnitsCsv?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("kg")
    }
    var selectedUnit by remember(selectedProduct?.id) {
        mutableStateOf(editingOffer?.unit?.takeIf { it in allowedUnits } ?: allowedUnits.first())
    }

    val schema = remember(selectedProduct?.id) {
        DynamicFormSchemaCodec.parseSchema(selectedProduct?.attributeSchemaJson.orEmpty())
    }
    var attrValues by remember(selectedProduct?.id) {
        val initial = if (editingOffer != null && editingOffer.productId == selectedProduct?.id) {
            DynamicFormSchemaCodec.parseValues(editingOffer.attributesJson)
        } else {
            // Pre-fill first option on select fields for ultra-fast <60s flow
            buildMap {
                schema.forEach { f ->
                    if (f.type == AttributeFieldType.SELECT && f.required && f.options.isNotEmpty()) {
                        put(f.key, f.options.first())
                    }
                }
            }
        }
        mutableStateOf(initial)
    }

    var quantityText by remember { mutableStateOf(editingOffer?.quantity?.let { formatMad(it) } ?: "50") }
    var priceText by remember { mutableStateOf(editingOffer?.askingPrice?.let { formatMad(it) } ?: "") }
    val today = remember { LocalDate.now().toEpochDay() }
    var readyEpochDay by remember { mutableStateOf(editingOffer?.readyDateEpochDay ?: today) }

    val defaultRegion = state.regions.firstOrNull { it.id == user.regionId } ?: state.regions.firstOrNull()
    val cities = remember(defaultRegion) {
        defaultRegion?.citiesCsv?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf(user.city)
    }
    var selectedCity by remember { mutableStateOf(editingOffer?.city ?: user.city) }

    var photoPaths by remember {
        mutableStateOf(
            editingOffer?.photoUrisCsv?.split(",")?.filter { it.isNotBlank() && !it.startsWith("drawable:") } ?: emptyList()
        )
    }
    var voicePath by remember { mutableStateOf(editingOffer?.voiceNotePath.orEmpty()) }
    var voiceDuration by remember { mutableStateOf(editingOffer?.voiceDurationSec ?: 0) }
    var micPermissionDenied by remember { mutableStateOf(false) }

    var validationErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showProposalDialog by remember { mutableStateOf(false) }
    var proposalSentSnackbar by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris ->
        scope.launch {
            val compressed = uris.mapNotNull { onCompressPhoto(it) }
            photoPaths = (photoPaths + compressed).take(4)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micPermissionDenied = false
            voiceManager.startRecording { path, sec ->
                voicePath = path
                voiceDuration = sec
            }
        } else {
            micPermissionDenied = true
        }
    }

    val marketSummary = remember(selectedProduct?.id, user.regionId, selectedUnit) {
        selectedProduct?.let { onGetMarketSummary(it.id, user.regionId, selectedUnit) }
    }
    val parsedPrice = priceText.replace(',', '.').toDoubleOrNull()
    val priceHint = PriceAnalytics.evaluateAskingPriceHint(
        askingPrice = parsedPrice,
        marketAverage = marketSummary?.averagePrice,
        sampleCount = marketSummary?.sampleCount ?: 0
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(l10n.get(R.string.create_offer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.Close, contentDescription = l10n.get(R.string.close_btn))
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (proposalSentSnackbar) {
                Surface(color = SoftSageContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = l10n.get(R.string.proposal_sent_msg),
                        color = OnSageContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // STEP 1: Category + Product
            Text(l10n.get(R.string.step_select_product), style = MaterialTheme.typography.titleLarge, color = ForestGreen)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.categories.forEach { cat ->
                    FilterChip(
                        selected = cat.id == selectedCategoryId,
                        onClick = {
                            selectedCategoryId = cat.id
                            val firstProd = state.products.firstOrNull { it.categoryId == cat.id }
                            if (firstProd != null) selectedProductId = firstProd.id
                        },
                        label = { Text(l10n.localizedName(cat.nameDarija, cat.nameAr, cat.nameFr)) }
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categoryProducts.forEach { prod ->
                    val isSel = prod.id == selectedProduct?.id
                    FilterChip(
                        selected = isSel,
                        onClick = { selectedProductId = prod.id },
                        label = { Text(l10n.localizedName(prod.nameDarija, prod.nameAr, prod.nameFr), fontWeight = FontWeight.Bold) },
                        leadingIcon = if (isSel) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }

            TextButton(onClick = { showProposalDialog = true }) {
                Icon(Icons.Rounded.AddCircleOutline, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(l10n.get(R.string.propose_other_product))
            }

            // STEP 2: Quantity, Unit, Price + Dynamic JSON-schema attributes
            Text(l10n.get(R.string.step_offer_details), style = MaterialTheme.typography.titleLarge, color = ForestGreen)

            Card(
                colors = CardDefaults.cardColors(containerColor = CrispSurface),
                border = BorderStroke(1.dp, SubtleBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = quantityText,
                            onValueChange = { quantityText = it },
                            label = { Text(l10n.get(R.string.quantity_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = "quantity" in validationErrors,
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("offer_quantity_input")
                        )
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = { priceText = it },
                            label = { Text(l10n.get(R.string.asking_price_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = "askingPrice" in validationErrors,
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("offer_price_input")
                        )
                    }

                    // Price transparency hint
                    if (priceHint != PriceHintLevel.NONE && marketSummary?.averagePrice != null) {
                        val avgStr = formatMad(marketSummary.averagePrice)
                        val (bg, fg, msg) = when (priceHint) {
                            PriceHintLevel.ABOVE_MARKET -> Triple(
                                WarmApricotContainer,
                                OnApricotContainer,
                                l10n.format(R.string.price_hint_above, avgStr)
                            )
                            PriceHintLevel.BELOW_MARKET -> Triple(
                                SaffronContainer,
                                OnSaffronContainer,
                                l10n.format(R.string.price_hint_below, avgStr)
                            )
                            PriceHintLevel.FAIR_MARKET -> Triple(
                                SoftSageContainer,
                                OnSageContainer,
                                l10n.format(R.string.price_hint_good, avgStr)
                            )
                            PriceHintLevel.NONE -> Triple(SoftSageContainer, OnSageContainer, "")
                        }
                        Surface(color = bg, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Insights, contentDescription = null, tint = fg)
                                Text(msg, color = fg, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    Text(l10n.get(R.string.unit_label), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        allowedUnits.forEach { u ->
                            FilterChip(
                                selected = selectedUnit == u,
                                onClick = { selectedUnit = u },
                                label = { Text(l10n.unitLabel(u)) }
                            )
                        }
                    }

                    // Dynamic JSON schema fields
                    schema.forEach { field ->
                        val label = field.label(l10n.language) + if (field.required) " *" else ""
                        val hasError = field.key in validationErrors
                        when (field.type) {
                            AttributeFieldType.SELECT -> {
                                Text(label, style = MaterialTheme.typography.titleMedium, color = if (hasError) ErrorCrimson else InkCharcoal)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    field.options.forEach { opt ->
                                        FilterChip(
                                            selected = attrValues[field.key] == opt,
                                            onClick = { attrValues = attrValues + (field.key to opt) },
                                            label = { Text(opt) }
                                        )
                                    }
                                }
                            }
                            AttributeFieldType.NUMBER -> {
                                OutlinedTextField(
                                    value = attrValues[field.key].orEmpty(),
                                    onValueChange = { attrValues = attrValues + (field.key to it) },
                                    label = { Text(label) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    isError = hasError,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            AttributeFieldType.BOOLEAN -> {
                                val checked = attrValues[field.key] == "true"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .background(SoftFieldSurface)
                                        .clickable { attrValues = attrValues + (field.key to (!checked).toString()) }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                    Switch(checked = checked, onCheckedChange = { attrValues = attrValues + (field.key to it.toString()) })
                                }
                            }
                            AttributeFieldType.TEXT -> {
                                OutlinedTextField(
                                    value = attrValues[field.key].orEmpty(),
                                    onValueChange = { attrValues = attrValues + (field.key to it) },
                                    label = { Text(label) },
                                    isError = hasError,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Text(l10n.get(R.string.ready_date_label), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            0L to R.string.today_label,
                            1L to R.string.tomorrow_label,
                            3L to R.string.in_3_days_label,
                            7L to R.string.in_7_days_label
                        ).forEach { (offset, resId) ->
                            val target = today + offset
                            FilterChip(
                                selected = readyEpochDay == target,
                                onClick = { readyEpochDay = target },
                                label = { Text(l10n.get(resId)) }
                            )
                        }
                    }

                    Text(l10n.get(R.string.city_label), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cities.forEach { city ->
                            FilterChip(
                                selected = selectedCity == city,
                                onClick = { selectedCity = city },
                                label = { Text(city) }
                            )
                        }
                    }
                }
            }

            // STEP 3: Photos (1-4 via Photo Picker) & Optional 60s Voice Note
            Text(l10n.get(R.string.step_media_voice), style = MaterialTheme.typography.titleLarge, color = ForestGreen)

            Card(
                colors = CardDefaults.cardColors(containerColor = CrispSurface),
                border = BorderStroke(1.dp, SubtleBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(l10n.get(R.string.photos_label), style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        photoPaths.forEachIndexed { index, path ->
                            Box(modifier = Modifier.size(88.dp)) {
                                OfferPhotoView(
                                    photoToken = path,
                                    contentDescription = "Photo ${index + 1}",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(MaterialTheme.shapes.small)
                                )
                                IconButton(
                                    onClick = { photoPaths = photoPaths.filterIndexed { i, _ -> i != index } },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(28.dp)
                                        .background(InkCharcoal.copy(alpha = 0.7f), RoundedCornerShape(50))
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = null, tint = CrispSurface, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        if (photoPaths.size < 4) {
                            OutlinedButton(
                                onClick = {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.height(88.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Rounded.AddAPhoto, contentDescription = null)
                                    Text(l10n.get(R.string.add_photo_btn))
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = SubtleBorder)

                    Text(l10n.get(R.string.voice_note_label), style = MaterialTheme.typography.titleMedium)
                    if (micPermissionDenied) {
                        Text(l10n.get(R.string.mic_permission_needed), color = ErrorCrimson, style = MaterialTheme.typography.bodyMedium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!voiceState.isRecording) {
                            FilledTonalButton(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        voiceManager.startRecording { path, sec ->
                                            voicePath = path
                                            voiceDuration = sec
                                        }
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier.height(50.dp)
                            ) {
                                Icon(Icons.Rounded.Mic, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(l10n.get(R.string.record_voice_btn))
                            }
                        } else {
                            Button(
                                onClick = {
                                    val res = voiceManager.stopRecording()
                                    if (res != null) {
                                        voicePath = res.first
                                        voiceDuration = res.second
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = HarvestTerracotta),
                                modifier = Modifier.height(50.dp)
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("${l10n.get(R.string.stop_voice_btn)} (${voiceState.recordedSeconds}s / 60s)")
                            }
                        }

                        if (voicePath.isNotBlank() && !voiceState.isRecording) {
                            OutlinedButton(
                                onClick = { voiceManager.togglePlayback(voicePath) },
                                modifier = Modifier.height(50.dp)
                            ) {
                                Icon(
                                    if (voiceState.isPlaying && voiceState.playingPath == voicePath) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("${voiceDuration}s")
                            }
                            IconButton(onClick = {
                                voiceManager.stopPlayback()
                                voicePath = ""
                                voiceDuration = 0
                            }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = l10n.get(R.string.delete_voice_btn), tint = ErrorCrimson)
                            }
                        }
                    }
                }
            }

            if (validationErrors.isNotEmpty()) {
                Text(l10n.get(R.string.required_field), color = ErrorCrimson, style = MaterialTheme.typography.bodyLarge)
            }

            Button(
                onClick = {
                    val prod = selectedProduct ?: return@Button
                    val qty = quantityText.replace(',', '.').toDoubleOrNull()
                    val price = priceText.replace(',', '.').toDoubleOrNull()
                    val validation = DynamicFormSchemaCodec.validate(schema, attrValues, qty, price)
                    if (!validation.isValid) {
                        validationErrors = validation.fieldErrors
                    } else {
                        onSaveOffer(
                            editingOffer?.id,
                            prod,
                            selectedCity,
                            defaultRegion?.id ?: user.regionId,
                            qty!!,
                            selectedUnit,
                            price!!,
                            readyEpochDay,
                            DynamicFormSchemaCodec.encodeValues(attrValues),
                            photoPaths,
                            voicePath,
                            voiceDuration
                        )
                        onBack()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .testTag("publish_offer_button")
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.isEffectivelyOnline) l10n.get(R.string.publish_offer_btn) else l10n.get(R.string.save_offline_btn),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }

    if (showProposalDialog) {
        ProposeProductDialog(
            categories = state.categories,
            onDismiss = { showProposalDialog = false },
            onSubmit = { name, catId, unit, notes ->
                onSubmitProposal(name, catId, unit, notes)
                showProposalDialog = false
                proposalSentSnackbar = true
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProposeProductDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String) -> Unit
) {
    val l10n = LocalL10n.current
    var name by remember { mutableStateOf("") }
    var catId by remember { mutableStateOf(categories.firstOrNull()?.id ?: "cat_veg") }
    var unit by remember { mutableStateOf("kg") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(l10n.get(R.string.propose_product_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(l10n.get(R.string.proposed_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("kg", "ton", "liter", "crate", "piece", "head").forEach { u ->
                        FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(l10n.unitLabel(u)) })
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(l10n.get(R.string.proposed_notes_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSubmit(name, catId, unit, notes) },
                enabled = name.isNotBlank()
            ) {
                Text(l10n.get(R.string.submit_proposal_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(l10n.get(R.string.cancel_btn)) }
        }
    )
}
