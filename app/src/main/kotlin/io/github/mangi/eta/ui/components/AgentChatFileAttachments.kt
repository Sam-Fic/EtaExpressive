package io.github.mangi.eta.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaDimens
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.window.PopupPositionProvider
import java.io.File

internal val ChatInputPopupMargin = 8.dp
/** 主页 / 侧边栏按钮统一档：官方 Small 图标按钮容器（40dp），尺寸只引用 IconButtonDefaults 令牌。 */
internal val ChatInputActionSize = IconButtonDefaults.smallContainerSize().height

/** 附件动作集：系统选择器、相机拍照与绝对路径输入的统一入口，由 [rememberAgentAttachmentActions] 创建。 */
internal class AgentAttachmentActions(
    val pickImage: () -> Unit,
    val takePhoto: () -> Unit,
    val pickVideo: () -> Unit,
    val pickFiles: () -> Unit,
    val pickFolder: () -> Unit,
    val inputPath: () -> Unit,
)

/**
 * 创建附件动作集并托管「输入绝对路径」弹窗（官方 `AlertDialog`）。
 * 图片 / 视频选择成功后先持久化读取权限再回调；拍照先写入缓存目录的 FileProvider URI，
 * 成功后按同一图片通路回调；文件 / 文件夹 / 路径直接回调。
 */
@Composable
internal fun rememberAgentAttachmentActions(
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
): AgentAttachmentActions {
    val context = LocalContext.current
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    // 相机输出文件路径：拍照期间可能经历进程重建，用可保存的字符串持有，
    // 结果回调时再按路径重建 FileProvider URI。
    var cameraOutputPath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { captured ->
        val output = cameraOutputPath?.let(::File)
        cameraOutputPath = null
        if (captured && output != null) {
            onAttachImage(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
                    .toString()
            )
        } else {
            output?.delete()
        }
    }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachImage(uri.toString())
        }
    }
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachVideo(uri.toString())
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onAttachFiles(uris.map { it.toString() })
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onAttachFolder(uri.toString())
    }

    if (showPathDialog) {
        AlertDialog(
            onDismissRequest = { showPathDialog = false },
            title = { M3Text(text = stringResource(R.string.ui_input_file_path_36d474)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    M3Text(
                        text = stringResource(
                            R.string.ui_supports_files_and_folders_under_internal_storage_or_520786
                        ),
                        style = etaTextStyle(EtaType.Secondary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { M3Text(text = stringResource(R.string.ui_absolute_path_9ac6fc)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val path = pathInput.trim()
                        showPathDialog = false
                        onAttachFilePath(path)
                    },
                    enabled = pathInput.trim().startsWith('/'),
                ) {
                    M3Text(text = stringResource(R.string.attachment_add))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showPathDialog = false }) {
                    M3Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    return AgentAttachmentActions(
        pickImage = {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        takePhoto = {
            val directory = File(context.cacheDir, "camera-captures").apply { mkdirs() }
            val output = File(directory, chatImageFileName("image/jpeg"))
            cameraOutputPath = output.absolutePath
            runCatching {
                cameraLauncher.launch(
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
                )
            }.onFailure {
                cameraOutputPath = null
                output.delete()
                Toast.makeText(
                    context,
                    context.getString(R.string.attachment_camera_unavailable),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        pickVideo = {
            videoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        },
        pickFiles = { filePicker.launch(arrayOf("*/*")) },
        pickFolder = { folderPicker.launch(null) },
        inputPath = {
            pathInput = ""
            showPathDialog = true
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentAttachmentPickerButton(
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Add,
    containerShape: androidx.compose.ui.graphics.Shape? = null,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    containerSize: androidx.compose.ui.unit.Dp = ChatInputActionSize,
) {
    val view = LocalView.current
    val menuState = rememberEtaMenuState()
    val keepIme = rememberKeepImeWhenOpeningMenu()
    val actions = rememberAgentAttachmentActions(
        onAttachImage = onAttachImage,
        onAttachVideo = onAttachVideo,
        onAttachFiles = onAttachFiles,
        onAttachFolder = onAttachFolder,
        onAttachFilePath = onAttachFilePath,
    )
    val options = listOf(
        Triple(stringResource(R.string.attachment_image), Icons.Rounded.Image, 0),
        Triple(stringResource(R.string.attachment_video), Icons.Rounded.Videocam, 1),
        Triple(stringResource(R.string.attachment_file), Icons.Rounded.Description, 2),
        Triple(stringResource(R.string.attachment_folder), Icons.Rounded.FolderOpen, 3),
        Triple(stringResource(R.string.attachment_enter_path), Icons.Rounded.Link, 4),
    )
    fun runOption(index: Int) {
        TouchHaptics.click(view)
        menuState.dismiss()
        when (index) {
            0 -> actions.pickImage()
            1 -> actions.pickVideo()
            2 -> actions.pickFiles()
            3 -> actions.pickFolder()
            4 -> actions.inputPath()
        }
    }
    Box(modifier = modifier) {
        ChatInputNonFocusableIconButton(
            onClick = {
                keepIme()
                menuState.onAnchorClick()
            },
            contentDescription = stringResource(R.string.ui_add_attachment_dba9e8),
            containerShape = containerShape,
            containerColor = containerColor,
            containerSize = containerSize,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                // 官方图标按钮图标尺寸令牌。
                modifier = Modifier.size(etaOfficialIconButtonIconSize(containerSize)),
                // 官方图标按钮已通过 LocalContentColor 下发内容色。
                tint = LocalContentColor.current,
            )
        }
        // Material 3 Expressive：官方 DropdownMenuPopup + 官方 DropdownMenuGroup，
        // 行高 / 内边距 / 前置图标尺寸 / 行形状 / 组形状全部取官方默认值。
        EtaMenuPopup(
            expanded = menuState.expanded,
            onDismissRequest = menuState::dismiss,
        ) {
            EtaMenuGroup(index = 0, count = 1) {
                options.forEachIndexed { index, (label, optionIcon, action) ->
                    DropdownMenuItem(
                        text = { M3Text(text = label) },
                        leadingIcon = { Icon(imageVector = optionIcon, contentDescription = null) },
                        shape = EtaMenuItemShape(index, options.size),
                        onClick = { runOption(action) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PendingFileReferenceStrip(
    references: List<PendingFileReferenceUi>,
    onRemoveReference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val referenceStripScrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 两端截断淡出带：文件引用横向滚动时，被视口切一半的卡片用 DstIn 蒙版擦掉。
            .horizontalScrollEdgeFade(referenceStripScrollState)
            .horizontalScroll(referenceStripScrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        references.forEach { pending ->
            val reference = pending.reference
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .widthIn(max = 250.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(SentFileChipCornerRadius))
                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(SentFileChipCornerRadius))
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
                M3Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = etaTextStyle(EtaType.Secondary),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onRemoveReference(pending.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_file_reference_04bbfc),
                        modifier = Modifier.size(EtaDimens.StatusIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 文件 chip 的默认大圆角；发送出去后由调用方统一成用户气泡同档。 */
private val SentFileChipCornerRadius = 12.dp

/** 文件 chip 之间、以及 chip 与气泡相接的接缝角（设置页分段条目内圆角同档）。 */
private val SentFileChipSeamCorner = 4.dp

/**
 * 文件 chip 的四角：整行当成一条横向的分段条，跟横排图片同一套规则——
 * 最左一个 chip 的左侧（上、下）保持大圆角；中间被夹住的四角全收成接缝小圆角；
 * 最右一个 chip 的右上角保持大圆角（顶边永远算自由边，上方就算有图片行，
 * 收角也只收在图片行自己那一侧），右下角只在下面接了东西时收。
 *
 * FlowRow 会换行，这里只能按列表下标认首尾：只有末行铺满时「最右一个」才真贴在右缘，
 * 文件多到换行且末行没铺满时这一点会不准。
 */
private fun sentFileChipShape(
    index: Int,
    count: Int,
    largeCorner: Dp,
    seamCorner: Dp,
    connectsBelow: Boolean,
): Shape {
    val isFirst = index == 0
    val isLast = index == count - 1
    return RoundedCornerShape(
        topStart = if (isFirst) largeCorner else seamCorner,
        topEnd = if (isLast) largeCorner else seamCorner,
        bottomEnd = if (isLast && !connectsBelow) largeCorner else seamCorner,
        bottomStart = if (isFirst) largeCorner else seamCorner,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SentFileReferenceFlow(
    references: List<AgentFileReference>,
    modifier: Modifier = Modifier,
    largeCorner: Dp = SentFileChipCornerRadius,
    seamCorner: Dp = SentFileChipSeamCorner,
    connectsBelow: Boolean = false,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        references.forEachIndexed { index, reference ->
            val chipShape = sentFileChipShape(
                index = index,
                count = references.size,
                largeCorner = largeCorner,
                seamCorner = seamCorner,
                connectsBelow = connectsBelow,
            )
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .widthIn(max = 280.dp)
                    .background(MaterialTheme.colorScheme.surface, chipShape)
                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), chipShape)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(EtaDimens.StatusIconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
                M3Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = etaTextStyle(EtaType.Secondary),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal class InputPopupPositionProvider(
    private val inputContainerTopPx: Int,
    private val windowHorizontalInsetPx: Int? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // 官方接口不提供 alignment / popupMargin；带窗口水平内边距时贴窗口末端，否则贴锚点起始。
        val requestedX = if (windowHorizontalInsetPx != null) {
            windowSize.width - popupContentSize.width - windowHorizontalInsetPx
        } else {
            anchorBounds.left
        }
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val requestedY = inputContainerTopPx - popupContentSize.height
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(
            x = requestedX.coerceIn(0, maxX),
            y = requestedY.coerceIn(0, maxY),
        )
    }
}
