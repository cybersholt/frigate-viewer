package net.triton.frigateviewer.feature.cameras

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * "Edit Cameras" modal bottom sheet.
 *
 * Displays all cameras (visible + hidden) with:
 * - Drag handle on the right — long-press drag to reorder
 * - Hide/show toggle button
 * - Camera name + server URL subtitle
 *
 * Changes are committed immediately via [onReorder] and [onToggleHide].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraEditSheet(
    allCameraNames: List<String>,
    hiddenCameras: Set<String>,
    serverUrl: String?,
    onReorder: (List<String>) -> Unit,
    onToggleHide: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Edit Cameras",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Done") }
            }

            Text(
                "Drag cameras to change order. Hidden cameras will not appear in the normal list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            HorizontalDivider()

            ReorderableList(
                items = allCameraNames,
                onReorder = onReorder,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 32.dp),
            ) { name, dragHandleModifier ->
                val hidden = name in hiddenCameras
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                if (hidden) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                        if (serverUrl != null) {
                            Text(
                                text = serverUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { onToggleHide(name) }) {
                        Icon(
                            imageVector = if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (hidden) "Show camera" else "Hide camera",
                            tint =
                                if (hidden) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = "Drag to reorder",
                        modifier = dragHandleModifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(start = 4.dp))
            }
        }
    }
}

/**
 * A Column whose items can be reordered by dragging their provided handle modifier.
 *
 * [itemContent] receives (item, dragHandleModifier). Apply [dragHandleModifier] to
 * whichever element the user should grab to drag the row.
 */
@Composable
private fun <T> ReorderableList(
    items: List<T>,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, dragHandleModifier: Modifier) -> Unit,
) {
    val list = remember(items) { items.toMutableStateList() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableIntStateOf(0) }

    Column(modifier) {
        list.forEachIndexed { idx, item ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (idx == draggingIndex) 1f else 0f)
                    .onGloballyPositioned { coords ->
                        if (itemHeightPx == 0) itemHeightPx = coords.size.height
                    }.graphicsLayer {
                        translationY = if (idx == draggingIndex) dragOffsetY else 0f
                        shadowElevation = if (idx == draggingIndex) 8f else 0f
                    },
            ) {
                val handleModifier =
                    Modifier.pointerInput(idx) {
                        detectDragGestures(
                            onDragStart = {
                                draggingIndex = idx
                                dragOffsetY = 0f
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                dragOffsetY += delta.y
                                val h = itemHeightPx.toFloat()
                                if (h > 0) {
                                    val target =
                                        (draggingIndex + (dragOffsetY / h).roundToInt())
                                            .coerceIn(0, list.size - 1)
                                    if (target != draggingIndex) {
                                        list.add(target, list.removeAt(draggingIndex))
                                        dragOffsetY -= (target - draggingIndex) * h
                                        draggingIndex = target
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                dragOffsetY = 0f
                                onReorder(list.toList())
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffsetY = 0f
                            },
                        )
                    }
                itemContent(item, handleModifier)
            }
        }
    }
}
