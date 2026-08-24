package edu.fnosari.momedm.ui.layouts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import edu.fnosari.momedm.R
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch


class Layout {

    data class DrawerItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

    companion object {
        @Composable
        fun BasicLayoutWithTopBarAndDrawer(
            title: String,
            rightActions: @Composable (RowScope.() -> Unit) = {},
            drawerItems: List<DrawerItem> = listOf(),
            drawerName: String = "Drawer",
            content: @Composable () -> Unit = {},
        ){

            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent =  {
                    ModalDrawerSheet {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                drawerName,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleLarge
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            for (item in drawerItems){
                                NavigationDrawerItem(
                                    label = { Text(item.label) },
                                    selected = false, // Indicates if this item is currently selected.
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    onClick = {
                                        item.onClick()
                                        scope.launch { drawerState.close() }
                                    }
                                )
                            }
                        }
                    }
                }
            ) {
                // Delegates to the single top-level BasicLayoutWithTopBar.
                BasicLayoutWithTopBar(
                    title = title,
                    rightActions = rightActions,
                    content = content,
                    leftActionIcon = Icons.Filled.Menu,
                    leftActionLabel = stringResource(R.string.nav_open_menu),
                    leftAction = {
                        scope.launch {
                            if (drawerState.isClosed) drawerState.open() else drawerState.close()
                        }
                    }
                )
            }
        }
    }
}
