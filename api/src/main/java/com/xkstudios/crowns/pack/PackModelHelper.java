package com.xkstudios.crowns.pack;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

public final class PackModelHelper {
    private PackModelHelper() {
    }

    public static void apply(ItemMeta meta, String modelPath) {
        if (meta == null || modelPath == null || modelPath.isBlank()) {
            return;
        }
        NamespacedKey key = normalize(modelPath);
        if (key != null) {
            meta.setItemModel(key);
        }
    }

    public static NamespacedKey normalize(String modelPath) {
        if (modelPath == null) {
            return null;
        }
        String raw = modelPath.trim();
        if (raw.isBlank()) {
            return null;
        }
        if (raw.contains(":")) {
            try {
                return NamespacedKey.fromString(raw);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        int slash = raw.indexOf('/');
        if (slash > 0 && slash < raw.length() - 1) {
            try {
                return new NamespacedKey(raw.substring(0, slash), raw.substring(slash + 1));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        try {
            return new NamespacedKey("lowlight", raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
