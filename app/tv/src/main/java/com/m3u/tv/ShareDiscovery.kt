package com.m3u.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import java.net.Inet4Address

/* -------------------------------------------------------------------------------------------------
 * Finding shares on the home network for the "Add a shared folder" form: NAS boxes, Macs and PCs
 * that announce Windows sharing (SMB) or WebDAV over Bonjour/mDNS show up as buttons that fill the
 * address in. Uses Android's own network service discovery, only while the form is open.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class FoundShare(val name: String, val host: String, val port: Int, val kind: ShareKind) {
    /** What goes in the address field. */
    val address: String
        get() = when (kind) {
            ShareKind.Smb -> host
            ShareKind.WebDav -> "http://$host${if (port == 80 || port <= 0) "" else ":$port"}/"
        }
}

private class ShareDiscovery(context: Context, private val onChange: (List<FoundShare>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val found = linkedMapOf<String, FoundShare>()
    private val pending = ArrayDeque<Pair<NsdServiceInfo, ShareKind>>()
    private var resolving = false
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    @Volatile private var stopped = false

    fun start() {
        val manager = nsd ?: return
        TYPES.forEach { (type, kind) ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) = enqueue(info, kind)
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            if (runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess) {
                listeners += listener
            }
        }
    }

    fun stop() {
        stopped = true
        listeners.forEach { listener -> runCatching { nsd?.stopServiceDiscovery(listener) } }
        listeners.clear()
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo, kind: ShareKind) {
        if (stopped) return
        pending.addLast(info to kind)
        next()
    }

    /** NsdManager resolves one service at a time. */
    @Synchronized
    private fun next() {
        if (resolving || stopped) return
        val (info, kind) = pending.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        val started = runCatching {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = resolved.host
                    // IPv4 only: it's what people type, and what fits in an address as it is.
                    val address = (host as? Inet4Address)?.hostAddress
                    if (address != null) {
                        val share = FoundShare(info.serviceName, address, resolved.port, kind)
                        synchronized(this@ShareDiscovery) { found["${kind.name}:$address:${resolved.port}"] = share }
                        if (!stopped) onChange(synchronized(this@ShareDiscovery) { found.values.toList() })
                    }
                    done()
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) = done()
            })
        }.isSuccess
        if (!started) {
            resolving = false
            next()
        }
    }

    @Synchronized
    private fun done() {
        resolving = false
        next()
    }

    private companion object {
        val TYPES = listOf("_smb._tcp." to ShareKind.Smb, "_webdav._tcp." to ShareKind.WebDav)
    }
}

/** The shares announcing themselves on the network, while this is on screen (and the app in front). */
@Composable
fun rememberFoundShares(): State<List<FoundShare>> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<List<FoundShare>>(emptyList()) }
    LifecycleStartEffect(Unit) {
        val discovery = ShareDiscovery(context.applicationContext) { shares -> state.value = shares }
        discovery.start()
        onStopOrDispose { discovery.stop() }
    }
    return state
}
