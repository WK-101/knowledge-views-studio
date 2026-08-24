package com.tasktree.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.tasktree.app.ui.theme.TaskTreeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TaskTreeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OutlineScreen()
                }
            }
        }
    }
}

/** A task in the outline. Children make this an unbounded tree (MyLifeOrganized-style). */
data class TaskNode(
    val id: Int,
    val title: String,
    val children: List<TaskNode> = emptyList()
)

/** A flattened, indented row derived from the tree for rendering in a LazyColumn. */
private data class OutlineRow(val node: TaskNode, val depth: Int, val hasChildren: Boolean)

private fun sampleTasks(): List<TaskNode> = listOf(
    TaskNode(
        1, "Welcome to TaskTree", listOf(
            TaskNode(2, "Skeleton build — proves the direct-install APK pipeline"),
            TaskNode(3, "Fully offline · no account · no network permission"),
        )
    ),
    TaskNode(
        10, "Work", listOf(
            TaskNode(
                11, "Quarterly report", listOf(
                    TaskNode(12, "Collect figures"),
                    TaskNode(13, "Draft summary"),
                    TaskNode(14, "Send for review"),
                )
            ),
            TaskNode(15, "Clear inbox"),
        )
    ),
    TaskNode(
        20, "Home", listOf(
            TaskNode(
                21, "Groceries", listOf(
                    TaskNode(22, "Milk"),
                    TaskNode(23, "Coffee"),
                )
            ),
            TaskNode(24, "Book dentist"),
        )
    ),
)

private fun flatten(
    nodes: List<TaskNode>,
    depth: Int,
    isExpanded: (Int) -> Boolean,
    out: MutableList<OutlineRow>
) {
    for (n in nodes) {
        val hasChildren = n.children.isNotEmpty()
        out.add(OutlineRow(n, depth, hasChildren))
        if (hasChildren && isExpanded(n.id)) {
            flatten(n.children, depth + 1, isExpanded, out)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineScreen() {
    val roots = remember { sampleTasks() }
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val done = remember { mutableStateMapOf<Int, Boolean>() }

    val isExpanded: (Int) -> Boolean = { id -> expanded[id] ?: true }

    // Recomputes whenever an expand/collapse toggle changes the observed map.
    val rows = mutableListOf<OutlineRow>().also { flatten(roots, 0, isExpanded, it) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("TaskTree", fontWeight = FontWeight.SemiBold) })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(rows, key = { it.node.id }) { row ->
                TaskRow(
                    row = row,
                    expanded = isExpanded(row.node.id),
                    done = done[row.node.id] ?: false,
                    onToggleExpand = { expanded[row.node.id] = !isExpanded(row.node.id) },
                    onToggleDone = { done[row.node.id] = !(done[row.node.id] ?: false) }
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    row: OutlineRow,
    expanded: Boolean,
    done: Boolean,
    onToggleExpand: () -> Unit,
    onToggleDone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.hasChildren) { onToggleExpand() }
            .padding(start = (8 + row.depth * 20).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.hasChildren) {
            IconButton(onClick = onToggleExpand) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowDown
                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Checkbox(checked = done, onCheckedChange = { onToggleDone() })
        Spacer(Modifier.width(4.dp))
        Text(
            text = row.node.title,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )
    }
}
