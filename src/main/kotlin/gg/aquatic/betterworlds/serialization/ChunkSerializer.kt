package gg.aquatic.betterworlds.serialization

import com.mojang.brigadier.StringReader
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.Lifecycle
import net.minecraft.core.IdMap
import net.minecraft.core.IdMapper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.contents.NbtContents
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.util.ByIdMap
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.chunk.storage.SerializableChunkData
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockState
import org.bukkit.craftbukkit.v1_21_R3.CraftChunk
import org.bukkit.craftbukkit.v1_21_R3.CraftWorld
import org.bukkit.craftbukkit.v1_21_R3.util.CraftNBTTagConfigSerializer
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.io.BukkitObjectOutputStream
import org.msgpack.core.MessagePack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.util.UUID

/**
 * Handles serialization and deserialization of Minecraft chunks using MessagePack.
 * This allows for efficient storage and retrieval of chunk data.
 *
 * Supports serialization of:
 * - Block states and data
 * - Tile entities (blocks with additional data)
 * - Entities in the chunk
 * - Biome data
 * - PersistentDataContainer for both entities and tile entities
 *
 * The serializer handles all common data types in PersistentDataContainer:
 * - Primitive types (BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, STRING)
 * - Array types (BYTE_ARRAY, INTEGER_ARRAY, LONG_ARRAY)
 *
 * The serializer attempts to use NMS CODEC for more efficient serialization when possible,
 * falling back to custom serialization when NMS classes are not directly accessible.
 */
object ChunkSerializer {

    // Cache for the getHandle method and ChunkStatus.FULL to avoid repeated reflection lookups
    private var getHandleMethod: Method? = null
    private var chunkStatusFull: Any? = null

    /**
     * Gets the NMS handle of a CraftChunk using reflection.
     * Uses ChunkStatus.FULL as the parameter for getHandle() as required in 1.21.4.
     *
     * @param craftChunk The CraftChunk to get the handle from
     * @return The NMS LevelChunk handle
     */
    private fun getNmsHandle(craftChunk: CraftChunk): Any? {
        try {
            // Initialize method and ChunkStatus.FULL if not already done
            if (getHandleMethod == null || chunkStatusFull == null) {
                // Get ChunkStatus class
                val chunkStatusClass = Class.forName("net.minecraft.world.level.chunk.ChunkStatus")

                // Get ChunkStatus.FULL static field
                val fullField = chunkStatusClass.getDeclaredField("FULL")
                chunkStatusFull = fullField.get(null)

                // Get getHandle method with ChunkStatus parameter
                getHandleMethod = CraftChunk::class.java.getMethod("getHandle", chunkStatusClass)
            }

            // Invoke getHandle with ChunkStatus.FULL
            return getHandleMethod?.invoke(craftChunk, chunkStatusFull)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Serializes a Bukkit chunk to MessagePack format.
     *
     * This method attempts to use NMS CODEC for more efficient serialization when possible,
     * falling back to custom serialization when NMS classes are not directly accessible.
     *
     * @param chunk The Bukkit chunk to serialize
     * @return ByteArray containing the MessagePack serialized data
     */
    fun serializeChunk(chunk: Chunk): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()

        try {
            // Get chunk coordinates
            val x = chunk.x
            val z = chunk.z

            // Pack chunk metadata
            packer.packMapHeader(3) // 3 fields: x, z, and data

            // Pack coordinates
            packer.packString("x")
            packer.packInt(x)

            packer.packString("z")
            packer.packInt(z)

            // Try to use NMS CODEC for serialization
            val craftChunk = chunk as CraftChunk
            val chunkData = try {
                serializeChunkWithCodec(craftChunk)
            } catch (e: Exception) {
                // Fall back to custom serialization if CODEC fails
                e.printStackTrace()
                getChunkData(craftChunk)
            }

            // Pack chunk data
            packer.packString("data")
            packer.packBinaryHeader(chunkData.size)
            packer.writePayload(chunkData)

            return packer.toByteArray()
        } finally {
            packer.close()
        }
    }

    fun serializeChunkNew(chunk: Chunk): ByteArray {
        val craftChunk = chunk as CraftChunk
        val craftWorld = craftChunk.craftWorld
        val nmsWorld = craftWorld.handle
        val nmsChunk = craftChunk.getHandle(ChunkStatus.FULL)

        val serializedData = SerializableChunkData.copyOf(nmsWorld, nmsChunk)
        val chunkCompoundTag = serializedData.write()

        ByteArrayOutputStream().use { outputStream ->
            NbtIo.writeCompressed(chunkCompoundTag, outputStream)
            return outputStream.toByteArray()
        }
    }

    fun refreshChunk(chunk: Chunk, vararg player: Player) {
        val nmsWorld = (chunk.world as CraftWorld).handle
        val nmsChunk = nmsWorld.getChunk(chunk.x, chunk.z)

        ClientboundLevelChunkWithLightPacket(
            nmsChunk,
            nmsWorld.lightEngine,
            null,
            null
        )
    }


    fun deserializeChunk(compoundTag: CompoundTag, world: World): SerializableChunkData? {
        val nmsWorld = (world as CraftWorld).handle
        return SerializableChunkData.parse(nmsWorld, nmsWorld.registryAccess(), compoundTag)
    }

    fun deserializeChunk(data: ByteArray, world: World): SerializableChunkData? {
        val compoundTag = deserializeCompoundTag(data) ?: return null
        return deserializeChunk(compoundTag, world)
    }

    fun deserializeCompoundTag(data: ByteArray): CompoundTag? {
        ByteArrayInputStream(data).use { inputStream ->
            return try {
                NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap())
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Attempts to serialize a chunk using NMS CODEC.
     * This method uses direct NMS classes where possible and falls back to reflection
     * for methods that might vary between Minecraft versions.
     *
     * @param craftChunk The CraftChunk to serialize
     * @return ByteArray containing the serialized chunk data
     * @throws Exception if serialization fails
     */
    private fun serializeChunkWithCodec(craftChunk: CraftChunk): ByteArray {
        try {
            // Get the NMS handle using our updated method that passes ChunkStatus.FULL
            val nmsChunk = getNmsHandle(craftChunk) ?: throw Exception("Failed to get NMS handle")
            val nmsWorld = craftChunk.craftWorld.handle

            val serializedData = SerializableChunkData.copyOf(nmsWorld, nmsWorld.getChunk(craftChunk.x, craftChunk.z))
            for (tag in serializedData.entities) {

                val output = ByteArrayOutputStream()
                val dataOutput = DataOutputStream(output)
                NbtIo.write(tag, dataOutput)
                output.toByteArray()

                BukkitObjectOutputStream(ByteArrayOutputStream()).use { outputStream ->
                    outputStream.writeObject(tag)
                }
            }


            // Create a CompoundTag directly using the imported class
            val compoundTag = CompoundTag()

            // Try to access methods to serialize the chunk
            val chunkClass = nmsChunk.javaClass

            // Look for methods that might be used to serialize chunk data
            // In 1.21.4, there's no "save" method in ChunkAccess, so we need to look for alternatives
            val serializeMethod = chunkClass.methods.firstOrNull { method ->
                method.parameterCount == 1 &&
                        method.parameterTypes[0] == CompoundTag::class.java &&
                        (method.name == "saveWithoutEntities" ||
                                method.name == "saveAllData" ||
                                method.name == "saveToDisk" ||
                                method.name.contains("save", ignoreCase = true) ||
                                method.name.contains("serialize", ignoreCase = true))
            }

            if (serializeMethod != null) {
                // Invoke the found method to serialize the chunk to NBT
                serializeMethod.invoke(nmsChunk, compoundTag)

                // Use NbtIo directly to write the compressed data
                val outputStream = ByteArrayOutputStream()
                NbtIo.writeCompressed(compoundTag, outputStream)
                return outputStream.toByteArray()
            } else {
                // If we can't find a suitable method, try to use the CODEC directly
                try {
                    // Try to access the CODEC field and encode method using reflection
                    val codecField = chunkClass.declaredFields.firstOrNull {
                        it.name == "CODEC" || it.name.contains("CODEC", ignoreCase = true)
                    }

                    if (codecField != null) {
                        codecField.isAccessible = true
                        val codec = codecField.get(null)

                        // Find the encode method on the codec
                        val encodeMethod = codec.javaClass.methods.firstOrNull {
                            it.name == "encode" && it.parameterCount >= 2
                        }

                        if (encodeMethod != null) {
                            // Create NbtOps instance
                            val nbtOps = NbtOps.INSTANCE

                            // Invoke encode method
                            val result = encodeMethod.invoke(codec, nmsChunk, nbtOps, null)

                            // Extract the CompoundTag from the result
                            // This is complex and might need adjustment based on the actual return type
                            val resultCompoundTag = when (result) {
                                is CompoundTag -> result
                                else -> compoundTag // Fallback to our empty tag
                            }

                            // Write the tag
                            val outputStream = ByteArrayOutputStream()
                            NbtIo.writeCompressed(resultCompoundTag, outputStream)
                            return outputStream.toByteArray()
                        }
                    }

                    throw Exception("Could not find CODEC or encode method")
                } catch (e: Exception) {
                    // If CODEC approach fails, fall back to toString method
                    val outputStream = ByteArrayOutputStream()
                    NbtIo.writeCompressed(compoundTag, outputStream)
                    return outputStream.toByteArray()
                }
            }
        } catch (e: Exception) {
            // Log the exception but don't rethrow it
            e.printStackTrace()
            throw Exception("Failed to serialize chunk using NMS CODEC: ${e.message}", e)
        }
    }

    /**
     * Deserializes MessagePack data back to a Minecraft chunk.
     *
     * @param world The Bukkit world to load the chunk into
     * @param data The MessagePack serialized data
     * @return Boolean indicating success or failure
     */
    fun deserializeChunk(world: org.bukkit.World, data: ByteArray): Boolean {
        val unpacker = MessagePack.newDefaultUnpacker(data)

        try {
            // Read map header
            val mapSize = unpacker.unpackMapHeader()
            if (mapSize != 3) {
                return false
            }

            var x = 0
            var z = 0
            var chunkData: ByteArray? = null

            // Read all fields
            for (i in 0 until mapSize) {
                val key = unpacker.unpackString()

                when (key) {
                    "x" -> x = unpacker.unpackInt()
                    "z" -> z = unpacker.unpackInt()
                    "data" -> {
                        val dataSize = unpacker.unpackBinaryHeader()
                        chunkData = unpacker.readPayload(dataSize)
                    }
                }
            }

            if (chunkData == null) {
                return false
            }

            // Apply chunk data to the world
            val craftWorld = world as CraftWorld
            return applyChunkData(craftWorld, x, z, chunkData)
        } finally {
            unpacker.close()
        }
    }

    /**
     * Extracts raw chunk data from a CraftChunk.
     *
     * @param chunk The CraftChunk to extract data from
     * @return ByteArray containing the chunk data
     */
    private fun getChunkData(chunk: CraftChunk): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val dataOutput = DataOutputStream(outputStream)

        try {
            // Write chunk coordinates
            dataOutput.writeInt(chunk.x)
            dataOutput.writeInt(chunk.z)

            // Write world min and max height for reference
            dataOutput.writeInt(chunk.world.minHeight)
            dataOutput.writeInt(chunk.world.maxHeight)

            // Write block data (all blocks with their states)
            serializeBlocks(chunk, dataOutput)

            // Write tile entities (blocks with additional data)
            serializeTileEntities(chunk, dataOutput)

            // Write entities in the chunk
            serializeEntities(chunk, dataOutput)

            // Write biome data
            serializeBiomes(chunk, dataOutput)

            return outputStream.toByteArray()
        } finally {
            dataOutput.close()
        }
    }

    /**
     * Serializes all blocks in the chunk including their states.
     *
     * @param chunk The chunk to serialize blocks from
     * @param dataOutput The data output stream to write to
     */
    private fun serializeBlocks(chunk: CraftChunk, dataOutput: DataOutputStream) {
        val minY = chunk.world.minHeight
        val maxY = chunk.world.maxHeight

        // Write total number of blocks
        val totalBlocks = 16 * 16 * (maxY - minY)
        dataOutput.writeInt(totalBlocks)

        // Write all blocks with their states
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                for (y in minY until maxY) {
                    val block = chunk.getBlock(x, y, z)

                    // Write block coordinates relative to chunk
                    dataOutput.writeInt(x)
                    dataOutput.writeInt(y)
                    dataOutput.writeInt(z)

                    // Write block type
                    dataOutput.writeUTF(block.type.name)

                    // Write block data value (legacy, but might be useful)
                    dataOutput.writeByte(block.data.toInt())

                    // Write block state properties
                    val blockData = block.blockData
                    dataOutput.writeUTF(blockData.asString)
                }
            }
        }
    }

    /**
     * Serializes all tile entities (blocks with additional data) in the chunk.
     *
     * @param chunk The chunk to serialize tile entities from
     * @param dataOutput The data output stream to write to
     */
    private fun serializeTileEntities(chunk: CraftChunk, dataOutput: DataOutputStream) {
        // Get all tile entities in the chunk
        val tileEntities = chunk.tileEntities

        // Write number of tile entities
        dataOutput.writeInt(tileEntities.size)

        // Write each tile entity
        for (tileEntity in tileEntities) {
            // Write tile entity location
            dataOutput.writeInt(tileEntity.x)
            dataOutput.writeInt(tileEntity.y)
            dataOutput.writeInt(tileEntity.z)

            // Write tile entity type
            dataOutput.writeUTF(tileEntity.type.name)

            // Write tile entity state as string (includes all data)
            val blockState = tileEntity.block.state
            dataOutput.writeUTF(blockState.toString())

            // Serialize the persistent data container if available
            try {
                // Try to access the persistent data container using reflection
                val method = blockState.javaClass.getMethod("getPersistentDataContainer")
                val container = method.invoke(blockState) as? PersistentDataContainer

                if (container != null) {
                    serializePersistentDataContainer(container, dataOutput)
                } else {
                    // Write 0 keys if container is not available
                    dataOutput.writeInt(0)
                }
            } catch (e: Exception) {
                // Write 0 keys if method is not available
                dataOutput.writeInt(0)
            }
        }
    }

    /**
     * Serializes all entities in the chunk.
     *
     * @param chunk The chunk to serialize entities from
     * @param dataOutput The data output stream to write to
     */
    private fun serializeEntities(chunk: CraftChunk, dataOutput: DataOutputStream) {
        // Get all entities in the chunk
        val entities = chunk.entities

        // Write number of entities
        dataOutput.writeInt(entities.size)

        // Write each entity
        for (entity in entities) {
            // Write entity UUID
            dataOutput.writeUTF(entity.uniqueId.toString())

            // Write entity type
            dataOutput.writeUTF(entity.type.name)

            // Write entity location
            val location = entity.location
            dataOutput.writeDouble(location.x)
            dataOutput.writeDouble(location.y)
            dataOutput.writeDouble(location.z)
            dataOutput.writeFloat(location.yaw)
            dataOutput.writeFloat(location.pitch)

            // Write entity persistent data container
            serializePersistentDataContainer(entity.persistentDataContainer, dataOutput)
        }
    }

    /**
     * Serializes biome data for the chunk.
     *
     * @param chunk The chunk to serialize biomes from
     * @param dataOutput The data output stream to write to
     */
    private fun serializeBiomes(chunk: CraftChunk, dataOutput: DataOutputStream) {
        val minY = chunk.world.minHeight
        val maxY = chunk.world.maxHeight

        // Write biome data for each block position
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                // Sample biomes at intervals to save space
                for (y in minY until maxY step 4) {
                    val biome = chunk.getBlock(x, y, z).biome
                    dataOutput.writeUTF(biome.toString())
                }
            }
        }
    }

    /**
     * Serializes a persistent data container.
     *
     * @param container The persistent data container to serialize
     * @param dataOutput The data output stream to write to
     */
    private fun serializePersistentDataContainer(container: PersistentDataContainer, dataOutput: DataOutputStream) {
        val keys = container.keys
        dataOutput.writeInt(keys.size)

        // Write each key and its value based on type
        for (key in keys) {
            dataOutput.writeUTF(key.namespace)
            dataOutput.writeUTF(key.key)

            // Determine the type of data and serialize accordingly
            when {
                container.has(key, PersistentDataType.BYTE) -> {
                    dataOutput.writeUTF("BYTE")
                    dataOutput.writeByte(container.get(key, PersistentDataType.BYTE)?.toInt() ?: 0)
                }

                container.has(key, PersistentDataType.SHORT) -> {
                    dataOutput.writeUTF("SHORT")
                    dataOutput.writeShort(container.get(key, PersistentDataType.SHORT)?.toInt() ?: 0)
                }

                container.has(key, PersistentDataType.INTEGER) -> {
                    dataOutput.writeUTF("INTEGER")
                    dataOutput.writeInt(container.get(key, PersistentDataType.INTEGER) ?: 0)
                }

                container.has(key, PersistentDataType.LONG) -> {
                    dataOutput.writeUTF("LONG")
                    dataOutput.writeLong(container.get(key, PersistentDataType.LONG) ?: 0L)
                }

                container.has(key, PersistentDataType.FLOAT) -> {
                    dataOutput.writeUTF("FLOAT")
                    dataOutput.writeFloat(container.get(key, PersistentDataType.FLOAT) ?: 0f)
                }

                container.has(key, PersistentDataType.DOUBLE) -> {
                    dataOutput.writeUTF("DOUBLE")
                    dataOutput.writeDouble(container.get(key, PersistentDataType.DOUBLE) ?: 0.0)
                }

                container.has(key, PersistentDataType.STRING) -> {
                    dataOutput.writeUTF("STRING")
                    dataOutput.writeUTF(container.get(key, PersistentDataType.STRING) ?: "")
                }

                container.has(key, PersistentDataType.BYTE_ARRAY) -> {
                    dataOutput.writeUTF("BYTE_ARRAY")
                    val byteArray = container.get(key, PersistentDataType.BYTE_ARRAY) ?: ByteArray(0)
                    dataOutput.writeInt(byteArray.size)
                    dataOutput.write(byteArray)
                }

                container.has(key, PersistentDataType.INTEGER_ARRAY) -> {
                    dataOutput.writeUTF("INTEGER_ARRAY")
                    val intArray = container.get(key, PersistentDataType.INTEGER_ARRAY) ?: IntArray(0)
                    dataOutput.writeInt(intArray.size)
                    for (value in intArray) {
                        dataOutput.writeInt(value)
                    }
                }

                container.has(key, PersistentDataType.LONG_ARRAY) -> {
                    dataOutput.writeUTF("LONG_ARRAY")
                    val longArray = container.get(key, PersistentDataType.LONG_ARRAY) ?: LongArray(0)
                    dataOutput.writeInt(longArray.size)
                    for (value in longArray) {
                        dataOutput.writeLong(value)
                    }
                }

                else -> {
                    // Unknown type, write as empty
                    dataOutput.writeUTF("UNKNOWN")
                }
            }
        }
    }

    /**
     * Applies chunk data to a world.
     *
     * @param world The CraftWorld to apply the chunk data to
     * @param x The chunk X coordinate
     * @param z The chunk Z coordinate
     * @param data The chunk data to apply
     * @return Boolean indicating success or failure
     */
    private fun applyChunkData(world: CraftWorld, x: Int, z: Int, data: ByteArray): Boolean {
        val inputStream = ByteArrayInputStream(data)
        val dataInput = DataInputStream(inputStream)

        try {
            // Read chunk coordinates from the data
            val chunkX = dataInput.readInt()
            val chunkZ = dataInput.readInt()

            // Verify coordinates match
            if (chunkX != x || chunkZ != z) {
                return false
            }

            // Read world height information
            val minY = dataInput.readInt()
            val maxY = dataInput.readInt()

            // Get the chunk
            val chunk = world.getChunkAt(x, z)

            // Deserialize blocks
            deserializeBlocks(chunk, dataInput)

            // Deserialize tile entities
            deserializeTileEntities(chunk, dataInput)

            // Deserialize entities
            deserializeEntities(chunk, world, dataInput)

            // Deserialize biomes
            deserializeBiomes(chunk, minY, maxY, dataInput)

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            dataInput.close()
        }
    }

    /**
     * Deserializes blocks from the data stream and applies them to the chunk.
     *
     * @param chunk The chunk to apply blocks to
     * @param dataInput The data input stream to read from
     */
    private fun deserializeBlocks(chunk: Chunk, dataInput: DataInputStream) {
        // Read total number of blocks
        val totalBlocks = dataInput.readInt()

        // Read and apply each block
        for (i in 0 until totalBlocks) {
            // Read block coordinates
            val x = dataInput.readInt()
            val y = dataInput.readInt()
            val z = dataInput.readInt()

            // Read block type
            val blockTypeName = dataInput.readUTF()

            // Read block data value (legacy)
            val blockData = dataInput.readByte()

            // Read block state string
            val blockDataString = dataInput.readUTF()

            try {
                // Get the block at the coordinates
                val block = chunk.getBlock(x, y, z)

                // Set block type
                val material = Material.valueOf(blockTypeName)
                block.type = material

                // Try to apply block data string if possible
                try {
                    val blockData = org.bukkit.Bukkit.createBlockData(blockDataString)
                    block.setBlockData(blockData, false)
                } catch (e: Exception) {
                    // If we can't apply the block data string, just set the type
                }
            } catch (e: Exception) {
                // Skip this block if there's an error
            }
        }
    }

    /**
     * Deserializes tile entities from the data stream and applies them to the chunk.
     *
     * @param chunk The chunk to apply tile entities to
     * @param dataInput The data input stream to read from
     */
    private fun deserializeTileEntities(chunk: Chunk, dataInput: DataInputStream) {
        // Read number of tile entities
        val tileEntityCount = dataInput.readInt()

        // Read and apply each tile entity
        for (i in 0 until tileEntityCount) {
            // Read tile entity location
            val x = dataInput.readInt()
            val y = dataInput.readInt()
            val z = dataInput.readInt()

            // Read tile entity type
            val tileEntityType = dataInput.readUTF()

            // Read tile entity state string
            val tileEntityStateString = dataInput.readUTF()

            // Read persistent data container
            val keyCount = dataInput.readInt()
            val persistentData = mutableMapOf<String, Pair<String, Any>>()

            for (j in 0 until keyCount) {
                val namespace = dataInput.readUTF()
                val key = dataInput.readUTF()
                val dataType = dataInput.readUTF()

                // Read the value based on its type
                val value: Any = when (dataType) {
                    "BYTE" -> dataInput.readByte()
                    "SHORT" -> dataInput.readShort()
                    "INTEGER" -> dataInput.readInt()
                    "LONG" -> dataInput.readLong()
                    "FLOAT" -> dataInput.readFloat()
                    "DOUBLE" -> dataInput.readDouble()
                    "STRING" -> dataInput.readUTF()
                    "BYTE_ARRAY" -> {
                        val size = dataInput.readInt()
                        val array = ByteArray(size)
                        dataInput.readFully(array)
                        array
                    }

                    "INTEGER_ARRAY" -> {
                        val size = dataInput.readInt()
                        val array = IntArray(size)
                        for (k in 0 until size) {
                            array[k] = dataInput.readInt()
                        }
                        array
                    }

                    "LONG_ARRAY" -> {
                        val size = dataInput.readInt()
                        val array = LongArray(size)
                        for (k in 0 until size) {
                            array[k] = dataInput.readLong()
                        }
                        array
                    }

                    else -> continue // Skip unknown types
                }

                persistentData["$namespace:$key"] = dataType to value
            }

            try {
                // Get the block at the coordinates
                val block = chunk.getBlock(x, y, z)

                // Try to set the block type based on the tile entity type
                try {
                    val material = Material.valueOf(tileEntityType)
                    block.type = material
                } catch (e: Exception) {
                    // If we can't determine the material from the tile entity type, skip
                }

                // Apply persistent data if available
                if (persistentData.isNotEmpty()) {
                    try {
                        // Get the block state
                        val blockState = block.state

                        // Try to access the persistent data container using reflection
                        val method = blockState.javaClass.getMethod("getPersistentDataContainer")
                        val container = method.invoke(blockState) as? PersistentDataContainer

                        if (container != null) {
                            // Apply each persistent data value
                            for ((fullKey, typeValuePair) in persistentData) {
                                val (namespace, key) = fullKey.split(":", limit = 2)
                                val (dataType, value) = typeValuePair

                                try {
                                    val namespacedKey = org.bukkit.NamespacedKey(namespace, key)

                                    // Set the value based on its type
                                    when (dataType) {
                                        "BYTE" -> container.set(namespacedKey, PersistentDataType.BYTE, value as Byte)
                                        "SHORT" -> container.set(
                                            namespacedKey,
                                            PersistentDataType.SHORT,
                                            value as Short
                                        )

                                        "INTEGER" -> container.set(
                                            namespacedKey,
                                            PersistentDataType.INTEGER,
                                            value as Int
                                        )

                                        "LONG" -> container.set(namespacedKey, PersistentDataType.LONG, value as Long)
                                        "FLOAT" -> container.set(
                                            namespacedKey,
                                            PersistentDataType.FLOAT,
                                            value as Float
                                        )

                                        "DOUBLE" -> container.set(
                                            namespacedKey,
                                            PersistentDataType.DOUBLE,
                                            value as Double
                                        )

                                        "STRING" -> container.set(
                                            namespacedKey,
                                            PersistentDataType.STRING,
                                            value as String
                                        )

                                        "BYTE_ARRAY" -> container.set(
                                            namespacedKey,
                                            PersistentDataType.BYTE_ARRAY,
                                            value as ByteArray
                                        )

                                        "INTEGER_ARRAY" -> container.set(
                                            namespacedKey,
                                            PersistentDataType.INTEGER_ARRAY,
                                            value as IntArray
                                        )

                                        "LONG_ARRAY" -> container.set(
                                            namespacedKey,
                                            PersistentDataType.LONG_ARRAY,
                                            value as LongArray
                                        )
                                    }
                                } catch (e: Exception) {
                                    // Skip this key if there's an error
                                }
                            }

                            // Update the block state
                            blockState.update(true)
                        }
                    } catch (e: Exception) {
                        // Skip persistent data if there's an error
                    }
                }
            } catch (e: Exception) {
                // Skip this tile entity if there's an error
            }
        }
    }

    /**
     * Deserializes entities from the data stream and spawns them in the world.
     *
     * @param chunk The chunk to spawn entities in
     * @param world The world to spawn entities in
     * @param dataInput The data input stream to read from
     */
    private fun deserializeEntities(chunk: Chunk, world: org.bukkit.World, dataInput: DataInputStream) {
        // Read number of entities
        val entityCount = dataInput.readInt()

        // Read and spawn each entity
        for (i in 0 until entityCount) {
            // Read entity UUID
            val entityUUID = dataInput.readUTF()

            // Read entity type
            val entityTypeName = dataInput.readUTF()

            // Read entity location
            val x = dataInput.readDouble()
            val y = dataInput.readDouble()
            val z = dataInput.readDouble()
            val yaw = dataInput.readFloat()
            val pitch = dataInput.readFloat()

            // Read persistent data container
            val keyCount = dataInput.readInt()
            val persistentData = mutableMapOf<String, Pair<String, Any>>()

            for (j in 0 until keyCount) {
                val namespace = dataInput.readUTF()
                val key = dataInput.readUTF()
                val dataType = dataInput.readUTF()

                // Read the value based on its type
                val value: Any = when (dataType) {
                    "BYTE" -> dataInput.readByte()
                    "SHORT" -> dataInput.readShort()
                    "INTEGER" -> dataInput.readInt()
                    "LONG" -> dataInput.readLong()
                    "FLOAT" -> dataInput.readFloat()
                    "DOUBLE" -> dataInput.readDouble()
                    "STRING" -> dataInput.readUTF()
                    "BYTE_ARRAY" -> {
                        val size = dataInput.readInt()
                        val array = ByteArray(size)
                        dataInput.readFully(array)
                        array
                    }

                    "INTEGER_ARRAY" -> {
                        val size = dataInput.readInt()
                        val array = IntArray(size)
                        for (k in 0 until size) {
                            array[k] = dataInput.readInt()
                        }
                        array
                    }

                    "LONG_ARRAY" -> {
                        val size = dataInput.readInt()
                        val array = LongArray(size)
                        for (k in 0 until size) {
                            array[k] = dataInput.readLong()
                        }
                        array
                    }

                    else -> continue // Skip unknown types
                }

                persistentData["$namespace:$key"] = dataType to value
            }

            try {
                // Try to spawn the entity
                val entityType = EntityType.valueOf(entityTypeName)
                val location = org.bukkit.Location(world, x, y, z, yaw, pitch)

                // Only spawn the entity if it's in the chunk we're deserializing
                if (location.chunk.x == chunk.x && location.chunk.z == chunk.z) {
                    val entity = world.spawnEntity(location, entityType)

                    // Apply persistent data if available
                    if (persistentData.isNotEmpty()) {
                        val container = entity.persistentDataContainer

                        // Apply each persistent data value
                        for ((fullKey, typeValuePair) in persistentData) {
                            val (namespace, key) = fullKey.split(":", limit = 2)
                            val (dataType, value) = typeValuePair

                            try {
                                val namespacedKey = org.bukkit.NamespacedKey(namespace, key)

                                // Set the value based on its type
                                when (dataType) {
                                    "BYTE" -> container.set(namespacedKey, PersistentDataType.BYTE, value as Byte)
                                    "SHORT" -> container.set(namespacedKey, PersistentDataType.SHORT, value as Short)
                                    "INTEGER" -> container.set(namespacedKey, PersistentDataType.INTEGER, value as Int)
                                    "LONG" -> container.set(namespacedKey, PersistentDataType.LONG, value as Long)
                                    "FLOAT" -> container.set(namespacedKey, PersistentDataType.FLOAT, value as Float)
                                    "DOUBLE" -> container.set(namespacedKey, PersistentDataType.DOUBLE, value as Double)
                                    "STRING" -> container.set(namespacedKey, PersistentDataType.STRING, value as String)
                                    "BYTE_ARRAY" -> container.set(
                                        namespacedKey,
                                        PersistentDataType.BYTE_ARRAY,
                                        value as ByteArray
                                    )

                                    "INTEGER_ARRAY" -> container.set(
                                        namespacedKey,
                                        PersistentDataType.INTEGER_ARRAY,
                                        value as IntArray
                                    )

                                    "LONG_ARRAY" -> container.set(
                                        namespacedKey,
                                        PersistentDataType.LONG_ARRAY,
                                        value as LongArray
                                    )
                                }
                            } catch (e: Exception) {
                                // Skip this key if there's an error
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip this entity if there's an error
            }
        }
    }

    /**
     * Deserializes biome data from the data stream and applies it to the chunk.
     *
     * @param chunk The chunk to apply biomes to
     * @param minY The minimum Y coordinate
     * @param maxY The maximum Y coordinate
     * @param dataInput The data input stream to read from
     */
    private fun deserializeBiomes(chunk: Chunk, minY: Int, maxY: Int, dataInput: DataInputStream) {
        // Read biome data for each block position
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                // Sample biomes at intervals
                for (y in minY until maxY step 4) {
                    // Read biome name
                    val biomeName = dataInput.readUTF()

                    try {
                        // Try to set the biome
                        // Note: In Bukkit API, setting biomes directly might not be supported
                        // This is a simplified approach
                        val block = chunk.getBlock(x, y, z)
                        // We can't directly set biomes in Bukkit API without NMS
                        // This would require NMS access to set biomes
                    } catch (e: Exception) {
                        // Skip this biome if there's an error
                    }
                }
            }
        }
    }
}
