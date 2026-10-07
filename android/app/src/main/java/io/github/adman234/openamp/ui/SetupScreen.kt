package io.github.adman234.openamp.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

val qualities = listOf("original" to "Original", "high" to "High", "medium" to "Medium", "low" to "Low")

/** Turns a folder picker address into something readable, such as "Internal storage / Music / OpenAmp". */
private fun folderLabel(treeUri: String): String {
    val id = Uri.parse(treeUri).lastPathSegment ?: return treeUri
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    val root = if (volume == "primary") "Internal storage" else "SD card"
    return (listOf(root) + path.split('/').filter { it.isNotBlank() }).joinToString(" / ")
}

/**
 * An address field. It shows the URL keyboard, refuses spaces, starts with
 * https:// when empty, and turns red when the address cannot be used.
 */
@Composable
private fun UrlField(
    value: String,
    onChange: (String) -> Unit,
    onSettle: () -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean = true,
    hint: String? = null,
) {
    val focus = LocalFocusManager.current
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The value also changes from outside: sign-in fills it, leaving the field tidies it.
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))

    val bad = value.isNotBlank() && normalizeUrl(value).isNotEmpty() && !validUrl(value)
    val message = when {
        !bad -> hint
        normalizeUrl(value).startsWith("http://", ignoreCase = true) ->
            "Use https://. The Plex token is not sent over plain http."
        else -> "This is not a usable address."
    }
    OutlinedTextField(
        value = field,
        onValueChange = { next ->
            val text = next.text.filterNot { it.isWhitespace() }
            field = if (text == next.text) next else TextFieldValue(text, TextRange(text.length))
            onChange(text)
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = enabled,
        isError = bad,
        supportingText = message?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (state.isFocused && value.isBlank()) onChange("https://")
            if (!state.isFocused) onSettle()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.updateFolder(uri)
        }
    }
    var editPlex by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Settings")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Plex account", style = MaterialTheme.typography.titleMedium)
            when {
                vm.signingIn -> Text("Finish signing in to Plex in the browser, then come back here.")
                vm.signedIn -> {
                    Text(if (vm.serverName.isBlank()) "Signed in." else "Signed in. Server: ${vm.serverName}")
                    vm.servers.takeIf { it.size > 1 }?.forEach { server ->
                        OutlinedButton(onClick = { vm.pickServer(server) }) { Text("Use ${server.name}") }
                    }
                    TextButton(onClick = vm::signOut) { Text("Sign out") }
                }
                else -> Button(onClick = {
                    vm.signIn { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Text("Sign in with Plex") }
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("Addresses", style = MaterialTheme.typography.titleMedium)
            UrlField(
                value = vm.plexUrl,
                onChange = vm::updatePlexUrl,
                onSettle = vm::settleUrls,
                label = "Plex address",
                placeholder = "https://plex.example.com",
                enabled = editPlex,
                hint = if (editPlex) null else "Filled in for you when you sign in.",
            )
            if (!editPlex) {
                TextButton(onClick = { editPlex = true }) { Text("Enter the Plex address myself") }
            }
            UrlField(
                value = vm.fileUrl,
                onChange = vm::updateFileUrl,
                onSettle = vm::settleUrls,
                label = "File service address",
                placeholder = "https://openamp.example.com",
            )

            Text("Downloads", style = MaterialTheme.typography.titleMedium)
            Text(
                if (vm.folderUri.isBlank()) "No download folder chosen yet."
                else "Download folder: ${folderLabel(vm.folderUri)}",
            )
            vm.folderNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { pickFolder.launch(null) }) {
                Text(if (vm.folderUri.isBlank()) "Choose download folder" else "Change download folder")
            }
            if (vm.folderUri.isBlank()) {
                Text(
                    "After a reinstall, choose the same folder as before and the app finds the music already in it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Download quality", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                qualities.forEach { (value, label) ->
                    FilterChip(
                        selected = vm.quality == value,
                        onClick = { vm.updateQuality(value) },
                        label = { Text(label) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { vm.updateAllowMobile(!vm.allowMobile) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = vm.allowMobile, onCheckedChange = vm::updateAllowMobile)
                Text("Allow downloads using mobile data")
            }

            if (vm.configured) {
                OutlinedButton(onClick = { vm.open(Screen.Downloads) }) { Text("Downloads and storage") }
            }

            Text("Playback", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().clickable { vm.updateLeveling(!vm.leveling) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = vm.leveling, onCheckedChange = vm::updateLeveling)
                Column {
                    Text("Volume leveling")
                    Text(
                        "Evens out loud and quiet tracks, using the loudness Plex measured for each one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().clickable { vm.updateReportPlays(!vm.reportPlays) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = vm.reportPlays, onCheckedChange = vm::updateReportPlays)
                Column {
                    Text("Report plays to Plex")
                    Text(
                        "Counts a track as played on your Plex server when it finishes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text("Browse menu", style = MaterialTheme.typography.titleMedium)
            Text("Choose what the menu at the top of the main screen offers.", style = MaterialTheme.typography.bodySmall)
            Column {
                BrowseMode.entries.forEach { item ->
                    val fixed = item == BrowseMode.Home
                    val on = fixed || item.name in vm.menuModes
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !fixed) { vm.setMenuMode(item, !on) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { vm.setMenuMode(item, it) }, enabled = !fixed)
                        Text(if (fixed) "Home (always shown)" else item.label)
                    }
                }
            }

            Text("Music requests", style = MaterialTheme.typography.titleMedium)
            Text(
                "Optional. With a DroppedNeedle server you can search for music and ask for it from inside the app. " +
                    "It arrives in your Plex library like any other album.",
                style = MaterialTheme.typography.bodySmall,
            )
            UrlField(
                value = vm.dnUrl,
                onChange = vm::updateDnUrl,
                onSettle = vm::settleDnUrl,
                label = "DroppedNeedle address",
                placeholder = "https://music.example.com",
            )
            if (vm.dnReady) {
                when {
                    vm.dnSigningIn -> Text("Finish signing in to Plex in the browser, then come back here.")
                    vm.dnSignedIn -> {
                        Text("Signed in to DroppedNeedle.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.open(Screen.Requests) }) { Text("Request music") }
                            TextButton(onClick = vm::dnSignOut) { Text("Sign out") }
                        }
                    }
                    else -> {
                        Button(onClick = {
                            vm.dnSignInPlex { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }) { Text("Sign in to DroppedNeedle with Plex") }
                        Text("Or use a DroppedNeedle username and password:", style = MaterialTheme.typography.bodySmall)
                        var username by rememberSaveable { mutableStateOf("") }
                        var password by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = { vm.dnSignInPassword(username, password) },
                            enabled = username.isNotBlank() && password.isNotEmpty() && !vm.dnBusy,
                        ) { Text("Sign in with password") }
                    }
                }
                vm.dnError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "Follow the phone", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    FilterChip(
                        selected = vm.theme == value,
                        onClick = { vm.updateTheme(value) },
                        label = { Text(label) },
                    )
                }
            }

            Button(onClick = vm::finishSetup, enabled = vm.configured) { Text("Done") }
            if (!vm.configured) {
                Text(
                    "Sign in, fill in both addresses and choose a folder to continue.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
