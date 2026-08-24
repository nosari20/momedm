package edu.fnosari.momedm.ui.layouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import edu.fnosari.momedm.R
import edu.fnosari.momedm.ui.common.pronoteTopBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasicLayoutWithTopBar(
    title: String,
    leftActionIcon: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack,
    /** Describes what the left icon does, for screen readers; defaults to "Back". */
    leftActionLabel: String? = null,
    leftAction: (() -> Unit)? = null,
    rightActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit = {},
){
    // Pinned behavior tints/elevates the app bar once content scrolls beneath it.
    // It picks up scroll from any nested-scroll-aware content (LazyColumn,
    // Modifier.verticalScroll, …) via the nestedScroll connection below.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pronoteColors = pronoteTopBarColors()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            CenterAlignedTopAppBar(
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = pronoteColors.containerColor,
                    titleContentColor = pronoteColors.titleContentColor,
                    navigationIconContentColor = pronoteColors.navigationIconContentColor,
                    actionIconContentColor = pronoteColors.actionIconContentColor,
                ),
                title = { Text(text = title) },
                navigationIcon = {
                    if (leftAction != null) {
                        IconButton(onClick = leftAction) {
                            Icon(leftActionIcon, contentDescription = leftActionLabel ?: stringResource(R.string.back))
                        }
                    }
                },
                actions = rightActions,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            content()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
fun BasicLayoutWithTopBarPreview() {
    BasicLayoutWithTopBar(title = "Preview")
}
