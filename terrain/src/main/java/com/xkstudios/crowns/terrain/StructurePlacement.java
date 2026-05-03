package com.xkstudios.crowns.terrain;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;

public final class StructurePlacement {
    private final StructureTemplate template;
    private final int originX;
    private final int baseY;
    private final int originZ;
    private final int rotation;
    private final Material foundation;
    private final Set<Long> footprint = new HashSet<>();
    private final int minWorldX;
    private final int maxWorldX;
    private final int minWorldZ;
    private final int maxWorldZ;

    public StructurePlacement(StructureTemplate template, int originX, int baseY, int originZ, int rotation, Material foundation) {
        this.template = template;
        this.originX = originX;
        this.baseY = baseY;
        this.originZ = originZ;
        this.rotation = Math.floorMod(rotation, 4);
        this.foundation = foundation;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] point : template.footprint(this.rotation)) {
            int x = originX + point[0];
            int z = originZ + point[1];
            this.footprint.add(key(x, z));
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        this.minWorldX = minX == Integer.MAX_VALUE ? originX : minX;
        this.maxWorldX = maxX == Integer.MIN_VALUE ? originX : maxX;
        this.minWorldZ = minZ == Integer.MAX_VALUE ? originZ : minZ;
        this.maxWorldZ = maxZ == Integer.MIN_VALUE ? originZ : maxZ;
    }

    public boolean touchesColumn(int worldX, int worldZ) {
        return worldX >= this.minWorldX && worldX <= this.maxWorldX
                && worldZ >= this.minWorldZ && worldZ <= this.maxWorldZ
                && this.footprint.contains(key(worldX, worldZ));
    }

    public void applyToColumn(ChunkGenerator.ChunkData chunkData, int localX, int localZ, int maxY, int worldX, int worldZ) {
        if (!this.touchesColumn(worldX, worldZ)) {
            return;
        }
        for (int y = Math.max(chunkData.getMinHeight(), this.baseY); y <= this.baseY + 18 && y < maxY; y++) {
            chunkData.setBlock(localX, y, localZ, Material.AIR);
        }
        if (this.foundation != null) {
            for (int y = Math.max(chunkData.getMinHeight(), this.baseY - 3); y < this.baseY; y++) {
                chunkData.setBlock(localX, y, localZ, this.foundation);
            }
        }
        for (StructureTemplate.BlockEntry block : this.template.blocks()) {
            int[] rotated = this.template.rotate(block.x() - this.template.anchorX(), block.z() - this.template.anchorZ(), this.rotation);
            int blockWorldX = this.originX + rotated[0];
            int blockWorldZ = this.originZ + rotated[1];
            if (blockWorldX == worldX && blockWorldZ == worldZ) {
                int y = this.baseY + block.y() - this.template.anchorY();
                if (y >= chunkData.getMinHeight() && y < maxY) {
                    chunkData.setBlock(localX, y, localZ, block.material());
                }
            }
        }
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
