package pl.arcom.aitrackereye.util

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Ping do kamery: systemowe polecenie ping (bez roota na większości telefonów), awaryjnie czas połączenia TCP. */
object Net {
    private val PING_RE = Regex("""[=<]\s*(\d+(?:[.,]\d+)?)\s*ms""", RegexOption.IGNORE_CASE)

    data class PingResult(val ms: Double, val below: Boolean)

    fun parsePingOutput(output: String): PingResult? {
        if (!output.lowercase().contains("ttl")) return null
        val m = PING_RE.find(output) ?: return null
        val below = output.getOrNull(m.range.first) == '<'
        return PingResult(m.groupValues[1].replace(',', '.').toDouble(), below)
    }

    private fun icmpPing(host: String, timeoutMs: Int): PingResult? {
        val proc = ProcessBuilder("ping", "-c", "1", "-W", "${maxOf(1, timeoutMs / 1000)}", host.trim('[', ']'))
            .redirectErrorStream(true).start()
        val finished = proc.waitFor(timeoutMs + 3000L, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroy()
            return null
        }
        val out = proc.inputStream.bufferedReader().readText()
        return if (proc.exitValue() == 0) parsePingOutput(out) else null
    }

    private fun tcpPing(host: String, port: Int, timeoutMs: Int): PingResult? {
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host.trim('[', ']'), port), timeoutMs) }
            PingResult((System.nanoTime() - start) / 1e6, false)
        } catch (_: Exception) {
            null
        }
    }

    fun ping(host: String, port: Int, timeoutMs: Int = 1000): PingResult? =
        try {
            icmpPing(host, timeoutMs) ?: tcpPing(host, port, timeoutMs)
        } catch (_: Exception) {
            tcpPing(host, port, timeoutMs)   // brak polecenia ping lub blokada
        }

    fun pingText(r: PingResult?): String = when {
        r == null -> "ping: brak odpowiedzi"
        r.below || r.ms < 1 -> "ping < 1 ms"
        else -> "ping ${r.ms.toInt()} ms"
    }
}
