package com.example.vpn.discovery

import com.example.data.model.DiscoveredNode
import com.example.data.model.SniProfileEntity
import com.example.data.model.TlsTransportType
import com.example.vpn.tls.TlsSniVerifier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class DiscoveryReport(
  val nodes: List<DiscoveredNode>,
  val compatibleCount: Int,
  val summaryMessage: String,
)

/**
 * Free Public Infrastructure Discovery.
 * Queries real public open proxy/relay directories without inventing fake servers or accounts,
 * then probes every candidate live for TLS + real end-to-end tunnel compatibility.
 */
object FreeInfraDiscovery {
  private val client =
    OkHttpClient.Builder()
      .connectTimeout(6, TimeUnit.SECONDS)
      .readTimeout(8, TimeUnit.SECONDS)
      .build()

  suspend fun discoverAndProbe(maxCandidates: Int = 12): DiscoveryReport =
    withContext(Dispatchers.IO) {
      val rawCandidates = mutableListOf<DiscoveredNode>()

      // Source 1: ProxyScrape HTTPS/TLS public directory
      runCatching {
        val req =
          Request.Builder()
            .url(
              "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=4000&country=all&ssl=yes&anonymity=all"
            )
            .build()
        client.newCall(req).execute().use { resp ->
          if (resp.isSuccessful) {
            resp.body?.string()?.lineSequence()?.forEach { line ->
              val parts = line.trim().split(':')
              if (parts.size == 2) {
                val port = parts[1].toIntOrNull()
                if (parts[0].isNotBlank() && port != null && port in 1..65535) {
                  rawCandidates.add(
                    DiscoveredNode(
                      host = parts[0],
                      port = port,
                      source = "ProxyScrape Public",
                    )
                  )
                }
              }
            }
          }
        }
      }

      // Source 2: GeoNode public free relay JSON list (lightweight regex extraction)
      if (rawCandidates.size < maxCandidates) {
        runCatching {
          val req =
            Request.Builder()
              .url(
                "https://proxylist.geonode.com/api/proxy-list?limit=10&page=1&sort_by=lastChecked&sort_type=desc&protocols=https%2Csocks5"
              )
              .build()
          client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
              val body = resp.body?.string().orEmpty()
              val ipPortRegex =
                Regex("\"ip\":\"([^\"]+)\".*?\"port\":\"(\\d+)\".*?\"country\":\"([^\"]*)\"")
              ipPortRegex.findAll(body).forEach { m ->
                val ip = m.groupValues[1]
                val port = m.groupValues[2].toIntOrNull()
                val country = m.groupValues[3].ifBlank { "--" }
                if (port != null && port in 1..65535) {
                  rawCandidates.add(
                    DiscoveredNode(
                      host = ip,
                      port = port,
                      source = "GeoNode Public",
                      country = country,
                    )
                  )
                }
              }
            }
          }
        }
      }

      val distinctCandidates = rawCandidates.distinctBy { "${it.host}:${it.port}" }.take(maxCandidates)
      if (distinctCandidates.isEmpty()) {
        return@withContext DiscoveryReport(
          nodes = emptyList(),
          compatibleCount = 0,
          summaryMessage =
            "Nenhum serviço público gratuito respondeu à consulta neste momento (sem ligação ou bloqueio da rede). Adicione um endpoint TLS/SSH manualmente.",
        )
      }

      // Probe every discovered node live — never trust directory listings without end-to-end test
      val probedNodes = coroutineScope {
        distinctCandidates
          .map { candidate ->
            async(Dispatchers.IO) {
              val tempProfile =
                SniProfileEntity(
                  name = "Probe-${candidate.host}",
                  sniHost = candidate.host,
                  gatewayHost = candidate.host,
                  gatewayPort = candidate.port,
                  transportType = TlsTransportType.HTTP_CONNECT_TLS,
                  verifyHostname = false,
                )
              val res = TlsSniVerifier.probeProfile(tempProfile)
              candidate.copy(
                supportsTls = res.tlsHandshakeOk,
                tunnelCompatible = res.endToEndVerified,
                latencyMs = res.latencyMs,
                statusDetail = res.diagnosticMessage,
              )
            }
          }
          .awaitAll()
      }

      val sorted =
        probedNodes.sortedWith(
          compareByDescending<DiscoveredNode> { it.tunnelCompatible }
            .thenByDescending { it.supportsTls }
            .thenBy { it.latencyMs ?: Long.MAX_VALUE }
        )
      val compatibleCount = sorted.count { it.tunnelCompatible }
      val summary =
        if (compatibleCount > 0) {
          "$compatibleCount de ${sorted.size} gateways públicos gratuitos confirmaram túnel TLS funcional."
        } else {
          "Foram testados ${sorted.size} nós públicos gratuitos, mas NENHUM autorizou encaminhamento real de túnel TLS neste momento."
        }

      DiscoveryReport(
        nodes = sorted,
        compatibleCount = compatibleCount,
        summaryMessage = summary,
      )
    }
}
