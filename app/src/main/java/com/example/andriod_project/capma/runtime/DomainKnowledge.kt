package com.example.andriod_project.capma.runtime

import android.content.Context
import org.json.JSONObject
import java.util.Locale

object DomainKnowledge {
    @Volatile
    private var domainMap: Map<String, String> = emptyMap()

    fun load(context: Context) {
        val json = context.assets.open("domain_map.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        val loaded = mutableMapOf<String, String>()
        obj.keys().forEach { key ->
            loaded[key.lowercase(Locale.US)] = obj.optString(key, "normal")
        }
        domainMap = loaded
    }

    fun classify(domains: Set<String>): Set<String> {
        if (domains.isEmpty()) return emptySet()
        return domains.mapNotNull { domain ->
            val lowered = domain.lowercase(Locale.US)
            domainMap.entries.firstOrNull { lowered.contains(it.key) }?.value
        }.toSet()
    }
}
