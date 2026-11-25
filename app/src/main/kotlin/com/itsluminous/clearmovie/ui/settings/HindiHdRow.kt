package com.itsluminous.clearmovie.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.voice.PackDownloadError
import com.itsluminous.clearmovie.data.voice.PackDownloadState
import com.itsluminous.clearmovie.domain.voice.HindiModel
import com.itsluminous.clearmovie.ui.components.sizeText
import com.itsluminous.clearmovie.work.VoicePackNotifications

/** Test tag of Storage's Hindi HD Download (cm-hindi-voices). */
const val SETTINGS_HINDI_HD_DOWNLOAD_TAG = "settings_hindi_hd_download"

/** Test tag of the Hindi HD row. */
const val SETTINGS_HINDI_HD_TAG = "settings_hindi_hd"

/**
 * Storage's Hindi HD row (cm-hindi-voices): what it is and its sizes, with
 * Download; the download's progress, with Cancel; that it is added; or, on a
 * phone without the memory for it, that plainly. A phone with less than 8 GB
 * is told it gets the lighter model.
 */
@Composable
internal fun HindiHdRow(
    offer: HindiHdOffer,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(SETTINGS_HINDI_HD_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_hindi_hd_title), style = MaterialTheme.typography.bodyLarge)
        val download = offer.download
        val running = download is PackDownloadState.Waiting || download is PackDownloadState.Running
        when {
            offer.model == HindiModel.SMALL -> {
                Text(stringResource(R.string.settings_hindi_hd_low_memory), color = muted)
            }

            offer.files.isEmpty() -> {
                Text(stringResource(R.string.settings_hindi_hd_added), color = muted)
                if (offer.model == HindiModel.LIGHT) Text(stringResource(R.string.settings_hindi_hd_light_note), color = muted)
            }

            running -> {
                val progress = (download as? PackDownloadState.Running)?.progress
                Text(
                    when {
                        progress?.checking == true -> {
                            stringResource(R.string.pack_checking)
                        }

                        progress != null && progress.total > 0 -> {
                            stringResource(
                                R.string.pack_progress,
                                sizeText(progress.done),
                                sizeText(progress.total),
                            )
                        }

                        else -> {
                            stringResource(R.string.pack_starting)
                        }
                    },
                    color = muted,
                )
                if (progress != null && progress.total > 0 && !progress.checking) {
                    LinearProgressIndicator({ (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }

            else -> {
                Text(
                    stringResource(
                        if (offer.withPack) R.string.settings_hindi_hd_offer_with_pack else R.string.settings_hindi_hd_offer,
                        sizeText(offer.bytes),
                        sizeText(offer.unpackedBytes),
                    ),
                    color = muted,
                )
                if (offer.model == HindiModel.LIGHT) Text(stringResource(R.string.settings_hindi_hd_light_note), color = muted)
                val failed = download as? PackDownloadState.Failed
                if (failed != null) {
                    Text(
                        if (failed.error == PackDownloadError.NO_SPACE && failed.missingBytes > 0) {
                            stringResource(R.string.pack_error_space, sizeText(failed.missingBytes))
                        } else {
                            stringResource(VoicePackNotifications.errorText(failed.error))
                        },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDownload,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(SETTINGS_HINDI_HD_DOWNLOAD_TAG),
                    ) { Text(stringResource(if (failed != null) R.string.export_try_again else R.string.pack_download)) }
                }
            }
        }
    }
}
