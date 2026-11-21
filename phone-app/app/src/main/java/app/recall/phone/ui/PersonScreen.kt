package app.recall.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recall.phone.AppContainer
import app.recall.phone.core.Profile
import app.recall.phone.core.RefreshRequest
import app.recall.phone.core.formatDate
import app.recall.phone.data.PersonRecord
import app.recall.phone.net.BackendException
import kotlinx.coroutines.launch

@Composable
fun PersonScreen(container: AppContainer, personId: String, onDone: () -> Unit) {
    val loaded by produceState<PersonRecord?>(null, personId) {
        value = container.people.person(personId)
    }
    val person = loaded
    if (person == null) {
        Column(Modifier.fillMaxSize()) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    } else {
        PersonEditor(container, person, onDone)
    }
}

@Composable
private fun PersonEditor(container: AppContainer, person: PersonRecord, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val original = person.profile
    var name by remember { mutableStateOf(original.name) }
    var headline by remember { mutableStateOf(original.headline) }
    var summary by remember { mutableStateOf(original.summary) }
    var topics by remember { mutableStateOf(original.topics.joinToString("\n")) }
    var followUps by remember {
        mutableStateOf(original.followUps.joinToString("\n") { it.text })
    }
    var commonInterests by remember {
        mutableStateOf(original.commonInterests.joinToString("\n"))
    }
    var avoid by remember { mutableStateOf(original.avoid.joinToString("\n")) }
    var sinceYouMet by remember { mutableStateOf(original.sinceYouMet.joinToString("\n")) }
    var links by remember { mutableStateOf(original.sharedLinks.joinToString("\n")) }
    var spoken by remember { mutableStateOf(original.spoken.joinToString("\n")) }
    val notes = remember { mutableStateListOf(*original.encounters.map { it.notes }.toTypedArray()) }
    var isBackup by remember { mutableStateOf(person.isDemoBackup) }
    var isLocked by remember { mutableStateOf(original.locked) }
    var confirmDelete by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }

    fun edited(): Profile = original.copy(
        name = name.trim().ifEmpty { "Unknown" },
        headline = headline.trim(),
        summary = summary.trim(),
        encounters = original.encounters.mapIndexed { i, encounter ->
            encounter.copy(notes = notes.getOrElse(i) { encounter.notes }.trim())
        },
        topics = topics.toLines(),
        followUps = app.recall.phone.core.mergeEditedFollowUps(
            original.followUps,
            followUps.toLines(),
        ),
        commonInterests = commonInterests.toLines(),
        avoid = avoid.toLines(),
        sinceYouMet = sinceYouMet.toLines(),
        sharedLinks = links.toLines(),
        spoken = spoken.toLines(),
        locked = isLocked,
    )

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Thumbnail(person.thumbnailPath, name, 44.dp)
            Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp),
                maxLines = 1,
            )
        }
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field("Name", name) { name = it }
            Field("Headline", headline) { headline = it }
            Field("Summary", summary, multiline = true) { summary = it }
            Field("Talk about (one per line)", topics, multiline = true) { topics = it }
            Field("In common (one per line)", commonInterests, multiline = true) {
                commonInterests = it
            }
            Field("Follow ups (one per line)", followUps, multiline = true) { followUps = it }
            Field("Avoid (one per line)", avoid, multiline = true) { avoid = it }
            Field("Since you met (one per line)", sinceYouMet, multiline = true) {
                sinceYouMet = it
            }
            Field("Shared links (one per line)", links, multiline = true) { links = it }
            Field("Read aloud (one per line, max 4)", spoken, multiline = true) { spoken = it }

            original.encounters.forEachIndexed { i, encounter ->
                val label = listOf(encounter.place, formatDate(encounter.time))
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                Field("Notes: $label", notes.getOrElse(i) { "" }, multiline = true) {
                    if (i < notes.size) notes[i] = it
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Demo backup", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Show this card if the backend is down during the demo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isBackup,
                    onCheckedChange = { enabled ->
                        isBackup = enabled
                        scope.launch { container.people.setDemoBackup(person.id, enabled) }
                    },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lock profile", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "New recordings of this person are discarded and never change this card.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isLocked,
                    onCheckedChange = { enabled ->
                        isLocked = enabled
                        scope.launch { container.people.setLocked(person.id, enabled) }
                    },
                )
            }

            if (status.isNotBlank()) {
                Text(status, color = MaterialTheme.colorScheme.secondary)
            }

            Button(
                onClick = {
                    scope.launch {
                        container.people.updateProfile(person.id, edited())
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            OutlinedButton(
                enabled = !refreshing,
                onClick = {
                    refreshing = true
                    scope.launch {
                        try {
                            val current = edited()
                            val updates = container.backend.refresh(
                                RefreshRequest(
                                    personId = person.id,
                                    name = current.name,
                                    sharedLinks = current.sharedLinks,
                                    profile = current,
                                ),
                            ).sinceYouMet
                            sinceYouMet = updates.joinToString("\n")
                            status = if (updates.isEmpty()) {
                                "No updates found."
                            } else {
                                "Updated. Tap Save to keep this."
                            }
                        } catch (e: BackendException) {
                            status = e.message.orEmpty()
                        } finally {
                            refreshing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (refreshing) "Refreshing" else "Refresh updates") }

            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete person") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $name?") },
            text = { Text("This removes their profile, face data, and photo from this phone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            container.people.delete(person.id)
                            onDone()
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    multiline: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun String.toLines(): List<String> =
    lines().map { it.trim() }.filter { it.isNotEmpty() }
