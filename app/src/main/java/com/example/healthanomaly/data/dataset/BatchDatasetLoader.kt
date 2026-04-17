package com.example.healthanomaly.data.dataset

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class BatchDatasetLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val SAMPLES_DIR_NAME = "samples"
    }

    suspend fun loadFromTreeUri(treeUri: Uri): BatchDataset = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Unable to open the selected folder")
        val manifestFile = root.findFile(MANIFEST_FILE_NAME)
            ?: throw IllegalArgumentException("Dataset manifest.json is missing")
        val samplesDir = root.findFile(SAMPLES_DIR_NAME)
            ?.takeIf { it.isDirectory }
            ?: throw IllegalArgumentException("Dataset samples folder is missing")

        val manifestText = context.contentResolver.openInputStream(manifestFile.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalArgumentException("Unable to read dataset manifest")

        parseManifest(manifestText) { fileName ->
            val sampleFile = samplesDir.findFile(fileName)
                ?: throw IllegalArgumentException("Dataset sample '$fileName' is missing")
            SampleSource(contentUri = sampleFile.uri)
        }
    }

    suspend fun loadFromZipUri(zipUri: Uri): BatchDataset = withContext(Dispatchers.IO) {
        val extractionDir = File(
            context.cacheDir,
            "batch_dataset_${System.currentTimeMillis()}"
        ).apply {
            mkdirs()
        }

        try {
            extractZip(zipUri, extractionDir)
            val datasetRoot = resolveExtractedRoot(extractionDir)
            val manifestFile = File(datasetRoot, MANIFEST_FILE_NAME)
            if (!manifestFile.exists()) {
                throw IllegalArgumentException("Dataset manifest.json is missing inside the ZIP")
            }
            val samplesDir = File(datasetRoot, SAMPLES_DIR_NAME)
            if (!samplesDir.isDirectory) {
                throw IllegalArgumentException("Dataset samples folder is missing inside the ZIP")
            }

            val dataset = parseManifest(manifestFile.readText()) { fileName ->
                val sampleFile = File(samplesDir, fileName)
                if (!sampleFile.exists()) {
                    throw IllegalArgumentException("Dataset sample '$fileName' is missing")
                }
                SampleSource(extractedFile = sampleFile)
            }
            dataset.copy(extractionDir = extractionDir)
        } catch (throwable: Throwable) {
            extractionDir.deleteRecursively()
            throw throwable
        }
    }

    fun cleanup(dataset: BatchDataset) {
        dataset.extractionDir?.deleteRecursively()
    }

    private fun parseManifest(
        manifestText: String,
        resolveSampleSource: (String) -> SampleSource
    ): BatchDataset {
        val root = JSONObject(manifestText)
        val datasetName = root.optString("dataset_name").ifBlank {
            root.optString("datasetName")
        }.ifBlank {
            throw IllegalArgumentException("Dataset manifest is missing dataset_name")
        }
        val version = root.optString("version", "1.0")
        val intervalMs = root.optLong("interval_ms", root.optLong("intervalMs", 0L))
        if (intervalMs <= 0L) {
            throw IllegalArgumentException("Dataset manifest has an invalid interval_ms")
        }
        val durationMs = root.optLong("duration_ms", root.optLong("durationMs", 0L))
        if (durationMs <= 0L) {
            throw IllegalArgumentException("Dataset manifest has an invalid duration_ms")
        }
        val predictionRule = root.optString("prediction_rule").ifBlank {
            root.optString("predictionRule", "max_severity")
        }
        val samplesJson = root.optJSONArray("samples")
            ?: throw IllegalArgumentException("Dataset manifest is missing samples[]")

        val samples = buildList(samplesJson.length()) {
            for (index in 0 until samplesJson.length()) {
                val sampleJson = samplesJson.optJSONObject(index)
                    ?: throw IllegalArgumentException("Dataset sample at index $index is invalid")
                val sampleId = sampleJson.optString("sample_id").ifBlank {
                    sampleJson.optString("sampleId")
                }.ifBlank {
                    throw IllegalArgumentException("Dataset sample at index $index is missing sample_id")
                }
                val fileName = sampleJson.optString("file").ifBlank {
                    throw IllegalArgumentException("Dataset sample '$sampleId' is missing file")
                }
                val expectedLabel = sampleJson.optString("expected_label").ifBlank {
                    sampleJson.optString("expectedLabel")
                }.ifBlank {
                    throw IllegalArgumentException("Dataset sample '$sampleId' is missing expected_label")
                }
                if (expectedLabel !in BATCH_CORE_LABELS) {
                    throw IllegalArgumentException(
                        "Dataset sample '$sampleId' has unsupported expected_label '$expectedLabel'"
                    )
                }
                val difficulty = sampleJson.optString("difficulty", "borderline")
                val templateType = sampleJson.optString("template_type").ifBlank {
                    sampleJson.optString("templateType", "unknown")
                }
                val seed = sampleJson.optLong("seed", index.toLong())
                val sampleSource = resolveSampleSource(fileName)
                add(
                    BatchDatasetSample(
                        sampleId = sampleId,
                        fileName = fileName,
                        expectedLabel = expectedLabel,
                        difficulty = difficulty,
                        templateType = templateType,
                        seed = seed,
                        contentUri = sampleSource.contentUri,
                        extractedFile = sampleSource.extractedFile
                    )
                )
            }
        }

        if (samples.isEmpty()) {
            throw IllegalArgumentException("Dataset manifest has no samples")
        }

        return BatchDataset(
            datasetName = datasetName,
            version = version,
            intervalMs = intervalMs,
            durationMs = durationMs,
            predictionRule = predictionRule,
            samples = samples
        )
    }

    private fun extractZip(zipUri: Uri, destinationDir: File) {
        context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipStream ->
                var entry = zipStream.nextEntry
                while (entry != null) {
                    val normalizedName = entry.name.replace('\\', '/')
                    val outputFile = File(destinationDir, normalizedName)
                    val canonicalDestination = destinationDir.canonicalPath
                    val canonicalOutput = outputFile.canonicalPath
                    if (!canonicalOutput.startsWith(canonicalDestination)) {
                        throw IllegalArgumentException("ZIP contains an invalid entry path")
                    }

                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { output ->
                            zipStream.copyTo(output)
                        }
                    }

                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("Unable to read the selected ZIP file")
    }

    private fun resolveExtractedRoot(extractionDir: File): File {
        val manifestAtRoot = File(extractionDir, MANIFEST_FILE_NAME)
        if (manifestAtRoot.exists()) {
            return extractionDir
        }

        val childDirectories = extractionDir.listFiles()
            ?.filter { it.isDirectory }
            .orEmpty()
        if (childDirectories.size == 1) {
            val candidate = childDirectories.first()
            if (File(candidate, MANIFEST_FILE_NAME).exists()) {
                return candidate
            }
        }

        return extractionDir
    }

    private data class SampleSource(
        val contentUri: Uri? = null,
        val extractedFile: File? = null
    )
}
