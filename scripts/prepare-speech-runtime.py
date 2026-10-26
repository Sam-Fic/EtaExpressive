#!/usr/bin/env python3
"""Pinned upstream Android JNI binaries. No model weights in the APK."""
import hashlib
import io
import os
from pathlib import Path
import sys
import tarfile
import time
import urllib.error
import urllib.request

# APK 只打包 ARM64（与 app/build.gradle.kts 的 ndk.abiFilters 一致），
# 因此只从归档抽取 arm64-v8a。归档整包读取不变——SHA-256 校验要求完整字节，
# 收窄的是落盘产物：其余三个 ABI 不再进入构建输入，避免它们被带进 APK。
ABIS = ('arm64-v8a',)

# Two engines coexist so the user can pick a model without a rebuild:
# sherpa-ncnn   -> ncnn/pnnx Streaming Zipformer transducer (2023 bilingual pack);
# sherpa-onnx   -> ONNX Runtime, used by OnlineRecognizer (Streaming Zipformer2,
#                  e.g. X-ASR-zh-en chunk-480ms).
# Each entry is (url, sha256, exact size, library file names).
RUNTIMES = (
    (
        'https://github.com/k2-fsa/sherpa-ncnn/releases/download/v2.1.15/sherpa-ncnn-v2.1.15-android.tar.bz2',
        '7fbdb6f8bb1f8ec092467ed61d9ec85c01f060d125ccf632ef9d7ec5816d4a65',
        9820071,
        ('libsherpa-ncnn-jni.so', 'libncnn.so'),
    ),
    (
        'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2',
        '2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44',
        46093321,
        ('libsherpa-onnx-jni.so', 'libonnxruntime.so'),
    ),
)

# A single ABI's JNI library is far below this; guards a corrupted member header.
MAX_LIBRARY_BYTES = 64 * 1024 * 1024

# 归档最大 44 MB，release 资产域名偶发断连；整包重试，不中断构建。
DOWNLOAD_ATTEMPTS = 4
RETRY_BACKOFF_SECONDS = (2, 5, 10)


def find_member(archive, abi, name):
    """Match by path suffix; the archive root prefix is not part of the contract."""
    suffix = f'jniLibs/{abi}/{name}'
    matches = [
        member for member in archive.getmembers()
        if member.isfile() and member.name.endswith(suffix)
    ]
    if len(matches) != 1:
        raise RuntimeError(f'Expected exactly one archive member ending with {suffix}')
    return matches[0]


def download(url, size):
    """Return the pinned archive bytes, retrying transient transport failures."""
    last_error = None
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read(size + 1)
        except (OSError, urllib.error.URLError) as error:
            last_error = error
            if attempt > DOWNLOAD_ATTEMPTS - 1:
                break
            time.sleep(RETRY_BACKOFF_SECONDS[min(attempt - 1, len(RETRY_BACKOFF_SECONDS) - 1)])
            continue
        if len(data) != size:
            raise RuntimeError('Speech runtime size mismatch')
        return data
    raise RuntimeError(f'Speech runtime download failed after {DOWNLOAD_ATTEMPTS} attempts: {last_error}')


def prepare(output):
    for url, sha256, size, libraries in RUNTIMES:
        data = download(url, size)
        if hashlib.sha256(data).hexdigest() != sha256:
            raise RuntimeError('Speech runtime SHA-256 mismatch')
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:bz2') as archive:
            for abi in ABIS:
                for name in libraries:
                    member = find_member(archive, abi, name)
                    if member.size > MAX_LIBRARY_BYTES:
                        raise RuntimeError('Oversized runtime member')
                    target = output / abi / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    tmp = target.with_suffix('.tmp')
                    tmp.write_bytes(archive.extractfile(member).read())
                    os.replace(tmp, target)


if __name__ == '__main__':
    prepare(Path(sys.argv[1]))
