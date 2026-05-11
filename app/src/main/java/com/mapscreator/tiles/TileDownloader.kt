package com.mapscreator.tiles

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

class TileDownloader(
    private val store: MBTilesStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
) {
    data class Progress(
        val downloaded: Int,
        val skipped: Int,
        val failed: Int,
        val total: Int
    ) {
        val done: Int get() = downloaded + skipped + failed
        val percent: Int get() = if (total == 0) 100 else done * 100 / total
    }

    suspend fun download(
        tiles: List<TileCoord>,
        source: TileSource,
        maxAgeMs: Long = Long.MAX_VALUE,
        parallelism: Int = 4,
        onProgress: suspend (Progress) -> Unit = {}
    ) = coroutineScope {
        val downloaded = AtomicInteger()
        val skipped = AtomicInteger()
        val failed = AtomicInteger()
        val total = tiles.size

        val semaphore = kotlinx.coroutines.sync.Semaphore(parallelism)
        tiles.map { (z, x, y) ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    if (store.hasTile(z, x, y, source.id, maxAgeMs)) {
                        skipped.incrementAndGet()
                    } else {
                        try {
                            val bytes = fetchTile(source.tileUrl(z, x, y))
                            store.putTile(z, x, y, source.id, bytes)
                            downloaded.incrementAndGet()
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                        }
                    }
                    onProgress(Progress(downloaded.get(), skipped.get(), failed.get(), total))
                }
            }
        }.joinAll()
    }

    private fun fetchTile(url: String): ByteArray {
        val request = Request.Builder().url(url)
            .header("User-Agent", "MapsCreator/1.0")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            response.body!!.bytes()
        }
    }
}
