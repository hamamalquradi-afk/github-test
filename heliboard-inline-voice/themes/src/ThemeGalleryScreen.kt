// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.themes

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.settings.dialogs.ColorPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val specSaver = Saver<ThemeSpec, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeGalleryScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        context.prefs().registerOnSharedPreferenceChangeListener(listener)
        onDispose { context.prefs().unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val themes = remember(context) { ThemeCatalog.load(context) }
    val saved = remember(revision) { ThemeGalleryRepository.saved(context) }
    val active = remember(revision) { ThemeGalleryRepository.selected(context)?.id }
    var category by rememberSaveable { mutableStateOf("all") }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    var editSource by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); notice = null } }
    val appliedMessage = stringResource(R.string.gallery_applied_notice)
    val defaultMessage = stringResource(R.string.gallery_default_notice)
    val categories = listOf("all", "custom") + ThemeCatalog.categories
    val visible = when (category) {
        "all" -> themes
        "custom" -> saved
        else -> themes.filter { it.category == category }
    }
    BackHandler(onBack = onClickBack)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.gallery_title)) }, navigationIcon = { BackButton(onClickBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.gallery_subtitle, themes.size), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { editSource = Json.encodeToString(ThemeGalleryRepository.capture(context)) }) { Text(stringResource(R.string.gallery_create)) }
                TextButton(onClick = { ThemeGalleryRepository.useOriginal(context); notice = appliedMessage }) { Text(stringResource(R.string.gallery_original)) }
                TextButton(onClick = { ThemeGalleryRepository.restoreDefault(context); notice = defaultMessage }) { Text(stringResource(R.string.gallery_restore_default)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { item ->
                    FilterChip(selected = category == item, onClick = { category = item }, label = { Text(stringResource(categoryName(item))) })
                }
            }
            if (visible.isEmpty()) Text(stringResource(R.string.gallery_empty), Modifier.padding(24.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(172.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visible, key = { it.id }) { spec ->
                    val applied = active == spec.id
                    val appliedText = stringResource(R.string.gallery_applied)
                    val previewText = stringResource(R.string.gallery_preview)
                    Card(
                        modifier = Modifier.fillMaxWidth().semantics {
                            selected = applied
                            stateDescription = if (applied) appliedText else previewText
                        }.clickable { preview = spec.id },
                        colors = CardDefaults.cardColors(containerColor = if (applied) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        ThemePreview(spec, Modifier.fillMaxWidth())
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(spec.displayName(context), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(categoryName(spec.category)), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { ThemeGalleryRepository.apply(context, spec); notice = appliedMessage }, enabled = !applied) {
                                Text(if (applied) "✓ $appliedText" else stringResource(R.string.gallery_apply))
                            }
                        }
                    }
                }
            }
        }
    }
    val previewSpec = (themes + saved).firstOrNull { it.id == preview }
    if (previewSpec != null) {
        ThemePreviewDialog(previewSpec,
            onClose = { preview = null },
            onApply = { ThemeGalleryRepository.apply(context, previewSpec); preview = null; notice = appliedMessage },
            onCustomize = { editSource = Json.encodeToString(previewSpec); preview = null },
            onDelete = if (previewSpec.category == "custom") ({ ThemeGalleryRepository.delete(context, previewSpec); preview = null }) else null)
    }
    editSource?.let { source ->
        ThemeEditor(Json.decodeFromString(source), onClose = { editSource = null }, onSave = { draft, name ->
            val savedSpec = ThemeGalleryRepository.save(context, draft, name)
            ThemeGalleryRepository.apply(context, savedSpec)
            editSource = null; category = "custom"; notice = appliedMessage
        })
    }
}

@Composable
private fun ThemePreview(spec: ThemeSpec, modifier: Modifier = Modifier, language: String = "current") {
    val context = LocalContext.current
    // A fresh view is needed after rotation: the real layout geometry changes with configuration.
    key(context.resources.configuration.orientation, spec, language) {
        AndroidView(factory = { ThemePreviewView(it).apply { show(spec, language) } },
            update = { it.show(spec, language) },
            modifier = modifier.aspectRatio(ThemePreviewView.aspectRatio(context)).clip(RoundedCornerShape(8.dp)))
    }
}

@Composable
private fun LanguageChoices(language: String, onLanguage: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("current" to stringResource(R.string.gallery_current_layout), "ar" to "العربية", "en" to "English").forEach { (id, name) ->
            FilterChip(selected = language == id, onClick = { onLanguage(id) }, label = { Text(name) })
        }
    }
}

@Composable
private fun ThemePreviewDialog(spec: ThemeSpec, onClose: () -> Unit, onApply: () -> Unit, onCustomize: () -> Unit, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    var language by rememberSaveable { mutableStateOf("current") }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.9f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(spec.displayName(context), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(categoryName(spec.category)), style = MaterialTheme.typography.labelLarge)
                    LanguageChoices(language) { language = it }
                    ThemePreview(spec, Modifier.fillMaxWidth().padding(vertical = 16.dp), language)
                    Text(stringResource(R.string.gallery_preview_note), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCustomize) { Text(stringResource(R.string.gallery_customize)) }
                    if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.dialog_close)) }
                    Button(onClick = onApply) { Text(stringResource(R.string.gallery_apply)) }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete)) }, text = { Text(spec.displayName(context)) },
        confirmButton = { TextButton(onClick = { onDelete?.invoke(); confirmDelete = false }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.dialog_close)) } })
}

@Composable
private fun ThemeEditor(source: ThemeSpec, onClose: () -> Unit, onSave: (ThemeSpec, String) -> Unit) {
    val context = LocalContext.current
    var draft by rememberSaveable(stateSaver = specSaver) { mutableStateOf(source) }
    var name by rememberSaveable { mutableStateOf(if (source.category == "custom") source.name else "") }
    var language by rememberSaveable { mutableStateOf("current") }
    var imageWide by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var colorField by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun closeEditor() { ThemeGalleryRepository.pruneImages(context); onClose() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val image = withContext(Dispatchers.IO) { ThemeGalleryRepository.importImage(context, uri) }
            if (image == null) error = true
            else draft = if (imageWide) draft.copy(landscapeImage = image, borders = true, allColors = emptyMap())
                else draft.copy(image = image, borders = true, allColors = emptyMap())
            busy = false
        }
    }
    val fields = listOf("background" to R.string.gallery_background, "keys" to R.string.gallery_keys,
        "functional" to R.string.gallery_functional, "label" to R.string.gallery_labels, "accent" to R.string.gallery_accent)
    fun value(field: String) = when (field) {
        "keys" -> draft.keys; "functional" -> draft.functional; "label" -> draft.label; "accent" -> draft.accent; else -> draft.background
    }
    Dialog(onDismissRequest = { if (!busy) closeEditor() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.95f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.gallery_customize), style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                        label = { Text(stringResource(R.string.gallery_name)) }, modifier = Modifier.fillMaxWidth())
                    LanguageChoices(language) { language = it }
                    ThemePreview(draft, Modifier.fillMaxWidth().padding(vertical = 12.dp), language)
                    Text(stringResource(R.string.gallery_contrast_note), style = MaterialTheme.typography.bodySmall)
                    fields.forEach { (field, res) ->
                        Row(Modifier.fillMaxWidth().clickable { colorField = field }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(android.graphics.Color.parseColor(value(field)))))
                            Text(stringResource(res), Modifier.padding(start = 12.dp))
                        }
                    }
                    Text(stringResource(R.string.theme_style), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyboardTheme.STYLES.forEach { style ->
                            FilterChip(selected = draft.style == style, onClick = { draft = draft.copy(style = style) }, label = { Text(style.getStringResourceOrName("style_name_", context)) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.key_borders), Modifier.weight(1f))
                        Switch(checked = draft.borders, enabled = draft.image == null && draft.landscapeImage == null,
                            onCheckedChange = { draft = draft.copy(borders = it) })
                    }
                    listOf(false to R.string.gallery_image, true to R.string.gallery_image_landscape).forEach { (wide, res) ->
                        TextButton(enabled = !busy, onClick = {
                            imageWide = wide
                            runCatching { picker.launch(arrayOf("image/*")) }.onFailure { error = true }
                        }) { Text(stringResource(res)) }
                    }
                    if (draft.image != null || draft.landscapeImage != null) TextButton(enabled = !busy, onClick = { draft = draft.copy(image = null, landscapeImage = null) }) {
                        Text(stringResource(R.string.gallery_remove_image))
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error) Text(stringResource(R.string.file_read_error), color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = ::closeEditor) { Text(stringResource(R.string.dialog_close)) }
                    Button(enabled = !busy, onClick = { onSave(draft, name) }) { Text(stringResource(R.string.gallery_save_apply)) }
                }
            }
        }
    }
    colorField?.let { field ->
        ColorPickerDialog(onDismissRequest = { colorField = null }, initialColor = android.graphics.Color.parseColor(value(field)),
            title = stringResource(fields.first { it.first == field }.second), showDefault = false, onDefault = {},
            onConfirmed = { selected ->
                val hex = String.format("#%06X", selected and 0xFFFFFF)
                draft = when (field) {
                    "keys" -> draft.copy(keys = hex, allColors = emptyMap())
                    "functional" -> draft.copy(functional = hex, allColors = emptyMap())
                    "label" -> draft.copy(label = hex, allColors = emptyMap())
                    "accent" -> draft.copy(accent = hex, allColors = emptyMap())
                    else -> draft.copy(background = hex, secondary = hex, pattern = "solid", allColors = emptyMap())
                }
                colorField = null
            })
    }
}

private fun categoryName(category: String): Int = when (category) {
    "all" -> R.string.gallery_all; "custom" -> R.string.gallery_my_themes
    "dark" -> R.string.gallery_category_dark; "light" -> R.string.gallery_category_light
    "gold" -> R.string.gallery_category_gold; "neon" -> R.string.gallery_category_neon
    "gradient" -> R.string.gallery_category_gradient; "minimal" -> R.string.gallery_category_minimal
    "nature" -> R.string.gallery_category_nature; "flowers" -> R.string.gallery_category_flowers
    "geometry" -> R.string.gallery_category_geometry; else -> R.string.gallery_category_code
}
