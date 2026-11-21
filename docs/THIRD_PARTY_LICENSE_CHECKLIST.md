# 第三方许可证核查清单

新增、移植或随包分发任何第三方代码 / 二进制 / 模型 / 图标时，按本清单逐项勾选。三处登记必须同时对齐，漏一处即视为不合规。

三处登记：

| 层 | 文件 | 作用 |
| --- | --- | --- |
| 项目文档 | `docs/THIRD_PARTY_NOTICES.md` | 对外完整声明，每组件一节 |
| README | `README.md` / `README_EN.md`「参考与致谢」 | 面向访客的上游致谢 |
| 应用内 | 关于页（Credits + 开源许可） | 终端用户可查，许可证全文随包 |

---

## 第 0 步：判定组件类型

先确定引入的是哪一类，决定走哪些后续步骤：

- [ ] **随包分发**——进 APK 的代码、`.so`、模型、图标、字体、AAR。许可证全文必须随包，义务最重。
- [ ] **仅参考**——只读其源码/行为重写，或仅作协议/设计参考，代码不进包。只需 README 致谢，通常无需随包全文。
- [ ] **按需下载**——运行时从网络拉取（模型权重、可选工具）。需固定 URL + SHA-256 校验，并在下载处提示权重许可证限制。

> 判定口诀：**进包的重、参考的轻、下载的要校验**。拿不准按"进包"处理。

---

## 第 1 步：查清许可证

- [ ] 找到**官方**许可证文件（仓库根 `LICENSE` / `COPYING`，或 `NOTICE`），逐字节留档到 `third_party/<name>/`。
- [ ] 记录：许可证名称、SPDX 标识、版权人、是否含**嵌套第三方组件**（如 ncnn 内嵌 zlib / BSD-2）。
- [ ] 区分**代码许可证**与**权重/资产许可证**——引擎的 Apache 不覆盖模型权重，两者常不同。

### 许可证义务速查

| 许可证 | 允许随包再分发？ | 核心义务 | 常见坑 |
| --- | --- | --- | --- |
| Apache-2.0 | ✅ | 保留版权+许可全文；NOTICE 归属；标注修改 | 不能代替其内嵌 MIT/BSD 组件的义务 |
| MIT | ✅ | 保留版权声明 + 许可声明 | 需随包 MIT 全文，Apache 覆盖不了 |
| BSD-2/3-Clause | ✅ | 保留版权+许可+免责；**不得用署名背书** | 营销文案不得暗示官方认证 |
| zlib | ✅ | 不得冒称原创；改动需标注 | 常作为嵌套组件被忽略 |
| GPL-3.0 | ✅（本仓本身 GPL-3.0） | 整体以 GPL-3.0 分发，提供源码 | 无 |
| AGPL-3.0-only | ✅ 可并入 GPL-3.0 | 见下方 AGPL 专条 | 易误判为不兼容 |
| LGPL-3.0-or-later | ✅ | 允许用户替换该库；保留许可 | 静态链接需注意 |
| 无许可证 / 自定义协议 | ⚠️ | 默认保留所有权利，**先问再引入** | 别当"开源"直接用 |

### AGPL-3.0 专条（GPL-3.0 §13）

AGPL-3.0 覆盖的代码**可以**并入 GPL-3.0 工程，合并后整体按 GPL-3.0 分发（单向，GPL 不能反向并入 AGPL-only）。引入时必须：保留 AGPL 全文随包、在 `THIRD_PARTY_NOTICES.md` 写明来源与归属、不使用其商标暗示官方背书。参考先例见「取餐码识别模块」节。

---

## 第 2 步：`docs/THIRD_PARTY_NOTICES.md`

- [ ] 新增一节（`## 组件名`），写明：用途、上游仓库链接、版本/tag、许可证（带官方链接）、随包形态（哪个 `.so` / 依赖坐标 / vendored 路径）。
- [ ] 有嵌套组件或模型时，逐个列明许可证与归属。
- [ ] 按需下载的，附 URL、revision、字节数、SHA-256 表，并提示权重许可证限制。
- [ ] **核实 URL 与许可证归属**——凭印象写仓库/许可证极易写错（例：`com.materialkolor:material-color-utilities` 的上游是 `jordond/MaterialKolor`，MIT，内含 Google 上游 Apache-2.0，而非同名的其它仓库）。用 web 搜索核对后再落笔。

---

## 第 3 步：README「参考与致谢」

- [ ] `README.md` 与 `README_EN.md` 各加一条，格式与相邻条目一致：`[作者/仓库](链接)：说明，含许可证标识`。
- [ ] 应用内 Credits 页同步加同一条目（见第 4 步），保持两处链接一致。

---

## 第 4 步：应用内关于页

按组件类型二选一或都做：

### 4a. 致谢（Credits）——所有引入的上游项目

- [ ] `AboutScreen.kt` 的 `entries` 加 `CreditEntry(name, summary, url)`。
- [ ] 三份 locale 各加 `credits_entry_<id>` 字符串：
  - `values/about_credits.xml`
  - `values-b+zh+Hans/about_credits.xml`
  - `values-b+zh+Hant/about_credits.xml`

### 4b. 开源许可（Licenses）——仅随包分发的组件

- [ ] 许可证**官方全文**放入 `app/src/main/assets/licenses/`，逐字节复制，勿手改。
- [ ] `AboutLicensesScreen.kt` 的 `licenseEntries()` 加 `LicenseEntry(id, title, summary, assetName)`，`assetName` 指向上一步文件。
- [ ] 三份 locale 各加 `licenses_entry_<id>_summary` 字符串（同上目录，`about_licenses.xml`）。

---

## 第 5 步：验证

- [ ] **XML 解析**：改动过的每份 locale XML 用 `python3 -c "import xml.dom.minidom;xml.dom.minidom.parse('<file>')"` 校验。
- [ ] **字符串 key 齐全**：新增的每个 key 在 `values` + `values-b+zh+Hans` + `values-b+zh+Hant` 三份都在；Kotlin 里引用的每个 `R.string.*` 都能找到定义。
- [ ] **asset 对应**：`licenseEntries()` 里每个 `assetName` 的文件都存在；`assets/licenses/` 下无孤儿文件（每个文件都被某条目引用）。
- [ ] **三处对齐**：README 的上游链接集合与 Credits 页 `url` 集合完全一致（用 `diff <(README 链接 | sort) <(Credits url | sort)` 核对，无输出即对齐）。
- [ ] **无契约测试断言**：本仓 `app/src/test/python/` 不对许可页 / Credits 结构做断言，纯新增条目不触发 CI verify；若改到被断言的结构需同步改测试。

---

## 附：本次落地示例

| 组件 | 类型 | 许可证 | 三处登记 |
| --- | --- | --- | --- |
| Hyper-pick-up-code | 仅参考（后续移植） | AGPL-3.0-only | NOTICES 节 + README 致谢 + Credits + AGPL 全文随包 |
| sherpa-onnx / sherpa-ncnn | 随包（引擎） | Apache-2.0 | NOTICES 节 + README 致谢 + Credits + Apache 全文随包 |
| ONNX Runtime | 随包（`libonnxruntime.so`） | MIT | NOTICES 点明 + MIT 全文随包 + 许可页登记 |
| ncnn | 随包（`libncnn.so`） | BSD-3-Clause | NOTICES 点明 + BSD-3 全文随包 + 许可页登记 |
| MaterialKolor `material-color-utilities` | 随包（Maven） | MIT（内含 Google Apache-2.0） | NOTICES 节 + Apache 全文随包 |
