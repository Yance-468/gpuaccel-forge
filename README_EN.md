# GPU Accelerator

A Minecraft Forge 1.20.1 server-side mod that offloads terrain generation and mob AI to the GPU via OpenCL (LWJGL).

## Overview

GPU Accelerator moves two of the most parallel, computation-heavy parts of a Minecraft server onto the GPU:

1. **Terrain generation** — a full 3D density field (surface, caves, ores, aquifers, lava) computed in a single OpenCL kernel.
2. **Mob pathfinding** — a GPU flow field that routes hostile mobs toward players around obstacles.

## Features

### GPU Terrain (custom world type)
- **3D density terrain** — caves, floating overhangs, and cliffs.
- **Ores** — coal, iron, gold, and diamond distributed by depth.
- **Biomes** — grass plains, desert, snow, and mountain stone, selected by a climate noise.
- **Aquifers & lava** — water-filled caves below sea level, lava lakes deep underground.
- **Trees** — oak trees on grass plains.

### GPU Mob AI (flow-field pathfinding)
- Hostile mobs follow a **3D flow field** (8 horizontal + up/down directions) toward the nearest player.
- **Per-player flow fields** — every online player gets their own GPU-computed field.
- Mobs are only attracted within 32 blocks and move at a reduced speed.

### Client GUI
- Press **G** in-game to open the status screen.
- Shows GPU device info, GPU busy %, density stats, and server CPU / memory / world size.
- **Operator-only** — restricted to players with permission level 2+.

### Other
- **CPU fallback** with bit-identical output when no GPU is available.
- **Chunky-compatible** — bounded memory cache for large pre-generation; nether/end included.
- GPU engine auto-initializes when the server starts.

## Requirements
- Minecraft 1.20.1, Forge 47.x (any 47.x).
- Java 17.
- An OpenCL-capable GPU and driver (NVIDIA / AMD / Intel). Falls back to CPU otherwise.

## Installation
- **Server**: put `gpuaccel-1.1.0-all.jar` into `mods/`.
- **Client** (for the GUI): put `gpuaccel-1.1.0-client.jar` into `.minecraft/mods/`.

## Usage
1. Create a new world with the **GPU Terrain** world type, or set `level-type=gpuaccel:gpu_terrain` in `server.properties` and delete the old world.
2. Join the game and press **G** to view GPU / server status.
3. Optional: use Chunky to pre-generate the world — the cache is bounded, so it won't run out of memory.

## How it works
- **Terrain**: a 256x256x96 grid of density samples is computed in one OpenCL kernel (6-octave value-noise fBm), then converted to block types. Regions are cached (bounded).
- **Mob AI**: for each player, a 32x8x32 walkability grid is built, then a BFS flood-fill + direction field is computed on the GPU. Hostile mobs inside the field follow the per-voxel direction.

## Performance
- The 3D density field is roughly 384x more voxels than the original 2D heightmap, so the GPU is genuinely busy during generation.
- On an NVIDIA GTX 960, a 1024x1024 heightmap computes in ~12 ms (vs ~240 ms single-threaded CPU).

## Links
- GitHub: https://github.com/Yance-468/gpuaccel-forge
- Releases: https://github.com/Yance-468/gpuaccel-forge/releases
