package gg.aquatic.betterworlds.data

import org.bukkit.Chunk

interface WorldDataDriver {
    fun save(x: Int, z: Int, data: ByteArray)
    fun load(x: Int, z: Int): ByteArray?
}