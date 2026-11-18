package com.example.vpn.speed

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extreme Speed Engine V2: Adaptive buffer pool that dynamically scales buffer sizes
 * from 4 KB (control/handshake) up to 64 KB (bulk throughput) while recycling arrays
 * to eliminate GC pressure on the packet forwarding hot path.
 */
class AdaptiveBufferPool(
  private val maxPooledPerBucket: Int = 32,
) {
  companion object {
    const val SMALL_SIZE = 4096
    const val MEDIUM_SIZE = 16384
    const val LARGE_SIZE = 32768
    const val JUMBO_SIZE = 65536
  }

  private val smallPool = ConcurrentLinkedQueue<ByteArray>()
  private val mediumPool = ConcurrentLinkedQueue<ByteArray>()
  private val largePool = ConcurrentLinkedQueue<ByteArray>()
  private val jumboPool = ConcurrentLinkedQueue<ByteArray>()
  private val currentRecommendedSize = AtomicInteger(MEDIUM_SIZE)

  fun recommendedSize(): Int = currentRecommendedSize.get()

  fun adaptForThroughput(bytesPerSecond: Long) {
    val target =
      when {
        bytesPerSecond >= 2_000_000L -> JUMBO_SIZE
        bytesPerSecond >= 350_000L -> LARGE_SIZE
        bytesPerSecond >= 32_000L -> MEDIUM_SIZE
        else -> SMALL_SIZE
      }
    currentRecommendedSize.set(target)
  }

  fun acquire(requestedSize: Int = recommendedSize()): ByteArray {
    val bucketSize = normalizeBucket(requestedSize)
    val pool = poolFor(bucketSize)
    return pool.poll() ?: ByteArray(bucketSize)
  }

  fun release(buffer: ByteArray) {
    val pool =
      when (buffer.size) {
        SMALL_SIZE -> smallPool
        MEDIUM_SIZE -> mediumPool
        LARGE_SIZE -> largePool
        JUMBO_SIZE -> jumboPool
        else -> return
      }
    if (pool.size < maxPooledPerBucket) {
      pool.offer(buffer)
    }
  }

  private fun normalizeBucket(size: Int): Int =
    when {
      size <= SMALL_SIZE -> SMALL_SIZE
      size <= MEDIUM_SIZE -> MEDIUM_SIZE
      size <= LARGE_SIZE -> LARGE_SIZE
      else -> JUMBO_SIZE
    }

  private fun poolFor(bucketSize: Int): ConcurrentLinkedQueue<ByteArray> =
    when (bucketSize) {
      SMALL_SIZE -> smallPool
      MEDIUM_SIZE -> mediumPool
      LARGE_SIZE -> largePool
      else -> jumboPool
    }
}
