package com.xkstudios.crowns.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public final class InventorySerialization {
    private InventorySerialization() {
    }

    public static String serialize(ItemStack[] items) throws IOException {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(buffer)) {
            output.writeInt(items.length);
            for (ItemStack item : items) {
                output.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        }
    }

    public static ItemStack[] deserialize(String encoded, int expectedLength) throws IOException, ClassNotFoundException {
        if (encoded == null || encoded.isBlank()) {
            return new ItemStack[expectedLength];
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try (ByteArrayInputStream buffer = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream input = new BukkitObjectInputStream(buffer)) {
            int length = input.readInt();
            ItemStack[] items = new ItemStack[Math.max(expectedLength, length)];
            for (int i = 0; i < length; i++) {
                Object value = input.readObject();
                if (value instanceof ItemStack item) {
                    items[i] = item;
                }
            }
            if (items.length == expectedLength) {
                return items;
            }
            ItemStack[] resized = new ItemStack[expectedLength];
            System.arraycopy(items, 0, resized, 0, Math.min(items.length, expectedLength));
            return resized;
        }
    }
}
