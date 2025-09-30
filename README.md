# BetterWorlds

A Minecraft Paper plugin for efficiently serializing and deserializing chunks using MessagePack.

## Features

- Serialize Minecraft chunks to MessagePack format
- Deserialize MessagePack data back to Minecraft chunks
- Save and load chunks to/from files
- Asynchronous operations for better performance
- Simple API for developers
- Command interface for server operators

## Requirements

- Minecraft 1.21.4
- Paper server

## Installation

1. Download the latest release from the releases page
2. Place the JAR file in your server's `plugins` directory
3. Restart your server

## Commands

### /savechunk [filename]

Saves the chunk the player is currently in to a file.

- `filename` (optional): The name of the file to save to. If not provided, a default name based on chunk coordinates will be used.

Permission: `betterworlds.command.savechunk`

### /loadchunk <filename> [world]

Loads a chunk from a file.

- `filename` (required): The name of the file to load from.
- `world` (optional): The name of the world to load the chunk into. If not provided, the player's current world will be used.

Permission: `betterworlds.command.loadchunk`

## Developer API

### Getting Started

```kotlin
// Get the plugin instance
val plugin = BetterWorlds.getInstance()

// Get a BetterWorld instance for a specific world
val betterWorld = BetterWorlds.getBetterWorld(world)

// Or directly from the plugin instance
val betterWorld = plugin.getBetterWorld(world)
```

### Serializing and Deserializing Chunks

```kotlin
// Serialize a chunk to MessagePack format
val chunkData = betterWorld.serializeChunk(chunk)

// Deserialize MessagePack data back to a chunk
val success = betterWorld.deserializeChunk(chunkData)
```

### Saving and Loading Chunks

```kotlin
// Save a chunk to a file
val file = File(plugin.dataFolder, "chunks/world/chunk_0_0.dat")
val success = betterWorld.saveChunk(chunk, file)

// Load a chunk from a file
val success = betterWorld.loadChunk(file)
```

### Asynchronous Operations

```kotlin
// Save a chunk asynchronously
betterWorld.saveChunkAsync(chunk, file).thenAccept { success ->
    if (success) {
        // Chunk saved successfully
    } else {
        // Failed to save chunk
    }
}

// Load a chunk asynchronously
betterWorld.loadChunkAsync(file).thenAccept { success ->
    if (success) {
        // Chunk loaded successfully
    } else {
        // Failed to load chunk
    }
}
```

### Using the WorldHandler Directly

```kotlin
// Serialize a chunk
val chunkData = WorldHandler.serializeChunk(chunk)

// Deserialize chunk data
val success = WorldHandler.deserializeChunk(world, chunkData)

// Save a chunk to a file
val success = WorldHandler.saveChunkToFile(chunk, file)

// Load a chunk from a file
val success = WorldHandler.loadChunkFromFile(world, file)
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.