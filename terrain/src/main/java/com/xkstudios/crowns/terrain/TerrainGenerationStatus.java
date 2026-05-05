package com.xkstudios.crowns.terrain;

public record TerrainGenerationStatus(
        int floor,
        String worldName,
        String profileVersion,
        String status,
        int totalChunks,
        int generatedChunks,
        int totalBlocks,
        int placedBlocks,
        long startedAt,
        long completedAt,
        String startedBy,
        String message
) {
    public static TerrainGenerationStatus notGenerated(int floor, String worldName, String profileVersion) {
        return new TerrainGenerationStatus(floor, worldName, profileVersion, "NOT_GENERATED", 0, 0, 0, 0, 0L, 0L, "", "No generation job has completed for this floor.");
    }

    public boolean readyForPlayers() {
        return this.status.equalsIgnoreCase("CRITICAL_READY")
                || this.status.equalsIgnoreCase("SAFE_READY")
                || this.status.equalsIgnoreCase("FULL_READY")
                || this.status.equalsIgnoreCase("critical-ready")
                || this.status.equalsIgnoreCase("complete");
    }

    public boolean active() {
        return this.status.equalsIgnoreCase("GENERATING") || this.status.equalsIgnoreCase("generating");
    }

    public String progressSummary() {
        String chunkProgress = this.totalChunks <= 0 ? "0/0 chunks" : this.generatedChunks + "/" + this.totalChunks + " chunks";
        String blockProgress = this.totalBlocks <= 0 ? "0/0 blocks" : this.placedBlocks + "/" + this.totalBlocks + " blocks";
        return chunkProgress + ", " + blockProgress;
    }
}
