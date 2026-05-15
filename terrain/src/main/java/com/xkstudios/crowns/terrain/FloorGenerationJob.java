package com.xkstudios.crowns.terrain;

import com.xkstudios.crowns.api.TerrainPoint;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

public final class FloorGenerationJob {
    private enum Phase {
        LOAD_CHUNKS,
        BUILD_SET_MAP,
        FINISH
    }

    private final CrownsTerrainPlugin plugin;
    private final TerrainManager terrainManager;
    private final int floor;
    private final String worldName;
    private final String startedBy;
    private final int chunksPerTick;
    private final int blocksPerTick;
    private final FloorBlueprint blueprint;
    private final List<SetMapFloorBuilder.ChunkCoord> chunks;
    private final CommandSender starter;
    private BukkitTask task;
    private World world;
    private Phase phase = Phase.LOAD_CHUNKS;
    private int chunkIndex;
    private int blockIndex;
    private List<SetMapFloorBuilder.BlockOperation> operations = List.of();
    private boolean cancelled;

    public FloorGenerationJob(CrownsTerrainPlugin plugin, TerrainManager terrainManager, int floor, CommandSender starter) {
        this.plugin = plugin;
        this.terrainManager = terrainManager;
        this.floor = floor;
        this.worldName = terrainManager.getWorldName(floor);
        this.startedBy = starter.getName();
        this.starter = starter;
        this.chunksPerTick = Math.max(1, plugin.getConfig().getInt("terrain.floors." + floor + ".pregeneration.chunks-per-tick", 12));
        this.blocksPerTick = Math.max(128, plugin.getConfig().getInt("terrain.floors." + floor + ".pregeneration.block-ops-per-tick", 3500));
        this.blueprint = terrainManager.isHybridBlueprintFloor(floor) ? terrainManager.getOrCreateBlueprint(floor) : null;
        if (this.blueprint != null) {
            this.chunks = new ArrayList<>(SetMapFloorBuilder.criticalChunks(this.blueprint));
        } else {
            List<TerrainPoint> points = terrainManager.getAllPoints(floor, this.worldName);
            this.chunks = new ArrayList<>(SetMapFloorBuilder.criticalChunks(points));
        }
    }

    public void start() {
        this.world = this.terrainManager.createFloorWorld(this.floor);
        if (this.world == null) {
            this.terrainManager.finishGenerationJob(this.floor, "failed", 0, 0, 0, 0, this.startedBy, "World could not be created.");
            this.terrainManager.clearActiveGeneration(this.floor);
            this.starter.sendMessage(Component.text("CrownsTerrain could not create Floor " + this.floor + " world '" + this.worldName + "'.", NamedTextColor.RED));
            return;
        }
        this.terrainManager.saveGenerationJob(this.floor, this.worldName, "GENERATING", this.chunks.size(), 0, 0, 0, this.startedBy, "Loading critical route chunks.");
        this.task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, this::tick, 1L, 1L);
        this.starter.sendMessage(Component.text("Started CrownsTerrain Floor " + this.floor + " " + this.modeLabel() + " generation for '" + this.worldName + "'.", NamedTextColor.GREEN));
        this.starter.sendMessage(Component.text("Critical-route chunks: " + this.chunks.size() + ". Players should wait for status CRITICAL_READY.", NamedTextColor.YELLOW));
    }

    public void cancel(String reason) {
        this.cancelled = true;
        if (this.task != null) {
            this.task.cancel();
        }
        this.terrainManager.finishGenerationJob(this.floor, "CANCELLED", this.chunks.size(), this.chunkIndex, this.operations.size(), this.blockIndex, this.startedBy, reason == null ? "Cancelled." : reason);
        this.terrainManager.clearActiveGeneration(this.floor);
    }

    public TerrainGenerationStatus snapshot() {
        return new TerrainGenerationStatus(this.floor, this.worldName, this.profileVersion(), this.cancelled ? "CANCELLED" : "GENERATING",
                this.chunks.size(), this.chunkIndex, this.operations.size(), this.blockIndex, 0L, 0L, this.startedBy,
                this.phase == Phase.LOAD_CHUNKS ? "Loading critical route chunks." : "Placing authored route blocks.");
    }

    private void tick() {
        if (this.cancelled) {
            return;
        }
        if (this.phase == Phase.LOAD_CHUNKS) {
            this.tickChunks();
            return;
        }
        if (this.phase == Phase.BUILD_SET_MAP) {
            this.tickBlocks();
            return;
        }
        this.finish();
    }

    private void tickChunks() {
        int limit = Math.min(this.chunks.size(), this.chunkIndex + this.chunksPerTick);
        while (this.chunkIndex < limit) {
            SetMapFloorBuilder.ChunkCoord chunk = this.chunks.get(this.chunkIndex);
            this.world.getChunkAt(chunk.x(), chunk.z()).load(true);
            this.chunkIndex++;
        }
        if (this.chunkIndex % Math.max(1, this.chunksPerTick * 10) == 0 || this.chunkIndex >= this.chunks.size()) {
            this.terrainManager.saveGenerationJob(this.floor, this.worldName, "GENERATING", this.chunks.size(), this.chunkIndex,
                    this.operations.size(), this.blockIndex, this.startedBy, "Loading critical route chunks.");
        }
        if (this.chunkIndex >= this.chunks.size()) {
            List<SetMapFloorBuilder.BlockOperation> builtOperations = this.blueprint == null
                    ? SetMapFloorBuilder.operations(this.world, this.terrainManager.theme(this.floor), this.terrainManager.getAllPoints(this.floor, this.worldName))
                    : SetMapFloorBuilder.operations(this.terrainManager.theme(this.floor), this.blueprint);
            if (this.terrainManager.isWorldPainterFloor(this.floor)) {
                builtOperations = new ArrayList<>(builtOperations);
                builtOperations.addAll(this.terrainManager.plannedStructureOperations(this.floor, this.worldName, this.world));
            }
            this.operations = builtOperations;
            this.terrainManager.saveGenerationJob(this.floor, this.worldName, "GENERATING", this.chunks.size(), this.chunkIndex,
                    this.operations.size(), 0, this.startedBy, "Placing authored route blocks.");
            this.phase = Phase.BUILD_SET_MAP;
        }
    }

    private void tickBlocks() {
        int limit = Math.min(this.operations.size(), this.blockIndex + this.blocksPerTick);
        while (this.blockIndex < limit) {
            SetMapFloorBuilder.apply(this.world, this.operations.get(this.blockIndex));
            this.blockIndex++;
        }
        if (this.blockIndex % Math.max(1, this.blocksPerTick * 10) == 0 || this.blockIndex >= this.operations.size()) {
            this.terrainManager.saveGenerationJob(this.floor, this.worldName, "GENERATING", this.chunks.size(), this.chunkIndex,
                    this.operations.size(), this.blockIndex, this.startedBy, "Placing authored route blocks.");
        }
        if (this.blockIndex >= this.operations.size()) {
            this.phase = Phase.FINISH;
        }
    }

    private void finish() {
        if (this.task != null) {
            this.task.cancel();
        }
        this.world.save();
        this.terrainManager.finishGenerationJob(this.floor, "CRITICAL_READY", this.chunks.size(), this.chunkIndex,
                this.operations.size(), this.blockIndex, this.startedBy, "Critical route generated: First Haven, roads, camp, shrine, waystone, farms, and arena.");
        this.terrainManager.clearActiveGeneration(this.floor);
        this.starter.sendMessage(Component.text("CrownsTerrain Floor " + this.floor + " is CRITICAL_READY. Run /cterrain verify floor " + this.floor + ".", NamedTextColor.GREEN));
    }

    private String profileVersion() {
        return this.blueprint == null ? this.terrainManager.getTerrainProfile(this.floor) : this.blueprint.profileVersion();
    }

    private String modeLabel() {
        if (this.terrainManager.isWorldPainterFloor(this.floor)) {
            return "WorldPainter + .ctpl overlay";
        }
        return this.blueprint == null ? "set-map" : "hybrid engine";
    }
}
