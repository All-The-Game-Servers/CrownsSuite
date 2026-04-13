package com.xkstudios.crowns.economy;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class DemandManager {
    private final CrownsPlugin plugin;
    private final Map<Integer, DemandOrder> orders = new HashMap<>();
    private final Map<Integer, TraderOffer> offers = new HashMap<>();
    private final Random random = new Random();

    public DemandManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        this.ensureTables();
        this.load();
        this.refreshMarket();
    }

    public void refreshMarket() {
        if (!this.isDemandEnabled() && !this.isTraderEnabled()) {
            return;
        }
        this.deleteExpired();
        this.load();
        this.ensureDemandOrders();
        this.ensureTraderOffers();
    }

    public List<DemandOrder> getOrders() {
        return this.orders.values().stream()
                .filter(order -> !order.isExpired())
                .sorted(Comparator.comparingLong(DemandOrder::getRefreshAt).thenComparing(DemandOrder::getPayout).reversed())
                .toList();
    }

    public List<TraderOffer> getOffers() {
        return this.offers.values().stream()
                .filter(offer -> !offer.isExpired())
                .sorted(Comparator.comparingLong(TraderOffer::getPrice))
                .toList();
    }

    public boolean fulfillOrder(Player player, int orderId) {
        DemandOrder order = this.orders.get(orderId);
        if (order == null || order.isExpired()) {
            return false;
        }
        if (!player.getInventory().containsAtLeast(new ItemStack(order.getMaterial()), order.getAmount())) {
            return false;
        }
        this.removeFromInventory(player, order.getMaterial(), order.getAmount());
        this.plugin.getEconomy().deposit(player, order.getPayout(), "server-demand", "Fulfilled demand order #" + order.getId());
        order.setRemainingClaims(order.getRemainingClaims() - 1);
        this.saveOrder(order);
        this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "demand_fulfilled",
                "Demand order filled: " + Currency.format(order.getPayout()),
                "The Server bought " + order.getAmount() + "x " + order.getDisplayName() + ".");
        if (order.getRemainingClaims() <= 0) {
            this.deleteOrder(order.getId());
            this.orders.remove(order.getId());
            this.ensureDemandOrders();
        }
        return true;
    }

    public boolean buyOffer(Player player, int offerId) {
        TraderOffer offer = this.offers.get(offerId);
        if (offer == null || offer.isExpired()) {
            return false;
        }
        if (!this.plugin.getEconomy().withdraw(player, offer.getPrice(), "server-trader", "Bought " + offer.getDisplayName())) {
            return false;
        }
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(offer.getItem().clone());
        for (ItemStack extra : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
        this.plugin.getEconomyLedgerManager().recordServerSink("server-trader", offer.getPrice(), "Sold " + offer.getDisplayName() + " to " + player.getName());
        this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "trader_purchase",
                "Server Trader purchase",
                "You bought " + offer.getDisplayName() + " for " + Currency.format(offer.getPrice()) + ".");
        return true;
    }

    public void openDemandMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("ce-demand", 54, Component.text("Server Demand Board", NamedTextColor.GOLD));
        List<DemandOrder> activeOrders = this.getOrders();
        int slot = 10;
        for (DemandOrder order : activeOrders) {
            if (slot >= 44) {
                break;
            }
            inventory.setItem(slot, this.button(order.getMaterial(), order.getDisplayName(), NamedTextColor.GREEN, List.of(
                    Component.text("Turn in: " + order.getAmount(), NamedTextColor.GRAY),
                    Component.text("Payout: " + Currency.format(order.getPayout()), NamedTextColor.YELLOW),
                    Component.text("Claims left: " + order.getRemainingClaims(), NamedTextColor.GRAY),
                    Component.text("Click to sell from inventory.", NamedTextColor.AQUA)
            ), "ce:demand:sell:" + order.getId()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        if (activeOrders.isEmpty()) {
            inventory.setItem(22, this.info(Material.BARRIER, "No Live Demand", List.of(
                    Component.text("Fresh buy orders will appear after the next refresh.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(48, this.info(Material.CLOCK, "Next Refresh", List.of(
                Component.text(this.describeNextRefresh(this.getOrders().stream().mapToLong(DemandOrder::getRefreshAt).min().orElse(0L)), NamedTextColor.GRAY)
        )));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:open"));
        inventory.setItem(50, this.button(Material.EMERALD, "Server Trader", NamedTextColor.YELLOW, List.of(
                Component.text("Spend money on curated stock.", NamedTextColor.GRAY)
        ), "ce:menu:trader"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openDemandOrdersMenu(Player player) {
        this.openDemandMenu(player);
    }

    public void openTraderMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("ce-trader", 54, Component.text("The Server Trader", NamedTextColor.GOLD));
        List<TraderOffer> activeOffers = this.getOffers();
        int slot = 10;
        for (TraderOffer offer : activeOffers) {
            if (slot >= 44) {
                break;
            }
            ItemStack display = offer.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Price: " + Currency.format(offer.getPrice()), NamedTextColor.YELLOW));
            lore.add(Component.text("Theme: " + this.prettyKey(offer.getTheme()), NamedTextColor.GRAY));
            lore.add(Component.text("Rarity: " + this.prettyKey(offer.getRarity()), this.rarityColor(offer.getRarity())));
            lore.add(Component.text(offer.isCosmetic() ? "Prestige stock." : "Utility stock.", NamedTextColor.GRAY));
            lore.add(Component.text("Click to purchase.", NamedTextColor.AQUA));
            lore.add(Component.text("ce:trader:buy:" + offer.getId(), NamedTextColor.DARK_GRAY));
            meta.displayName(Component.text(offer.getDisplayName(), this.rarityColor(offer.getRarity())));
            meta.lore(lore);
            display.setItemMeta(meta);
            inventory.setItem(slot, display);
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        if (activeOffers.isEmpty()) {
            inventory.setItem(22, this.info(Material.BARRIER, "Trader Restocking", List.of(
                    Component.text("No offers are active right now.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(48, this.info(Material.CLOCK, "Next Refresh", List.of(
                Component.text(this.describeNextRefresh(this.getOffers().stream().mapToLong(TraderOffer::getRefreshAt).min().orElse(0L)), NamedTextColor.GRAY)
        )));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:open"));
        inventory.setItem(50, this.button(Material.HOPPER, "Demand Board", NamedTextColor.GREEN, List.of(
                Component.text("Sell gathered goods to The Server.", NamedTextColor.GRAY)
        ), "ce:menu:demand"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public String getSuiteSummary() {
        int orderCount = this.getOrders().size();
        int offerCount = this.getOffers().size();
        return orderCount + " demand orders, " + offerCount + " trader offers live.";
    }

    private boolean isDemandEnabled() {
        return this.plugin.getConfig().getBoolean("demand.enabled", true);
    }

    private boolean isTraderEnabled() {
        return this.plugin.getConfig().getBoolean("server-trader.enabled", true);
    }

    private void ensureTables() {
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economy_demand_orders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        material TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        payout INTEGER NOT NULL,
                        remaining_claims INTEGER NOT NULL,
                        refresh_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economy_trader_offers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        material TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        price INTEGER NOT NULL,
                        cosmetic INTEGER NOT NULL DEFAULT 0,
                        theme TEXT NOT NULL DEFAULT 'utility',
                        rarity TEXT NOT NULL DEFAULT 'standard',
                        refresh_at INTEGER NOT NULL
                    )
                    """);
            try {
                statement.executeUpdate("ALTER TABLE economy_trader_offers ADD COLUMN theme TEXT NOT NULL DEFAULT 'utility'");
            } catch (SQLException ignored) {
            }
            try {
                statement.executeUpdate("ALTER TABLE economy_trader_offers ADD COLUMN rarity TEXT NOT NULL DEFAULT 'standard'");
            } catch (SQLException ignored) {
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Demand] Table setup failed: " + exception.getMessage());
        }
    }

    private void load() {
        this.orders.clear();
        this.offers.clear();
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM economy_demand_orders")) {
            while (resultSet.next()) {
                Material material = Material.matchMaterial(resultSet.getString("material"));
                if (material == null) {
                    continue;
                }
                DemandOrder order = new DemandOrder(
                        resultSet.getInt("id"),
                        material,
                        resultSet.getString("display_name"),
                        resultSet.getInt("amount"),
                        resultSet.getLong("payout"),
                        resultSet.getInt("remaining_claims"),
                        resultSet.getLong("refresh_at")
                );
                if (!order.isExpired()) {
                    this.orders.put(order.getId(), order);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Demand] Load orders failed: " + exception.getMessage());
        }
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM economy_trader_offers")) {
            while (resultSet.next()) {
                Material material = Material.matchMaterial(resultSet.getString("material"));
                if (material == null) {
                    continue;
                }
                ItemStack item = new ItemStack(material, Math.max(1, resultSet.getInt("amount")));
                TraderOffer offer = new TraderOffer(
                        resultSet.getInt("id"),
                        item,
                        resultSet.getString("display_name"),
                        resultSet.getLong("price"),
                        resultSet.getInt("cosmetic") == 1,
                        resultSet.getString("theme"),
                        resultSet.getString("rarity"),
                        resultSet.getLong("refresh_at")
                );
                if (!offer.isExpired()) {
                    this.offers.put(offer.getId(), offer);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Demand] Load offers failed: " + exception.getMessage());
        }
    }

    private void deleteExpired() {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM economy_demand_orders WHERE refresh_at <= ? OR remaining_claims <= 0")) {
            statement.setLong(1, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Demand] Delete expired orders failed: " + exception.getMessage());
        }
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM economy_trader_offers WHERE refresh_at <= ?")) {
            statement.setLong(1, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Demand] Delete expired offers failed: " + exception.getMessage());
        }
    }

    private void ensureDemandOrders() {
        if (!this.isDemandEnabled()) {
            return;
        }
        int slots = Math.max(1, this.plugin.getConfig().getInt("demand.slots", 5));
        List<OrderTemplate> templates = this.loadDemandTemplates();
        while (this.orders.size() < slots && !templates.isEmpty()) {
            OrderTemplate template = templates.get(this.random.nextInt(templates.size()));
            int amount = Math.max(1, template.amount() + this.random.nextInt(Math.max(1, template.amount() / 3 + 1)) - Math.max(1, template.amount() / 6));
            long payout = Math.max(1L, template.payout() + this.random.nextInt(81) - 40L);
            int claims = Math.max(1, template.claims());
            long refreshAt = System.currentTimeMillis() + Math.max(1, this.plugin.getConfig().getInt("demand.refresh-hours", 12)) * 3600000L;
            int id = this.insertDemandOrder(template.material(), template.displayName(), amount, payout, claims, refreshAt);
            if (id != -1) {
                this.orders.put(id, new DemandOrder(id, template.material(), template.displayName(), amount, payout, claims, refreshAt));
            } else {
                break;
            }
        }
    }

    private void ensureTraderOffers() {
        if (!this.isTraderEnabled()) {
            return;
        }
        int slots = Math.max(1, this.plugin.getConfig().getInt("server-trader.slots", 5));
        List<OfferTemplate> templates = this.loadOfferTemplates();
        Set<Material> usedMaterials = new HashSet<>();
        for (TraderOffer offer : this.offers.values()) {
            if (!offer.isExpired()) {
                usedMaterials.add(offer.getItem().getType());
            }
        }
        while (this.offers.size() < slots && !templates.isEmpty()) {
            List<OfferTemplate> available = templates.stream()
                    .filter(template -> templates.size() <= slots || !usedMaterials.contains(template.material()))
                    .toList();
            List<OfferTemplate> pool = available.isEmpty() ? templates : available;
            OfferTemplate template = pool.get(this.random.nextInt(pool.size()));
            long refreshAt = System.currentTimeMillis() + Math.max(1, this.plugin.getConfig().getInt("server-trader.refresh-hours", 8)) * 3600000L;
            int id = this.insertTraderOffer(template.material(), template.amount(), template.displayName(), template.price(), template.cosmetic(), template.theme(), template.rarity(), refreshAt);
            if (id != -1) {
                this.offers.put(id, new TraderOffer(id, new ItemStack(template.material(), template.amount()), template.displayName(), template.price(), template.cosmetic(), template.theme(), template.rarity(), refreshAt));
                usedMaterials.add(template.material());
            } else {
                break;
            }
        }
    }

    private int insertDemandOrder(Material material, String displayName, int amount, long payout, int remainingClaims, long refreshAt) {
        String sql = "INSERT INTO economy_demand_orders (material, display_name, amount, payout, remaining_claims, refresh_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, material.name());
            statement.setString(2, displayName);
            statement.setInt(3, amount);
            statement.setLong(4, payout);
            statement.setInt(5, remainingClaims);
            statement.setLong(6, refreshAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Demand] Insert order failed: " + exception.getMessage());
        }
        return -1;
    }

    private int insertTraderOffer(Material material, int amount, String displayName, long price, boolean cosmetic, String theme, String rarity, long refreshAt) {
        String sql = "INSERT INTO economy_trader_offers (material, display_name, amount, price, cosmetic, theme, rarity, refresh_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, material.name());
            statement.setString(2, displayName);
            statement.setInt(3, amount);
            statement.setLong(4, price);
            statement.setInt(5, cosmetic ? 1 : 0);
            statement.setString(6, theme);
            statement.setString(7, rarity);
            statement.setLong(8, refreshAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Demand] Insert offer failed: " + exception.getMessage());
        }
        return -1;
    }

    private void saveOrder(DemandOrder order) {
        String sql = "UPDATE economy_demand_orders SET remaining_claims = ? WHERE id = ?";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setInt(1, order.getRemainingClaims());
            statement.setInt(2, order.getId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Demand] Save order failed: " + exception.getMessage());
        }
    }

    private void deleteOrder(int orderId) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement("DELETE FROM economy_demand_orders WHERE id = ?")) {
            statement.setInt(1, orderId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Demand] Delete order failed: " + exception.getMessage());
        }
    }

    private List<OrderTemplate> loadDemandTemplates() {
        List<OrderTemplate> templates = new ArrayList<>();
        List<?> rawTemplates = this.plugin.getConfig().getList("demand.templates");
        if (rawTemplates != null) {
            for (Object rawTemplate : rawTemplates) {
                if (!(rawTemplate instanceof Map<?, ?> map)) {
                    continue;
                }
                Object materialValue = map.containsKey("material") ? map.get("material") : "IRON_INGOT";
                Material material = Material.matchMaterial(String.valueOf(materialValue));
                if (material == null) {
                    continue;
                }
                Object displayValue = map.containsKey("display-name") ? map.get("display-name") : this.niceName(material);
                Object amountValue = map.containsKey("amount") ? map.get("amount") : 24;
                Object payoutValue = map.containsKey("payout") ? map.get("payout") : 250L;
                Object claimsValue = map.containsKey("claims") ? map.get("claims") : 3;
                String displayName = String.valueOf(displayValue);
                int amount = ((Number) amountValue).intValue();
                long payout = ((Number) payoutValue).longValue();
                int claims = ((Number) claimsValue).intValue();
                templates.add(new OrderTemplate(material, displayName, Math.max(1, amount), Math.max(1L, payout), Math.max(1, claims)));
            }
        }
        if (!templates.isEmpty()) {
            return templates;
        }
        templates.add(new OrderTemplate(Material.IRON_INGOT, "Blacksmith Iron Request", 24, 320L, 3));
        templates.add(new OrderTemplate(Material.GOLD_INGOT, "Guild Gold Reserve", 16, 380L, 2));
        templates.add(new OrderTemplate(Material.COPPER_INGOT, "Builder Copper Order", 32, 280L, 3));
        templates.add(new OrderTemplate(Material.BREAD, "Town Bread Delivery", 24, 190L, 4));
        templates.add(new OrderTemplate(Material.COD, "Dockside Fish Contract", 20, 240L, 3));
        templates.add(new OrderTemplate(Material.OAK_LOG, "Lumber Yard Pickup", 32, 210L, 3));
        return templates;
    }

    private List<OfferTemplate> loadOfferTemplates() {
        List<OfferTemplate> templates = new ArrayList<>();
        List<?> rawTemplates = this.plugin.getConfig().getList("server-trader.offers");
        if (rawTemplates != null) {
            for (Object rawTemplate : rawTemplates) {
                if (!(rawTemplate instanceof Map<?, ?> map)) {
                    continue;
                }
                Object materialValue = map.containsKey("material") ? map.get("material") : "GOLDEN_CARROT";
                Material material = Material.matchMaterial(String.valueOf(materialValue));
                if (material == null) {
                    continue;
                }
                Object displayValue = map.containsKey("display-name") ? map.get("display-name") : this.niceName(material);
                Object amountValue = map.containsKey("amount") ? map.get("amount") : 1;
                Object priceValue = map.containsKey("price") ? map.get("price") : 250L;
                Object cosmeticValue = map.containsKey("cosmetic") ? map.get("cosmetic") : false;
                Object themeValue = map.containsKey("theme") ? map.get("theme") : "utility";
                Object rarityValue = map.containsKey("rarity") ? map.get("rarity") : "standard";
                String displayName = String.valueOf(displayValue);
                int amount = ((Number) amountValue).intValue();
                long price = ((Number) priceValue).longValue();
                boolean cosmetic = Boolean.parseBoolean(String.valueOf(cosmeticValue));
                String theme = String.valueOf(themeValue);
                String rarity = String.valueOf(rarityValue);
                templates.add(new OfferTemplate(material, Math.max(1, amount), displayName, Math.max(1L, price), cosmetic, theme, rarity));
            }
        }
        if (!templates.isEmpty()) {
            return templates;
        }
        templates.add(new OfferTemplate(Material.GOLDEN_CARROT, 16, "Builder's Snack Bundle", 240L, false, "builder", "common"));
        templates.add(new OfferTemplate(Material.SCAFFOLDING, 24, "Scaffold Crate", 360L, false, "builder", "standard"));
        templates.add(new OfferTemplate(Material.ENDER_PEARL, 4, "Traveler Pearl Pouch", 320L, false, "traveler", "standard"));
        templates.add(new OfferTemplate(Material.FIREWORK_ROCKET, 18, "Skytrail Rocket Pack", 340L, false, "traveler", "standard"));
        templates.add(new OfferTemplate(Material.EXPERIENCE_BOTTLE, 8, "Scholar's Vials", 280L, false, "utility", "common"));
        templates.add(new OfferTemplate(Material.SHULKER_BOX, 1, "Portable Storage Crate", 780L, false, "utility", "rare"));
        templates.add(new OfferTemplate(Material.GLOW_ITEM_FRAME, 4, "Showcase Frame Set", 300L, true, "prestige", "rare"));
        templates.add(new OfferTemplate(Material.CYAN_BANNER, 1, "Crowns Showcase Banner", 450L, true, "prestige", "rare"));
        templates.add(new OfferTemplate(Material.ARMOR_STAND, 2, "Display Stand Pair", 360L, true, "prestige", "standard"));
        return templates;
    }

    private void removeFromInventory(Player player, Material material, int amount) {
        int remaining = amount;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != material) {
                continue;
            }
            int removed = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - removed);
            remaining -= removed;
            if (remaining <= 0) {
                return;
            }
        }
    }

    private ItemStack button(Material material, String name, NamedTextColor color, List<Component> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        List<Component> fullLore = new ArrayList<>(lore);
        fullLore.add(Component.text(action, NamedTextColor.DARK_GRAY));
        meta.lore(fullLore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack info(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.WHITE));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void fillBorder(Inventory inventory) {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(" "));
        filler.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null && (slot < 9 || slot >= inventory.getSize() - 9 || slot % 9 == 0 || slot % 9 == 8)) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private String describeNextRefresh(long timestamp) {
        if (timestamp <= 0L) {
            return "Refreshing soon";
        }
        long remaining = Math.max(0L, timestamp - System.currentTimeMillis());
        long hours = remaining / 3600000L;
        long minutes = (remaining % 3600000L) / 60000L;
        if (hours <= 0L) {
            return minutes + "m";
        }
        return hours + "h " + minutes + "m";
    }

    private String niceName(Material material) {
        String[] parts = material.name().toLowerCase().split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private record OrderTemplate(Material material, String displayName, int amount, long payout, int claims) {
    }

    private NamedTextColor rarityColor(String rarity) {
        return switch (rarity == null ? "" : rarity.toLowerCase(java.util.Locale.ROOT)) {
            case "rare" -> NamedTextColor.LIGHT_PURPLE;
            case "epic" -> NamedTextColor.DARK_PURPLE;
            case "common" -> NamedTextColor.WHITE;
            default -> NamedTextColor.GOLD;
        };
    }

    private String prettyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "General";
        }
        String[] parts = raw.toLowerCase(java.util.Locale.ROOT).replace('_', ' ').replace('-', ' ').split(" ");
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

    private record OfferTemplate(Material material, int amount, String displayName, long price, boolean cosmetic, String theme, String rarity) {
    }
}
