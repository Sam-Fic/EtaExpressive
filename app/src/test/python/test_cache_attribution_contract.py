"""缓存归因导出链路的文本级契约：不编译 Kotlin，只锁住字段传递不中途丢失。

一次会话里 `cached=0` 有两种成因：请求前缀被我们改写，或上游缓存丢失。
AgentLoop 已经算出 tools/system/history 三段状态并发事件，但落库与导出此前
只留了 token 五项，事后无法区分。这里锁住「事件 → UI 行 → DB 列 → 导出」
每一环都在，以及导入后再次导出不丢。
"""
from pathlib import Path
import unittest

APP = Path(__file__).resolve().parents[3]
MAIN = APP / 'src/main/kotlin/io/github/mangi/eta'
DB = MAIN / 'data/db'
REPO = MAIN / 'data/repository'
UI = MAIN / 'ui'


def source(path):
    return (MAIN / path).read_text(encoding='utf-8')


class CacheAttributionContractTest(unittest.TestCase):
    def setUp(self):
        self.entities = source('data/db/ConversationEntities.kt')
        self.database = source('data/db/EtaDatabase.kt')
        self.format = source('data/repository/EtaConversationAnalysisFormat.kt')
        self.rounds = source('data/repository/EtaConversationAnalysisRounds.kt')
        self.repository = source('data/repository/EtaConversationAnalysisRepository.kt')
        self.store = source('ui/app/AgentConversationStore.kt')
        self.app_state = source('ui/app/AgentAppState.kt')
        self.ui_state = source('ui/model/AgentChatUiState.kt')
        self.codec = source('data/model/UsageDiagnostics.kt')

    def test_diagnostics_codec_covers_four_states_and_cache_writes(self):
        for key in ('prefixState', 'toolsState', 'systemState', 'historyState',
                    'cacheCreationTokens'):
            self.assertIn('val ' + key, self.codec)
        self.assertIn('fun encode(value: UsageDiagnostics?): String', self.codec)
        self.assertIn('fun decode(raw: String?): UsageDiagnostics?', self.codec)
        self.assertIn('runCatching { JSONObject(raw) }', self.codec)

    def test_schema_migration_adds_column_with_idempotent_guard(self):
        self.assertRegex(self.entities,
                         r'usage_diagnostics_json", defaultValue = "\'\'"')
        self.assertIn('internal const val ETA_SCHEMA_VERSION = 41', self.database)
        block = self.database.split('internal val MIGRATION_40_41 =', 1)[1]
        block = block.split('internal val MIGRATION_30_31', 1)[0]
        self.assertIn('Migration(40, 41)', block)
        self.assertIn('tableHasColumn(database, "conversation_messages", "usage_diagnostics_json")', block)
        self.assertIn('ALTER TABLE conversation_messages ADD COLUMN', block)
        self.assertIn('MIGRATION_40_41,', self.database.split('ALL_MIGRATIONS: Array<Migration>', 1)[1])

    def test_ui_row_keeps_diagnostics_and_is_persisted_both_ways(self):
        self.assertIn('val usageDiagnostics: UsageDiagnostics? = null', self.ui_state)
        self.assertIn('message.copy(usage = null, usageDiagnostics = null)', self.ui_state)
        self.assertIn('usageDiagnosticsJson = UsageDiagnosticsJson.encode(usageDiagnostics)', self.store)
        self.assertIn('usageDiagnostics = UsageDiagnosticsJson.decode(usageDiagnosticsJson)', self.store)
        # 真实回执带四段状态；本地估算不写诊断。
        branch = self.app_state.split('is AgentEvent.UsageReceived -> {', 1)[1]
        branch = branch.split('is AgentEvent.UserSupplementReceived', 1)[0]
        self.assertIn('event.requestPrefixState', branch)
        self.assertIn('event.requestToolsState', branch)
        self.assertIn('event.requestSystemState', branch)
        self.assertIn('event.requestHistoryState', branch)
        self.assertIn('event.usage.cacheCreationTokens', branch)
        self.assertIn('usageDiagnostics = diagnostics', self.app_state)
        self.assertIn('diagnostics ?: target.usageDiagnostics', self.app_state)

    def test_export_format_carries_round_level_and_message_level_evidence(self):
        self.assertIn('data class EtaConversationAnalysisCacheDiagnostics', self.format)
        for field in ('prefixState', 'toolsState', 'systemState', 'historyState',
                      'cacheCreationTokens'):
            self.assertIn('val ' + field, self.format)
        self.assertIn('val cache: EtaConversationAnalysisCacheDiagnostics? = null', self.format)
        self.assertIn('val cacheCreationTokens: Long = 0', self.format)
        self.assertIn('cacheCreationTokens = cacheCreationTokens + other.cacheCreationTokens',
                      self.format)
        self.assertIn('message.cache?.let { target.cache = it }', self.rounds)
        self.assertIn('cacheCreationTokens = message.cacheCreationTokens', self.rounds)

    def test_repository_decodes_for_export_and_reencodes_for_import(self):
        self.assertIn('val diagnostics = UsageDiagnosticsJson.decode(usageDiagnosticsJson)',
                      self.repository)
        self.assertIn('cacheCreationTokens = diagnostics?.cacheCreationTokens', self.repository)
        self.assertIn('cache = cache,', self.repository)
        self.assertIn('usageDiagnosticsJson = UsageDiagnosticsJson.encode(diagnostics)',
                      self.repository)


if __name__ == '__main__':
    unittest.main()
