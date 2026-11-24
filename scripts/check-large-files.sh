#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 拦截会撑大仓库的二进制文件入库。
#
# 背景：docs/Screenshots 的两个演示 GIF 曾累计 60+ MB（历史对象至今仍占 .git 四成），
# 且旧版本无法靠 gc 回收。体积一旦进了历史就只能重写历史才能清掉，代价是改写全部
# 提交哈希与已发布标签，所以**必须在提交前拦**，不能依赖事后清理。
#
# 用法：
#   scripts/check-large-files.sh            # 检查已暂存文件（pre-commit 钩子用这个）
#   scripts/check-large-files.sh --all      # 检查全部已跟踪文件（CI 用这个）
#
# 阈值按"是否该进 Git"而非"GitHub 是否拒绝"来定：GitHub 单文件 50 MiB 才警告、
# 100 MiB 才硬拒，但到那个量级时仓库已经没法看了。

set -euo pipefail

# 单个文件上限。超过即视为"应走发布附件 / 外部托管，而不是入库"。
readonly MAX_BYTES=$((8 * 1024 * 1024))   # 8 MiB
# 动图（GIF/WebP）单独设更严的线：它们最容易失控，且几乎没有无损压缩空间。
readonly MAX_ANIMATED_BYTES=$((6 * 1024 * 1024))  # 6 MiB

repo=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo"

if [[ "${1:-}" == "--all" ]]; then
    mapfile -t files < <(git ls-files)
else
    # --diff-filter=d 排除删除；-z 保证含空格/中文的路径不被拆开。
    mapfile -d '' -t files < <(git diff --cached --name-only --diff-filter=d -z)
fi

violations=0
while IFS= read -r path; do
    [[ -f "$path" ]] || continue
    size=$(stat -c%s "$path" 2>/dev/null || echo 0)

    limit=$MAX_BYTES
    case "${path,,}" in
        *.gif|*.webp) limit=$MAX_ANIMATED_BYTES ;;
    esac

    if (( size > limit )); then
        printf '✗ %s\n    %s，上限 %s\n' \
            "$path" \
            "$(numfmt --to=iec --suffix=B "$size" 2>/dev/null || echo "${size}B")" \
            "$(numfmt --to=iec --suffix=B "$limit" 2>/dev/null || echo "${limit}B")" >&2
        violations=$((violations + 1))
    fi
done < <(printf '%s\n' "${files[@]+"${files[@]}"}")

if (( violations > 0 )); then
    cat >&2 <<'EOF'

大文件不应进 Git：写进历史后只能靠重写全部提交哈希来清除。
请改用以下任一方式：
  · 演示视频/动图 → 上传到 issue 附件，README 里放链接
    （GitHub README 只有 user-attachments 地址能内联播放，仓库内相对路径的
     <video> 会被直接剥离；所以 mp4 走附件比放仓库更合适）
  · 需要内联循环播放的短动图 → 压到 6 MiB 以内
    ffmpeg -i in.gif -vf "fps=10,scale=400:-1:flags=lanczos,split[a][b];\
[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
      -loop 0 out.gif
  · 确实要入库的大二进制 → 先和仓库维护者确认，再显式加例外

若确认本次提交必须包含该文件：
  git commit --no-verify
EOF
    exit 1
fi

printf '大文件检查通过（上限 %s，动图 %s）\n' \
    "$(numfmt --to=iec --suffix=B "$MAX_BYTES")" \
    "$(numfmt --to=iec --suffix=B "$MAX_ANIMATED_BYTES")"
