package com.v2ray.ang.haima

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Probes servers five at a time. The next batch is only touched when the whole current batch
 * fails, matching the product's connection policy without hammering every endpoint.
 */
class BestServerSelector(private val batchSize: Int = 5) {
    init {
        require(batchSize > 0)
    }

    suspend fun select(
        serverGuids: List<String>,
        probe: suspend (String) -> Long
    ): BestServerSelection? {
        for ((batchIndex, batch) in serverGuids.chunked(batchSize).withIndex()) {
            val results = coroutineScope {
                batch.map { guid ->
                    async {
                        val latency = runCatching { probe(guid) }.getOrDefault(-1L)
                        guid to latency
                    }
                }.awaitAll()
            }
            val best = results.filter { it.second > 0 }.minByOrNull { it.second }
            if (best != null) {
                return BestServerSelection(best.first, best.second, batchIndex)
            }
        }
        return null
    }
}
