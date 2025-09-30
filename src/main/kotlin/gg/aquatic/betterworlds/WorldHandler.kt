package gg.aquatic.betterworlds

import gg.aquatic.betterworlds.data.FlatFileWorldDataDriver
import gg.aquatic.betterworlds.data.WorldDataDriver
import gg.aquatic.betterworlds.serialization.ChunkSerializer
import net.md_5.bungee.api.chat.hover.content.EntitySerializer
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtIo
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ProtoChunk
import net.minecraft.world.level.chunk.storage.SerializableChunkData
import net.minecraft.world.level.lighting.LevelLightEngine
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.craftbukkit.v1_21_R3.CraftWorld
import org.bukkit.craftbukkit.v1_21_R3.block.data.CraftBlockData
import org.bukkit.craftbukkit.v1_21_R3.generator.CraftChunkData
import org.bukkit.craftbukkit.v1_21_R3.generator.CraftWorldInfo
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import org.bukkit.scheduler.BukkitRunnable
import java.util.*

/**
 * Handles operations related to world and chunk management.
 */
object WorldHandler {

    val customWorlds = HashMap<String, BetterWorld>()

    /**
     * Serializes a chunk to MessagePack format.
     *
     * @param chunk The chunk to serialize
     * @return ByteArray containing the MessagePack serialized data
     */
    fun serializeChunk(chunk: Chunk): ByteArray {
        return ChunkSerializer.serializeChunkNew(chunk)
    }

    fun initialize() {
        val dataDriver = FlatFileWorldDataDriver(
            BetterWorlds.getInstance().dataFolder.resolve("worlds/test").apply {
                mkdirs()
            }
        )

        event<ChunkLoadEvent> { e ->
            val worldName = e.world.name
            val betterWorld = customWorlds[worldName] ?: return@event
            val chunk = e.chunk
            val data = betterWorld.dataDriver.load(chunk.x, chunk.z)
            if (data == null) {
                Bukkit.getLogger().info("Could not load data for ${chunk.x},${chunk.z}")
                return@event
            }
            val serializedData = deserializeChunk(chunk.world, data) ?: return@event

            Bukkit.getLogger().info("Loaded chunk ${chunk.x},${chunk.z} from database")
            applyChunkData(chunk, serializedData)
            Bukkit.getLogger().info("Applied chunk ${chunk.x},${chunk.z}")
        }

        event<AsyncPlayerChatEvent> {
            val message = it.message

            if (message.lowercase() == "convert world") {
                val world = it.player.world
                it.player.sendMessage("Converting world ${world.name}...")
                convertWorld(world, 3, dataDriver)
                return@event
            } else if (message.lowercase() == "create world") {
                it.player.sendMessage("Creating better world...")
                object : BukkitRunnable() {
                    override fun run() {
                        loadBetterWorld(dataDriver, "test")
                        it.player.sendMessage("World created!")
                    }
                }.runTaskLater(BetterWorlds.getInstance(), 1)
            }
        }
    }

    /**
     * Deserializes MessagePack data back to a Minecraft chunk.
     *
     * @param world The world to load the chunk into
     * @param data The MessagePack serialized data
     * @return Boolean indicating success or failure
     */
    fun deserializeChunk(world: World, data: ByteArray): SerializableChunkData? {
        return ChunkSerializer.deserializeChunk(data, world)
    }

    fun applyChunkData(chunk: Chunk, data: SerializableChunkData) {
        val nmsWorld = (chunk.world as CraftWorld).handle
        val nmsChunk = nmsWorld.getChunk(chunk.x, chunk.z)
        //SerializableChunkData.parse(nmsChunk, nmsWorld.registryAccess(), data.write())
        /*
        SerializableChunkData.parse(nmsChunk, nmsWorld.registryAccess(), data.write())
        val protoChunk = data.read(nmsWorld, nmsWorld.poiManager, null, nmsChunk.pos)
        nmsWorld.chunkSource.chunkMap.write(ChunkPos(chunk.x, chunk.z)) { data.write() }
        nmsWorld.chunkSource
        /*
        object : BukkitRunnable() {
            override fun run() {
                val protoChunk = data.read(nmsWorld, nmsWorld.poiManager,null, nmsChunk.pos)
                for (y in protoChunk.minY..protoChunk.maxY) {
                    for (x in protoChunk.pos.minBlockX..protoChunk.pos.maxBlockX) {
                        for (z in protoChunk.pos.minBlockZ .. protoChunk.pos.maxBlockZ) {
                            val pos = BlockPos(x,y,z)
                            val state = protoChunk.getBlockState(pos)
                            //val combined = Block.getId(state)

                            if (state.block.name.string.lowercase() == "air") continue
                            val sectionIndex = nmsChunk.getSectionIndex(y)
                            val section = nmsChunk.sections[sectionIndex]
                            Bukkit.getLogger().info("Setting block to ${state.block.name.string}")
                            section.setBlockState(x and 15, y and 15, z and 15, state, false)
                            //nmsWorld.setBlock(pos,state,1024)
                        }
                    }
                }
            }
        }.runTaskAsynchronously(BetterWorlds.getInstance())
         */
        Bukkit.getLogger().info("Gathered the proto chunk")
        //SerializableChunkData.parse(nmsWorld, nmsWorld.registryAccess(), data.write())
        chunk.world.refreshChunk(chunk.x, chunk.z)

         */
    }

    fun convertWorld(world: World, radius: Int, dataDriver: WorldDataDriver) {
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val chunk = world.getChunkAt(x, z)
                if (!chunk.isLoaded) {
                    chunk.load(true)
                }
                val data = serializeChunk(chunk)
                dataDriver.save(chunk.x, chunk.z,data)
            }
        }
    }

    fun loadBetterWorld(dataDriver: WorldDataDriver, worldName: String): BetterWorld {
        val betterWorld = BetterWorld(worldName, null, dataDriver)
        customWorlds[worldName] = betterWorld
        val world = createEmptyWorld(worldName,betterWorld)
        betterWorld.world = world

        return betterWorld
    }

    /*
    fun createCustomWorld(name: String, seed: Long, generator: ChunkGenerator): World {
        val craftServer = Bukkit.getServer() as CraftServer
        val console = craftServer.server
    }
     */

    fun createEmptyWorld(name: String, betterWorld: BetterWorld): World? {
        val worldCreator = org.bukkit.WorldCreator(name)
            .generateStructures(false)
            .environment(World.Environment.NORMAL)
            .type(org.bukkit.WorldType.NORMAL)
            .generator(EmptyGenerator(betterWorld))

        val world = worldCreator.createWorld() ?: return null
        world.isAutoSave = false
        return world
    }

    private class EmptyGenerator(val betterWorld: BetterWorld) : ChunkGenerator() {
        override fun generateSurface(
            worldInfo: WorldInfo,
            random: Random,
            x: Int,
            z: Int,
            chunkData: ChunkData
        ) {
            val craftChunkData = chunkData as CraftChunkData
            val chunkAccess = craftChunkData.handle as ProtoChunk

            val data = betterWorld.dataDriver.load(x, z) ?: return
            /*
            if (accessor is Level) {
                Bukkit.getLogger().info("It is level!")
            } else {
                Bukkit.getLogger().info("It is not level! ${accessor.javaClass.name}")
            }

             */


            /*
            val nmsWorld = (world as CraftWorld).handle
            val nmsChunkData = ChunkSerializer.deserializeChunk(data, world) ?: return chunkData
            val protoChunk = nmsChunkData.read(nmsWorld, nmsWorld.poiManager, null, ChunkPos(x, z))
            for (y in protoChunk.minY..protoChunk.maxY) {
                for (x in protoChunk.pos.minBlockX..protoChunk.pos.maxBlockX) {
                    for (z in protoChunk.pos.minBlockZ..protoChunk.pos.maxBlockZ) {
                        val pos = BlockPos(x, y, z)
                        val state = protoChunk.getBlockState(pos)
                        //val combined = Block.getId(state)

                        chunkData.setBlock(x and 15, y, z and 15, CraftBlockData.fromData(state))
                        /*
                        if (state.block.name.string.lowercase() == "air") continue
                        val sectionIndex = nmsChunk.getSectionIndex(y)
                        val section = nmsChunk.sections[sectionIndex]
                        Bukkit.getLogger().info("Setting block to ${state.block.name.string}")
                        section.setBlockState(x and 15, y and 15, z and 15, state, false)
                        //nmsWorld.setBlock(pos,state,1024)
                         */
                    }
                }
            }
            val nmsChunk = chunkData.handle

            if (nmsChunk is ProtoChunk) {
                Bukkit.getLogger().info("Set entities!")
                nmsChunk.entities += protoChunk.entities
            } else {
                Bukkit.getLogger().info("It is not proto chunk!")
            }
            return chunkData
             */
        }

        override fun generateChunkData(world: World, random: Random, x: Int, z: Int, biome: BiomeGrid): ChunkData {
            val chunkData = createChunkData(world)

            val data = betterWorld.dataDriver.load(x, z) ?: return chunkData
            val nmsWorld = (world as CraftWorld).handle
            val nmsChunkData = ChunkSerializer.deserializeChunk(data, world) ?: return chunkData
            val protoChunk = nmsChunkData.read(nmsWorld, nmsWorld.poiManager, null, ChunkPos(x, z))
            for (y in protoChunk.minY..protoChunk.maxY) {
                for (x in protoChunk.pos.minBlockX..protoChunk.pos.maxBlockX) {
                    for (z in protoChunk.pos.minBlockZ..protoChunk.pos.maxBlockZ) {
                        val pos = BlockPos(x, y, z)
                        val state = protoChunk.getBlockState(pos)
                        //val combined = Block.getId(state)

                        chunkData.setBlock(x and 15, y, z and 15, CraftBlockData.fromData(state))
                        /*
                        if (state.block.name.string.lowercase() == "air") continue
                        val sectionIndex = nmsChunk.getSectionIndex(y)
                        val section = nmsChunk.sections[sectionIndex]
                        Bukkit.getLogger().info("Setting block to ${state.block.name.string}")
                        section.setBlockState(x and 15, y and 15, z and 15, state, false)
                        //nmsWorld.setBlock(pos,state,1024)
                         */
                    }
                }
            }

            val nmsChunk = nmsWorld.getChunk(x, z)

            if (nmsChunk is ProtoChunk) {
                Bukkit.getLogger().info("Set entities!")
                nmsChunk.entities += protoChunk.entities
            } else {
                Bukkit.getLogger().info("It is not proto chunk!")
            }
            return chunkData
        }

        override fun generateNoise(
            worldInfo: WorldInfo,
            random: Random,
            chunkX: Int,
            chunkZ: Int,
            chunkData: ChunkData
        ) {
        }
    }
}
