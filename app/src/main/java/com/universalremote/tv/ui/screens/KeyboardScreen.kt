package com.universalremote.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.universalremote.tv.protocol.TvKey
import com.universalremote.tv.remote.ConnectionState
import com.universalremote.tv.ui.MainViewModel
import com.universalremote.tv.ui.components.LabeledKey
import com.universalremote.tv.ui.components.PillKey

/**
 * Virtual keyboard that types into whatever text field is focused on the TV.
 *
 * Text is inserted through the remote protocol's IME edit message, so it works in the TV's own
 * search fields (Google, YouTube, Netflix, browsers) without any app on the TV.
 */
@Composable
fun KeyboardScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val textField by vm.textField.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected
    var draft by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    fun sendDraft() {
        if (draft.isEmpty()) return
        vm.sendText(draft)
        draft = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        Text("Keyboard", style = MaterialTheme.typography.titleLarge)
        Text(
            "Text is sent to the field that is focused on the TV.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Text(
            if (textField.focused && connected) {
                "A text field is focused on the TV. What you send is inserted there."
            } else {
                "Open a search or text field on the TV first, then type here."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (textField.focused) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Text to send") },
            placeholder = { Text("Type here…") },
            enabled = connected,
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { sendDraft() }),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LabeledKey(
                icon = Icons.Rounded.Backspace,
                label = "Delete",
                onClick = {
                    if (draft.isNotEmpty()) {
                        draft = draft.dropLast(1)
                    } else {
                        vm.sendKey(TvKey.DEL)
                    }
                },
                enabled = connected,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.SpaceBar,
                label = "Space",
                onClick = { draft = "$draft " },
                enabled = connected,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.KeyboardReturn,
                label = "Enter",
                onClick = { vm.sendKey(TvKey.ENTER) },
                enabled = connected,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LabeledKey(
                icon = Icons.Rounded.ContentPaste,
                label = "Paste",
                onClick = {
                    val pasted = clipboard.getText()?.text
                    if (!pasted.isNullOrEmpty()) draft = draft + pasted
                },
                enabled = connected,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.Clear,
                label = "Clear",
                onClick = { draft = "" },
                enabled = connected,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.Send,
                label = "Send",
                onClick = { sendDraft() },
                enabled = connected && draft.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))

        PillKey(
            icon = Icons.Rounded.Search,
            label = "Open TV search",
            onClick = { vm.repository.openTvSearch() },
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        Text(
            "The TV applies the text to the focused field, exactly like the on-screen keyboard of " +
                "the official remote app. Pressing Search on the TV opens a field for YouTube, " +
                "Play Store or the assistant.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
