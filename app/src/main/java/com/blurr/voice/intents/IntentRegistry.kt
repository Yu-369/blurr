package com.blurr.voice.intents

import android.content.Context
import android.util.Log
import dalvik.system.DexFile

/**
 * Discovers and manages AppIntent implementations.
 * Convention: Put intent implementations under package com.blurr.voice.intents.impl
 */
object IntentRegistry {
    private const val TAG = "IntentRegistry"
    private const val IMPLEMENTATION_PACKAGE = "com.blurr.voice.intents.impl"

    private val discovered: MutableMap<String, AppIntent> = linkedMapOf()
    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        try {
            // Scan classes from the app's APK and load implementations
            val sourcePath = context.applicationInfo.sourceDir
            val dexFile = DexFile(sourcePath)
            val entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                if (!className.startsWith(IMPLEMENTATION_PACKAGE)) continue
                try {
                    val clazz = Class.forName(className)
                    if (AppIntent::class.java.isAssignableFrom(clazz)) {
                        val instance = clazz.getDeclaredConstructor().newInstance() as AppIntent
                        register(instance)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to load intent class: $className", e)
                }
            }
            initialized = true
        } catch (e: Throwable) {
            Log.e(TAG, "Dex scan failed; falling back to manual registration", e)
            // no-op; allow manual registration paths (tests or future wiring)
            initialized = true
        }
    }

    fun register(intent: AppIntent) {
        val key = intent.name.trim()
        if (discovered.containsKey(key)) {
            Log.w(TAG, "Duplicate intent registration for name: ${intent.name}; overriding")
        }
        discovered[key] = intent
    }

    fun listIntents(context: Context): List<AppIntent> {
        if (!initialized) init(context)
        return discovered.values.toList()
    }

    fun findByName(context: Context, name: String): AppIntent? {
        if (!initialized) init(context)
        // exact match first, then case-insensitive
        discovered[name]?.let { return it }
        return discovered.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}

