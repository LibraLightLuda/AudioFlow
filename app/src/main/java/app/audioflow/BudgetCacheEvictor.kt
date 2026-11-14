@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.audioflow

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor

internal class BudgetCacheEvictor(bytes: Long) : CacheEvictor {
    private var delegate = LeastRecentlyUsedCacheEvictor(bytes)
    fun resize(cache: Cache, bytes: Long) = synchronized(cache) {
        delegate = LeastRecentlyUsedCacheEvictor(bytes)
        val spans = cache.keys.flatMap { cache.getCachedSpans(it).toList() }.sortedBy { it.lastTouchTimestamp }
        spans.forEach { if (cache.isCached(it.key, it.position, it.length)) delegate.onSpanAdded(cache, it) }
    }
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = delegate.onCacheInitialized()
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) = delegate.onStartFile(cache, key, position, length)
    override fun onSpanAdded(cache: Cache, span: CacheSpan) = delegate.onSpanAdded(cache, span)
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) = delegate.onSpanRemoved(cache, span)
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = delegate.onSpanTouched(cache, oldSpan, newSpan)
}
