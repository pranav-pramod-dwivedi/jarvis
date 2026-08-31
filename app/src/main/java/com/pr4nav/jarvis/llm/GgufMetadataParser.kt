package com.pr4nav.jarvis.llm

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GgufParsedMetadata(
    val isValidGguf: Boolean,
    val version: Int = 0,
    val tensorCount: Long = 0L,
    val kvCount: Long = 0L,
    val architecture: String = "UNKNOWN",
    val modelName: String = "UNKNOWN",
    val sizeLabel: String = "UNKNOWN",
    val parameterCountEstimate: String = "UNKNOWN",
    val fileType: Int = 0,
    val quantization: String = "UNKNOWN",
    val contextLength: Long = 0L,
    val embeddingLength: Long = 0L,
    val blockCount: Long = 0L,
    val eosTokenId: Long = 0L,
    val chatTemplate: String = "",
    val error: String? = null
) {
    fun verifyIdentity(expectedModelIdentifier: String): ModelIdentityCheck {
        val expectedClean = expectedModelIdentifier.lowercase().replace("-", "").replace(".", "").replace("_", "")
        val detectedClean = modelName.lowercase().replace("-", "").replace(".", "").replace("_", "")

        val isMatch = isValidGguf && (detectedClean.contains(expectedClean) || expectedClean.contains(detectedClean))
        return ModelIdentityCheck(
            requestedModel = expectedModelIdentifier,
            detectedModelName = modelName,
            detectedArchitecture = architecture,
            detectedParameters = if (sizeLabel != "UNKNOWN") sizeLabel else parameterCountEstimate,
            detectedQuantization = quantization,
            isIdentityPass = isMatch,
            statusText = if (isMatch) "PASS" else "FAIL (Model mismatch: expected $expectedModelIdentifier, found $modelName)"
        )
    }
}

data class ModelIdentityCheck(
    val requestedModel: String,
    val detectedModelName: String,
    val detectedArchitecture: String,
    val detectedParameters: String,
    val detectedQuantization: String,
    val isIdentityPass: Boolean,
    val statusText: String
)

object GgufMetadataParser {

    private const val GGUF_TYPE_UINT8 = 0
    private const val GGUF_TYPE_INT8 = 1
    private const val GGUF_TYPE_UINT16 = 2
    private const val GGUF_TYPE_INT16 = 3
    private const val GGUF_TYPE_UINT32 = 4
    private const val GGUF_TYPE_INT32 = 5
    private const val GGUF_TYPE_FLOAT32 = 6
    private const val GGUF_TYPE_BOOL = 7
    private const val GGUF_TYPE_STRING = 8
    private const val GGUF_TYPE_ARRAY = 9
    private const val GGUF_TYPE_UINT64 = 10
    private const val GGUF_TYPE_INT64 = 11
    private const val GGUF_TYPE_FLOAT64 = 12

    fun parse(file: File): GgufParsedMetadata {
        if (!file.exists() || file.length() < 1024) {
            return GgufParsedMetadata(isValidGguf = false, error = "File does not exist or is too small")
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (String(magic) != "GGUF") {
                    return GgufParsedMetadata(isValidGguf = false, error = "Invalid GGUF magic header")
                }

                fun readInt32(): Int {
                    val b = ByteArray(4)
                    raf.readFully(b)
                    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
                }

                fun readInt64(): Long {
                    val b = ByteArray(8)
                    raf.readFully(b)
                    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
                }

                fun readString(): String {
                    val len = readInt64()
                    if (len <= 0 || len > 10_000_000L) return ""
                    val b = ByteArray(len.toInt())
                    raf.readFully(b)
                    return String(b, Charsets.UTF_8)
                }

                val version = readInt32()
                val tensorCount = readInt64()
                val kvCount = readInt64()

                var architecture = "UNKNOWN"
                var modelName = "UNKNOWN"
                var sizeLabel = "UNKNOWN"
                var fileType = 0
                var contextLength = 0L
                var embeddingLength = 0L
                var blockCount = 0L
                var eosTokenId = 0L
                var chatTemplate = ""

                for (i in 0 until minOf(kvCount, 150L)) {
                    val key = readString()
                    val valType = readInt32()

                    when (valType) {
                        GGUF_TYPE_STRING -> {
                            val strVal = readString()
                            when (key) {
                                "general.architecture" -> architecture = strVal
                                "general.name" -> modelName = strVal
                                "general.size_label" -> sizeLabel = strVal
                                "tokenizer.chat_template" -> chatTemplate = strVal
                            }
                        }
                        GGUF_TYPE_UINT32 -> {
                            val u32Val = readInt32()
                            when (key) {
                                "general.file_type" -> fileType = u32Val
                                "qwen2.context_length", "llama.context_length" -> contextLength = u32Val.toLong()
                                "qwen2.embedding_length", "llama.embedding_length" -> embeddingLength = u32Val.toLong()
                                "qwen2.block_count", "llama.block_count" -> blockCount = u32Val.toLong()
                                "tokenizer.ggml.eos_token_id" -> eosTokenId = u32Val.toLong()
                            }
                        }
                        GGUF_TYPE_INT32 -> {
                            val i32Val = readInt32()
                            when (key) {
                                "general.file_type" -> fileType = i32Val
                                "tokenizer.ggml.eos_token_id" -> eosTokenId = i32Val.toLong()
                            }
                        }
                        GGUF_TYPE_UINT64, GGUF_TYPE_INT64 -> {
                            val i64Val = readInt64()
                            when (key) {
                                "tokenizer.ggml.eos_token_id" -> eosTokenId = i64Val
                                "qwen2.context_length", "llama.context_length" -> contextLength = i64Val
                            }
                        }
                        GGUF_TYPE_ARRAY -> {
                            val arrType = readInt32()
                            val arrLen = readInt64()
                            if (arrType == GGUF_TYPE_STRING && arrLen < 50) {
                                for (a in 0 until arrLen) readString()
                            } else {
                                val elemSize = when (arrType) {
                                    GGUF_TYPE_UINT8, GGUF_TYPE_INT8, GGUF_TYPE_BOOL -> 1
                                    GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> 2
                                    GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32 -> 4
                                    GGUF_TYPE_UINT64, GGUF_TYPE_INT64, GGUF_TYPE_FLOAT64 -> 8
                                    else -> 1
                                }
                                raf.seek(raf.filePointer + (elemSize * arrLen))
                            }
                        }
                        GGUF_TYPE_UINT8, GGUF_TYPE_INT8, GGUF_TYPE_BOOL -> raf.skipBytes(1)
                        GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> raf.skipBytes(2)
                        GGUF_TYPE_FLOAT32 -> raf.skipBytes(4)
                        GGUF_TYPE_FLOAT64 -> raf.skipBytes(8)
                        else -> break
                    }
                }

                val quantStr = when (fileType) {
                    0 -> "F32"
                    1 -> "F16"
                    2 -> "Q4_0"
                    3 -> "Q4_1"
                    7 -> "Q8_0"
                    12 -> "Q4_K"
                    15 -> "Q4_K_M"
                    16 -> "Q4_K_S"
                    else -> "Type-$fileType"
                }

                val paramEst = if (sizeLabel != "UNKNOWN") sizeLabel else if (file.length() < 1_500_000_000L) "1.8B" else "3.0B"

                GgufParsedMetadata(
                    isValidGguf = true,
                    version = version,
                    tensorCount = tensorCount,
                    kvCount = kvCount,
                    architecture = architecture,
                    modelName = modelName,
                    sizeLabel = sizeLabel,
                    parameterCountEstimate = paramEst,
                    fileType = fileType,
                    quantization = quantStr,
                    contextLength = contextLength,
                    embeddingLength = embeddingLength,
                    blockCount = blockCount,
                    eosTokenId = eosTokenId,
                    chatTemplate = chatTemplate
                )
            }
        } catch (e: Exception) {
            GgufParsedMetadata(isValidGguf = false, error = "Parsing failed: ${e.message}")
        }
    }
}
