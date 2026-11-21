package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text as M3Text

@Composable
internal fun MoveFolderOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 官方单选 ListItem 承担容器、形状、选中配色、涟漪与单选语义（Role.RadioButton），
    // 行高与内容内边距全部走 ListItemDefaults.ContentPadding。
    // 底部面板的选择指示器放行尾对勾，与其它选择面板一致。
    val trailing: (@Composable () -> Unit)? = if (selected) {
        { Icon(imageVector = Icons.Rounded.Check, contentDescription = null) }
    } else {
        null
    }
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            Icon(imageVector = Icons.Rounded.Folder, contentDescription = null)
        },
        trailingContent = trailing,
    ) {
        M3Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}