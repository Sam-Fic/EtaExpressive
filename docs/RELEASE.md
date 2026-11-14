# 发版与构建流程

只放可执行的步骤与硬规矩；细则背景见 `.github/workflows/android-release.yml` 内注释。

## 本地装机

```bash
./gradlew :app:assembleRelease -x prepareSpeechRuntime --console=plain \
  && adb install -r app/build/outputs/apk/release/app-release.apk
```

- `-x prepareSpeechRuntime` 必带：本机联网拉语音包会 502。
- 交付门槛 = 编译通过 + 安装成功；单元测试非每次必需。

## 版本号与变更日志

- `versionCode` = `yyyyMMdd` + 两位当日序号（01 起），随 `versionName` 一起手动递增（`app/build.gradle.kts` defaultConfig）。
- 每次发版在 `CHANGELOG.md` 顶部新增该版本段落（newest-first，Keep a Changelog 风格）。

## 发版步骤

1. bump `versionName` / `versionCode` + 写 CHANGELOG 段落。
2. 打 `v*` tag 推送（或 `gh workflow run "Eta Build" --ref <tag>`）。tag 名必须与 `versionName` 一致（workflow 内有校验）。
3. CI：`verify`（lint + 全量单测，45 分钟上限）与 `build-apks` 并行；两者都绿才跑 `draft-release`。
4. **草稿必须手动发布**：CI 只建 draft（草稿不出现在 `releases/latest`，App 内更新检查不可见）。发布命令：

   ```bash
   gh release edit <tag> --draft=false
   ```

5. 发布说明手写中文分节 + 短 SHA；先写进 `.git/release-notes-<tag>.md` 再贴，避免跨进程临时文件路径问题（Git Bash 与 Windows Python 的 `/tmp` 不是同一个目录）。

## 注意事项

- 并发组 `android-build-$GITHUB_REF` + cancel-in-progress：同一 ref 的 tag push 与 workflow_dispatch 会互相取消，别混用。
- 查草稿用 `GET /releases?per_page=5`；`GET /releases/tags/<tag>` 对草稿返回 404，不代表发版失败。
- `verify` job 曾因 `UserTerminalControllerTest` 常驻 shell 用例死等被抬到 75 分钟超时；现已物理退出 + 每用例 30 秒上限，正常一轮约 16–18 分钟，勿再盲目抬时限。
