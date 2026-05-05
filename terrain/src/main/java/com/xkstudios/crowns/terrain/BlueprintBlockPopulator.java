package com.xkstudios.crowns.terrain;

import java.util.Random;
import org.bukkit.Material;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

public final class BlueprintBlockPopulator extends BlockPopulator {
    private final FloorBlueprint blueprint;

    public BlueprintBlockPopulator(FloorBlueprint blueprint) {
        this.blueprint = blueprint;
    }

    @Override
    public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        for (FloorBlueprint.Decoration decoration : this.blueprint.decorations()) {
            if (!intersects(minX, minZ, maxX, maxZ, decoration)) {
                continue;
            }
            switch (decoration.type()) {
                case "tree" -> this.placeTree(region, decoration);
                case "rock" -> this.placeRock(region, decoration);
                case "ruin" -> this.placeRuin(region, decoration);
                default -> {
                }
            }
        }
    }

    private void placeTree(LimitedRegion region, FloorBlueprint.Decoration decoration) {
        int x = decoration.x();
        int z = decoration.z();
        int y = this.blueprint.surfaceHeight(x, z) + 1;
        for (int yy = 0; yy <= 8; yy++) {
            this.set(region, x, y + yy, z, yy % 4 == 0 ? Material.STRIPPED_OAK_LOG : Material.OAK_LOG);
        }
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int distance = (int) Math.round(Math.hypot(dx, dz));
                if (distance > 5 || Math.floorMod(dx * 13 + dz * 17 + x + z, 3) == 0) {
                    continue;
                }
                int leafY = y + 6 + (distance <= 2 ? 2 : 0);
                this.set(region, x + dx, leafY, z + dz, distance >= 4 ? Material.AZALEA_LEAVES : Material.OAK_LEAVES);
            }
        }
        if (Math.floorMod(x + z, 5) == 0) {
            this.set(region, x + 1, y, z, Material.ROOTED_DIRT);
            this.set(region, x - 1, y, z, Material.ROOTED_DIRT);
            this.set(region, x, y, z + 1, Material.ROOTED_DIRT);
        }
    }

    private void placeRock(LimitedRegion region, FloorBlueprint.Decoration decoration) {
        int x = decoration.x();
        int z = decoration.z();
        int y = this.blueprint.surfaceHeight(x, z) + 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int distance = (int) Math.round(Math.hypot(dx, dz));
                if (distance > 2) {
                    continue;
                }
                this.set(region, x + dx, y, z + dz, distance == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
                if (distance <= 1 && Math.floorMod(dx + dz + x, 2) == 0) {
                    this.set(region, x + dx, y + 1, z + dz, Material.MOSSY_COBBLESTONE);
                }
            }
        }
    }

    private void placeRuin(LimitedRegion region, FloorBlueprint.Decoration decoration) {
        int x = decoration.x();
        int z = decoration.z();
        int y = this.blueprint.surfaceHeight(x, z) + 1;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                if (Math.abs(dx) != 5 && Math.abs(dz) != 5) {
                    continue;
                }
                if (Math.floorMod(dx * 31 + dz * 17 + x, 5) == 0) {
                    continue;
                }
                this.set(region, x + dx, y, z + dz, Material.MOSSY_STONE_BRICKS);
                if (Math.floorMod(dx + dz, 3) == 0) {
                    this.set(region, x + dx, y + 1, z + dz, Material.CRACKED_STONE_BRICKS);
                }
            }
        }
    }

    private void set(LimitedRegion region, int x, int y, int z, Material material) {
        if (region.isInRegion(x, y, z)) {
            region.setType(x, y, z, material);
        }
    }

    private static boolean intersects(int minX, int minZ, int maxX, int maxZ, FloorBlueprint.Decoration decoration) {
        return decoration.x() + decoration.radius() >= minX
                && decoration.x() - decoration.radius() <= maxX
                && decoration.z() + decoration.radius() >= minZ
                && decoration.z() - decoration.radius() <= maxZ;
    }
}
