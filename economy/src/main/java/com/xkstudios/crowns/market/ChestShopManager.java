/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  org.bukkit.Bukkit
 *  org.bukkit.Location
 *  org.bukkit.Material
 *  org.bukkit.block.Block
 *  org.bukkit.block.BlockState
 *  org.bukkit.block.Chest
 *  org.bukkit.entity.Player
 *  org.bukkit.inventory.ItemStack
 */
package com.xkstudios.crowns.market;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.util.ItemSerialization;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class ChestShopManager {
    private final CrownsPlugin plugin;
    private final Map<String, ChestShopData> shops = new ConcurrentHashMap<String, ChestShopData>();

    public ChestShopManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        try (Statement s = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM chest_shops");){
            while (rs.next()) {
                ChestShopData shop = new ChestShopData(rs.getString("id"), UUID.fromString(rs.getString("owner_uuid")), rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getString("item_data"), rs.getLong("price"));
                this.shops.put(shop.key(), shop);
            }
        }
        catch (Exception e) {
            this.plugin.getLogger().warning("[Shops] Load: " + e.getMessage());
        }
        this.plugin.getLogger().info("[Shops] " + this.shops.size() + " shop(s)");
    }

    public boolean create(Player owner, Block block, long price) {
        BlockState blockState = block.getState();
        if (!(blockState instanceof Chest)) {
            return false;
        }
        Chest chest = (Chest)blockState;
        int max = this.plugin.getConfig().getInt("shops.max-per-player", 15);
        if (this.shops.values().stream().filter(s -> s.getOwner().equals(owner.getUniqueId())).count() >= (long)max) {
            return false;
        }
        long fee = this.plugin.getConfig().getLong("economy.taxes.shop-creation-fee", 200L);
        if (!this.plugin.getEconomy().withdraw(owner, fee, "shop-creation-fees", "Created chest shop")) {
            return false;
        }
        ItemStack saleItem = null;
        for (ItemStack item : chest.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            saleItem = item.clone();
            saleItem.setAmount(1);
            break;
        }
        if (saleItem == null) {
            this.plugin.getEconomy().deposit(owner, fee, null, null);
            return false;
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Location loc = block.getLocation();
        String itemData;
        try {
            itemData = ItemSerialization.serialize(saleItem);
        } catch (Exception exception) {
            this.plugin.getEconomy().deposit(owner, fee, null, null);
            return false;
        }
        ChestShopData shop = new ChestShopData(id, owner.getUniqueId(), loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), itemData, price);
        this.shops.put(shop.key(), shop);
        this.save(shop);
        return true;
    }

    public boolean buy(Player buyer, String key) {
        BlockState blockState;
        ChestShopData shop = this.shops.get(key);
        if (shop == null || shop.getOwner().equals(buyer.getUniqueId())) {
            return false;
        }
        if (!this.plugin.getEconomy().withdraw(buyer, shop.getPrice(), "shop-purchases", "Purchase from chest shop " + shop.getId())) {
            return false;
        }
        Location loc = shop.location();
        if (loc == null || !((blockState = loc.getBlock().getState()) instanceof Chest)) {
            this.plugin.getEconomy().deposit(buyer, shop.getPrice(), null, null);
            return false;
        }
        Chest chest = (Chest)blockState;
        ItemStack item;
        try {
            item = ItemSerialization.deserialize(shop.getItemData());
        } catch (Exception exception) {
            this.plugin.getEconomy().deposit(buyer, shop.getPrice(), null, null);
            return false;
        }
        if (!chest.getInventory().containsAtLeast(item, 1)) {
            this.plugin.getEconomy().deposit(buyer, shop.getPrice(), null, null);
            buyer.sendMessage((Component)Component.text((String)"Out of stock!", (TextColor)NamedTextColor.RED));
            return false;
        }
        chest.getInventory().removeItem(new ItemStack[]{item});
        HashMap<Integer, ItemStack> ov = buyer.getInventory().addItem(new ItemStack[]{item});
        for (ItemStack drop : ov.values()) {
            buyer.getWorld().dropItemNaturally(buyer.getLocation(), drop);
        }
        double taxRate = this.plugin.getConfig().getDouble("economy.taxes.shop-sale-tax", 0.02);
        long tax = (long)((double)shop.getPrice() * taxRate);
        String ownerName = Bukkit.getOfflinePlayer((UUID)shop.getOwner()).getName();
        this.plugin.getEconomy().deposit(shop.getOwner(), ownerName, shop.getPrice() - tax, "shop-sales", "Chest shop sale " + shop.getId());
        if (tax > 0L) {
            this.plugin.getEconomyLedgerManager().recordSink(shop.getOwner(), ownerName, "shop-tax", tax, "Tax on chest shop sale " + shop.getId());
        }
        Player ownerPlayer = Bukkit.getPlayer((UUID)shop.getOwner());
        if (ownerPlayer != null) {
            ownerPlayer.sendMessage((Component)Component.text((String)("Shop sale! +" + Currency.format(shop.getPrice() - tax)), (TextColor)NamedTextColor.GREEN));
        }
        if (this.plugin.getConfig().getBoolean("shops.offline-sale-notifications", true) || ownerPlayer != null) {
            this.plugin.getInboxManager().push(shop.getOwner(), ownerName, "shop_sale",
                    "Chest shop sale: " + Currency.format(shop.getPrice() - tax),
                    buyer.getName() + " bought " + item.getType().name() + " from your shop.");
        }
        return true;
    }

    public void remove(String key) {
        ChestShopData shop = this.shops.remove(key);
        if (shop != null) {
            try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("DELETE FROM chest_shops WHERE id = ?");){
                ps.setString(1, shop.getId());
                ps.executeUpdate();
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
    }

    public ChestShopData getAt(String key) {
        return this.shops.get(key);
    }

    public List<ChestShopData> getShopsByOwner(UUID uuid) {
        return this.shops.values().stream().filter(s -> s.getOwner().equals(uuid)).toList();
    }

    private void save(ChestShopData s) {
        try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("INSERT OR REPLACE INTO chest_shops (id, owner_uuid, world, x, y, z, item_data, price) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");){
            ps.setString(1, s.getId());
            ps.setString(2, s.getOwner().toString());
            ps.setString(3, s.getWorld());
            ps.setInt(4, s.getX());
            ps.setInt(5, s.getY());
            ps.setInt(6, s.getZ());
            ps.setString(7, s.getItemData());
            ps.setLong(8, s.getPrice());
            ps.executeUpdate();
        }
        catch (Exception e) {
            this.plugin.getLogger().warning("[Shops] Save: " + e.getMessage());
        }
    }
}
