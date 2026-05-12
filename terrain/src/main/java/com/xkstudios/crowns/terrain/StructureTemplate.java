package com.xkstudios.crowns.terrain;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

public final class StructureTemplate {
    private final String key;
    private final int anchorX;
    private final int anchorY;
    private final int anchorZ;
    private final List<BlockEntry> blocks;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public StructureTemplate(String key, int anchorX, int anchorY, int anchorZ, List<BlockEntry> blocks) {
        this.key = key;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.blocks = List.copyOf(blocks);
        int localMinX = Integer.MAX_VALUE;
        int localMinY = Integer.MAX_VALUE;
        int localMinZ = Integer.MAX_VALUE;
        int localMaxX = Integer.MIN_VALUE;
        int localMaxY = Integer.MIN_VALUE;
        int localMaxZ = Integer.MIN_VALUE;
        for (BlockEntry block : blocks) {
            localMinX = Math.min(localMinX, block.x());
            localMinY = Math.min(localMinY, block.y());
            localMinZ = Math.min(localMinZ, block.z());
            localMaxX = Math.max(localMaxX, block.x());
            localMaxY = Math.max(localMaxY, block.y());
            localMaxZ = Math.max(localMaxZ, block.z());
        }
        this.minX = localMinX == Integer.MAX_VALUE ? 0 : localMinX;
        this.minY = localMinY == Integer.MAX_VALUE ? 0 : localMinY;
        this.minZ = localMinZ == Integer.MAX_VALUE ? 0 : localMinZ;
        this.maxX = localMaxX == Integer.MIN_VALUE ? 0 : localMaxX;
        this.maxY = localMaxY == Integer.MIN_VALUE ? 0 : localMaxY;
        this.maxZ = localMaxZ == Integer.MIN_VALUE ? 0 : localMaxZ;
    }

    public String key() {
        return this.key;
    }

    public int anchorX() {
        return this.anchorX;
    }

    public int anchorY() {
        return this.anchorY;
    }

    public int anchorZ() {
        return this.anchorZ;
    }

    public List<BlockEntry> blocks() {
        return this.blocks;
    }

    public int width() {
        return this.maxX - this.minX + 1;
    }

    public int height() {
        return this.maxY - this.minY + 1;
    }

    public int depth() {
        return this.maxZ - this.minZ + 1;
    }

    public int blockCount() {
        return this.blocks.size();
    }

    public String sizeSummary() {
        return this.width() + "x" + this.height() + "x" + this.depth() + " (" + this.blockCount() + " blocks)";
    }

    public List<int[]> footprint(int rotation) {
        List<int[]> points = new ArrayList<>();
        for (int x = this.minX; x <= this.maxX; x++) {
            for (int z = this.minZ; z <= this.maxZ; z++) {
                points.add(this.rotate(x - this.anchorX, z - this.anchorZ, rotation));
            }
        }
        return points;
    }

    public int[] rotate(int relX, int relZ, int rotation) {
        return switch (Math.floorMod(rotation, 4)) {
            case 1 -> new int[]{-relZ, relX};
            case 2 -> new int[]{-relX, -relZ};
            case 3 -> new int[]{relZ, -relX};
            default -> new int[]{relX, relZ};
        };
    }

    public record BlockEntry(int x, int y, int z, Material material, BlockData blockData) {
        public BlockEntry(int x, int y, int z, Material material) {
            this(x, y, z, material, Bukkit.createBlockData(material));
        }

        public BlockEntry {
            if (blockData == null) {
                blockData = Bukkit.createBlockData(material == null ? Material.STONE : material);
            }
            if (material == null) {
                material = blockData.getMaterial();
            }
        }
    }
}
