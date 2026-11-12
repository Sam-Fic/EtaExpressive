package io.github.mangi.eta.ui.components

/** Static labels only: never route.toString(), IDs, URLs, names, or user content. */
internal enum class FrameDiagnosticPage {
    Unknown, Transition, BrowserOverlay, ConversationDrawer,
    Home, Chat, Browser, Terminal, Tools, AgentTaskPreference, VirtualDisplayRecovery, Haptics, Skills, Permissions, SystemEnhance, Settings, SpeechSettings, TtsSettings, VoiceSettings, AuxiliaryVision, TitleModel, ErrorReconnectSettings, SubAgents, VoiceModeSettings, AppearanceSettings, DataBackup, Memory, LinuxEnvironment, SharedFolders, Workspace, LinuxFiles, ModelProviders, McpServers, McpServerDetail, McpServerAdd, ModelProviderDetail, ModelProviderAuthMethod, ModelProviderNew, ModelProviderModelEdit, ContextCompression, Assistants, AssistantEdit, UsageStats, ManageChats, ToolRecipes, ToolRecipeEdit;

    val frameStage: String = "frame.page.$name"
}

internal data class FramePageAttribution(
    val start: FrameDiagnosticPage,
    val end: FrameDiagnosticPage,
    val changed: Boolean,
    val startSegment: Long = 0,
    val endSegment: Long = 0,
) {
    val aggregatePage: FrameDiagnosticPage get() = when {
        start == FrameDiagnosticPage.Unknown || end == FrameDiagnosticPage.Unknown -> FrameDiagnosticPage.Unknown
        changed -> FrameDiagnosticPage.Transition
        else -> start
    }
    fun fields(): String = "page=${start.name} pageEnd=${end.name} pageChanged=$changed pageSource=route"
}

/** Bounded route history. Read immutable snapshots without contending with the UI thread. */
internal class FramePageTimeline(private val capacity: Int = 64) {
    private data class Entry(val atNs: Long, val page: FrameDiagnosticPage, val segment: Long)
    private var nextSegment = 0L
    @Volatile private var entries: List<Entry> = emptyList()

    init { require(capacity >= 2) }

    @Synchronized fun mark(page: FrameDiagnosticPage, nowNs: Long) {
        val last = entries.lastOrNull()
        if (last?.page == page) return
        require(last == null || nowNs >= last.atNs)
        entries = (entries.takeLast(capacity - 1) + Entry(nowNs, page, ++nextSegment))
    }

    fun attributeFrame(intendedNs: Long, totalNs: Long): FramePageAttribution {
        if (intendedNs < 0 || totalNs < 0 || intendedNs > Long.MAX_VALUE - totalNs) {
            return FramePageAttribution(FrameDiagnosticPage.Unknown, FrameDiagnosticPage.Unknown, false)
        }
        return attribute(intendedNs, intendedNs + totalNs)
    }

    /** Resolve the frame's timestamps, not the later callback delivery time. */
    fun attribute(startNs: Long, endNs: Long): FramePageAttribution {
        val snapshot = entries
        if (endNs < startNs) return FramePageAttribution(FrameDiagnosticPage.Unknown, FrameDiagnosticPage.Unknown, false)
        var endIndex = snapshot.lastIndex
        while (endIndex >= 0 && snapshot[endIndex].atNs > endNs) endIndex--
        var startIndex = endIndex
        while (startIndex >= 0 && snapshot[startIndex].atNs > startNs) startIndex--
        val start = snapshot.getOrNull(startIndex)?.page ?: FrameDiagnosticPage.Unknown
        val end = snapshot.getOrNull(endIndex)?.page ?: FrameDiagnosticPage.Unknown
        return FramePageAttribution(start, end, startIndex != endIndex,
            snapshot.getOrNull(startIndex)?.segment ?: 0, snapshot.getOrNull(endIndex)?.segment ?: 0)
    }
}
