package gg.aquatic.betterworlds.data

import java.io.File

class FlatFileWorldDataDriver(
    val folder: File
): WorldDataDriver {
    override fun save(x: Int, z: Int, data: ByteArray) {
        val file = File(folder, "${x}_${z}.dat")
        file.writeBytes(data)
    }

    override fun load(x: Int, z: Int): ByteArray? {
        val file = File(folder, "${x}_${z}.dat")
        if (!file.exists()) {
            return null
        }
        return file.readBytes()
    }
}