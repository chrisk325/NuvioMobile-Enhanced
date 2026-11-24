package com.nuvio.app.core.network

import android.app.Application
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FastDnsTest {
    private val v4 = InetAddress.getByAddress("server.example", byteArrayOf(10, 0, 0, 1))
    private val v4b = InetAddress.getByAddress("server.example", byteArrayOf(10, 0, 0, 2))
    private val v6 = InetAddress.getByAddress("server.example", ByteArray(16).also { it[15] = 1 })

    /** A system lookup that hangs the way one waiting on an unanswered IPv6 question does. */
    private class SlowDns(private val delayMs: Long, private val answer: List<InetAddress>) : Dns {
        val calls = AtomicInteger()
        override fun lookup(hostname: String): List<InetAddress> {
            calls.incrementAndGet()
            Thread.sleep(delayMs)
            return answer
        }
    }

    @Test(timeout = 10_000)
    fun `the IPv4 answer is used without waiting for the slow system lookup`() {
        val system = SlowDns(5_000, listOf(v6, v4))
        val dns = FastDns(system, ipv4Only = { listOf(v4) })
        val started = System.nanoTime()
        assertEquals(listOf(v4), dns.lookup("server.example"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test(timeout = 10_000)
    fun `without the IPv4 lookup the system answer is used, IPv4 first`() {
        val dns = FastDns(SlowDns(0, listOf(v6, v4)), ipv4Only = null)
        assertEquals(listOf(v4, v6), dns.lookup("server.example"))
    }

    @Test(timeout = 10_000)
    fun `an empty IPv4 answer waits for the system lookup`() {
        val dns = FastDns(SlowDns(200, listOf(v6)), ipv4Only = { emptyList() })
        assertEquals(listOf(v6), dns.lookup("server.example"))
    }

    @Test(timeout = 10_000)
    fun `a failed IPv4 lookup leaves the system answer`() {
        val dns = FastDns(SlowDns(100, listOf(v4b)), ipv4Only = { null })
        assertEquals(listOf(v4b), dns.lookup("server.example"))
    }

    @Test(timeout = 10_000)
    fun `an unknown host fails as the system lookup does`() {
        val dns = FastDns(Dns { throw UnknownHostException("nope.example") }, ipv4Only = { null })
        try {
            dns.lookup("nope.example")
            fail("expected UnknownHostException")
        } catch (expected: UnknownHostException) {
        }
    }

    @Test(timeout = 10_000)
    fun `a recent answer is reused without a lookup`() {
        val system = SlowDns(0, listOf(v4))
        val dns = FastDns(system, ipv4Only = null)
        dns.lookup("server.example")
        dns.lookup("SERVER.example")
        assertEquals(1, system.calls.get())
    }

    @Test(timeout = 10_000)
    fun `an older answer is used at once and looked up again meanwhile`() {
        val now = AtomicLong(0L)
        val system = SlowDns(0, listOf(v4))
        val dns = FastDns(system, ipv4Only = null, nanoTime = now::get)
        dns.lookup("server.example")
        now.set(30L * 60 * 1_000_000_000L)
        assertEquals(listOf(v4), dns.lookup("server.example"))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (system.calls.get() < 2) {
            if (System.nanoTime() > deadline) fail("not looked up again")
            Thread.sleep(10)
        }
    }

    @Test(timeout = 10_000)
    fun `a forgotten host is looked up again`() {
        val system = SlowDns(0, listOf(v4))
        val dns = FastDns(system, ipv4Only = null)
        dns.lookup("server.example")
        dns.forget("server.example")
        dns.lookup("server.example")
        assertEquals(2, system.calls.get())
    }

    @Test(timeout = 10_000)
    fun `addresses written as numbers are not looked up`() {
        val system = SlowDns(0, listOf(v4))
        val dns = FastDns(Dns { host -> listOf(InetAddress.getByName(host)) }, ipv4Only = { fail("looked up"); null })
        assertEquals("10.1.2.3", dns.lookup("10.1.2.3").single().hostAddress)
        assertEquals(0, system.calls.get())
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail(what)
            Thread.sleep(10)
        }
    }

    @Test(timeout = 10_000)
    fun `a prefetched name is looked up in the background and then used`() {
        val system = SlowDns(0, listOf(v4))
        val dns = FastDns(system, ipv4Only = null)
        dns.prefetch(listOf("SERVER.example", "server.example"))
        waitFor("not looked up") { system.calls.get() >= 1 }
        Thread.sleep(100)
        assertEquals(listOf(v4), dns.lookup("server.example"))
        assertEquals(1, system.calls.get())
    }

    @Test(timeout = 10_000)
    fun `prefetch skips recent names, numbers and localhost`() {
        val system = SlowDns(0, listOf(v4))
        val dns = FastDns(system, ipv4Only = null)
        dns.lookup("server.example")
        dns.prefetch(listOf("server.example", "10.0.0.1", "localhost", ""))
        Thread.sleep(200)
        assertEquals(1, system.calls.get())
    }

    @Test(timeout = 10_000)
    fun `prefetch asks for a handful of names at most`() {
        val system = SlowDns(0, listOf(v4))
        val dns = FastDns(system, ipv4Only = null)
        dns.prefetch((1..20).map { "addon$it.example" })
        waitFor("not looked up") { system.calls.get() >= 8 }
        Thread.sleep(200)
        assertEquals(8, system.calls.get())
    }
}
