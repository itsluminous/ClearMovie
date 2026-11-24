package com.itsluminous.clearmovie.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.clearmovie.BuildConfig
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.data.files.StorageReport
import com.itsluminous.clearmovie.domain.model.AppSettings
import com.itsluminous.clearmovie.domain.model.ThemeMode
import com.itsluminous.clearmovie.ui.components.sizeText
import com.itsluminous.clearmovie.ui.theme.isDynamicColorSupported

/** Settings wired to its view model and to the per-app language. */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val loaded by viewModel.settings.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val voicePack by viewModel.voicePackBytes.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val settings = loaded ?: return
    val context = LocalContext.current
    val languages = remember(context) { AppLanguage.options(context) }
    // Held as state, not only re-read on recreation: choosing "Phone language"
    // while the phone already speaks the chosen language changes nothing on
    // screen, so the activity is not recreated and the radio must move itself.
    var selectedLanguage by remember(languages) { mutableStateOf(AppLanguage.selectedTag(languages.map { it.tag })) }
    SettingsScreen(
        settings = settings,
        dynamicColorSupported = isDynamicColorSupported,
        languages = languages,
        selectedLanguageTag = selectedLanguage,
        versionName = BuildConfig.VERSION_NAME,
        onBack = onBack,
        onThemeModeSelected = viewModel::setThemeMode,
        onDynamicColorChanged = viewModel::setDynamicColor,
        onLanguageSelected = { tag ->
            selectedLanguage = tag
            AppLanguage.select(tag)
        },
        storage = storage,
        onOpenProjectPage = { openProjectPage(context) },
        voicePackBytes = voicePack,
        onRemoveVoicePack = viewModel::removeVoicePack,
    )
}

/** ClearMovie's page on GitHub, which About's version opens (cm-home-presets). */
const val PROJECT_PAGE_URL = "https://github.com/itsluminous/ClearMovie"

/** Test tag of Storage's Remove voice pack. */
const val SETTINGS_REMOVE_PACK_TAG = "settings_remove_voice_pack"

/** Test tag of About's version row. */
const val SETTINGS_VERSION_TAG = "settings_version"

/**
 * The intent that opens [PROJECT_PAGE_URL] in the phone's browser: a fixed
 * address, to an app that opens web links, so ClearMovie itself never goes
 * online (it has no internet permission).
 */
fun projectPageIntent(): Intent = Intent(Intent.ACTION_VIEW, PROJECT_PAGE_URL.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

/** Opens [projectPageIntent], or says the phone has no app for web pages. */
internal fun openProjectPage(context: Context) {
    try {
        context.startActivity(projectPageIntent())
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.settings_no_browser), Toast.LENGTH_LONG).show()
    }
}

/** Test tag of the scrolling settings column. */
const val SETTINGS_LIST_TAG = "settings_list"

/**
 * Settings: Theme, Colours from wallpaper, Language, Storage and About,
 * whose version opens ClearMovie's page on GitHub. Presets are made and
 * edited from Home (cm-home-presets). Stateless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    dynamicColorSupported: Boolean,
    languages: List<LanguageOption>,
    selectedLanguageTag: String?,
    versionName: String,
    onBack: () -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onLanguageSelected: (String?) -> Unit,
    storage: StorageReport? = null,
    onOpenProjectPage: () -> Unit = {},
    voicePackBytes: Long = 0L,
    onRemoveVoicePack: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .testTag(SETTINGS_LIST_TAG)
                        .verticalScroll(rememberScrollState()),
            ) {
                SectionHeader(stringResource(R.string.settings_section_theme))
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { mode ->
                        RadioRow(
                            label = stringResource(mode.labelRes()),
                            selected = settings.themeMode == mode,
                            onClick = { onThemeModeSelected(mode) },
                        )
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    summary =
                        stringResource(
                            if (dynamicColorSupported) {
                                R.string.settings_dynamic_color_summary
                            } else {
                                R.string.settings_dynamic_color_unavailable
                            },
                        ),
                    checked = dynamicColorSupported && settings.dynamicColor,
                    enabled = dynamicColorSupported,
                    onCheckedChange = onDynamicColorChanged,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                SectionHeader(stringResource(R.string.settings_section_language))
                Column(Modifier.selectableGroup()) {
                    RadioRow(
                        label = stringResource(R.string.language_system),
                        selected = selectedLanguageTag == null,
                        onClick = { onLanguageSelected(null) },
                    )
                    languages.forEach { language ->
                        RadioRow(
                            label = language.nativeName,
                            selected = selectedLanguageTag == language.tag,
                            onClick = { onLanguageSelected(language.tag) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                if (storage != null) {
                    SectionHeader(stringResource(R.string.settings_section_storage))
                    StorageLine(stringResource(R.string.settings_storage_presets, sizeText(storage.presetBytes)))
                    StorageLine(stringResource(R.string.settings_storage_copied, sizeText(storage.projects.sumOf { it.copiedBytes })))
                    StorageLine(stringResource(R.string.settings_storage_cache, sizeText(storage.cacheBytes)))
                    if (voicePackBytes > 0) {
                        Row(
                            Modifier.fillMaxWidth().padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.weight(1f),
                            ) { StorageLine(stringResource(R.string.settings_storage_voice_pack, sizeText(voicePackBytes))) }
                            var confirm by rememberSaveable { mutableStateOf(false) }
                            if (confirm) {
                                AlertDialog(
                                    onDismissRequest = { confirm = false },
                                    title = { Text(stringResource(R.string.settings_remove_voice_pack_title)) },
                                    text = { Text(stringResource(R.string.settings_remove_voice_pack_body, sizeText(voicePackBytes))) },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            confirm = false
                                            onRemoveVoicePack()
                                        }) { Text(stringResource(R.string.settings_remove_voice_pack)) }
                                    },
                                    dismissButton = {
                                        TextButton(
                                            onClick = { confirm = false },
                                        ) { Text(stringResource(R.string.action_cancel)) }
                                    },
                                )
                            }
                            TextButton(
                                onClick = { confirm = true },
                                modifier = Modifier.heightIn(min = 48.dp).testTag(SETTINGS_REMOVE_PACK_TAG),
                            ) {
                                Text(stringResource(R.string.settings_remove_voice_pack))
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }

                SectionHeader(stringResource(R.string.settings_section_about))
                VersionRow(versionName, onOpenProjectPage)
                Spacer(Modifier.heightIn(min = 16.dp))
            }
        }
    }
}

private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

/** The version, with a line saying it opens ClearMovie on GitHub; the whole row is the link. */
@Composable
private fun VersionRow(
    versionName: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(SETTINGS_VERSION_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = stringResource(R.string.settings_version, versionName), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.settings_version_link),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_open_in_new),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StorageLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A full-width radio row: the whole row is the touch target (at least 56 dp tall). */
@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
