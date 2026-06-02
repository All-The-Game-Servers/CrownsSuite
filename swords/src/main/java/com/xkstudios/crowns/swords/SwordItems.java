package com.xkstudios.crowns.swords;

import com.xkstudios.crowns.pack.PackModelHelper;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class SwordItems {
    private SwordItems() {
    }

    public static ItemStack trainingBlade(CrownsSwordsPlugin plugin) {
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Training Blade", NamedTextColor.AQUA));
        meta.lore(List.of(
                Component.text("Sneak and gesture to use sword arts.", NamedTextColor.GRAY),
                Component.text("Use /swords to manage your skillbook.", NamedTextColor.DARK_GRAY)
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(plugin.trainingBladeKey(), PersistentDataType.BYTE, (byte) 1);
        PackModelHelper.apply(meta, "lowlight/swords/training_blade");
        item.setItemMeta(meta);
        return item;
    }
}
