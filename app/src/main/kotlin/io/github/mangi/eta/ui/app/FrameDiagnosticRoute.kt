package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.components.FrameDiagnosticPage
import io.github.mangi.eta.ui.navigation.AppRoute

/** Exhaustive mapping intentionally ignores all route payloads. */
internal fun AppRoute.frameDiagnosticPage(): FrameDiagnosticPage = when (this) {
    AppRoute.Home -> FrameDiagnosticPage.Home
    AppRoute.Browser -> FrameDiagnosticPage.Browser
    AppRoute.Terminal -> FrameDiagnosticPage.Terminal
    AppRoute.Tools -> FrameDiagnosticPage.Tools
    AppRoute.AgentTaskPreference -> FrameDiagnosticPage.AgentTaskPreference
    AppRoute.VirtualDisplay -> FrameDiagnosticPage.AgentTaskPreference
    AppRoute.VirtualDisplayRecovery -> FrameDiagnosticPage.AgentTaskPreference
    AppRoute.Haptics -> FrameDiagnosticPage.Haptics
    AppRoute.Skills -> FrameDiagnosticPage.Skills
    AppRoute.Permissions -> FrameDiagnosticPage.Permissions
    AppRoute.SystemEnhance -> FrameDiagnosticPage.SystemEnhance
    AppRoute.Settings -> FrameDiagnosticPage.Settings
    AppRoute.SettingsDetailEmpty -> FrameDiagnosticPage.Settings
    AppRoute.SettingsSearch -> FrameDiagnosticPage.Settings
    AppRoute.SystemTakeover -> FrameDiagnosticPage.Settings
    AppRoute.About -> FrameDiagnosticPage.Settings
    AppRoute.AboutCredits -> FrameDiagnosticPage.Settings
    AppRoute.SpeechSettings -> FrameDiagnosticPage.VoiceSettings
    AppRoute.TtsSettings -> FrameDiagnosticPage.VoiceSettings
    AppRoute.VoiceSettings -> FrameDiagnosticPage.VoiceSettings
    AppRoute.DoubaoVoices -> FrameDiagnosticPage.VoiceSettings
    AppRoute.MimoVoices -> FrameDiagnosticPage.VoiceSettings
    AppRoute.AuxiliaryVision -> FrameDiagnosticPage.AuxiliaryVision
    AppRoute.TitleModel -> FrameDiagnosticPage.TitleModel
    AppRoute.ErrorReconnectSettings -> FrameDiagnosticPage.ErrorReconnectSettings
    AppRoute.LiveActivitySettings -> FrameDiagnosticPage.Settings
    AppRoute.WebToolsSettings -> FrameDiagnosticPage.Settings
    AppRoute.SubAgents -> FrameDiagnosticPage.SubAgents
    is AppRoute.SubAgentPresetDetail -> FrameDiagnosticPage.SubAgents
    AppRoute.VoiceModeSettings -> FrameDiagnosticPage.VoiceSettings
    AppRoute.AppearanceSettings -> FrameDiagnosticPage.AppearanceSettings
    AppRoute.DataBackup -> FrameDiagnosticPage.DataBackup
    AppRoute.Memory -> FrameDiagnosticPage.Memory
    is AppRoute.AssistantMemory -> FrameDiagnosticPage.Memory
    AppRoute.LinuxEnvironment -> FrameDiagnosticPage.LinuxEnvironment
    AppRoute.SharedFolders -> FrameDiagnosticPage.SharedFolders
    AppRoute.SharedFolderPicker -> FrameDiagnosticPage.SharedFolders
    AppRoute.Workspace -> FrameDiagnosticPage.Workspace
    is AppRoute.LinuxFiles -> FrameDiagnosticPage.LinuxFiles
    AppRoute.ModelProviders -> FrameDiagnosticPage.ModelProviders
    AppRoute.ModelProviderSearch -> FrameDiagnosticPage.ModelProviders
    AppRoute.McpServers -> FrameDiagnosticPage.McpServers
    is AppRoute.McpServerDetail -> FrameDiagnosticPage.McpServerDetail
    AppRoute.McpServerAdd -> FrameDiagnosticPage.McpServerAdd
    is AppRoute.ModelProviderDetail -> FrameDiagnosticPage.ModelProviderDetail
    is AppRoute.ModelProviderAuthMethod -> FrameDiagnosticPage.ModelProviderAuthMethod
    is AppRoute.ModelProviderNew -> FrameDiagnosticPage.ModelProviderNew
    is AppRoute.ModelProviderModelEdit -> FrameDiagnosticPage.ModelProviderModelEdit
    AppRoute.ContextCompression -> FrameDiagnosticPage.ContextCompression
    AppRoute.DecisionModel -> FrameDiagnosticPage.Settings
    is AppRoute.Assistants -> FrameDiagnosticPage.Assistants
    is AppRoute.AssistantSearch -> FrameDiagnosticPage.Assistants
    is AppRoute.AssistantEdit -> FrameDiagnosticPage.AssistantEdit
    AppRoute.UsageStats -> FrameDiagnosticPage.UsageStats
    AppRoute.ManageChats -> FrameDiagnosticPage.ManageChats
    AppRoute.ManageFolders -> FrameDiagnosticPage.ManageChats
    AppRoute.ConversationTrash -> FrameDiagnosticPage.ManageChats
    AppRoute.ConversationTrashSearch -> FrameDiagnosticPage.ManageChats
    is AppRoute.MessageSearch -> FrameDiagnosticPage.ManageChats
    AppRoute.ConversationTokenUsage -> FrameDiagnosticPage.UsageStats
    AppRoute.SubAgentStatus -> FrameDiagnosticPage.SubAgents
    AppRoute.TerminalDaemonTasks -> FrameDiagnosticPage.Terminal
    AppRoute.MediaSearch -> FrameDiagnosticPage.Settings
    AppRoute.MediaIndexSettings -> FrameDiagnosticPage.Settings
    AppRoute.MobileActionsSettings -> FrameDiagnosticPage.Settings
    AppRoute.MobileActions -> FrameDiagnosticPage.Settings
    AppRoute.ToolRecipes -> FrameDiagnosticPage.ToolRecipes
    is AppRoute.ToolRecipeEdit -> FrameDiagnosticPage.ToolRecipeEdit
    AppRoute.RemoteAgents -> FrameDiagnosticPage.Settings
    is AppRoute.RemoteAgentEditor -> FrameDiagnosticPage.Settings
}
