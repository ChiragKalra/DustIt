package com.bruhascended.dustit.ui

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import com.bruhascended.dustit.R
import java.text.NumberFormat

@Composable
@ReadOnlyComposable
fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)

@Composable
fun photoCount(count: Int): String = pluralStringResource(R.plurals.photo_count, count, formatCount(count))
