package io.github.mangi.eta.ui.components

import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import androidx.core.content.FileProvider
import io.github.mangi.eta.agent.media.AgentVideoCodec
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaFloatingToolbarConcentricPadding
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.fullImageSourceAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.IconButtonDefaults

/** 取官方 surfaceContainer 角色。 */
@Composable
private fun previewPlaceholderColor(): Color = MaterialTheme.colorScheme.surfaceContainer

/** 图片占位与失败提示的圆角：走官方 shapes 令牌（medium = 12dp）。 */
@Composable
private fun previewMediaShape(): Shape = MaterialTheme.shapes.medium

/** 失败提示文字：走主页统一字阶的 Secondary 档。 */
@Composable
private fun previewBodyStyle(): TextStyle = etaTextStyle(EtaType.Secondary)

/**
 * 图片加载指示：官方 M3E `LoadingIndicator`（morph 变形），尺寸收敛进传入槽位。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewProgressIndicator(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    LoadingIndicator(modifier = modifier.size(size))
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

internal val LocalOpenChatImagePreview = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
internal fun ChatImagePreviewHost(
    gallery: List<String> = emptyList(),
    /**
     * 把当前预览的这张图继续作为附件加进对话（写回输入区待发图片）。
     *
     * 由宿主（AgentChatBody 的 onAttachImage / 语音面板 composer.attachImage）提供；
     * 没有宿主时（如无会话的入口浮层）该动作不传，工具栏里对应按钮置灰。
     */
    onAttachImageToConversation: ((String) -> Unit)? = null,
    /**
     * 把当前预览的这个视频继续作为附件加进对话（写回输入区待发视频）。
     *
     * 视频与图片走两条不同的附件入口（attachVideo 会重新转码 / 复制一份缓存），
     * 宿主同样按能力提供；缺失时预览视频的添加按钮置灰，其余按钮不受影响。
     */
    onAttachVideoToConversation: ((String) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var source by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalOpenChatImagePreview provides { source = it }) {
        content()
    }
    val current = source
    if (current != null) {
        val sources = remember(current, gallery) { previewGalleryFor(current, gallery) }
        ChatImagePreviewDialog(
            sources = sources,
            initialIndex = sources.indexOf(current).coerceAtLeast(0),
            onDismiss = { source = null },
            onAttachImageToConversation = onAttachImageToConversation,
            onAttachVideoToConversation = onAttachVideoToConversation,
        )
    }
}

internal fun previewGalleryFor(source: String, gallery: List<String>): List<String> {
    val images = gallery.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (source.isBlank()) return images
    return if (source in images) images else images + source
}

internal fun collectPreviewableChatImages(
    messages: List<AgentChatMessageUi>,
    pendingImages: List<PendingImageUi> = emptyList(),
    imageSources: (AgentMessageUi) -> List<String> = { collectMarkdownImageSources(it.content) },
): List<String> {
    val out = LinkedHashSet<String>()
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> {
                message.images.indices.forEach { index ->
                    message.fullImageSourceAt(index).trim().takeIf { it.isNotEmpty() }?.let(out::add)
                }
            }
            is AgentMessageUi -> imageSources(message).forEach(out::add)
            // 工具卡片里读到的图也进画廊，左右滑动就能连着看这一轮的所有观察。
            is ToolActivityMessageUi -> message.mediaPreviews.forEach(out::add)
            else -> Unit
        }
    }
    pendingImages.forEach { image ->
        val source = image.uri.trim().ifEmpty { image.dataUrl.trim() }
        if (source.isNotEmpty()) out += source
    }
    return out.toList()
}

@Composable
internal fun ChatClickableImage(
    source: String,
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val openPreview = LocalOpenChatImagePreview.current
    val view = LocalView.current
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.clickable {
            TouchHaptics.click(view)
            openPreview(source)
        },
    )
}

@Composable
internal fun ChatMarkdownImage(
    content: String,
    node: ASTNode,
    modifier: Modifier = Modifier,
    sourceOverride: String? = null,
    fillPlaceholder: Boolean = false,
) {
    val source = remember(content, node.startOffset, node.endOffset, sourceOverride) {
        resolveChatImageSource(content, node, sourceOverride)
    } ?: return
    val imageModifier = if (fillPlaceholder) {
        modifier.fillMaxSize().clipToBounds()
    } else {
        modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(previewMediaShape())
    }
    DisableSelection {
        ChatRemoteClickableImage(
            source = source,
            modifier = imageModifier,
            contentScale = ContentScale.Fit,
            compactLoading = fillPlaceholder,
        )
    }
}

@Composable
private fun ChatRemoteClickableImage(
    source: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    compactLoading: Boolean = false,
) {
    val context = LocalContext.current
    val openPreview = LocalOpenChatImagePreview.current
    val view = LocalView.current
    val isVideo = AgentVideoCodec.isVideoSource(source)
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        val loaded = withContext(Dispatchers.IO) {
            if (isVideo) {
                val file = AgentVideoCodec.fileFromSource(source) ?: return@withContext null
                ChatImageBytes.load(context, AgentVideoCodec.previewThumbnail(file, source).reference)
            } else {
                ChatImageBytes.load(context, source)
            }
        }
        // ChatImageBytes.load 是阻塞 IO，withContext 即使已取消也会等它跑完再返回，
        // 因此不能只靠协程取消来阻止这次赋值：composition 已销毁时再写 state 会在
        // 后台线程触发一次 layout，宿主视图层级已拆掉就会崩（Robolectric 下表现为
        // CalledFromWrongThreadException / SlotTable 越界）。这里显式检查取消态。
        if (currentCoroutineContext().isActive) {
            bitmap = loaded?.bitmap
            failed = loaded == null
        }
    }
    val image = bitmap
    when {
        image != null -> {
            @Composable fun imageContent() {
                ChatClickableImage(
                    source = source,
                    bitmap = image,
                    contentDescription = stringResource(
                        if (isVideo) R.string.chat_video_preview else R.string.chat_image_preview,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
                if (isVideo) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.chat_video_preview),
                        tint = Color.White,
                        modifier = Modifier.size(EtaDimens.EmptyStateHintIconSize),
                    )
                }
            }
            if (compactLoading) {
                Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) { imageContent() }
            } else {
                // Bound the measured height explicitly. A fillMaxSize child in an unbounded
                // paragraph used to depend on the text placeholder's height rather than media.
                BoxWithConstraints(modifier.clipToBounds()) {
                    val ratio = image.width.toFloat() / image.height.coerceAtLeast(1)
                    val displayHeight = (maxWidth / ratio).coerceAtMost(320.dp).coerceAtMost(maxHeight)
                    Box(Modifier.fillMaxWidth().height(displayHeight), contentAlignment = Alignment.Center) {
                        imageContent()
                    }
                }
            }
        }
        isVideo && failed -> {
            val videoPoster: @Composable () -> Unit = {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.chat_video_preview),
                        tint = Color.White,
                        modifier = Modifier.size(EtaDimens.EmptyStateHintIconSize),
                    )
                }
            }
            val openPreviewAction: () -> Unit = {
                TouchHaptics.click(view)
                openPreview(source)
            }
            // M3 Expressive：官方 Surface(onClick) 承担容器、形状、容器色与按压涟漪；
            // 纯黑是视频信箱底色，属于媒体浮层固有色，不随主题变化。
            M3Surface(
                onClick = openPreviewAction,
                modifier = modifier.heightIn(min = 180.dp),
                color = Color.Black,
                content = videoPoster,
            )
        }
        failed -> Unit
        else -> Box(
            modifier = if (compactLoading) {
                modifier.background(previewPlaceholderColor())
            } else {
                modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(previewPlaceholderColor())
            },
            contentAlignment = Alignment.Center,
        ) {
            PreviewProgressIndicator(size = 18.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatImagePreviewDialog(
    sources: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onAttachImageToConversation: ((String) -> Unit)?,
    onAttachVideoToConversation: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0)),
        pageCount = { sources.size.coerceAtLeast(1) },
    )
    val source = sources.getOrNull(pagerState.currentPage).orEmpty()
    var loaded by remember(source) { mutableStateOf<LoadedChatImage?>(null) }
    var saving by remember { mutableStateOf(false) }
    var scale by remember(pagerState.currentPage) { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember(pagerState.currentPage) { mutableStateOf(Offset.Zero) }

    val isVideo = AgentVideoCodec.isVideoSource(source)
    LaunchedEffect(source) {
        if (source.isBlank() || isVideo) {
            loaded = null
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { ChatImageBytes.load(context, source) }
        // 同为阻塞 IO：协程被取消后 withContext 仍会返回，宿主已销毁时再写 state 会崩。
        if (currentCoroutineContext().isActive) loaded = result
    }

    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = scale <= MIN_ZOOM && sources.size > 1,
            ) { page ->
                ChatImagePreviewPage(
                    source = sources.getOrNull(page).orEmpty(),
                    active = page == pagerState.currentPage,
                    scale = if (page == pagerState.currentPage) scale else MIN_ZOOM,
                    offset = if (page == pagerState.currentPage) offset else Offset.Zero,
                    onTransform = { nextScale, nextOffset ->
                        scale = nextScale
                        offset = nextOffset
                    },
                )
            }
            val image = loaded
            val saveEnabled = !saving && (isVideo || image != null)
            val saveAction: () -> Unit = {
                TouchHaptics.click(view)
                saving = true
                scope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        if (isVideo) {
                            ChatImageGallery.saveVideo(context, source)
                        } else {
                            val payload = loaded ?: return@withContext null
                            // 保存到相册保留原始文件名（原始文件 / 原始名字，不重命名）。
                            ChatImageGallery.save(
                                context,
                                payload.bytes,
                                payload.mimeType,
                                previewOriginalName(context, source),
                            )
                        }
                    }
                    saving = false
                    Toast.makeText(
                        context,
                        context.getString(
                            if (uri != null) {
                                R.string.chat_image_saved
                            } else {
                                R.string.chat_image_save_failed
                            },
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            // 工具栏五个按钮共用一套官方 IconButton 令牌：内容色跟随 tertiaryContainer 容器的
            // onTertiaryContainer（涟漪 / 按压形变仍走官方默认）。
            val toolbarButtonColors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            val clipboard = LocalClipboardManager.current
            val copyPathAction: () -> Unit = {
                TouchHaptics.click(view)
                val path = previewAbsolutePath(context, source)
                if (path.isNullOrBlank()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.chat_image_path_copy_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    clipboard.setText(AnnotatedString(path))
                    Toast.makeText(
                        context,
                        context.getString(R.string.chat_image_path_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            // 添加到对话后直接关闭预览，回到输入区即可看到待发缩略图。
            // 视频与图片走各自宿主的附件入口；视频源是本机绝对路径，先补 file:// scheme
            // （无 scheme 的路径 Uri.parse 后 contentResolver 打不开）。
            val attachCallback = if (isVideo) onAttachVideoToConversation else onAttachImageToConversation
            val attachEnabled = source.isNotBlank() && attachCallback != null
            val attachAction: () -> Unit = {
                TouchHaptics.click(view)
                val reference = if (isVideo) previewVideoAttachReference(source) else source
                attachCallback?.invoke(reference)
                onDismiss()
            }
            val shareEnabled = source.isNotBlank() && (isVideo || image != null)
            val openWithAction: () -> Unit = {
                TouchHaptics.click(view)
                scope.launch {
                    // 载荷构建可能落缓存字节，走 IO；startActivity 与 Toast 回到主线程。
                    val payload = withContext(Dispatchers.IO) {
                        previewSharePayload(context, source, loaded)
                    }
                    if (payload == null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_image_open_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@launch
                    }
                    val (uri, mime) = payload
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        clipData = android.content.ClipData.newRawUri(source, uri)
                    }
                    val launched = runCatching {
                        context.startActivity(Intent.createChooser(intent, null))
                    }.isSuccess
                    if (!launched) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_image_open_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            val shareAction: () -> Unit = {
                TouchHaptics.click(view)
                scope.launch {
                    val payload = withContext(Dispatchers.IO) {
                        previewSharePayload(context, source, loaded)
                    }
                    if (payload == null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_image_share_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@launch
                    }
                    val (uri, mime) = payload
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        clipData = android.content.ClipData.newRawUri(source, uri)
                    }
                    val launched = runCatching {
                        context.startActivity(Intent.createChooser(intent, null))
                    }.isSuccess
                    if (!launched) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_image_share_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            // 页码指示器：单张图没有“第几张”可指，只在多图画廊里显示。
            if (sources.size > 1) {
                PreviewPageIndicator(
                    page = pagerState.currentPage + 1,
                    total = sources.size,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 12.dp),
                )
            }
            // 保存中的形变指示器：搬到原下载钮的右上角，让位给底部工具栏。
            if (saving) {
                PreviewProgressIndicator(
                    size = 20.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp),
                )
            }
            // M3E 官方浮动工具栏（M3 toolbars 组件）：底部居中的药丸，tertiaryContainer 容器 +
            // onTertiaryContainer 内容色；保存 / 复制绝对路径 / 添加到对话 / 其他应用打开 / 分享。
            // leadingContent 传空：官方 minimumInteractiveBalancedPadding 只在两侧都没有
            // leading/trailing 内容时生效，会把纯 IconButton 内容的工具栏撑高（见输入区同款注释）。
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                    toolbarContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    toolbarContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
                // 端盖同心：首末两枚 40dp 圆钮与药丸端头弧心对齐（环隙与上下等宽）。
                contentPadding = EtaFloatingToolbarConcentricPadding(
                    IconButtonDefaults.smallContainerSize().width,
                ),
                leadingContent = {},
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                PreviewToolbarButton(
                    icon = Icons.Rounded.Download,
                    label = stringResource(R.string.action_save),
                    onClick = saveAction,
                    enabled = saveEnabled,
                    colors = toolbarButtonColors,
                )
                Spacer(modifier = Modifier.width(PreviewToolbarGap))
                PreviewToolbarButton(
                    icon = Icons.Rounded.ContentCopy,
                    label = stringResource(R.string.chat_image_copy_path),
                    onClick = copyPathAction,
                    enabled = source.isNotBlank(),
                    colors = toolbarButtonColors,
                )
                Spacer(modifier = Modifier.width(PreviewToolbarGap))
                PreviewToolbarButton(
                    icon = Icons.Rounded.AddPhotoAlternate,
                    label = stringResource(
                        if (isVideo) {
                            R.string.chat_video_add_to_conversation
                        } else {
                            R.string.chat_image_add_to_conversation
                        },
                    ),
                    onClick = attachAction,
                    enabled = attachEnabled,
                    colors = toolbarButtonColors,
                )
                Spacer(modifier = Modifier.width(PreviewToolbarGap))
                PreviewToolbarButton(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    label = stringResource(R.string.chat_image_open_with),
                    onClick = openWithAction,
                    enabled = shareEnabled,
                    colors = toolbarButtonColors,
                )
                Spacer(modifier = Modifier.width(PreviewToolbarGap))
                PreviewToolbarButton(
                    icon = Icons.Rounded.Share,
                    label = stringResource(R.string.chat_image_share),
                    onClick = shareAction,
                    enabled = shareEnabled,
                    colors = toolbarButtonColors,
                )
            }
        }
    }
}

/** 底部工具栏相邻按钮的间距（与输入区浮动工具栏同款小间隔）。 */
private val PreviewToolbarGap = 4.dp

/**
 * 页码指示器：`x/y` 胶囊，secondaryContainer 底板 + onSecondaryContainer 文字。
 *
 * 走官方 [M3Surface] 承担容器 / 形状 / 容器色；视觉只显示 “x/y”，
 * 读屏经 clearAndSetSemantics 统一念完整句式（“第 x 张，共 y 张”）。
 */
@Composable
private fun PreviewPageIndicator(
    page: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val spoken = stringResource(R.string.chat_image_page_of, page, total)
    M3Surface(
        // 读屏只念完整句式；视觉上的 “x/y” 由 Text 承担，清掉子节点语义避免重复。
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = "$page/$total",
            style = etaTextStyle(EtaType.Caption),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 工具栏单键：官方 IconButton 涟漪 + small 容器 / small 图标档位（见输入区同款用法）。 */
@Composable
private fun PreviewToolbarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
) {
    M3IconButton(
        onClick = onClick,
        enabled = enabled,
        // small 容器：五个动作横排也要在窄屏（折叠态 / 小手机）放得下。
        modifier = modifier.size(IconButtonDefaults.smallContainerSize()),
        colors = colors,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
}

/**
 * 预览源 → 视频附件入口可用的 Uri 字符串。
 *
 * 视频附件入口（attachVideo → AgentVideoCodec.importFromUri）只吃 contentResolver
 * 能打开的 Uri：预览画廊里的视频源都是本机绝对路径（stage 目录 / cache 缓存），
 * 无 scheme 的路径 `Uri.parse` 后打开会失败，这里补上 file:// scheme；
 * 已是 content:// / file:// / data: 的原样透传。
 */
private fun previewVideoAttachReference(source: String): String {
    val trimmed = source.trim()
    if (trimmed.startsWith("file://", ignoreCase = true) ||
        trimmed.startsWith("content://", ignoreCase = true) ||
        trimmed.startsWith("data:", ignoreCase = true)
    ) {
        return trimmed
    }
    return if (trimmed.startsWith("/")) "file://$trimmed" else trimmed
}

/**
 * 预览源 → 宿主绝对路径。与 [ChatImageBytes] 同一套解析：
 * file:// 与 Android 绝对路径原样；/workspace、minis:// 折算到宿主；
 * content:// 查 MediaStore `_data`（查不到退回 URI 本身）；http(s) 原样；
 * data: URL 没有路径概念，返回 null。
 */
private fun previewAbsolutePath(context: Context, source: String): String? {
    val trimmed = source.trim()
    return when {
        trimmed.isEmpty() -> null
        trimmed.startsWith("data:", ignoreCase = true) -> null
        trimmed.startsWith("content://", ignoreCase = true) ->
            contentUriAbsolutePath(context, trimmed) ?: trimmed
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("file://", ignoreCase = true) ->
            Uri.parse(trimmed).path
        trimmed.startsWith("minis://", ignoreCase = true) ->
            LinuxGuestPathResolver.resolveForApp(context, trimmed)
        trimmed.startsWith("/") -> LinuxGuestPathResolver.resolveForApp(context, trimmed)
        // 相对路径（无 scheme）按 Linux 工作区根解析，与 ChatImageBytes 读图同一映射。
        else -> java.io.File(LinuxGuestPathResolver.workspaceHostForApp(context), trimmed).path
    }
}

/** content:// 查 MediaStore `_data` 列拿宿主路径；SAF / Photo Picker 等非 MediaStore 文档查不到，返回 null。 */
private fun contentUriAbsolutePath(context: Context, source: String): String? {
    val uri = Uri.parse(source)
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * 预览源 → 可授予临时读权限的 content uri + MIME，供“用其他应用打开”与“分享”共用。
 *
 * 设计原则：直接操作【原始文件 / 原始路径 / 原始文件名】——不复制、不重命名。
 * file_paths.xml 已声明原始文件可能所在的全部根目录（cache、files、外部存储、终端 workspace），
 * 因此这里拿到的就是原始文件本身；FileProvider 生成的 content uri 会自动沿用原始文件名，
 * 接收方不会再把它误判成图片。整段包在 runCatching：仅当源既非本地文件、又无已加载字节
 * （如纯 data:/无法落地的 http）时才降级返回 null，由调用方提示失败。
 */
private fun previewSharePayload(
    context: Context,
    source: String,
    loaded: LoadedChatImage?,
): Pair<Uri, String>? = runCatching {
    val authority = "${context.packageName}.fileprovider"
    val isVideo = AgentVideoCodec.isVideoSource(source)
    val localPath = previewAbsolutePath(context, source)
    // 本地真实文件（file://、绝对路径、/workspace、minis:// 折算的宿主路径、外部存储等）：
    // 直接分享原始文件本体，文件名即原始文件名，不复制改名。
    if (localPath != null &&
        !localPath.startsWith("content://", ignoreCase = true) &&
        !localPath.startsWith("http://", ignoreCase = true) &&
        !localPath.startsWith("https://", ignoreCase = true)
    ) {
        val file = java.io.File(localPath)
        if (file.isFile) {
            // 视频按真实视频 MIME（嗅探 + 扩展名兜底），其余用已加载 MIME 或嗅探。
            val mime = if (isVideo) {
                videoMimeForFile(file)
            } else {
                loaded?.mimeType ?: AgentVideoCodec.sniffFile(file) ?: "application/octet-stream"
            }
            return@runCatching FileProvider.getUriForFile(context, authority, file) to mime
        }
    }
    // 非本地源（http(s)/data: 等没有本机文件）：用已加载字节落盘到 cache 再分享。
    // 此处无法还原“原始文件名”，按 MIME 给合理扩展名，但仍走原始字节、不重新编码。
    val payload = loaded ?: return@runCatching null
    val directory = java.io.File(context.cacheDir, "chat-files")
    if (!directory.isDirectory && !directory.mkdirs()) return@runCatching null
    val cacheFile = java.io.File(
        directory,
        if (isVideo) chatVideoFileName(payload.mimeType) else chatImageFileName(payload.mimeType),
    )
    cacheFile.writeBytes(payload.bytes)
    FileProvider.getUriForFile(context, authority, cacheFile) to payload.mimeType
}.getOrNull()

/**
 * 视频文件 → 视频 MIME：先嗅探头（mp4/webm），嗅不到再按扩展名推断
 * （mov/mkv/avi/3gp 等嗅探库认不出，否则会回退成 application/octet-stream）。
 */
private fun videoMimeForFile(file: java.io.File): String {
    return AgentVideoCodec.sniffFile(file) ?: when (file.extension.lowercase(Locale.US)) {
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        else -> "video/mp4"
    }
}

/**
 * 预览源 → 原始文件名（用于“保存到相册”时保留原名，不重命名）。
 * 本地文件取文件本名；http(s) 取 URL 末段（带扩展名时）；data:/content:// 无本名返回空串，
 * 由上层回退到生成名。
 */
private fun previewOriginalName(context: Context, source: String): String {
    val trimmed = source.trim()
    return when {
        trimmed.startsWith("data:", ignoreCase = true) -> ""
        trimmed.startsWith("content://", ignoreCase = true) -> ""
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ->
            trimmed.substringAfterLast('/').substringBefore('?').takeIf { it.contains('.') } ?: ""
        else -> {
            val p = previewAbsolutePath(context, trimmed) ?: trimmed
            java.io.File(p).name
        }
    }
}

@Composable
private fun ChatImagePreviewPage(
    source: String,
    active: Boolean,
    scale: Float,
    offset: Offset,
    onTransform: (Float, Offset) -> Unit,
) {
    val context = LocalContext.current
    val currentScale = rememberUpdatedState(scale)
    val currentOffset = rememberUpdatedState(offset)
    val currentTransform = rememberUpdatedState(onTransform)
    var loaded by remember(source) { mutableStateOf<LoadedChatImage?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    val isVideo = AgentVideoCodec.isVideoSource(source)
    LaunchedEffect(source) {
        if (source.isBlank() || isVideo) {
            loaded = null
            failed = source.isBlank()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { ChatImageBytes.load(context, source) }
        // 同上：只在仍存活时落 state。
        if (currentCoroutineContext().isActive) {
            loaded = result
            failed = result == null
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (isVideo) {
            ChatVideoPreviewPlayer(
                source = source,
                active = active,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        val image = loaded
        when {
            image != null -> {
                Image(
                    bitmap = image.bitmap,
                    contentDescription = stringResource(R.string.chat_image_preview),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize()
                        .pointerInput(source, active) {
                            if (!active) return@pointerInput
                            detectPreviewGestures(
                                scale = { currentScale.value },
                                offset = { currentOffset.value },
                                onTransform = { nextScale, nextOffset ->
                                    currentTransform.value(nextScale, nextOffset)
                                },
                            )
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }
            failed -> {
                Text(
                    text = stringResource(R.string.chat_image_load_failed),
                    color = Color.White,
                    style = previewBodyStyle(),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> {
                PreviewProgressIndicator(
                    size = 28.dp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        }
    }
}

private suspend fun PointerInputScope.detectPreviewGestures(
    scale: () -> Float,
    offset: () -> Offset,
    onTransform: (Float, Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val pointerCount = event.changes.count { it.pressed }
            val currentScale = scale()
            val zoomed = currentScale > MIN_ZOOM + 0.001f
            if (pointerCount >= 2 || zoomed) {
                event.changes.forEach { change ->
                    if (change.positionChanged()) change.consume()
                }
                val nextScale = (currentScale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                onTransform(
                    nextScale,
                    if (nextScale <= MIN_ZOOM) Offset.Zero else offset() + pan,
                )
            }
        } while (event.changes.any { it.pressed })
    }
}


@Composable
private fun ChatVideoPreviewPlayer(
    source: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VideoView(context).apply {
                setOnPreparedListener { player ->
                    player.isLooping = true
                    if (active) start()
                }
            }
        },
        update = { view ->
            val uri = when {
                source.startsWith("content://") || source.startsWith("file://") -> Uri.parse(source)
                source.startsWith("/") -> Uri.fromFile(java.io.File(source))
                else -> Uri.parse(source)
            }
            if (view.tag != source) {
                view.tag = source
                view.setVideoURI(uri)
            }
            if (active) {
                if (!view.isPlaying) view.start()
            } else if (view.isPlaying) {
                view.pause()
            }
        },
    )
}

/** Prepared document image entry; no AST traversal or reparsing in composition. */
@Composable
internal fun ChatDocumentImage(source: String, modifier: Modifier = Modifier) {
    DisableSelection {
        ChatRemoteClickableImage(
            source = source,
            modifier = modifier.fillMaxWidth().heightIn(max = 320.dp).clip(previewMediaShape()),
            contentScale = ContentScale.Fit,
            compactLoading = false,
        )
    }
}
