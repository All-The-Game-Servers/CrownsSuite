package com.xkstudios.crowns.drugs;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.api.EconomyProvider;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.pack.PackModelHelper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

public class DrugManager {
    private final CrownsPlugin plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey formKey;

    public DrugManager(CrownsPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "drug_type");
        this.formKey = new NamespacedKey(plugin, "drug_form");
    }

    public void initialize() {
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS drug_businesses (
                        owner_uuid TEXT PRIMARY KEY,
                        dirty_cash INTEGER DEFAULT 0,
                        lab_tier INTEGER DEFAULT 1,
                        storage_tier INTEGER DEFAULT 1,
                        processor_tier INTEGER DEFAULT 1,
                        seeds INTEGER DEFAULT 12,
                        supplies INTEGER DEFAULT 8,
                        marijuana_raw INTEGER DEFAULT 0,
                        marijuana_packaged INTEGER DEFAULT 0,
                        cocaine_raw INTEGER DEFAULT 0,
                        cocaine_packaged INTEGER DEFAULT 0,
                        meth_raw INTEGER DEFAULT 0,
                        meth_packaged INTEGER DEFAULT 0
                    )
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize drug tables", exception);
        }
    }

    public DrugBusiness getBusiness(UUID ownerId) {
        DrugBusiness business = new DrugBusiness(ownerId);
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "SELECT * FROM drug_businesses WHERE owner_uuid = ?")) {
            statement.setString(1, ownerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    this.save(business);
                    return business;
                }
                business.setDirtyCash(resultSet.getLong("dirty_cash"));
                business.setLabTier(resultSet.getInt("lab_tier"));
                business.setStorageTier(resultSet.getInt("storage_tier"));
                business.setProcessorTier(resultSet.getInt("processor_tier"));
                business.setSeeds(resultSet.getInt("seeds"));
                business.setSupplies(resultSet.getInt("supplies"));
                business.setRaw(DrugProduct.MARIJUANA, resultSet.getInt("marijuana_raw"));
                business.setPackaged(DrugProduct.MARIJUANA, resultSet.getInt("marijuana_packaged"));
                business.setRaw(DrugProduct.COCAINE, resultSet.getInt("cocaine_raw"));
                business.setPackaged(DrugProduct.COCAINE, resultSet.getInt("cocaine_packaged"));
                business.setRaw(DrugProduct.METH, resultSet.getInt("meth_raw"));
                business.setPackaged(DrugProduct.METH, resultSet.getInt("meth_packaged"));
                return business;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not load drug business", exception);
        }
    }

    public void save(DrugBusiness business) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement("""
                INSERT OR REPLACE INTO drug_businesses (
                    owner_uuid, dirty_cash, lab_tier, storage_tier, processor_tier, seeds, supplies,
                    marijuana_raw, marijuana_packaged, cocaine_raw, cocaine_packaged, meth_raw, meth_packaged
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, business.ownerId().toString());
            statement.setLong(2, business.dirtyCash());
            statement.setInt(3, business.labTier());
            statement.setInt(4, business.storageTier());
            statement.setInt(5, business.processorTier());
            statement.setInt(6, business.seeds());
            statement.setInt(7, business.supplies());
            statement.setInt(8, business.raw(DrugProduct.MARIJUANA));
            statement.setInt(9, business.packaged(DrugProduct.MARIJUANA));
            statement.setInt(10, business.raw(DrugProduct.COCAINE));
            statement.setInt(11, business.packaged(DrugProduct.COCAINE));
            statement.setInt(12, business.raw(DrugProduct.METH));
            statement.setInt(13, business.packaged(DrugProduct.METH));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save drug business", exception);
        }
    }

    public String grow(Player player, DrugProduct product) {
        DrugBusiness business = this.getBusiness(player.getUniqueId());
        if (!this.isUnlocked(business, product)) {
            return this.lockedMessage(product);
        }
        if (business.seeds() <= 0) {
            return "You need more seeds before you can grow " + product.display() + ".";
        }
        int yield = product.growYield() + Math.max(0, business.labTier() - 1);
        if (this.rawStock(player) + yield > business.storageCap()) {
            return "Storage is too full to grow more " + product.display() + ".";
        }
        business.setSeeds(business.seeds() - 1);
        this.save(business);
        this.giveItem(player, this.createDrugItem(product, "raw", yield));
        return "You grew " + yield + " raw " + product.display() + " batch(es).";
    }

    public String process(Player player, DrugProduct product) {
        DrugBusiness business = this.getBusiness(player.getUniqueId());
        if (!this.isUnlocked(business, product)) {
            return this.lockedMessage(product);
        }
        if (business.supplies() <= 0) {
            return "You need more supplies before you can process stock.";
        }
        int available = this.countItems(player, product, "raw");
        if (available <= 0) {
            return "You do not have any raw " + product.display() + " to process.";
        }
        int processed = Math.min(available, Math.max(1, business.processorTier()));
        if (!this.removeDrugItems(player, product, "raw", processed)) {
            return "You could not process that stock right now.";
        }
        business.setSupplies(business.supplies() - 1);
        this.save(business);
        this.giveItem(player, this.createDrugItem(product, "packaged", processed));
        return "You processed " + processed + " " + product.display() + " package(s).";
    }

    public String processAll(Player player) {
        List<String> lines = new ArrayList<>();
        for (DrugProduct product : DrugProduct.values()) {
            String result = this.process(player, product);
            if (!result.startsWith("You do not have")) {
                lines.add(result);
            }
        }
        return lines.isEmpty() ? "Nothing was ready to process." : String.join(" ", lines);
    }

    public String sell(Player player, DrugProduct product) {
        EconomyProvider economy = CrownsAPI.getEconomy();
        if (economy == null) {
            return "CrownsEconomy is required for buying, selling, and upgrades.";
        }
        DrugBusiness business = this.getBusiness(player.getUniqueId());
        if (!this.isUnlocked(business, product)) {
            return this.lockedMessage(product);
        }
        int packaged = this.countItems(player, product, "packaged");
        if (packaged <= 0) {
            return "You do not have any packaged " + product.display() + " ready to sell.";
        }
        int sold = Math.min(packaged, this.currentOrderAmount(player.getUniqueId(), product));
        long payout = sold * this.currentPrice(player.getUniqueId(), product);
        if (!this.removeDrugItems(player, product, "packaged", sold)) {
            return "You could not move that package stock.";
        }
        economy.deposit(player.getUniqueId(), payout);
        if (CrownsAPI.getInbox() != null) {
            CrownsAPI.getInbox().sendNotification(player.getUniqueId(), "Black Market Sale",
                    "You sold " + sold + " " + product.display() + " package(s) for " + Currency.format(payout) + ".");
        }
        return "You sold " + sold + " " + product.display() + " package(s) for " + Currency.format(payout) + ".";
    }

    public String sellAll(Player player) {
        List<String> lines = new ArrayList<>();
        for (DrugProduct product : DrugProduct.values()) {
            String result = this.sell(player, product);
            if (!result.startsWith("You do not have")) {
                lines.add(result);
            }
        }
        return lines.isEmpty() ? "Nothing was ready to sell." : String.join(" ", lines);
    }

    public String launder(Player player) {
        return "Drug sales already pay directly in Crowns now.";
    }

    public String restockSeeds(Player player) {
        DrugBusiness business = this.getBusiness(player.getUniqueId());
        long cost = this.plugin.getConfig().getLong("drugs.seeds-restock-cost", 120L);
        if (!this.withdrawCrowns(player, cost)) {
            return "You need " + Currency.format(cost) + " to restock seeds.";
        }
        business.setSeeds(business.seeds() + 10);
        this.save(business);
        return "You restocked seeds for your operation.";
    }

    public String restockSupplies(Player player) {
        DrugBusiness business = this.getBusiness(player.getUniqueId());
        long cost = this.plugin.getConfig().getLong("drugs.supplies-restock-cost", 180L);
        if (!this.withdrawCrowns(player, cost)) {
            return "You need " + Currency.format(cost) + " to restock supplies.";
        }
        business.setSupplies(business.supplies() + 8);
        this.save(business);
        return "You restocked processing supplies.";
    }

    public String upgrade(Player player, String target) {
        DrugBusiness business = this.getBusiness(player.getUniqueId());
        return switch (target) {
            case "lab" -> this.upgradeLab(player, business);
            case "storage" -> this.upgradeStorage(player, business);
            case "processor" -> this.upgradeProcessor(player, business);
            default -> "Unknown upgrade.";
        };
    }

    public boolean isUnlocked(DrugBusiness business, DrugProduct product) {
        return business.labTier() >= product.requiredLabTier()
                && business.processorTier() >= product.requiredProcessorTier();
    }

    public String lockedMessage(DrugProduct product) {
        return product.display() + " requires grow tier " + product.requiredLabTier()
                + " and processor tier " + product.requiredProcessorTier() + ".";
    }

    public String recipeSummary(DrugProduct product) {
        return "Grow tier " + product.requiredLabTier()
                + " + processor tier " + product.requiredProcessorTier()
                + " unlocks " + product.display() + ".";
    }

    private String upgradeLab(Player player, DrugBusiness business) {
        long cost = business.labTier() * 350L;
        if (!this.withdrawCrowns(player, cost)) {
            return "You need " + Currency.format(cost) + " to upgrade your grow operation.";
        }
        business.setLabTier(business.labTier() + 1);
        this.save(business);
        return "Your grow operation is now tier " + business.labTier() + ".";
    }

    private String upgradeStorage(Player player, DrugBusiness business) {
        long cost = business.storageTier() * 300L;
        if (!this.withdrawCrowns(player, cost)) {
            return "You need " + Currency.format(cost) + " to expand storage.";
        }
        business.setStorageTier(business.storageTier() + 1);
        this.save(business);
        return "Your storage is now tier " + business.storageTier() + ".";
    }

    private String upgradeProcessor(Player player, DrugBusiness business) {
        long cost = business.processorTier() * 325L;
        if (!this.withdrawCrowns(player, cost)) {
            return "You need " + Currency.format(cost) + " to upgrade processing.";
        }
        business.setProcessorTier(business.processorTier() + 1);
        this.save(business);
        return "Your processor is now tier " + business.processorTier() + ".";
    }

    public long currentPrice(UUID ownerId, DrugProduct product) {
        long window = System.currentTimeMillis() / (Math.max(5, this.plugin.getConfig().getInt("drugs.black-market.refresh-minutes", 30)) * 60000L);
        Random random = new Random((ownerId.toString() + ":" + product.key() + ":" + window).hashCode());
        double modifier = 0.85D + (random.nextDouble() * 0.5D);
        return Math.max(10L, Math.round(product.baseSellPrice() * modifier));
    }

    public int currentOrderAmount(UUID ownerId, DrugProduct product) {
        long window = System.currentTimeMillis() / (Math.max(5, this.plugin.getConfig().getInt("drugs.black-market.refresh-minutes", 30)) * 60000L);
        Random random = new Random((product.key() + ":" + ownerId + ":orders:" + window).hashCode());
        return 4 + random.nextInt(7);
    }

    public String formatDirtyCash(long amount) {
        return Currency.format(amount);
    }

    public String formatCrowns(long amount) {
        return Currency.format(amount);
    }

    public void migrateLegacyStock(Player player) {
        DrugBusiness business = this.getBusiness(player.getUniqueId());
        boolean changed = false;
        for (DrugProduct product : DrugProduct.values()) {
            if (business.raw(product) > 0) {
                this.giveItem(player, this.createDrugItem(product, "raw", business.raw(product)));
                business.setRaw(product, 0);
                changed = true;
            }
            if (business.packaged(product) > 0) {
                this.giveItem(player, this.createDrugItem(product, "packaged", business.packaged(product)));
                business.setPackaged(product, 0);
                changed = true;
            }
        }
        if (changed) {
            business.setDirtyCash(0L);
            this.save(business);
        }
    }

    public ItemStack createDrugItem(DrugProduct product, String form, int amount) {
        Material material = "raw".equalsIgnoreCase(form) ? product.rawMaterial() : product.packagedMaterial();
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(product.display() + ("raw".equalsIgnoreCase(form) ? " Leaf" : " Pack"),
                "raw".equalsIgnoreCase(form) ? NamedTextColor.GREEN : NamedTextColor.RED));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("CrownsDrugs item", NamedTextColor.DARK_GRAY));
        if ("packaged".equalsIgnoreCase(form)) {
            lore.add(Component.text("Right-click to use.", NamedTextColor.AQUA));
            lore.add(Component.text("Base sell value: " + Currency.format(product.baseSellPrice()), NamedTextColor.YELLOW));
            lore.add(Component.text("Buyer mood: " + this.prettyKey(product.buyerMood()), NamedTextColor.GRAY));
        } else {
            lore.add(Component.text("Process this into a usable package.", NamedTextColor.GRAY));
        }
        meta.lore(lore);
        PackModelHelper.apply(meta, "lowlight/drugs/" + product.key() + "_" + form.toLowerCase());
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(this.typeKey, PersistentDataType.STRING, product.key());
        container.set(this.formKey, PersistentDataType.STRING, form.toLowerCase());
        item.setItemMeta(meta);
        return item;
    }

    public DrugProduct identify(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String key = item.getItemMeta().getPersistentDataContainer().get(this.typeKey, PersistentDataType.STRING);
        return DrugProduct.fromKey(key);
    }

    public boolean isDrugItem(ItemStack item) {
        return this.identify(item) != null;
    }

    public boolean isPackagedItem(ItemStack item, DrugProduct product) {
        return this.matches(item, product, "packaged");
    }

    public String consume(Player player, ItemStack item) {
        DrugProduct product = this.identify(item);
        if (product == null || !this.isPackagedItem(item, product)) {
            return null;
        }
        player.addPotionEffect(new PotionEffect(product.primaryEffect(), product.primaryDurationTicks(), product.primaryAmplifier(), true, true, true));
        if (product.secondaryEffect() != null) {
            player.addPotionEffect(new PotionEffect(product.secondaryEffect(), product.secondaryDurationTicks(), product.secondaryAmplifier(), true, true, true));
        }
        item.setAmount(item.getAmount() - 1);
        return "You used " + product.display() + " and felt it kick in.";
    }

    public String equipmentSummary(Player player) {
        DrugBusiness business = this.getBusiness(player.getUniqueId());
        return "Grow tier " + business.labTier()
                + ", processor tier " + business.processorTier()
                + ", storage tier " + business.storageTier() + ".";
    }

    public int countItems(Player player, DrugProduct product, String form) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (this.matches(item, product, form)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public int rawStock(Player player) {
        int total = 0;
        for (DrugProduct product : DrugProduct.values()) {
            total += this.countItems(player, product, "raw");
        }
        return total;
    }

    public int packagedStock(Player player) {
        int total = 0;
        for (DrugProduct product : DrugProduct.values()) {
            total += this.countItems(player, product, "packaged");
        }
        return total;
    }

    private boolean matches(ItemStack item, DrugProduct product, String form) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        String key = container.get(this.typeKey, PersistentDataType.STRING);
        String itemForm = container.get(this.formKey, PersistentDataType.STRING);
        return product.key().equalsIgnoreCase(key) && form.equalsIgnoreCase(itemForm);
    }

    private boolean removeDrugItems(Player player, DrugProduct product, String form, int amount) {
        int remaining = amount;
        for (ItemStack item : player.getInventory().getContents()) {
            if (!this.matches(item, product, form)) {
                continue;
            }
            int removed = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - removed);
            remaining -= removed;
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
    }

    private void giveItem(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private boolean withdrawCrowns(Player player, long amount) {
        EconomyProvider economy = CrownsAPI.getEconomy();
        return economy != null && economy.withdraw(player.getUniqueId(), amount);
    }

    private String prettyKey(String raw) {
        String[] parts = raw.toLowerCase().replace('-', ' ').replace('_', ' ').split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
