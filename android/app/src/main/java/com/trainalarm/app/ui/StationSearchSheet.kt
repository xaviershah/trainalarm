package com.trainalarm.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trainalarm.app.SessionModel
import com.trainalarm.app.theme.LocalColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSearchSheet(model: SessionModel, isFrom: Boolean, onDismiss: () -> Unit) {
    val c = LocalColors.current
    var query by remember { mutableStateOf("") }
    val results = model.search(query)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.bg, contentColor = c.text) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.head, unfocusedTextColor = c.head, cursorColor = c.head,
                    focusedLabelColor = c.text, unfocusedLabelColor = c.muted,
                    focusedBorderColor = c.text, unfocusedBorderColor = c.muted
                ),
                label = { Text(if (isFrom) "Where are you now?" else "Where are you going?") }
            )
            when {
                query.isEmpty() -> Text("Type a station name", color = c.muted, modifier = Modifier.padding(vertical = 16.dp))
                results.isEmpty() -> Text("No station called that", color = c.muted, modifier = Modifier.padding(vertical = 16.dp))
                else -> LazyColumn {
                    items(results.take(30), key = { it.id }) { station ->
                        Text(
                            station.name, color = c.head,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (isFrom) model.selectFrom(station) else model.selectTo(station)
                                onDismiss()
                            }.padding(vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }
}
