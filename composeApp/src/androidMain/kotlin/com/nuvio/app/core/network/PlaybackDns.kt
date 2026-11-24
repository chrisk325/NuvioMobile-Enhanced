package com.nuvio.app.core.network

import okhttp3.Dns
import java.net.InetAddress

/**
 * DNS for playback. With the system DNS (the default) it is [FastDns]: the IPv4 question is asked
 * on its own as well, and answers are kept, so a debrid or addon stream does not wait on a DNS
 * server that never answers the IPv6 question. With a DNS-over-HTTPS provider chosen in settings,
 * that provider answers, IPv4 first, as before: nothing is asked of the network's DNS server then.
 */
internal object PlaybackDns : Dns {
    private val viaProvider = IPv4FirstDns(NuvioDns)

    override fun lookup(hostname: String): List<InetAddress> =
        if (NuvioDns.provider == DnsProvider.SYSTEM) FastDns.shared.lookup(hostname) else viaProvider.lookup(hostname)

    /** Forgets [hostname]'s kept addresses: one of them could not be connected to. */
    fun forget(hostname: String) = FastDns.shared.forget(hostname)

    /**
     * Looks up [hostnames] in the background, so a stream picked from a list does not wait for
     * it. With a DNS-over-HTTPS provider, that provider is asked (and keeps the answers).
     */
    fun prefetch(hostnames: Collection<String>) {
        if (NuvioDns.provider == DnsProvider.SYSTEM) {
            FastDns.shared.prefetch(hostnames)
            return
        }
        val hosts = hostnames.map { it.lowercase() }.distinct().take(PREFETCH_MAX)
        if (hosts.isEmpty()) return
        Thread({
            hosts.forEach { host -> runCatching { NuvioDns.lookup(host) } }
        }, "PlaybackDnsPrefetch").apply { isDaemon = true }.start()
    }

    private const val PREFETCH_MAX = 8
}
