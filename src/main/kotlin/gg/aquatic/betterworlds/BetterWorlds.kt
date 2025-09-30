package gg.aquatic.betterworlds

import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Main plugin class for BetterWorlds.
 * Provides functionality for serializing and deserializing Minecraft chunks using MessagePack.
 */
class BetterWorlds : JavaPlugin() {

    private val worldCache = ConcurrentHashMap<String, BetterWorld>()

    companion object {
        private lateinit var instance: BetterWorlds

        /**
         * Gets the instance of the plugin.
         * 
         * @return The plugin instance
         */
        fun getInstance(): BetterWorlds {
            return instance
        }
    }

    override fun onEnable() {
        instance = this

        // Create plugin directory if it doesn't exist
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        WorldHandler.initialize()

        logger.info("BetterWorlds has been enabled!")
    }

    override fun onDisable() {
        logger.info("BetterWorlds has been disabled!")
    }
}
