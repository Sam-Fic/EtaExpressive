# 第三方声明

新增、移植或随包分发第三方组件时，按 [第三方许可证核查清单](THIRD_PARTY_LICENSE_CHECKLIST.md) 逐项核对，保证本页、README「参考与致谢」与应用内关于页三处登记同时对齐。

## 终端原生组件

Eta 的免 Root Linux 后端以独立进程运行 [PRoot](https://github.com/termux/proot)，并静态链接 [talloc](https://talloc.samba.org/) 与 [libandroid-shmem](https://github.com/termux/libandroid-shmem)。PRoot 源码采用 GPL-2.0-or-later；talloc 采用 LGPL-3.0-or-later；libandroid-shmem 采用 BSD-3-Clause。附带的组合 PRoot 可执行程序按 GPL-3.0-or-later 分发，各组件版权与许可证保留。

APK 的 `assets/native-sources` 附带校验过的原始源码，以及从实际构建入口自动生成的脚本、PTY 源码和补丁包；`assets/licenses` 附带许可证全文。构建脚本与说明位于 [终端原生组件](TERMINAL_NATIVE.md)。这些独立第三方程序保持其开源许可权利，不受 Eta 主项目现行 GPL-3.0 许可证的附加限制。

## Material 3 组件

Eta 的应用界面使用官方 Jetpack Compose Material 3 Expressive 组件，依赖版本由 `gradle/libs.versions.toml` 声明，采用 [Apache License 2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt)。

## Material Icons

Eta 的功能图标使用 AndroidX Compose Material Icons 的 Rounded 系列，通过 `material-icons-extended` 依赖提供。图标及其 AndroidX 实现采用 [Apache License 2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt)，来源见 [Material Design Icons](https://github.com/google/material-design-icons)。

## Markdown 渲染

聊天消息与悬浮内容的 Markdown 渲染使用 [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)（Maven 依赖 `com.mikepenz:multiplatform-markdown-renderer-android` 与 `com.mikepenz:multiplatform-markdown-renderer-m3-android`，版本由 `gradle/libs.versions.toml` 声明），采用 [Apache License 2.0](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/develop/LICENSE)。该库的 markdown composer 部分基于 Erik Hellman 的 [MarkdownComposer](https://github.com/ErikHellman/MarkdownComposer)，采用 MIT License。

## Lucide Atom

思考图标使用 [Lucide Atom](https://github.com/lucide-icons/lucide/blob/main/icons/atom.svg)，以本地 VectorDrawable 资源保存，不依赖 Lucide 图标库。该图标采用 ISC License：

```text
ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## Android Hidden API Bypass

Eta 使用 [Android Hidden API Bypass](https://github.com/LSPosed/AndroidHiddenApiBypass) 应用用户选择的预测性返回设置。该库采用 [Apache License 2.0](https://github.com/LSPosed/AndroidHiddenApiBypass/blob/main/LICENSE)。

## Lobe Icons

模型与提供商品牌图标来自
[Lobe Icons](https://github.com/lobehub/lobe-icons) 的
`@lobehub/icons-static-avatar` 1.13.0。原始 1280×1280 WebP 素材在不改变颜色和比例的前提下，无损缩放为 128×128 后随 Eta 本地打包。

| Eta 资源 | Lobe Icons Avatar |
| --- | --- |
| `provider_logo_openai.webp` | `openai.webp` |
| `provider_logo_anthropic.webp` | `anthropic.webp` |
| `provider_logo_bailian.webp` | `bailian.webp` |
| `provider_logo_deepseek.webp` | `deepseek.webp` |
| `provider_logo_kimi.webp` | `kimi.webp` |
| `provider_logo_mimo.webp` | `xiaomimimo.webp` |
| `provider_logo_minimax.webp` | `minimax.webp` |
| `provider_logo_stepfun.webp` | `stepfun.webp` |
| `provider_logo_siliconflow.webp` | `siliconcloud.webp` |
| `provider_logo_openrouter.webp` | `openrouter.webp` |
| `model_logo_claude.webp` | `claude.webp` |
| `model_logo_qwen.webp` | `qwen.webp` |
| `model_logo_zai.webp` | `zai.webp` |
| `model_logo_chatglm.webp` | `chatglm.webp` |
| `model_logo_gemini.webp` | `gemini.webp` |
| `model_logo_gemma.webp` | `gemma.webp` |
| `model_logo_grok.webp` | `grok.webp` |
| `model_logo_meta.webp` | `meta.webp` |
| `model_logo_mistral.webp` | `mistral.webp` |
| `model_logo_doubao.webp` | `doubao.webp` |
| `model_logo_hunyuan.webp` | `hunyuan.webp` |
| `model_logo_yi.webp` | `yi.webp` |

Lobe Icons 使用 MIT License：

```text
MIT License

Copyright (c) 2023 LobeHub

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Kimi Code 图标

首页“启动 Kimi Web”入口的 `ic_kimi_code.xml` 转换自 Moonshot AI
[Kimi Code](https://github.com/MoonshotAI/kimi-code/blob/d1ca65e1de189617e9edbc54010e62d472a1de3d/apps/vscode/resources/kimi-icon.svg)
的单色侧栏图标。转换仅适配 Android VectorDrawable 格式，保留原始几何与比例。

Kimi Code 使用 MIT License：

```text
MIT License

Copyright (c) 2026 Moonshot AI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

OpenAI、Anthropic、Claude、阿里云百炼、Qwen、DeepSeek、Kimi、Xiaomi MiMo、MiniMax、StepFun、Z.ai、ChatGLM、Gemini、Gemma、Grok、Meta、Mistral、豆包、混元、Yi、硅基流动和 OpenRouter 的名称、图标及其他品牌标识归各自权利人所有。Eta 展示这些图标仅用于准确标识用户正在配置的模型服务，不表示这些厂商对 Eta 的赞助、认可或合作关系。OpenAI 图标的使用还应遵循其[品牌规范](https://openai.com/brand/)。

## 本地语义搜索运行时（LiteRT-LM 与 EmbeddingGemma 2）

媒体语义搜索使用 Google 的 [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) Android 运行时（Maven 依赖 `com.google.ai.edge.litertlm:litertlm-android:0.18.0`）与 Google DeepMind 的 EmbeddingGemma 2 嵌入模型，均采用 [Apache License 2.0](https://github.com/google-ai-edge/LiteRT-LM/blob/main/LICENSE)。

模型不打包进 APK，由用户在「媒体语义搜索」设置页按需从 Hugging Face 或 hf-mirror.com 镜像下载，下载后逐字节校验 SHA-256：

| 模型 | 来源仓库（litert-community） | revision | 文件 | 字节数 | SHA-256 |
| --- | --- | --- | --- | --- | --- |
| EmbeddingGemma 2 全模态 740M | `embeddinggemma-2-740m-litert-lm` | `24d962e906c7d332c6428e71c9676855024569e2` | `embeddinggemma-2-740m.litertlm` | 484622336 | `e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c` |
| EmbeddingGemma 2 纯文本 270M | `embeddinggemma-2-text-270m-litert-lm` | `9be6e8b90982095dc05c2bd162e4b954ee4dbac7` | `embeddinggemma-2-text-270m.litertlm` | 164626432 | `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb` |

EmbeddingGemma 模型名称与商标归 Google DeepMind 所有，本处使用仅用于准确标识所下载的开源模型制品。

## 离线语音运行时（sherpa-onnx 与 sherpa-ncnn）

Eta 的离线语音识别、合成与 VAD 基于两个 k2-fsa 引擎，Kotlin JNI 绑定随包分发，均采用 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)：

| 引擎 | 上游仓库 | 绑定版本 | 许可证 | 原生库 |
| --- | --- | --- | --- | --- |
| sherpa-onnx（ONNX Runtime） | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | v1.13.8 | Apache-2.0；ONNX Runtime 为 MIT | `libsherpa-onnx-jni.so`、`libonnxruntime.so` |
| sherpa-ncnn（ncnn / pnnx） | [k2-fsa/sherpa-ncnn](https://github.com/k2-fsa/sherpa-ncnn) | v2.1.15 | Apache-2.0 | `libsherpa-ncnn-jni.so`、`libncnn.so` |

两个引擎的 JNI 绑定从上游固定 tag 引入：sherpa-onnx 绑定原样 vendored 到 `app/src/main/kotlin/com/k2fsa/sherpa/onnx/`（包名、类名与 JNI 声明的配置字段逐字节保留，R8 keep 整个包）；sherpa-ncnn 绑定保留包与配置字段，裁去 demo 辅助并补充确定性 `AutoCloseable` 清理。原生库取官方 Android release，URL 与 SHA-256 固定在 `scripts/prepare-speech-runtime.py`，由 Gradle 在构建期下载校验。各引擎完整归属与模型清单见 `third_party/sherpa-onnx/NOTICE.md` 与 `third_party/sherpa-ncnn/NOTICE.md`。

随包原生库的底层组件适用各自许可证，许可证全文已随包并在「关于 › 开源许可」页可查：`libonnxruntime.so` 为 ONNX Runtime 的 [MIT License](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)（Copyright (c) Microsoft Corporation），全文见 `assets/licenses/onnxruntime-MIT.txt`；`libncnn.so` 为 Tencent [ncnn](https://github.com/Tencent/ncnn) 的 BSD 3-Clause License，其内嵌的 zlib、BSD-2 组件在官方 `LICENSE-ncnn` 中一并声明，全文见 `assets/licenses/ncnn-BSD-3-Clause.txt`。引擎的 Apache-2.0 授权不代替这两个库自身的许可义务。

与 EmbeddingGemma 一致，**语音模型权重不打包进 APK**。用户在语音设置页选择模型包与下载源（Hugging Face 或 ModelScope 镜像）后按需下载，逐文件校验字节数与 SHA-256；权重许可证不由引擎的 Apache 授权涵盖，下载确认步骤已提示此限制。

## 动态取色

Eta 的动态取色与 Material You 配色方案生成使用 [jordond/MaterialKolor](https://github.com/jordond/MaterialKolor) 的 `material-color-utilities` 模块（Maven 依赖 `com.materialkolor:material-color-utilities:5.0.0`，版本由 `gradle/libs.versions.toml` 声明）。该库整体采用 [MIT License](https://github.com/jordond/MaterialKolor/blob/main/LICENSE)；其中的 `material-color-utilities` 模块内含 Google 上游的 Material Color Utilities Kotlin 源码，采用 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)，该模块内保留了 Apache-2.0 全文（`material-color-utilities/src/commonMain/kotlin/com/materialkolor/LICENSE`）。Eta 使用该模块从壁纸或种子色生成 HCT / DynamicScheme 配色，供主题层取用。

## 可选语言运行时

Eta 不把 uv 或 Node.js 打包进 APK。用户主动安装对应 profile 时，Eta 下载当前固定的最新正式版制品；Debian 使用 Node.js 官方 Linux glibc 发行包，Alpine 使用其稳定仓库中的 `nodejs-current`，uv 按所选发行版下载官方 glibc 或 musl 制品。

| 工具 | 来源 | 许可证 |
| --- | --- | --- |
| uv / uvx | [astral-sh/uv](https://github.com/astral-sh/uv) | Apache License 2.0 / MIT |
| Node.js / npm / npx | [nodejs/node](https://github.com/nodejs/node) | MIT 及发行包内第三方许可证 |

## 可选 APK 分析工具

Eta 不把下列工具打包进 APK。用户在 Linux 工具环境页面主动安装“APK 分析”时，Eta 从固定官方 Release 下载并校验制品；工具保存在用户当前选择的 Alpine 或 Debian 环境中，适用各自许可证：

| 工具 | 来源 | 许可证 |
| --- | --- | --- |
| JADX | [skylot/jadx](https://github.com/skylot/jadx) | Apache License 2.0 |
| Apktool | [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool) | Apache License 2.0 |
| smali / baksmali | [google/smali](https://github.com/google/smali) | BSD 3-Clause License |

JADX 的发行包许可证会随所需 CLI 文件一并保留；Apktool、smali 与 baksmali 的许可证和第三方声明保留在各自 JAR 制品中。Eta 仅提供经过校验的安装、命令入口和能力边界，不对这些工具重新授权。

GitHub 的实际制品域名不可达或较慢时，安装器可能通过 `gh-proxy.com` 请求同一个公开 Release URL；Node.js 制品可能先通过 `cdn.npmmirror.com` 请求。下载入口会获知用户的网络地址及所请求的公开制品；Eta 不向其发送账号、Cookie、API Key 或其他 Eta 数据，并在落盘前继续校验内置的官方制品大小与 SHA-256。不希望使用下载入口的用户可以不安装对应可选档案。

## 内置浏览器（改编自 OpenMinis）

内置浏览器的源码改编自 [OpenMinis](https://github.com/OpenMinis/OpenMinis)（`agent/browser/ported/` 包，22 个 Kotlin 文件），上游以 GPL-3.0-only 分发。各文件头部带 `Adapted from OpenMinis/OpenMinis @ 4ef29002e88db1e20e462ec2ff46916e8a7dcb45` 固定版本钉与 `SPDX-License-Identifier: GPL-3.0-only` 标注，适配明细见 `third_party/openminis-browser/NOTICE.md` 与 `PORT_STATUS.md`（Eta 访客路径映射、Cookie 卸载、限额、接管编排等）。

该项目随 APK 分发。Eta 项目许可证已于 commit 2fafe5d6c 由 PolyForm Noncommercial 1.0.0 改为 GPL-3.0，与上游 GPL-3.0-only 同族兼容——改编代码随整体按 GPL-3.0 分发，对应源码可通过本仓库获取，满足上游保留许可证与归属的要求。

## miuix（导航容器）

页面切换转场使用 [miuix](https://github.com/compose-miuix-ui/miuix)（Maven 依赖 `libs.miuix.nav`，版本由 `gradle/libs.versions.toml` 声明）的 `NavDisplay` 导航容器，提供栈式转场动画与系统屏幕圆角适配；界面组件本体为 Material Design 3 Expressive，不使用 miuix 组件。miuix 采用 [Apache License 2.0](https://github.com/compose-miuix-ui/miuix/blob/main/LICENSE)，许可证全文随包见 `assets/licenses/Apache-2.0.txt`。

## libxposed API / Service

系统助手接管的 LSPosed 模块形态使用 [libxposed/api](https://github.com/libxposed/api) 与 [libxposed/service](https://github.com/libxposed/service)（Maven 依赖 `libs.libxposed.api` / `libs.libxposed.service`）。采用 [Apache License 2.0](https://github.com/libxposed/api/blob/main/LICENSE)，许可证全文随包见 `assets/licenses/Apache-2.0.txt`。

## Maven 网络与解析基础依赖

随包分发的 Maven 基础依赖：[OkHttp](https://github.com/square/okhttp)（含 `okhttp-sse`，[Apache License 2.0](https://github.com/square/okhttp/blob/master/LICENSE.txt)）承担网络与 SSE 流；[jsoup](https://jsoup.org/) 1.23.2（[MIT License](https://jsoup.org/license)，Copyright © 2009-2026 Jonathan Hedley）承担 HTML 解析。Apache-2.0 全文随包见 `assets/licenses/Apache-2.0.txt`；MIT 全文随包见 `assets/licenses/jsoup-MIT.txt`。

## 取餐码识别模块（移植自 Hyper-pick-up-code）

Eta 的取餐码 / 取件码识别功能参考并移植自 badnng 的开源项目 [Hyper-pick-up-code（澎湃记）](https://github.com/badnng/Hyper-pick-up-code)，原项目以 [GNU Affero General Public License v3.0（AGPL-3.0-only）](https://www.gnu.org/licenses/agpl-3.0.txt) 分发，版权归原作者所有。

依据 GPL-3.0 第 13 条（Remote Network Interaction），AGPL-3.0 覆盖的代码可并入 GPL-3.0 工程，合并后的整体按 Eta 现行的 GPL-3.0 分发。移植进 Eta 的相关代码随之以 GPL-3.0 对外提供，对应源码可通过本仓库获取，满足原项目关于分发修改版须提供源码并保留许可证与归属的要求。

原项目的识别能力依赖 PaddleOCR（PP-OCRv6 Tiny 模型）、ONNX Runtime、Google ML Kit 等第三方组件，各自适用其原始许可证。Eta 现已随包以下识别组件（均来自原项目仓库的分发物，未引入 Google ML Kit）：

- **PP-OCRv6 Tiny 模型**（`assets/models/ppocrv6_tiny/`，det/rec `inference.onnx` 与 `rec/inference.yml` 字典）：随原项目分发，源自 PaddleOCR，Apache-2.0。
- **onnxruntime-slim.aar**（`app/libs/`）：ONNX Runtime Android（CPU 变体，不含 QNN/NPU 后端），Microsoft，[MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)。
- **opencv-slim.aar**（`app/libs/`）：OpenCV Android（slim 裁剪版，DB 后处理的 findContours/minAreaRect 依赖），Apache-2.0。

移植进 Eta 的源码遵循上游的文件头标注：`com.paddle.ocr` 包、规则引擎与 OCR 编排文件注明移植来源；内置规则集 `assets/rules/default_rules.json` 为原项目 `assets/default_rules.json` 的逐字副本（仅路径改名）；提醒通知/超级岛形态移植自 `SuperIslandHelper` / `NotificationHelper`（`agent/pickup/PickupCodeNotifier.kt` 与 `agent/live/MiuiIslandFocus.kt` 模板 16 段落头注明）；Shizuku 截屏后端（`agent/capture/AssistantScreenshotCapture.kt`，原取码私有后端已上收为助手级能力）参考原项目 `RootHelper` / `ShizukuScreenshotHelper`，Shizuku SDK（dev.rikka.shizuku）为 Apache-2.0。

“澎湃记”“Hyper Note”及原项目 Logo 是官方项目的标识，Eta 不使用这些名称与标识，也不暗示获得官方背书。原项目 OOBE 中移植自 HyperCeiler 的视觉与动画内容不在本次移植范围，相关归属以其 [NOTICE](https://github.com/badnng/Hyper-pick-up-code/blob/main/NOTICE) 为准。
