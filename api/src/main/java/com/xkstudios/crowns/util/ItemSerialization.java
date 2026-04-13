package com.xkstudios.crowns.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public final class ItemSerialization {
    private ItemSerialization() {
    }

    public static String serialize(ItemStack item) throws IOException {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(buffer)) {
            output.writeObject(item);
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        }
    }

    public static ItemStack deserialize(String encoded) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try (ByteArrayInputStream buffer = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream input = new BukkitObjectInputStream(buffer)) {
            Object value = input.readObject();
            if (value instanceof ItemStack item) {
                return item;
            }
            throw new IOException("Decoded value was not an ItemStack");
        }
    }
}
