package com.xkstudios.crowns.market;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import com.xkstudios.crowns.util.ItemSerialization;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class StallManager {
    private static final String ALL_FILTER = "*";
    private final CrownsPlugin plugin;
    private final Map<Integer, MarketStall> stalls = new ConcurrentHashMap<>();
    private final Map<Integer, MarketStallListing> listings = new ConcurrentHashMap<>();
    private final Map<Long, MarketStallOverflowItem> overflow = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingActions = new ConcurrentHashMap<>();

    public StallManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM market_stalls")) {
            while (resultSet.next()) {
                MarketStall stall = new MarketStall(
                        resultSet.getInt("id"),
                        UUID.fromString(resultSet.getString("owner_uuid")),
                        resultSet.getString("owner_name"),
                        resultSet.getString("category"),
                        resultSet.getLong("rented_at"),
                        resultSet.getLong("expires_at"),
                        resultSet.getLong("grace_ends_at"),
                        resultSet.getInt("active") == 1,
                        resultSet.getInt("reminder_sent") == 1
                );
                this.stalls.put(stall.getId(), stall);
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Stalls] Load stalls failed: " + exception.getMessage());
        }
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM market_stall_listings")) {
            while (resultSet.next()) {
                this.listings.put(resultSet.getInt("id"), new MarketStallListing(
                        resultSet.getInt("id"),
                        resultSet.getInt("stall_id"),
                        ItemSerialization.deserialize(resultSet.getString("item_data")),
                        resultSet.getLong("price"),
                        resultSet.getLong("created_at")
                ));
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Stalls] Load listings failed: " + exception.getMessage());
        }
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM market_stall_overflow")) {
            while (resultSet.next()) {
                this.overflow.put(resultSet.getLong("id"), new MarketStallOverflowItem(
                        resultSet.getLong("id"),
                        resultSet.getInt("stall_id"),
                        UUID.fromString(resultSet.getString("owner_uuid")),
                        resultSet.getString("owner_name"),
                        ItemSerialization.deserialize(resultSet.getString("item_data")),
                        resultSet.getLong("price"),
                        resultSet.getLong("stored_at")
                ));
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Stalls] Load overflow failed: " + exception.getMessage());
        }
        this.plugin.getLogger().info("[Stalls] " + this.stalls.size() + " stalls, " + this.listings.size() + " listings, " + this.overflow.size() + " overflow item(s)");
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("stalls.enabled", true);
    }

    public int getDefaultDurationHours() {
        return Math.max(1, this.plugin.getConfig().getInt("stalls.default-duration-hours", 72));
    }

    public int getGracePeriodHours() {
        return Math.max(1, this.plugin.getConfig().getInt("stalls.grace-period-hours", 48));
    }

    public int getMaxStallsPerPlayer() {
        return Math.max(1, this.plugin.getConfig().getInt("stalls.max-per-player", 2));
    }

    public int getMaxListingsPerStall() {
        return Math.max(1, this.plugin.getConfig().getInt("stalls.max-listings-per-stall", 12));
    }

    public long getRentCost() {
        return Math.max(0L, this.plugin.getConfig().getLong("stalls.rent-cost", 1000L));
    }

    public double getSaleTaxRate() {
        return Math.max(0.0, this.plugin.getConfig().getDouble("stalls.sale-tax", 0.03));
    }

    public List<String> getConfiguredCategories() {
        return this.plugin.getConfig().getStringList("stalls.categories");
    }

    public boolean allowCategories() {
        return this.plugin.getConfig().getBoolean("stalls.allow-categories", true) && !this.getConfiguredCategories().isEmpty();
    }

    public List<MarketStall> getStallsByOwner(UUID ownerUuid) {
        return this.stalls.values().stream()
                .filter(stall -> stall.getOwnerUuid().equals(ownerUuid))
                .sorted(Comparator.comparingLong(MarketStall::getExpiresAt))
                .toList();
    }

    public List<MarketStallOverflowItem> getOverflow(UUID ownerUuid) {
        return this.overflow.values().stream()
                .filter(item -> item.getOwnerUuid().equals(ownerUuid))
                .sorted(Comparator.comparingLong(MarketStallOverflowItem::getStoredAt))
                .toList();
    }

    public MarketStall getStall(int stallId) {
        return this.stalls.get(stallId);
    }

    public MarketStallListing getListing(int listingId) {
        return this.listings.get(listingId);
    }

    public String getAwaitingAction(UUID playerId) {
        return this.awaitingActions.get(playerId);
    }

    public void clearAwaiting(UUID playerId) {
        this.awaitingActions.remove(playerId);
    }

    public void setAwaitingCreate(UUID playerId, int stallId) {
        this.awaitingActions.put(playerId, "create:" + stallId);
    }

    public void setAwaitingEdit(UUID playerId, int listingId) {
        this.awaitingActions.put(playerId, "edit:" + listingId);
    }

    public void handleChatInput(Player player, String action, String message) {
        String trimmed = message.trim();
        if (trimmed.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("Cancelled.", NamedTextColor.GRAY));
            return;
        }
        try {
            long price = Currency.parse(trimmed);
            if (action.startsWith("create:")) {
                int stallId = Integer.parseInt(action.substring("create:".length()));
                if (this.addListingFromHand(player, stallId, price)) {
                    player.sendMessage(Component.text("Stall listing created for " + Currency.format(price) + ".", NamedTextColor.GREEN));
                    this.openStall(player, stallId);
                } else {
                    player.sendMessage(Component.text("Could not create the stall listing.", NamedTextColor.RED));
                }
                return;
            }
            if (action.startsWith("edit:")) {
                int listingId = Integer.parseInt(action.substring("edit:".length()));
                if (this.updateListingPrice(player, listingId, price)) {
                    player.sendMessage(Component.text("Stall price updated to " + Currency.format(price) + ".", NamedTextColor.GREEN));
                    MarketStallListing listing = this.listings.get(listingId);
                    if (listing != null) {
                        this.openStall(player, listing.getStallId());
                    }
                } else {
                    player.sendMessage(Component.text("Could not update that listing.", NamedTextColor.RED));
                }
                return;
            }
        } catch (NumberFormatException exception) {
            player.sendMessage(Component.text("Invalid amount. Use: 500, 5s, 1c, 2c50s", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("That stall action is no longer waiting for input.", NamedTextColor.RED));
    }

    public boolean rentStall(Player player) {
        if (!this.isEnabled()) {
            return false;
        }
        long owned = this.getStallsByOwner(player.getUniqueId()).stream().filter(MarketStall::isActive).count();
        if (owned >= this.getMaxStallsPerPlayer()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + this.getDefaultDurationHours() * 3600000L;
        long graceEndsAt = expiresAt + this.getGracePeriodHours() * 3600000L;
        long rentCost = this.getRentCost();
        if (!this.plugin.getEconomy().withdraw(player, rentCost, "stall-rent", "Rented a market stall")) {
            return false;
        }
        String category = this.allowCategories() ? this.getConfiguredCategories().getFirst() : null;
        Integer generatedId = null;
        String sql = """
                INSERT INTO market_stalls (owner_uuid, owner_name, category, rented_at, expires_at, grace_ends_at, active, reminder_sent)
                VALUES (?, ?, ?, ?, ?, ?, 1, 0)
                """;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, player.getName());
            statement.setString(3, category);
            statement.setLong(4, now);
            statement.setLong(5, expiresAt);
            statement.setLong(6, graceEndsAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getInt(1);
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Stalls] Rent failed: " + exception.getMessage());
        }
        if (generatedId == null) {
            this.plugin.getEconomy().deposit(player, rentCost, "stall-rent", "Refund for failed stall rental");
            return false;
        }
        MarketStall stall = new MarketStall(generatedId, player.getUniqueId(), player.getName(), category, now, expiresAt, graceEndsAt, true, false);
        this.stalls.put(stall.getId(), stall);
        this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "stall_rented",
                "Market stall rented",
                "Your stall #" + stall.getId() + " is live for " + this.getDefaultDurationHours() + "h.");
        return true;
    }

    public boolean renewStall(Player player, int stallId) {
        MarketStall stall = this.stalls.get(stallId);
        if (stall == null || !stall.getOwnerUuid().equals(player.getUniqueId())) {
            return false;
        }
        long rentCost = this.getRentCost();
        if (!this.plugin.getEconomy().withdraw(player, rentCost, "stall-rent", "Renewed market stall #" + stallId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long base = Math.max(now, stall.getExpiresAt());
        stall.setRentedAt(now);
        stall.setExpiresAt(base + this.getDefaultDurationHours() * 3600000L);
        stall.setGraceEndsAt(stall.getExpiresAt() + this.getGracePeriodHours() * 3600000L);
        stall.setActive(true);
        stall.setReminderSent(false);
        this.saveStall(stall);
        this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "stall_renewed",
                "Market stall renewed",
                "Your stall #" + stall.getId() + " is active until " + this.describeRemaining(stall.getExpiresAt()) + ".");
        return true;
    }

    public boolean cycleCategory(Player player, int stallId) {
        MarketStall stall = this.stalls.get(stallId);
        if (stall == null || !stall.getOwnerUuid().equals(player.getUniqueId()) || !this.allowCategories()) {
            return false;
        }
        List<String> options = new ArrayList<>();
        options.add(null);
        options.addAll(this.getConfiguredCategories());
        int currentIndex = 0;
        for (int index = 0; index < options.size(); index++) {
            String option = options.get(index);
            if ((option == null && stall.getCategory() == null) || (option != null && option.equalsIgnoreCase(stall.getCategory()))) {
                currentIndex = index;
                break;
            }
        }
        stall.setCategory(options.get((currentIndex + 1) % options.size()));
        this.saveStall(stall);
        return true;
    }

    public boolean addListingFromHand(Player player, int stallId, long price) {
        MarketStall stall = this.stalls.get(stallId);
        if (stall == null || !stall.isActive() || !stall.getOwnerUuid().equals(player.getUniqueId())) {
            return false;
        }
        if (this.getListingsForStall(stallId).size() >= this.getMaxListingsPerStall()) {
            return false;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR || price <= 0L) {
            return false;
        }
        ItemStack listedItem = held.clone();
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        long now = System.currentTimeMillis();
        Integer generatedId = null;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT INTO market_stall_listings (stall_id, item_data, price, created_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, stallId);
            statement.setString(2, ItemSerialization.serialize(listedItem));
            statement.setLong(3, price);
            statement.setLong(4, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getInt(1);
                }
            }
        } catch (Exception exception) {
            player.getInventory().setItemInMainHand(listedItem);
            this.plugin.getLogger().warning("[Stalls] Add listing failed: " + exception.getMessage());
        }
        if (generatedId == null) {
            return false;
        }
        this.listings.put(generatedId, new MarketStallListing(generatedId, stallId, listedItem, price, now));
        return true;
    }

    public boolean updateListingPrice(Player player, int listingId, long price) {
        MarketStallListing listing = this.listings.get(listingId);
        if (listing == null || price <= 0L) {
            return false;
        }
        MarketStall stall = this.stalls.get(listing.getStallId());
        if (stall == null || !stall.getOwnerUuid().equals(player.getUniqueId())) {
            return false;
        }
        listing.setPrice(price);
        this.saveListing(listing);
        return true;
    }

    public boolean removeListing(Player player, int listingId) {
        MarketStallListing listing = this.listings.get(listingId);
        if (listing == null) {
            return false;
        }
        MarketStall stall = this.stalls.get(listing.getStallId());
        if (stall == null || !stall.getOwnerUuid().equals(player.getUniqueId())) {
            return false;
        }
        this.listings.remove(listingId);
        this.deleteListing(listingId);
        this.giveItem(player, listing.getItem().clone());
        return true;
    }

    public boolean buyListing(Player buyer, int listingId) {
        MarketStallListing listing = this.listings.get(listingId);
        if (listing == null) {
            return false;
        }
        MarketStall stall = this.stalls.get(listing.getStallId());
        if (stall == null || !stall.isActive() || stall.isExpired() || stall.getOwnerUuid().equals(buyer.getUniqueId())) {
            return false;
        }
        if (!this.plugin.getEconomy().withdraw(buyer, listing.getPrice(), "stall-purchases", "Bought from stall #" + stall.getId())) {
            return false;
        }
        long tax = (long) (listing.getPrice() * this.getSaleTaxRate());
        long payout = Math.max(0L, listing.getPrice() - tax);
        this.plugin.getEconomy().deposit(stall.getOwnerUuid(), stall.getOwnerName(), payout, "stall-sales", "Sale from stall #" + stall.getId());
        if (tax > 0L) {
            this.plugin.getEconomyLedgerManager().recordSink(stall.getOwnerUuid(), stall.getOwnerName(), "stall-tax", tax, "Tax on stall #" + stall.getId() + " sale");
        }
        this.listings.remove(listingId);
        this.deleteListing(listingId);
        this.giveItem(buyer, listing.getItem().clone());
        Player owner = Bukkit.getPlayer(stall.getOwnerUuid());
        if (owner != null) {
            owner.sendMessage(Component.text("Stall sale! +" + Currency.format(payout), NamedTextColor.GREEN));
        }
        this.plugin.getInboxManager().push(stall.getOwnerUuid(), stall.getOwnerName(), "stall_sale",
                "Market stall sale: " + Currency.format(payout),
                buyer.getName() + " bought " + this.describeItem(listing.getItem()) + " from stall #" + stall.getId() + ".");
        buyer.sendMessage(Component.text("Purchased from stall #" + stall.getId() + " for " + Currency.format(listing.getPrice()) + ".", NamedTextColor.GREEN));
        return true;
    }

    public boolean reclaimOverflow(Player player, long overflowId) {
        MarketStallOverflowItem item = this.overflow.get(overflowId);
        if (item == null || !item.getOwnerUuid().equals(player.getUniqueId())) {
            return false;
        }
        this.overflow.remove(overflowId);
        this.deleteOverflow(overflowId);
        this.giveItem(player, item.getItem().clone());
        return true;
    }

    public int reclaimAllOverflow(Player player) {
        int claimed = 0;
        for (MarketStallOverflowItem item : new ArrayList<>(this.getOverflow(player.getUniqueId()))) {
            if (this.reclaimOverflow(player, item.getId())) {
                claimed++;
            }
        }
        return claimed;
    }

    public boolean adminRenew(int stallId) {
        MarketStall stall = this.stalls.get(stallId);
        if (stall == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        stall.setRentedAt(now);
        stall.setExpiresAt(now + this.getDefaultDurationHours() * 3600000L);
        stall.setGraceEndsAt(stall.getExpiresAt() + this.getGracePeriodHours() * 3600000L);
        stall.setActive(true);
        stall.setReminderSent(false);
        this.saveStall(stall);
        return true;
    }

    public boolean adminClose(int stallId) {
        MarketStall stall = this.stalls.get(stallId);
        if (stall == null || !stall.isActive()) {
            return false;
        }
        this.expireStall(stall, true);
        return true;
    }

    public void checkExpirations() {
        long now = System.currentTimeMillis();
        for (MarketStall stall : new ArrayList<>(this.stalls.values())) {
            if (!stall.isActive()) {
                continue;
            }
            if (stall.getExpiresAt() <= now) {
                this.expireStall(stall, false);
                continue;
            }
            if (!stall.isReminderSent() && stall.getExpiresAt() - now <= 3600000L) {
                stall.setReminderSent(true);
                this.saveStall(stall);
                this.plugin.getInboxManager().push(stall.getOwnerUuid(), stall.getOwnerName(), "stall_reminder",
                        "Market stall ending soon",
                        "Your stall #" + stall.getId() + " expires in less than an hour.");
            }
        }
    }

    private void expireStall(MarketStall stall, boolean forced) {
        stall.setActive(false);
        stall.setReminderSent(true);
        this.saveStall(stall);
        List<MarketStallListing> activeListings = new ArrayList<>(this.getListingsForStall(stall.getId()));
        for (MarketStallListing listing : activeListings) {
            this.moveToOverflow(stall, listing);
        }
        this.plugin.getInboxManager().push(stall.getOwnerUuid(), stall.getOwnerName(), "stall_expired",
                forced ? "Market stall closed by staff" : "Market stall expired",
                "Stall #" + stall.getId() + " closed and " + activeListings.size() + " listing(s) moved to overflow.");
    }

    private void moveToOverflow(MarketStall stall, MarketStallListing listing) {
        long now = System.currentTimeMillis();
        Long generatedId = null;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "INSERT INTO market_stall_overflow (stall_id, owner_uuid, owner_name, item_data, price, stored_at) VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, stall.getId());
            statement.setString(2, stall.getOwnerUuid().toString());
            statement.setString(3, stall.getOwnerName());
            statement.setString(4, ItemSerialization.serialize(listing.getItem()));
            statement.setLong(5, listing.getPrice());
            statement.setLong(6, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getLong(1);
                }
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Stalls] Overflow insert failed: " + exception.getMessage());
        }
        if (generatedId != null) {
            this.overflow.put(generatedId, new MarketStallOverflowItem(generatedId, stall.getId(), stall.getOwnerUuid(), stall.getOwnerName(), listing.getItem().clone(), listing.getPrice(), now));
        }
        this.listings.remove(listing.getId());
        this.deleteListing(listing.getId());
    }

    private List<MarketStallListing> getListingsForStall(int stallId) {
        return this.listings.values().stream()
                .filter(listing -> listing.getStallId() == stallId)
                .sorted(Comparator.comparingLong(MarketStallListing::getCreatedAt))
                .toList();
    }

    private List<MarketStall> getBrowseResults(String categoryFilter, String ownerFilter) {
        return this.stalls.values().stream()
                .filter(MarketStall::isActive)
                .filter(stall -> !stall.isExpired())
                .filter(stall -> ALL_FILTER.equals(categoryFilter) || categoryFilter.equalsIgnoreCase(this.sanitizeCategory(stall.getCategory())))
                .filter(stall -> ALL_FILTER.equals(ownerFilter) || ownerFilter.equalsIgnoreCase(stall.getOwnerName()))
                .sorted(Comparator.comparingLong(MarketStall::getExpiresAt))
                .toList();
    }

    private void saveStall(MarketStall stall) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE market_stalls SET owner_name = ?, category = ?, rented_at = ?, expires_at = ?, grace_ends_at = ?, active = ?, reminder_sent = ? WHERE id = ?")) {
            statement.setString(1, stall.getOwnerName());
            statement.setString(2, stall.getCategory());
            statement.setLong(3, stall.getRentedAt());
            statement.setLong(4, stall.getExpiresAt());
            statement.setLong(5, stall.getGraceEndsAt());
            statement.setInt(6, stall.isActive() ? 1 : 0);
            statement.setInt(7, stall.isReminderSent() ? 1 : 0);
            statement.setInt(8, stall.getId());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Stalls] Save stall failed: " + exception.getMessage());
        }
    }

    private void saveListing(MarketStallListing listing) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE market_stall_listings SET price = ? WHERE id = ?")) {
            statement.setLong(1, listing.getPrice());
            statement.setInt(2, listing.getId());
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Stalls] Save listing failed: " + exception.getMessage());
        }
    }

    private void deleteListing(int listingId) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM market_stall_listings WHERE id = ?")) {
            statement.setInt(1, listingId);
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Stalls] Delete listing failed: " + exception.getMessage());
        }
    }

    private void deleteOverflow(long overflowId) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM market_stall_overflow WHERE id = ?")) {
            statement.setLong(1, overflowId);
            statement.executeUpdate();
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Stalls] Delete overflow failed: " + exception.getMessage());
        }
    }

    private void giveItem(Player player, ItemStack item) {
        HashMap<Integer, ItemStack> overflowItems = player.getInventory().addItem(item);
        for (ItemStack extra : overflowItems.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
    }

    public void openHub(Player player) {
        Inventory inventory = CrownsMenuHolder.create("stalls-hub", 27, Component.text("Market Stalls", NamedTextColor.GOLD));
        inventory.setItem(11, this.button(Material.EMERALD, "Rent Stall", NamedTextColor.GREEN, List.of(
                Component.text("Cost: " + Currency.format(this.getRentCost()), NamedTextColor.YELLOW),
                Component.text("Live for " + this.getDefaultDurationHours() + "h.", NamedTextColor.GRAY)
        ), "ce:stalls:rent"));
        inventory.setItem(13, this.button(Material.CHEST, "My Stalls", NamedTextColor.AQUA, List.of(
                Component.text("Manage your rented storefronts.", NamedTextColor.GRAY)
        ), "ce:stalls:mine"));
        inventory.setItem(15, this.button(Material.SPYGLASS, "Browse Stalls", NamedTextColor.YELLOW, List.of(
                Component.text("See public fixed-price storefronts.", NamedTextColor.GRAY)
        ), "ce:stalls:browse:0:*:*"));
        inventory.setItem(22, this.button(Material.BUNDLE, "Overflow Storage", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Stored items: " + this.getOverflow(player.getUniqueId()).size(), NamedTextColor.GRAY)
        ), "ce:stalls:overflow"));
        inventory.setItem(18, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:market"));
        if (player.hasPermission("crowns.stalls.admin")) {
            inventory.setItem(26, this.button(Material.REDSTONE_BLOCK, "Admin", NamedTextColor.RED, List.of(
                    Component.text("Inspect and manage all stalls.", NamedTextColor.GRAY)
            ), "ce:stalls:admin"));
        }
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openMyStalls(Player player) {
        Inventory inventory = CrownsMenuHolder.create("stalls-mine", 54, Component.text("My Market Stalls", NamedTextColor.GOLD));
        List<MarketStall> mine = this.getStallsByOwner(player.getUniqueId());
        int slot = 10;
        for (MarketStall stall : mine) {
            if (slot >= 44) {
                break;
            }
            inventory.setItem(slot, this.button(Material.CHEST_MINECART, "Stall #" + stall.getId(),
                    stall.isActive() ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
                    List.of(
                            Component.text("Category: " + this.displayCategory(stall.getCategory()), NamedTextColor.GRAY),
                            Component.text("Listings: " + this.getListingsForStall(stall.getId()).size(), NamedTextColor.GRAY),
                            Component.text(stall.isActive() ? "Ends in: " + this.describeRemaining(stall.getExpiresAt()) : "Closed. Overflow protected.", NamedTextColor.GRAY),
                            Component.text("Click to manage.", NamedTextColor.YELLOW)
                    ),
                    "ce:stalls:view:" + stall.getId()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        if (mine.isEmpty()) {
            inventory.setItem(22, this.info(Material.BARRIER, "No Stalls Yet", List.of(
                    Component.text("Rent a stall to open a curated storefront.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(48, this.button(Material.EMERALD, "Rent Another Stall", NamedTextColor.GREEN, List.of(
                Component.text("Cost: " + Currency.format(this.getRentCost()), NamedTextColor.YELLOW)
        ), "ce:stalls:rent"));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:stalls:hub"));
        inventory.setItem(50, this.button(Material.BUNDLE, "Overflow", NamedTextColor.LIGHT_PURPLE, List.of(), "ce:stalls:overflow"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openBrowse(Player player, int page, String categoryFilter, String ownerFilter) {
        List<MarketStall> browse = this.getBrowseResults(categoryFilter, ownerFilter);
        int pageSize = 28;
        int totalPages = Math.max(1, (int) Math.ceil((double) browse.size() / (double) pageSize));
        int currentPage = Math.max(0, Math.min(page, totalPages - 1));
        Inventory inventory = CrownsMenuHolder.create("stalls-browse", 54, Component.text("Browse Stalls", NamedTextColor.GOLD));
        int start = currentPage * pageSize;
        int slot = 10;
        for (int index = start; index < Math.min(start + pageSize, browse.size()); index++) {
            MarketStall stall = browse.get(index);
            inventory.setItem(slot, this.button(Material.CHEST, stall.getOwnerName() + " #" + stall.getId(), NamedTextColor.YELLOW, List.of(
                    Component.text("Category: " + this.displayCategory(stall.getCategory()), NamedTextColor.GRAY),
                    Component.text("Listings: " + this.getListingsForStall(stall.getId()).size(), NamedTextColor.GRAY),
                    Component.text("Ends in: " + this.describeRemaining(stall.getExpiresAt()), NamedTextColor.GRAY),
                    Component.text("Click to browse this stall.", NamedTextColor.GREEN)
            ), "ce:stalls:view:" + stall.getId()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        if (browse.isEmpty()) {
            inventory.setItem(22, this.info(Material.BARRIER, "No Matching Stalls", List.of(
                    Component.text("No active stalls match the current filters.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(45, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:stalls:hub"));
        if (currentPage > 0) {
            inventory.setItem(48, this.button(Material.ARROW, "Previous", NamedTextColor.AQUA, List.of(), "ce:stalls:browse:" + (currentPage - 1) + ":" + categoryFilter + ":" + ownerFilter));
        }
        inventory.setItem(49, this.info(Material.PAPER, "Page", List.of(
                Component.text((currentPage + 1) + " / " + totalPages, NamedTextColor.GRAY),
                Component.text("Owner filter: " + (ALL_FILTER.equals(ownerFilter) ? "Any" : ownerFilter), NamedTextColor.DARK_GRAY)
        )));
        if (currentPage < totalPages - 1) {
            inventory.setItem(50, this.button(Material.ARROW, "Next", NamedTextColor.AQUA, List.of(), "ce:stalls:browse:" + (currentPage + 1) + ":" + categoryFilter + ":" + ownerFilter));
        }
        inventory.setItem(52, this.button(Material.HOPPER, "Category Filter", NamedTextColor.YELLOW, this.categoryLore(categoryFilter), "ce:stalls:filter:" + this.nextCategoryFilter(categoryFilter) + ":" + ownerFilter));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openStall(Player player, int stallId) {
        MarketStall stall = this.stalls.get(stallId);
        if (stall == null) {
            this.openBrowse(player, 0, ALL_FILTER, ALL_FILTER);
            return;
        }
        boolean isOwner = stall.getOwnerUuid().equals(player.getUniqueId());
        boolean isAdmin = player.hasPermission("crowns.stalls.admin");
        Inventory inventory = CrownsMenuHolder.create("stalls-view", 54, Component.text((isOwner ? "Manage " : "Browse ") + "Stall #" + stall.getId(), NamedTextColor.GOLD));
        List<MarketStallListing> stallListings = this.getListingsForStall(stallId);
        int slot = 10;
        for (MarketStallListing listing : stallListings) {
            if (slot >= 44) {
                break;
            }
            ItemStack display = listing.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Price: " + Currency.format(listing.getPrice()), NamedTextColor.GREEN));
            lore.add(Component.text("Category: " + this.displayCategory(stall.getCategory()), NamedTextColor.GRAY));
            if (isOwner || isAdmin) {
                lore.add(Component.text("Left-click via buttons below to edit/remove.", NamedTextColor.YELLOW));
                lore.add(Component.text("ce:stalls:none", NamedTextColor.DARK_GRAY));
            } else {
                lore.add(Component.text("Click the buy button below.", NamedTextColor.YELLOW));
                lore.add(Component.text("ce:stalls:none", NamedTextColor.DARK_GRAY));
            }
            meta.lore(lore);
            display.setItemMeta(meta);
            inventory.setItem(slot, display);
            if (isOwner || isAdmin) {
                inventory.setItem(slot + 9, this.button(Material.GOLD_INGOT, "Edit Price", NamedTextColor.AQUA, List.of(
                        Component.text("Current: " + Currency.format(listing.getPrice()), NamedTextColor.GRAY)
                ), "ce:stalls:edit:" + listing.getId()));
                inventory.setItem(slot + 18, this.button(Material.BARRIER, "Remove", NamedTextColor.RED, List.of(
                        Component.text("Return the item to inventory.", NamedTextColor.GRAY)
                ), "ce:stalls:remove:" + listing.getId()));
            } else {
                inventory.setItem(slot + 9, this.button(Material.EMERALD, "Buy", NamedTextColor.GREEN, List.of(
                        Component.text("Price: " + Currency.format(listing.getPrice()), NamedTextColor.YELLOW)
                ), "ce:stalls:buy:" + listing.getId()));
            }
            slot++;
        }
        inventory.setItem(46, this.info(Material.NAME_TAG, "Owner", List.of(Component.text(stall.getOwnerName(), NamedTextColor.WHITE))));
        inventory.setItem(47, this.info(Material.PAPER, "Category", List.of(Component.text(this.displayCategory(stall.getCategory()), NamedTextColor.WHITE))));
        inventory.setItem(48, this.info(Material.CLOCK, "Expires", List.of(Component.text(this.describeRemaining(stall.getExpiresAt()), NamedTextColor.WHITE))));
        if (isOwner) {
            inventory.setItem(50, this.button(Material.ANVIL, "Add Held Item", NamedTextColor.GREEN, List.of(
                    Component.text("Hold an item, then type its price in chat.", NamedTextColor.GRAY)
            ), "ce:stalls:add:" + stallId));
            inventory.setItem(51, this.button(Material.CLOCK, "Renew", NamedTextColor.YELLOW, List.of(
                    Component.text("Cost: " + Currency.format(this.getRentCost()), NamedTextColor.GRAY)
            ), "ce:stalls:renew:" + stallId));
            inventory.setItem(52, this.button(Material.HOPPER, "Cycle Category", NamedTextColor.AQUA, List.of(
                    Component.text("Current: " + this.displayCategory(stall.getCategory()), NamedTextColor.GRAY)
            ), "ce:stalls:category:" + stallId));
        } else if (isAdmin) {
            inventory.setItem(50, this.button(Material.CLOCK, "Staff Renew", NamedTextColor.YELLOW, List.of(), "ce:stalls:adminrenew:" + stallId));
            inventory.setItem(51, this.button(Material.BARRIER, "Staff Close", NamedTextColor.RED, List.of(), "ce:stalls:adminclose:" + stallId));
        }
        inventory.setItem(53, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), isOwner ? "ce:stalls:mine" : (isAdmin ? "ce:stalls:admin" : "ce:stalls:browse:0:*:*")));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openOverflow(Player player) {
        Inventory inventory = CrownsMenuHolder.create("stalls-overflow", 54, Component.text("Stall Overflow", NamedTextColor.GOLD));
        List<MarketStallOverflowItem> items = this.getOverflow(player.getUniqueId());
        int slot = 10;
        for (MarketStallOverflowItem item : items) {
            if (slot >= 44) {
                break;
            }
            ItemStack display = item.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Stored from stall #" + item.getStallId(), NamedTextColor.GRAY));
            lore.add(Component.text("Last price: " + Currency.format(item.getPrice()), NamedTextColor.GRAY));
            lore.add(Component.text("Click to reclaim.", NamedTextColor.YELLOW));
            lore.add(Component.text("ce:stalls:reclaim:" + item.getId(), NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            display.setItemMeta(meta);
            inventory.setItem(slot, display);
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        if (items.isEmpty()) {
            inventory.setItem(22, this.info(Material.BARRIER, "Nothing Stored", List.of(
                    Component.text("Expired stall items will show up here.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(48, this.button(Material.LIME_DYE, "Reclaim All", NamedTextColor.GREEN, List.of(), "ce:stalls:reclaimall"));
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:stalls:hub"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openAdminMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("stalls-admin", 54, Component.text("Stall Admin", NamedTextColor.GOLD));
        List<MarketStall> allStalls = this.stalls.values().stream()
                .sorted(Comparator.comparingLong(MarketStall::getExpiresAt))
                .toList();
        int slot = 10;
        for (MarketStall stall : allStalls) {
            if (slot >= 44) {
                break;
            }
            inventory.setItem(slot, this.button(Material.CHEST, "Stall #" + stall.getId(), stall.isActive() ? NamedTextColor.GREEN : NamedTextColor.YELLOW, List.of(
                    Component.text("Owner: " + stall.getOwnerName(), NamedTextColor.GRAY),
                    Component.text("Listings: " + this.getListingsForStall(stall.getId()).size(), NamedTextColor.GRAY),
                    Component.text("Overflow: " + this.getOverflow(stall.getOwnerUuid()).stream().filter(item -> item.getStallId() == stall.getId()).count(), NamedTextColor.GRAY)
            ), "ce:stalls:adminview:" + stall.getId()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:admin"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
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
        for (int index = 0; index < inventory.getSize(); index++) {
            if (index < 9 || index >= inventory.getSize() - 9 || index % 9 == 0 || index % 9 == 8) {
                if (inventory.getItem(index) == null) {
                    inventory.setItem(index, filler);
                }
            }
        }
    }

    private String displayCategory(String category) {
        return category == null || category.isBlank() ? "General" : category;
    }

    private String describeItem(ItemStack item) {
        return item.getAmount() + "x " + item.getType().name();
    }

    private String describeRemaining(long timestamp) {
        long millis = Math.max(0L, timestamp - System.currentTimeMillis());
        long hours = millis / 3600000L;
        long minutes = (millis % 3600000L) / 60000L;
        if (hours <= 0L) {
            return minutes + "m";
        }
        return hours + "h " + minutes + "m";
    }

    private List<Component> categoryLore(String categoryFilter) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Current: " + (ALL_FILTER.equals(categoryFilter) ? "All" : categoryFilter), NamedTextColor.GRAY));
        lore.add(Component.text("Click to cycle category filters.", NamedTextColor.YELLOW));
        return lore;
    }

    private String nextCategoryFilter(String current) {
        List<String> options = new ArrayList<>();
        options.add(ALL_FILTER);
        for (String category : this.getConfiguredCategories()) {
            options.add(this.sanitizeCategory(category));
        }
        int index = Math.max(0, options.indexOf(current));
        return options.get((index + 1) % options.size());
    }

    private String sanitizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return ALL_FILTER;
        }
        return category.toLowerCase().replace(' ', '_');
    }
}
