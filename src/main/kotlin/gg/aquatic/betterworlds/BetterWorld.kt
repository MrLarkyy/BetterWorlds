package gg.aquatic.betterworlds

import gg.aquatic.betterworlds.data.WorldDataDriver
import org.bukkit.World

/**
 * Wrapper class for a Bukkit World that provides additional functionality.
 */
class BetterWorld(
    val name: String,
    var world: World?,
    val dataDriver: WorldDataDriver
) {
}
