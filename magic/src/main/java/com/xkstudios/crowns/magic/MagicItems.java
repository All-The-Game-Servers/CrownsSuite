package com.xkstudios.crowns.magic;

import com.xkstudios.crowns.pack.PackModelHelper;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class MagicItems {
    private MagicItems() {
    }

    public static ItemStack focus(CrownsMagicPlugin plugin) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Starlit Focus", NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(
                Component.text("Sneak and gesture to cast bound spells.", NamedTextColor.GRAY),
                Component.text("Use /magic to manage your spellbook.", NamedTextColor.DARK_GRAY)
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(plugin.focusKey(), PersistentDataType.BYTE, (byte) 1);
        PackModelHelper.apply(meta, "lowlight/magic/focus");
        item.setItemMeta(meta);
        return item;
    }
}
