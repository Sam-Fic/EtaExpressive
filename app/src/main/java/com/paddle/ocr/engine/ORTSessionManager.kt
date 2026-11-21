// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// 移植自 badnng/Hyper-pick-up-code（AGPL-3.0-only）com.paddle.ocr.engine.ORTSessionManager。
// Eta 移植版裁掉了 QNN/HTP NPU 后端与 ctx 缓存管线（NpuCapability/NpuLibLoader/NpuSupport
// 依赖整层不移植），只保留 CPU / NNAPI 两条 EP；NpuOptions 里的分块/画布守卫参数
// 仍被 DetectionEngine 的 CPU 路径消费，EngineConfig 结构保持原样。

package com.paddle.ocr.engine

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.paddle.ocr.AccelBackend
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.model.OCRError
import java.io.File
import java.nio.FloatBuffer

/**
 * ORT 会话管理（Eta 移植版：CPU-only）。
 *
 * 两条后端共用同一份 Java API 与同一个 `onnxruntime-slim` AAR，差别只在 SessionOptions：
 * - CPU    : 默认 EP，行为与原版一致
 * - NNAPI  : `addNnapi()`，零额外原生库
 *
 * 固定 shape 会话的输入尺寸仍通过 [detFixedInputShape] / [recFixedInputShape]
 * 暴露给预处理层 —— CPU 路径下动态 shape 模型返回 null。
 */
class ORTSessionManager(
    context: Context,
    private val config: EngineConfig,
) {
    private val appContext = context.applicationContext
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var detInputName: String = "x"
    private var recInputName: String = "x"

    var coldLoadTimeMs: Long = 0
        private set

    /** 实际生效的后端。Eta 版只会在 CPU / NNAPI 之间取值。 */
    var activeBackend: AccelBackend = AccelBackend.CPU
        private set

    /** 后端决策的说明文案，可直接展示给用户。 */
    var backendNote: String = ""
        private set

    /** 检测模型被固化的输入 shape；为 null 表示模型仍是动态 shape（CPU 路径常态）。 */
    var detFixedInputShape: LongArray? = null
        private set

    /** 识别模型被固化的输入 shape；为 null 表示动态 shape。 */
    var recFixedInputShape: LongArray? = null
        private set

    fun loadModels(detAssetPath: String, recAssetPath: String) {
        val loadStart = System.currentTimeMillis()
        env = OrtEnvironment.getEnvironment()

        val backend = config.accel
        val note = when (backend) {
            AccelBackend.NNAPI -> "使用 NNAPI 后端"
            // NPU 不在移植范围：出现该配置时如实落回 CPU，不尝试建 QNN 会话。
            AccelBackend.NPU -> "NPU 后端未随 Eta 打包，使用 CPU"
            else -> ""
        }

        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("OCR", Exception("Environment not initialized"))
        try {
            detSession = createSession(ortEnv, detAssetPath, backend, note, "det")
        } catch (t: Throwable) {
            throw OCRError.ModelLoadFailed("detection", t)
        }
        try {
            recSession = createSession(ortEnv, recAssetPath, backend, note, "rec")
        } catch (t: Throwable) {
            detSession?.close()
            detSession = null
            throw OCRError.ModelLoadFailed("recognition", t)
        }

        detInputName = firstInputName(detSession!!)
        recInputName = firstInputName(recSession!!)
        detFixedInputShape = fixedShapeOf(detSession!!, detInputName)
        recFixedInputShape = fixedShapeOf(recSession!!, recInputName)

        activeBackend = backend
        backendNote = note
        coldLoadTimeMs = System.currentTimeMillis() - loadStart
        Log.i(
            TAG,
            "会话就绪 backend=$activeBackend " +
                "detShape=${detFixedInputShape?.joinToString()} " +
                "recShape=${recFixedInputShape?.joinToString()} " +
                "耗时=${coldLoadTimeMs}ms note=$backendNote",
        )
    }

    fun runDetection(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = detSession
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Environment not initialized"))
        return runSession(ortEnv, session, detInputName, input, shape, "detection")
    }

    fun runRecognition(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = recSession
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Environment not initialized"))
        return runSession(ortEnv, session, recInputName, input, shape, "recognition")
    }

    fun release() {
        closeSessions()
        env = null
    }

    // ------------------------------------------------------------------ 会话建立

    private fun createSession(
        ortEnv: OrtEnvironment,
        modelPath: String,
        backend: AccelBackend,
        note: String,
        tag: String,
    ): OrtSession {
        val opts = OrtSession.SessionOptions()
        try {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setIntraOpNumThreads(config.numThreads)
            when (backend) {
                AccelBackend.NNAPI -> opts.addNnapi()
                // CPU 是默认 EP，无需额外配置；NPU 未移植，静默落 CPU。
                else -> if (backend == AccelBackend.NPU) {
                    Log.i(TAG, "[$tag] NPU 后端未随 Eta 打包，$note")
                }
            }
            return ortEnv.createSession(readModel(modelPath), opts)
        } finally {
            opts.close()
        }
    }

    private fun closeSessions() {
        try {
            detSession?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "关闭 detection 会话失败", t)
        } finally {
            detSession = null
            try {
                recSession?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "关闭 recognition 会话失败", t)
            } finally {
                recSession = null
                detFixedInputShape = null
                recFixedInputShape = null
            }
        }
    }

    // ------------------------------------------------------------------ 工具方法

    private fun firstInputName(session: OrtSession): String = try {
        session.inputNames.iterator().next()
    } catch (t: Throwable) {
        throw OCRError.ModelLoadFailed("model", t)
    }

    /**
     * 读取模型输入张量的固定 shape。任意一维 <= 0（动态维）即返回 null。
     */
    private fun fixedShapeOf(session: OrtSession, inputName: String): LongArray? = try {
        val nodeInfo: NodeInfo? = session.inputInfo[inputName]
        val tensorInfo = nodeInfo?.info as? TensorInfo
        val shape = tensorInfo?.shape
        if (shape == null || shape.isEmpty() || shape.any { it <= 0 }) null else shape
    } catch (t: Throwable) {
        Log.w(TAG, "读取输入 shape 失败:$inputName", t)
        null
    }

    /**
     * 读取模型：绝对路径按文件读，相对路径按 assets 读（内置模型）。
     * 绝对路径分支保留自原版，供未来外置模型复用。
     */
    private fun readModel(modelPath: String): ByteArray = try {
        val file = File(modelPath)
        if (file.isAbsolute) {
            file.readBytes()
        } else {
            appContext.assets.open(modelPath).use { it.readBytes() }
        }
    } catch (t: Throwable) {
        throw OCRError.ModelNotFound(modelPath, t)
    }

    private fun runSession(
        ortEnv: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        input: FloatArray,
        shape: LongArray,
        modelName: String,
    ): Pair<FloatArray, LongArray> {
        val tensor = try {
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), shape)
        } catch (t: Throwable) {
            throw OCRError.InferenceFailed(modelName, t)
        }
        val result = try {
            try {
                session.run(mapOf(inputName to tensor))
            } catch (t: Throwable) {
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            tensor.close()
        }

        return try {
            try {
                val outputName = session.outputNames.iterator().next()
                val ortValue = result.get(outputName)
                    .orElseThrow { Exception("No output tensor found") }
                val outputTensor = ortValue as? OnnxTensor
                    ?: throw Exception("Output is not an ONNX tensor")
                Pair(copyFloatBuffer(outputTensor.floatBuffer), outputTensor.info.shape)
            } catch (t: Throwable) {
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            result.close()
        }
    }

    private fun copyFloatBuffer(buffer: FloatBuffer): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val output = FloatArray(duplicate.remaining())
        duplicate.get(output)
        return output
    }

    private companion object {
        const val TAG = "ORTSessionManager"
    }
}
