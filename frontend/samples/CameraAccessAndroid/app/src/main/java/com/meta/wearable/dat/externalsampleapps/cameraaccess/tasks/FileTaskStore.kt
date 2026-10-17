package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-private, bounded task history. Atomic replacement leaves the prior file intact on failure. */
class FileTaskStore(private val file: File) : TaskStore {
    private val gson = Gson()
    override suspend fun load(): List<TaskRecord> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else {
            require(file.length() <= 32_000_000) { "작업 기록 파일 크기가 올바르지 않습니다." }
            gson.fromJson(file.readText(), Array<TaskRecord>::class.java).toList()
        }
    }
    override suspend fun archiveResult(key: String, result: String): String = withContext(Dispatchers.IO) {
        require(key.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        val directory = File(file.parentFile, "results").apply { mkdirs() }
        val bytes = result.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8_000_000) { "결과가 너무 큽니다. 검색 범위를 줄여주세요." }
        require(directory.listFiles().orEmpty().sumOf { it.length() } + bytes.size <= 128_000_000) { "결과 보관함이 가득 찼습니다." }
        val destination = File(directory, "$key.json")
        val temp = File(directory, "$key.tmp")
        temp.outputStream().use { it.write(bytes); it.fd.sync() }
        check(temp.renameTo(destination))
        destination.name
    }
    suspend fun fullResult(task: TaskRecord): String? = withContext(Dispatchers.IO) {
        task.resultFile?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,100}\\.json")) }
            ?.let { File(File(file.parentFile, "results"), it).takeIf(File::isFile)?.readText() } ?: task.result
    }
    override suspend fun save(tasks: List<TaskRecord>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.outputStream().use { stream ->
            stream.write(gson.toJson(tasks).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(temp.renameTo(file)) { "작업 기록 파일 교체 실패" }
        val retained = tasks.mapNotNull { it.resultFile }.toSet()
        File(file.parentFile, "results").listFiles().orEmpty().filter {
            it.name !in retained && System.currentTimeMillis() - it.lastModified() > 600_000
        }.forEach { it.delete() }
    }
}
