# AGENTS.md

Eta — Android Jetpack Compose（Kotlin）+ Material 3 Expressive（material3 1.5.0-alpha29）的 AI 终端 Agent 应用。

## 强制规则

- 只用官方 M3 组件与令牌，不自造组件、形状、尺寸。
- 引入或调用 Material3 / Compose / miuix 组件前，必须确认真实签名、参数名与默认值与本仓依赖版本一致（查 sources jar 或 AAR `javap`），不得凭记忆写参数名；结论写进调用处 KDoc。
- 设计护栏、行为红线与取证流程见 `docs/RULES.md`，以该文档为准。
- 代码注释与 KDoc 用中文。
- **容器型按钮直用必须传官方 `shapes`（M3E 按压 morph）**：TextButton 与聊天输入区豁免，细则见 `docs/RULES.md` §2.5。
- **整屏子页必须做成路由**；确实不能路由的页内子页（如共用不可序列化运行态），手补转场必须逐项对齐 NavDisplay 程序化转场（规格 + effects 层缺一不可），细则见 `docs/RULES.md` §3.3。

## 工程纪律

- **契约测试同步**：`app/src/test/python/` 下的契约测试对 Kotlin 源码做文本级结构断言；改动被断言的源码结构（类名、函数签名、调用形态）必须同步修改对应契约测试，否则 CI verify 挂掉。
- **发版装机**：本地构建安装用 `./gradlew :app:assembleRelease -x prepareSpeechRuntime --console=plain && adb install -r ...`（`-x` 必带，联网拉语音包会 502）；发版全流程见 `docs/RELEASE.md`。
- **多会话并行提交**：只提交自己改动的那部分文件，纯自己的用 `git add`；与他人共改的文件先 `git show HEAD:<path>` 核对，不得用 `git stash`（会卷走并行会话的改动）。
- **单测环境**：Gradle 测试 worker 在含中文的 `GRADLE_USER_HOME` 路径下起不来（`ClassNotFoundException: GradleWorkerMain`），定向单测加 `-Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=GBK"`，根治办法是把 Gradle 用户目录挪到纯 ASCII 路径。
