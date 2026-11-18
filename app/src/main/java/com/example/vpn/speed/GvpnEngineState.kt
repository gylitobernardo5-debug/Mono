package com.example.vpn.speed

import com.example.data.model.ConnectionPhase
import com.example.data.model.LogLevel
import com.example.data.model.TelemetrySnapshot
import com.example.data.model.TerminalLogEntry
import com.example.data.model.VpnMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared real-time telemetry and terminal logger between GvpnVpnService and UI ViewModel.
 */
object GvpnEngineState {
  private val _telemetry = MutableStateFlow(TelemetrySnapshot())
  val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

  private val _logs = MutableStateFlow<List<TerminalLogEntry>>(emptyList())
  val logs: StateFlow<List<TerminalLogEntry>> = _logs.asStateFlow()

  private val rxBytesTotal = AtomicLong(0L)
  private val txBytesTotal = AtomicLong(0L)
  private val rxBytesWindow = AtomicLong(0L)
  private val txBytesWindow = AtomicLong(0L)
  private val activeFlows = AtomicInteger(0)
  private val logSeq = AtomicLong(0L)
  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

  val bufferPool = AdaptiveBufferPool()

  @Volatile var connectedSinceMs: Long = 0L

  fun resetTrafficCounters() {
    rxBytesTotal.set(0L)
    txBytesTotal.set(0L)
    rxBytesWindow.set(0L)
    txBytesWindow.set(0L)
    activeFlows.set(0)
    connectedSinceMs = 0L
  }

  fun recordRxBytes(bytes: Int) {
    if (bytes > 0) {
      rxBytesTotal.addAndGet(bytes.toLong())
      rxBytesWindow.addAndGet(bytes.toLong())
    }
  }

  fun recordTxBytes(bytes: Int) {
    if (bytes > 0) {
      txBytesTotal.addAndGet(bytes.toLong())
      txBytesWindow.addAndGet(bytes.toLong())
    }
  }

  fun incrementFlows() {
    activeFlows.incrementAndGet()
  }

  fun decrementFlows() {
    activeFlows.updateAndGet { current -> if (current > 0) current - 1 else 0 }
  }

  fun sampleSpeedTick(elapsedMillis: Long) {
    val rxWin = rxBytesWindow.getAndSet(0L)
    val txWin = txBytesWindow.getAndSet(0L)
    val safeElapsed = elapsedMillis.coerceAtLeast(250L)
    val dlPerSec = (rxWin * 1000L) / safeElapsed
    val ulPerSec = (txWin * 1000L) / safeElapsed
    bufferPool.adaptForThroughput(dlPerSec + ulPerSec)

    val uptimeSec =
      if (connectedSinceMs > 0L && _telemetry.value.phase == ConnectionPhase.CONNECTED) {
        ((System.currentTimeMillis() - connectedSinceMs) / 1000L).coerceAtLeast(0L)
      } else {
        0L
      }

    _telemetry.update { current ->
      current.copy(
        connectedSeconds = uptimeSec,
        downloadBytesPerSec = dlPerSec,
        uploadBytesPerSec = ulPerSec,
        totalDownloadBytes = rxBytesTotal.get(),
        totalUploadBytes = txBytesTotal.get(),
        activeFlows = activeFlows.get(),
        bufferSizeBytes = bufferPool.recommendedSize(),
      )
    }
  }

  fun updateTelemetry(transform: (TelemetrySnapshot) -> TelemetrySnapshot) {
    _telemetry.update(transform)
  }

  fun setMode(mode: VpnMode) {
    _telemetry.update { it.copy(mode = mode) }
  }

  fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
    val entry =
      TerminalLogEntry(
        id = logSeq.incrementAndGet(),
        timestamp = synchronized(timeFormat) { timeFormat.format(Date()) },
        tag = tag,
        level = level,
        message = message,
      )
    _logs.update { current -> (current + entry).takeLast(250) }
  }

  fun clearLogs() {
    _logs.value = emptyList()
  }
}
