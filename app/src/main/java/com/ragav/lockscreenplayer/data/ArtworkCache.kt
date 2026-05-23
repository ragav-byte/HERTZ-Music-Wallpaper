package com.ragav.lockscreenplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object ArtworkCache {
    private const val CACHE_DIR_NAME = "artwork-cache"
    private const val PERSISTENT_CACHE_DIR_NAME = "persistent-artwork-cache"
    private const val MAX_MEMORY_BYTES = 40 * 1024 * 1024
    private const val MAX_DISK_FILES = 200
    private const val MAX_DISK_BYTES = 80L * 1024L * 1024L
    private const val STALE_AFTER_MS = 24L * 60L * 60L * 1000L
    private const val MAINTENANCE_INTERVAL_MS = 60L * 60L * 1000L
    private const val MAX_ARTWORK_EDGE = 512

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Volatile
    private var cacheDir: File? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var preserveAcrossReboot: Boolean = false

    @Volatile
    private var lastMaintenanceAtMs: Long = 0L

    fun initialize(context: Context, preserveAcrossReboot: Boolean = false) {
        appContext = context.applicationContext
        this.preserveAcrossReboot = preserveAcrossReboot
        cacheDir = resolveCacheDir(context.applicationContext, preserveAcrossReboot)
        scheduleMaintenance(force = true)
    }

    fun setPreserveAcrossReboot(enabled: Boolean) {
        val context = appContext ?: return
        if (preserveAcrossReboot == enabled && cacheDir != null) return
        val oldDir = cacheDir
        preserveAcrossReboot = enabled
        val newDir = resolveCacheDir(context, enabled)
        migrateCache(oldDir, newDir)
        cacheDir = newDir
        scheduleMaintenance(force = true)
    }

    fun getSync(signature: String): Bitmap? {
        if (signature.isBlank()) return null
        memoryCache.get(signature)?.let { bitmap ->
            touch(signature)
            return bitmap
        }
        val file = fileFor(signature) ?: return null
        val now = System.currentTimeMillis()
        if (!file.exists()) return null
        if (now - file.lastModified() > STALE_AFTER_MS) {
            file.delete()
            return null
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: run {
            file.delete()
            return null
        }
        memoryCache.put(signature, bitmap)
        file.setLastModified(now)
        scheduleMaintenance()
        return bitmap
    }

    fun storeAsync(signature: String, bitmap: Bitmap?) {
        if (signature.isBlank() || bitmap == null) return
        if (!shouldReplaceMemory(signature, bitmap)) return
        memoryCache.put(signature, bitmap)
        scope.launch {
            val normalized = normalize(bitmap)
            val file = fileFor(signature) ?: return@launch
            runCatching {
                FileOutputStream(file).use { stream ->
                    normalized.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, stream)
                }
                file.setLastModified(System.currentTimeMillis())
            }
            scheduleMaintenance()
        }
    }

    fun storeAsync(signatures: Collection<String>, bitmap: Bitmap?) {
        val validKeys = signatures.filter { it.isNotBlank() }.distinct()
        if (validKeys.isEmpty() || bitmap == null) return
        val writableKeys = validKeys.filter { shouldReplaceMemory(it, bitmap) }
        if (writableKeys.isEmpty()) return
        writableKeys.forEach { memoryCache.put(it, bitmap) }
        scope.launch {
            val normalized = normalize(bitmap)
            writableKeys.forEach { signature ->
                val file = fileFor(signature) ?: return@forEach
                runCatching {
                    FileOutputStream(file).use { stream ->
                        normalized.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, stream)
                    }
                    file.setLastModified(System.currentTimeMillis())
                }
            }
            scheduleMaintenance()
        }
    }

    private fun shouldReplaceMemory(signature: String, candidate: Bitmap): Boolean {
        val existing = memoryCache.get(signature) ?: return true
        val existingPixels = existing.width * existing.height
        val candidatePixels = candidate.width * candidate.height
        return candidatePixels >= (existingPixels * 0.9f).toInt()
    }

    fun getSync(signatures: Collection<String>): Bitmap? {
        signatures.forEach { signature ->
            getSync(signature)?.let { return it }
        }
        return null
    }

    fun getMemorySync(signatures: Collection<String>): Bitmap? {
        signatures.forEach { signature ->
            if (signature.isNotBlank()) {
                memoryCache.get(signature)?.let { return it }
            }
        }
        return null
    }

    private fun touch(signature: String) {
        scope.launch {
            fileFor(signature)?.takeIf { it.exists() }?.setLastModified(System.currentTimeMillis())
            scheduleMaintenance()
        }
    }

    private fun scheduleMaintenance(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastMaintenanceAtMs < MAINTENANCE_INTERVAL_MS) return
        lastMaintenanceAtMs = now
        scope.launch { cleanup() }
    }

    private fun cleanup() {
        val directory = cacheDir ?: return
        if (!directory.exists()) return
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        files.filter { now - it.lastModified() > STALE_AFTER_MS }
            .forEach { it.delete() }

        val remaining = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var totalBytes = remaining.sumOf { it.length() }
        var totalFiles = remaining.size
        for (file in remaining) {
            if (totalFiles <= MAX_DISK_FILES && totalBytes <= MAX_DISK_BYTES) break
            totalBytes -= file.length()
            totalFiles -= 1
            file.delete()
        }
    }

    private fun normalize(bitmap: Bitmap): Bitmap {
        val largestEdge = maxOf(bitmap.width, bitmap.height)
        if (largestEdge <= MAX_ARTWORK_EDGE) return bitmap
        val scale = MAX_ARTWORK_EDGE.toFloat() / largestEdge.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun fileFor(signature: String): File? {
        val directory = cacheDir ?: return null
        return File(directory, "${hash(signature)}.webp")
    }

    private fun resolveCacheDir(context: Context, preserveAcrossReboot: Boolean): File {
        return if (preserveAcrossReboot) {
            File(context.filesDir, PERSISTENT_CACHE_DIR_NAME).apply { mkdirs() }
        } else {
            File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        }
    }

    private fun migrateCache(from: File?, to: File) {
        if (from == null || !from.exists() || from.absolutePath == to.absolutePath) return
        from.listFiles()
            ?.filter { it.isFile }
            ?.forEach { source ->
                val target = File(to, source.name)
                if (!target.exists() || source.lastModified() > target.lastModified()) {
                    runCatching { source.copyTo(target, overwrite = true) }
                }
            }
    }

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return buildString(bytes.size * 2) {
            bytes.forEach { append("%02x".format(it)) }
        }
    }
}
