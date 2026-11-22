package com.example.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.data.*
import com.example.core.domain.PriceTrendPoint
import com.example.core.l10n.LocalL10n
import com.example.core.media.SignedUrlMediaCache
import com.example.ui.theme.*
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

val LocalMediaCache = staticCompositionLocalOf<SignedUrlMediaCache?> { null }

@DrawableRes
fun drawableForKey(key: String): Int = when (key.trim()) {
    "tomatoes" -> R.drawable.img_produce_tomatoes
    "citrus" -> R.drawable.img_produce_citrus_olives
    "honey" -> R.drawable.img_produce_honey_dates
    else -> R.drawable.img_souss_hero
}

@Composable
fun OfferPhotoView(
    photoToken: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    defaultBucket: String = "offer-photos"
) {
    if (photoToken.startsWith("drawable:")) {
        val key = photoToken.removePrefix("drawable:")
        Image(
            painter = painterResource(id = drawableForKey(key)),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        val mediaCache = LocalMediaCache.current
        val localFile = remember(photoToken) {
            File(photoToken).takeIf { photoToken.startsWith("/") && it.exists() }
        }
        val resolvedFile by produceState<File?>(initialValue = localFile, photoToken, defaultBucket, mediaCache) {
            value = localFile ?: mediaCache?.resolveLocalOrCachedFile(defaultBucket, photoToken)
        }
        AsyncImage(
            model = resolvedFile ?: R.drawable.img_souss_hero,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

@Composable
fun OfflineStatusBanner(
    isOnline: Boolean,
    pendingSyncCount: Int,
    onSyncNow: () -> Unit,
    failedSyncCount: Int = 0,
    onRetryFailedSync: () -> Unit = {}
) {
    val l10n = LocalL10n.current
    if (!isOnline) {
        Surface(
            color = SaffronContainer,
            contentColor = OnSaffronContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(
                    text = l10n.get(R.string.offline_banner),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else if (failedSyncCount > 0) {
        Surface(
            color = ErrorContainerLight,
            contentColor = ErrorCrimson,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.SyncProblem, contentDescription = null)
                    Text(
                        text = l10n.format(R.string.sync_failed_banner, failedSyncCount),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                TextButton(
                    onClick = onRetryFailedSync,
                    modifier = Modifier.testTag("retry_failed_sync_button")
                ) {
                    Text(l10n.get(R.string.retry_btn), color = ErrorCrimson, fontWeight = FontWeight.Bold)
                }
            }
        }
    } else if (pendingSyncCount > 0) {
        Surface(
            color = SoftSageContainer,
            contentColor = OnSageContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.CloudSync, contentDescription = null)
                    Text(
                        text = l10n.format(R.string.sync_now_btn, pendingSyncCount),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                TextButton(onClick = onSyncNow) {
                    Text(l10n.get(R.string.confirm_btn))
                }
            }
        }
    }
}

@Composable
fun VerificationBanner(profile: ProfileEntity) {
    val l10n = LocalL10n.current
    when (profile.verificationStatus) {
        VerificationStatus.PENDING -> {
            Surface(
                color = SaffronContainer,
                contentColor = OnSaffronContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.HourglassTop, contentDescription = null)
                    Text(
                        text = l10n.get(R.string.verification_banner_pending),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        VerificationStatus.REJECTED -> {
            Surface(
                color = ErrorContainerLight,
                contentColor = ErrorCrimson,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                    Text(
                        text = l10n.format(R.string.verification_banner_rejected, profile.rejectionReason),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun SkeletonCardPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        color = SoftFieldSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(SubtleBorder.copy(alpha = 0.6f))
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SubtleBorder.copy(alpha = 0.6f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SubtleBorder.copy(alpha = 0.5f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SubtleBorder.copy(alpha = 0.4f))
                )
            }
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CrispSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, SubtleBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(SoftSageContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = ForestGreen, modifier = Modifier.size(34.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = InkCharcoal)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
            }
        }
    }
}

@Composable
fun PriceTrendLineChart(
    points: List<PriceTrendPoint>,
    unitLabel: String,
    currencyLabel: String,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) return
    val minPrice = points.minOf { it.avgPrice }
    val maxPrice = points.maxOf { it.avgPrice }
    val range = (maxPrice - minPrice).coerceAtLeast(0.5)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "Min: ${formatMad(minPrice)} $currencyLabel/$unitLabel",
                style = MaterialTheme.typography.labelMedium,
                color = MutedMossText
            )
            Text(
                text = "Max: ${formatMad(maxPrice)} $currencyLabel/$unitLabel",
                style = MaterialTheme.typography.labelMedium,
                color = ForestGreen,
                fontWeight = FontWeight.Bold
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(MaterialTheme.shapes.small)
                .background(SoftFieldSurface)
                .padding(14.dp)
        ) {
            val w = size.width
            val h = size.height
            // Horizontal guide lines
            for (g in 0..2) {
                val y = h * (g / 2f)
                drawLine(
                    color = SubtleBorder,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
            val linePath = Path()
            val fillPath = Path()
            val coords = points.mapIndexed { index, pt ->
                val x = if (points.size == 1) w / 2f else w * (index.toFloat() / (points.size - 1))
                val normY = ((pt.avgPrice - minPrice) / range).toFloat()
                val y = h - (normY * h)
                Offset(x, y)
            }
            coords.forEachIndexed { idx, pt ->
                if (idx == 0) {
                    linePath.moveTo(pt.x, pt.y)
                    fillPath.moveTo(pt.x, h)
                    fillPath.lineTo(pt.x, pt.y)
                } else {
                    linePath.lineTo(pt.x, pt.y)
                    fillPath.lineTo(pt.x, pt.y)
                }
            }
            fillPath.lineTo(coords.last().x, h)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(FreshLeaf.copy(alpha = 0.28f), Color.Transparent)
                )
            )
            drawPath(
                path = linePath,
                color = ForestGreen,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            coords.lastOrNull()?.let { last ->
                drawCircle(color = HarvestTerracotta, radius = 5.dp.toPx(), center = last)
            }
        }
    }
}

fun formatMad(value: Double): String {
    return if (value % 1.0 == 0.0) {
        value.roundToInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", value)
    }
}

fun formatEpochDay(epochDay: Long): String {
    return runCatching {
        LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }.getOrDefault("")
}

fun openWhatsApp(context: Context, moroccanPhone: String, message: String) {
    val intl = if (moroccanPhone.startsWith("0")) "212" + moroccanPhone.substring(1) else moroccanPhone
    val encoded = Uri.encode(message)
    val uri = Uri.parse("https://wa.me/$intl?text=$encoded")
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

fun openPhoneDialer(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
