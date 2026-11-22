package com.example.feature.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.data.*
import com.example.core.domain.PinValidator
import com.example.core.l10n.AppLanguage
import com.example.core.l10n.L10nStrings
import com.example.core.l10n.LocalL10n
import com.example.core.media.ImageCompressor
import com.example.core.ui.AuthUiState
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun LanguageSelectionScreen(
    currentLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onContinue: () -> Unit
) {
    val l10n = LocalL10n.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmParchment)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Souss-Massa Hero Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(MaterialTheme.shapes.large)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_souss_hero),
                contentDescription = l10n.get(R.string.app_name),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, DeepOlive.copy(alpha = 0.88f))
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp)
            ) {
                Text(
                    text = l10n.get(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = CrispSurface
                )
                Text(
                    text = l10n.get(R.string.tagline_onboarding),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SoftSageContainer
                )
            }
        }

        Text(
            text = l10n.get(R.string.choose_language),
            style = MaterialTheme.typography.headlineSmall,
            color = InkCharcoal
        )

        val options = listOf(
            Triple(AppLanguage.DARIJA, R.string.lang_darija, "🇲🇦 الدارجة (الأساسية)"),
            Triple(AppLanguage.ARABIC, R.string.lang_arabic, "العربية الفصحى"),
            Triple(AppLanguage.FRENCH, R.string.lang_french, "Français (Pro)")
        )

        options.forEach { (lang, resId, badge) ->
            val selected = currentLanguage == lang
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (selected) SoftSageContainer else CrispSurface,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) ForestGreen else SubtleBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("lang_option_${lang.code}")
                    .clickable { onSelectLanguage(lang) }
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = l10n.get(resId),
                            style = MaterialTheme.typography.titleLarge,
                            color = if (selected) OnSageContainer else InkCharcoal
                        )
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedMossText
                        )
                    }
                    Icon(
                        imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) ForestGreen else MutedMossText,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .testTag("language_continue_button"),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(text = l10n.get(R.string.continue_btn), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Rounded.ArrowForward, contentDescription = null)
        }
    }
}

fun resolveAuthErrorMessage(l10n: L10nStrings, errorKey: String?, lockoutSeconds: Int = 0): String {
    return when (errorKey) {
        "invalid_phone" -> l10n.get(R.string.invalid_phone)
        "invalid_credentials" -> l10n.get(R.string.invalid_credentials_error)
        "phone_already_registered" -> l10n.get(R.string.phone_already_registered_error)
        "pin_too_short" -> l10n.get(R.string.pin_too_short_error)
        "pin_weak_repeated" -> l10n.get(R.string.pin_weak_repeated_error)
        "pin_weak_sequence" -> l10n.get(R.string.pin_weak_sequence_error)
        "pin_mismatch" -> l10n.get(R.string.pin_mismatch_error)
        "wrong_current_pin" -> l10n.get(R.string.wrong_current_pin_error)
        "account_locked" -> {
            val mins = lockoutSeconds / 60
            val secs = lockoutSeconds % 60
            l10n.format(R.string.account_locked_error, mins, secs)
        }
        "rate_limited" -> l10n.get(R.string.auth_rate_limited_error)
        "no_network" -> l10n.get(R.string.network_error_msg)
        "session_expired" -> l10n.get(R.string.session_expired_error)
        "not_configured" -> l10n.get(R.string.supabase_not_configured_error)
        "forbidden" -> l10n.get(R.string.forbidden_error)
        else -> l10n.get(R.string.error_generic)
    }
}

@Composable
fun PhonePinAuthScreen(
    authState: AuthUiState,
    isDemoMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onPhoneChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onConfirmPinChange: (String) -> Unit,
    onSubmitAuth: () -> Unit,
    onQuickDemoLogin: (String) -> Unit,
    onChangeLanguageClick: () -> Unit
) {
    val l10n = LocalL10n.current
    var showPin by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmParchment)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    painter = painterResource(id = R.drawable.ic_fellah_logo),
                    contentDescription = l10n.get(R.string.app_name),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Text(l10n.get(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = ForestGreen)
            }
            OutlinedButton(onClick = onChangeLanguageClick) {
                Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(l10n.language.code.uppercase())
            }
        }

        Text(text = l10n.get(R.string.auth_title), style = MaterialTheme.typography.headlineMedium)
        Text(text = l10n.get(R.string.auth_subtitle), style = MaterialTheme.typography.bodyLarge, color = MutedMossText)

        // Login vs Sign Up mode selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val isLogin = !authState.isSignUpMode
            Button(
                onClick = { onModeChange(false) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLogin) ForestGreen else CrispSurface,
                    contentColor = if (isLogin) CrispSurface else InkCharcoal
                ),
                border = if (isLogin) null else BorderStroke(1.dp, SubtleBorder),
                modifier = Modifier.weight(1f).height(48.dp).testTag("login_tab_button")
            ) {
                Text(l10n.get(R.string.login_tab))
            }
            Button(
                onClick = { onModeChange(true) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isLogin) ForestGreen else CrispSurface,
                    contentColor = if (!isLogin) CrispSurface else InkCharcoal
                ),
                border = if (!isLogin) null else BorderStroke(1.dp, SubtleBorder),
                modifier = Modifier.weight(1f).height(48.dp).testTag("signup_tab_button")
            ) {
                Text(l10n.get(R.string.signup_tab))
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = CrispSurface),
            border = BorderStroke(1.dp, SubtleBorder)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = authState.phoneInput,
                    onValueChange = onPhoneChange,
                    label = { Text(l10n.get(R.string.phone_label)) },
                    placeholder = { Text(l10n.get(R.string.phone_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Phone, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("phone_input")
                )

                OutlinedTextField(
                    value = authState.pinInput,
                    onValueChange = onPinChange,
                    label = { Text(l10n.get(R.string.pin_label)) },
                    leadingIcon = { Icon(Icons.Rounded.Pin, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showPin = !showPin }) {
                            Icon(
                                imageVector = if (showPin) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pin_input")
                )

                if (authState.isSignUpMode) {
                    OutlinedTextField(
                        value = authState.confirmPinInput,
                        onValueChange = onConfirmPinChange,
                        label = { Text(l10n.get(R.string.confirm_pin_label)) },
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                        visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("confirm_pin_input")
                    )

                    Text(
                        text = l10n.get(R.string.pin_rules_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedMossText
                    )
                }

                if (authState.errorKey != null || authState.lockoutRemainingSeconds > 0) {
                    val key = if (authState.lockoutRemainingSeconds > 0 && !authState.isSignUpMode) {
                        "account_locked"
                    } else {
                        authState.errorKey
                    }
                    if (key != null) {
                        val errText = resolveAuthErrorMessage(l10n, key, authState.lockoutRemainingSeconds)
                        Surface(
                            color = ErrorContainerLight,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errText,
                                color = ErrorCrimson,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(10.dp).testTag("auth_error_text")
                            )
                        }
                    }
                }

                val isLockedLogin = !authState.isSignUpMode && authState.lockoutRemainingSeconds > 0
                Button(
                    onClick = onSubmitAuth,
                    enabled = !authState.isLoading && !isLockedLogin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("auth_submit_button")
                ) {
                    if (authState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = CrispSurface,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (authState.isSignUpMode) Icons.Rounded.PersonAdd else Icons.Rounded.Login,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (authState.isSignUpMode) l10n.get(R.string.signup_btn) else l10n.get(R.string.login_btn),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }

        if (isDemoMode) {
            HorizontalDivider(color = SubtleBorder)

            Text(
                text = l10n.get(R.string.quick_demo_accounts),
                style = MaterialTheme.typography.titleMedium,
                color = MutedMossText
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DemoAccountButton(
                    label = l10n.get(R.string.demo_farmer),
                    icon = Icons.Rounded.Agriculture,
                    containerColor = SoftSageContainer,
                    contentColor = OnSageContainer,
                    testTag = "demo_farmer_button",
                    onClick = { onQuickDemoLogin("farmer_lahcen") }
                )
                DemoAccountButton(
                    label = l10n.get(R.string.demo_buyer),
                    icon = Icons.Rounded.Storefront,
                    containerColor = WarmApricotContainer,
                    contentColor = OnApricotContainer,
                    testTag = "demo_buyer_button",
                    onClick = { onQuickDemoLogin("buyer_tafraout") }
                )
                DemoAccountButton(
                    label = l10n.get(R.string.demo_admin),
                    icon = Icons.Rounded.AdminPanelSettings,
                    containerColor = SaffronContainer,
                    contentColor = OnSaffronContainer,
                    testTag = "demo_admin_button",
                    onClick = { onQuickDemoLogin("admin_fellah") }
                )
            }
        }
    }
}

@Composable
fun ForceChangePinScreen(
    onChangePin: (String, String, String, (Result<String?>) -> Unit) -> Unit,
    onLogout: () -> Unit
) {
    val l10n = LocalL10n.current
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorKey by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmParchment)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.LockReset,
            contentDescription = null,
            tint = SaffronGold,
            modifier = Modifier.size(48.dp)
        )
        Text(text = l10n.get(R.string.force_change_pin_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = l10n.get(R.string.force_change_pin_subtitle), style = MaterialTheme.typography.bodyLarge, color = MutedMossText)

        Card(
            colors = CardDefaults.cardColors(containerColor = CrispSurface),
            border = BorderStroke(1.dp, SubtleBorder)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { currentPin = it.filter { c -> c.isDigit() }.take(PinValidator.MAX_LENGTH); errorKey = null },
                    label = { Text(l10n.get(R.string.current_pin_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("force_current_pin_input")
                )
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(PinValidator.MAX_LENGTH); errorKey = null },
                    label = { Text(l10n.get(R.string.new_pin_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("force_new_pin_input")
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(PinValidator.MAX_LENGTH); errorKey = null },
                    label = { Text(l10n.get(R.string.confirm_new_pin_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("force_confirm_pin_input")
                )
                Text(l10n.get(R.string.pin_rules_hint), style = MaterialTheme.typography.bodySmall, color = MutedMossText)

                if (errorKey != null) {
                    Text(
                        text = resolveAuthErrorMessage(l10n, errorKey),
                        color = ErrorCrimson,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("force_pin_error_text")
                    )
                }

                Button(
                    onClick = {
                        isSaving = true
                        onChangePin(currentPin, newPin, confirmPin) { res ->
                            isSaving = false
                            if (res.isFailure) {
                                errorKey = res.exceptionOrNull()?.message ?: "error_generic"
                            }
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("force_change_pin_button")
                ) {
                    Text(l10n.get(R.string.change_pin_btn), style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("force_logout_button")
        ) {
            Text(l10n.get(R.string.logout_btn))
        }
    }
}

@Composable
private fun DemoAccountButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag(testTag)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Login, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoleAndProfileSetupScreen(
    regions: List<RegionEntity>,
    categories: List<CategoryEntity>,
    initialProfile: ProfileEntity? = null,
    onSubmit: (
        fullName: String,
        role: String,
        regionId: String,
        city: String,
        farmOrCoopName: String,
        businessName: String,
        businessType: String,
        selectedCategoryIds: List<String>,
        verificationDocPath: String
    ) -> Unit,
    onCancel: () -> Unit
) {
    val l10n = LocalL10n.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf(initialProfile?.role ?: UserRole.FARMER) }
    var fullName by remember { mutableStateOf(initialProfile?.fullName ?: "") }
    var farmOrCoopName by remember { mutableStateOf("") }
    var businessName by remember { mutableStateOf("") }
    var businessType by remember { mutableStateOf("restaurant") }
    var verificationDocPath by remember { mutableStateOf(initialProfile?.verificationDocPath ?: "") }

    val docPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val compressed = ImageCompressor.compressAndSave(context, uri)
                if (compressed != null) verificationDocPath = compressed
            }
        }
    }

    val defaultRegion = regions.firstOrNull { it.isDefault } ?: regions.firstOrNull()
    var selectedRegionId by remember(regions) { mutableStateOf(initialProfile?.regionId ?: defaultRegion?.id ?: "reg_souss_massa") }
    val activeRegion = regions.firstOrNull { it.id == selectedRegionId } ?: defaultRegion
    val cities = remember(activeRegion) { activeRegion?.citiesCsv?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("أكادير") }
    var selectedCity by remember(cities) { mutableStateOf(initialProfile?.city?.takeIf { it in cities } ?: cities.first()) }
    var selectedCats by remember { mutableStateOf(setOf(categories.firstOrNull()?.id ?: "cat_veg")) }
    var showError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmParchment)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(l10n.get(R.string.profile_setup_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onCancel) { Text(l10n.get(R.string.back_btn)) }
        }

        Text(l10n.get(R.string.choose_role_title), style = MaterialTheme.typography.titleMedium)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoleChoiceCard(
                title = l10n.get(R.string.role_farmer),
                desc = l10n.get(R.string.role_farmer_desc),
                icon = Icons.Rounded.Agriculture,
                selected = role == UserRole.FARMER,
                modifier = Modifier.weight(1f).testTag("role_farmer_card"),
                onClick = { role = UserRole.FARMER }
            )
            RoleChoiceCard(
                title = l10n.get(R.string.role_buyer),
                desc = l10n.get(R.string.role_buyer_desc),
                icon = Icons.Rounded.Storefront,
                selected = role == UserRole.BUYER,
                modifier = Modifier.weight(1f).testTag("role_buyer_card"),
                onClick = { role = UserRole.BUYER }
            )
        }

        OutlinedTextField(
            value = fullName,
            onValueChange = { fullName = it; showError = false },
            label = { Text(l10n.get(R.string.full_name_label)) },
            isError = showError && fullName.isBlank(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("full_name_input")
        )

        if (role == UserRole.FARMER) {
            OutlinedTextField(
                value = farmOrCoopName,
                onValueChange = { farmOrCoopName = it },
                label = { Text(l10n.get(R.string.farm_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedTextField(
                value = businessName,
                onValueChange = { businessName = it; showError = false },
                label = { Text(l10n.get(R.string.business_name_label)) },
                isError = showError && businessName.isBlank(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(l10n.get(R.string.business_type_label), style = MaterialTheme.typography.titleMedium)
            val bizTypes = listOf(
                "restaurant" to R.string.biz_restaurant,
                "hotel" to R.string.biz_hotel,
                "grocery" to R.string.biz_grocery,
                "wholesaler" to R.string.biz_wholesaler,
                "cooperative" to R.string.biz_cooperative
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bizTypes.forEach { (key, resId) ->
                    FilterChip(
                        selected = businessType == key,
                        onClick = { businessType = key },
                        label = { Text(l10n.get(resId)) }
                    )
                }
            }
        }

        Text(l10n.get(R.string.region_label), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            regions.forEach { reg ->
                FilterChip(
                    selected = selectedRegionId == reg.id,
                    onClick = { selectedRegionId = reg.id },
                    label = { Text(l10n.localizedName(reg.nameDarija, reg.nameAr, reg.nameFr)) }
                )
            }
        }

        Text(l10n.get(R.string.city_label), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cities.forEach { c ->
                FilterChip(
                    selected = selectedCity == c,
                    onClick = { selectedCity = c },
                    label = { Text(c) }
                )
            }
        }

        Text(l10n.get(R.string.categories_interest_label), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { cat ->
                val isSelected = cat.id in selectedCats
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedCats = if (isSelected && selectedCats.size > 1) selectedCats - cat.id else selectedCats + cat.id
                    },
                    label = { Text(l10n.localizedName(cat.nameDarija, cat.nameAr, cat.nameFr)) }
                )
            }
        }

        Text(l10n.get(R.string.verification_doc_label), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = { docPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth().testTag("pick_verification_doc_button")
        ) {
            Icon(Icons.Rounded.Badge, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (verificationDocPath.isNotBlank()) l10n.get(R.string.verification_doc_uploaded)
                else l10n.get(R.string.upload_verification_doc_btn)
            )
        }

        if (showError) {
            Text(l10n.get(R.string.required_field), color = ErrorCrimson)
        }

        Button(
            onClick = {
                if (fullName.isBlank() || (role == UserRole.BUYER && businessName.isBlank())) {
                    showError = true
                } else {
                    onSubmit(fullName, role, selectedRegionId, selectedCity, farmOrCoopName, businessName, businessType, selectedCats.toList(), verificationDocPath)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .testTag("submit_profile_button")
        ) {
            Text(l10n.get(R.string.submit_profile_btn), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RoleChoiceCard(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) SoftSageContainer else CrispSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) ForestGreen else SubtleBorder),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = if (selected) ForestGreen else MutedMossText, modifier = Modifier.size(30.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
        }
    }
}
