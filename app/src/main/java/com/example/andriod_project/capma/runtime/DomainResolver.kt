package com.example.andriod_project.capma.runtime

import android.util.Log
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class DomainResolver {
    private val ipToDomainCache = ConcurrentHashMap<String, String?>()

    fun resolve(ip: String, sniHint: String? = null): String? {
        return runCatching {
            if (!sniHint.isNullOrBlank()) {
                ipToDomainCache[ip] = sniHint
                sniHint
            } else {
                ipToDomainCache.getOrPut(ip) {
                    runCatching {
                        val host = InetAddress.getByName(ip).canonicalHostName
                        host.takeIf { it != ip }
                    }.getOrNull()
                }
            }
        }.onFailure {
            CapmaRuntimeBus.updateDebugStats {
                it.copy(domainResolutionFailures = it.domainResolutionFailures + 1)
            }
            Log.w(tag, "resolve_failed ip=$ip")
        }.getOrNull()
    }

    companion object {
        private const val tag = "CAPMA_SIGNAL"
    }
}
