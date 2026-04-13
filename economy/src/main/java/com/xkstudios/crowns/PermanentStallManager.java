package com.xkstudios.crowns.market;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import com.xkstudios.crowns.util.ItemSerialization;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

public class PermanentStallManager {
    private static final String ALL_FILTER = "*";
    private final CrownsPlugin plugin;
    private final Map<UUID, StallProfile> profiles = new ConcurrentHashMap<>();
    private final Map<Integer, StallListing> listings = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingActions = new ConcurrentHashMap<>();

    public PermanentStallManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        this.ensureTables();
        this.loadProfiles();
        this.loadListings();
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("stalls.enabled", true);
    }

    public long getPurchasePrice() {
        return Math.max(0L, this.plugin.getConfig().getLong("stalls.purchase-price", 2500L));
    }

    public int getBaseListingSlots() {
        return Math.max(1, this.plugin.getConfig().getInt("stalls.base-listing-slots", 6));
    }

    public int getSlotUpgradeStep() {
        return Math.max(1, this.plugin.getConfig().getInt("stalls.slot-upgrade-step", 3));
    }

    public int getMaxListingSlots() {
        return Math.max(this.getBaseListingSlots(), this.plugin.getConfig().getInt("stalls.max-listing-slots", 18));
    }

    public long getSlotUpgradeCost(StallProfile profile) {
        int tier = Math.max(0, (profile.getListingSlots() - this.getBaseListingSlots()) / this.getSlotUpgradeStep());
        return Math.max(0L, this.plugin.getConfig().getLong("stalls.slot-upgrade-cost", 1000L) + tier * this.plugin.getConfig().getLong("stalls.slot-upgrade-cost-step", 750L));
    }

    public long getSpotlightUpgradeCost(StallProfile profile) {
        return Math.max(0L, this.plugin.getConfig().getLong("stalls.spotlight-upgrade-cost", 1500L) * Math.max(1, profile.getSpotlightLevel() + 1));
    }

    public int getMaxSpotlightLevel() {
        return Math.max(0, this.plugin.getConfig().getInt("stalls.max-spotlight-level", 3));
    }

    public double getSaleTaxRate() {
        return Math.max(0.0, this.plugin.getConfig().getDouble("stalls.sale-tax", 0.03));
    }

    public boolean allowCategories() {
        return this.plugin.getConfig().getBoolean("stalls.allow-categories", true);
    }

    public List<String> getConfiguredCategories() {
        List<String> categories = this.plugin.getConfig().getStringList("stalls.categories");
        return categories.isEmpty() ? List.of("General", "Blocks", "Gear", "Materials") : categories;
    }

    public StallProfile getProfile(UUID ownerUuid) {
        return this.profiles.get(ownerUuid);
    }

    public String getAwaitingAction(UUID playerUuid) {
        return this.awaitingActions.get(playerUuid);
    }

    public void clearAwaiting(UUID playerUuid) {
        this.awaitingActions.remove(playerUuid);
    }

    public void setAwaitingCreate(UUID playerUuid) {
        this.awaitingActions.put(playerUuid, "create");
    }

    public void setAwaitingEdit(UUID playerUuid, int listingId) {
        this.awaitingActions.put(playerUuid, "edit:" + listingId);
    }

    public void handleChatInput(Player player, String action, String message) {
        String trimmed = message.trim();
        if (trimmed.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("Cancelled.", NamedTextColor.GRAY));
            return;
        }
        try {
            long price = Currency.parse(trimmed);
            if ("create".equals(action)) {
                if (this.addListingFromHand(player, price)) {
                    player.sendMessage(Component.text("Stall listing created for " + Currency.format(price) + ".", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Could not create that listing.", NamedTextColor.RED));
                }
                this.openMyStall(player);
                return;
            }
            if (action.startsWith("edit:")) {
                int listingId = Integer.parseInt(action.substring("edit:".length()));
                if (this.updateListingPrice(player, listingId, price)) {
                    player.sendMessage(Component.text("Listing price updated to " + Currency.format(price) + ".", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Could not update that listing.", NamedTextColor.RED));
                }
                this.openMyStall(player);
                return;
            }
        } catch (NumberFormatException exception) {
            player.sendMessage(Component.text("Invalid amount. Use values like 500, 5s, or 1c.", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("That stall action is no longer active.", NamedTextColor.RED));
    }

    public boolean purchaseStall(Player player) {
        if (!this.isEnabled() || this.profiles.containsKey(player.getUniqueId())) {
            return false;
        }
        long price = this.getPurchasePrice();
        if (!this.plugin.getEconomy().withdraw(player, price, "stall-unlocks", "Bought a permanent market stall")) {
            return false;
        }
        StallProfile profile = new StallProfile(
                player.getUniqueId(),
                player.getName(),
                player.getName() + "'s Stall",
                this.allowCategories() ? this.getConfiguredCategories().get(0) : "General",
                System.currentTimeMillis(),
                this.getBaseListingSlots(),
                0,
                0
        );
        this.profiles.put(player.getUniqueId(), profile);
        this.saveProfile(profile);
        this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "stall_bought",
                "Permanent stall unlocked",
                "You now own a permanent market stall.");
        return true;
    }

    public boolean upgradeListingSlots(Player player) {
        StallProfile profile = this.getProfile(player.getUniqueId());
        if (profile == null || profile.getListingSlots() >= this.getMaxListingSlots()) {
            return false;
        }
        long cost = this.getSlotUpgradeCost(profile);
        if (!this.plugin.getEconomy().withdraw(player, cost, "stall-upgrades", "Expanded stall listing slots")) {
            return false;
        }
        profile.setListingSlots(Math.min(this.getMaxListingSlots(), profile.getListingSlots() + this.getSlotUpgradeStep()));
        this.saveProfile(profile);
        return true;
    }

    public boolean upgradeSpotlight(Player player) {
        StallProfile profile = this.getProfile(player.getUniqueId());
        if (profile == null || profile.getSpotlightLevel() >= this.getMaxSpotlightLevel()) {
            return false;
        }
        long cost = this.getSpotlightUpgradeCost(profile);
        if (!this.plugin.getEconomy().withdraw(player, cost, "stall-upgrades", "Upgraded stall spotlight")) {
            return false;
        }
        profile.setSpotlightLevel(profile.getSpotlightLevel() + 1);
        profile.setPrestigeLevel(profile.getPrestigeLevel() + 1);
        this.saveProfile(profile);
        return true;
    }

    public boolean cycleCategory(Player player) {
        StallProfile profile = this.getProfile(player.getUniqueId());
        if (profile == null || !this.allowCategories()) {
            return false;
        }
        List<String> categories = this.getConfiguredCategories();
        int currentIndex = Math.max(0, categories.indexOf(profile.getCategory()));
        profile.setCategory(categories.get((currentIndex + 1) % categories.size()));
        this.saveProfile(profile);
        return true;
    }

    public boolean addListingFromHand(Player player, long price) {
        StallProfile profile = this.getProfile(player.getUniqueId());
        if (profile == null || price <= 0L) {
            return false;
        }
        if (this.getListingsForOwner(player.getUniqueId()).size() >= profile.getListingSlots()) {
            return false;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            return false;
        }
        ItemStack listedItem = held.clone();
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        Integer generatedId = null;
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO economy_stall_listings (owner_uuid, item_data, price, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, player.getUniqueId().toString());
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
        this.listings.put(generatedId, new StallListing(generatedId, player.getUniqueId(), listedItem, price, now));
        return true;
    }

    public boolean updateListingPrice(Player player, int listingId, long price) {
        StallListing listing = this.listings.get(listingId);
        if (listing == null || price <= 0L || !listing.getOwnerUuid().equals(player.getUniqueId())) {
            return false;
        }
        listing.setPrice(price);
        this.saveListing(listing);
        return true;
    }

    public boolean removeListing(Player player, int listingId) {
        StallListing listing = this.listings.get(listingId);
        if (listing == null || !listing.getOwnerUuid().equals(player.getUniqueId())) {
            return false;
        }
        this.listings.remove(listingId);
        this.deleteListing(listingId);
        this.giveItem(player, listing.getItem().clone());
        return true;
    }

    public boolean buyListing(Player buyer, int listingId) {
        StallListing listing = this.listings.get(listingId);
        if (listing == null || listing.getOwnerUuid().equals(buyer.getUniqueId())) {
            return false;
        }
        StallProfile profile = this.profiles.get(listing.getOwnerUuid());
        if (profile == null) {
            return false;
        }
        if (!this.plugin.getEconomy().withdraw(buyer, listing.getPrice(), "stall-purchases", "Bought from " + profile.getStallName())) {
            return false;
        }
        long tax = (long) (listing.getPrice() * this.getSaleTaxRate());
        long payout = Math.max(0L, listing.getPrice() - tax);
        this.plugin.getEconomy().deposit(profile.getOwnerUuid(), profile.getOwnerName(), payout, "stall-sales", "Sale from " + profile.getStallName());
        if (tax > 0L) {
            this.plugin.getEconomyLedgerManager().recordSink(profile.getOwnerUuid(), profile.getOwnerName(), "stall-tax", tax, "Tax on " + profile.getStallName());
        }
        this.listings.remove(listingId);
        this.deleteListing(listingId);
        this.giveItem(buyer, listing.getItem().clone());
        Player owner = Bukkit.getPlayer(profile.getOwnerUuid());
        if (owner != null) {
            owner.sendMessage(Component.text("Stall sale! +" + Currency.format(payout), NamedTextColor.GREEN));
        }
        this.plugin.getInboxManager().push(profile.getOwnerUuid(), profile.getOwnerName(), "stall_sale",
                "Stall sale: " + Currency.format(payout),
                buyer.getName() + " bought " + this.describeItem(listing.getItem()) + " from " + profile.getStallName() + ".");
        return true;
    }

    public void openHub(Player player) {
        StallProfile profile = this.getProfile(player.getUniqueId());
        Inventory inventory = CrownsMenuHolder.create("stalls-hub", 27, Component.text("Market Stalls", NamedTextColor.GOLD));
        if (profile == null) {
            inventory.setItem(11, this.button(Material.EMERALD, "Buy Your Stall", NamedTextColor.GREEN, List.of(
                    Component.text("One-time down payment: " + Currency.format(this.getPurchasePrice()), NamedTextColor.YELLOW),
                    Component.text("Unlock a permanent storefront.", NamedTextColor.GRAY)
            ), "ce:stalls:buyunlock"));
            inventory.setItem(13, this.button(Material.SPYGLASS, "Browse Stalls", NamedTextColor.YELLOW, List.of(
                    Component.text("Shop fixed-price player listings.", NamedTextColor.GRAY)
            ), "ce:stalls:browse:0:*"));
            inventory.setItem(15, this.info(Material.ANVIL, "Stall Upgrades", List.of(
                    Component.text("Buy once, then expand slots and spotlight later.", NamedTextColor.GRAY)
            )));
        } else {
            inventory.setItem(11, this.button(Material.CHEST_MINECART, "My Stall", NamedTextColor.GREEN, List.of(
                    Component.text(profile.getStallName(), NamedTextColor.WHITE),
                    Component.text("Listings: " + this.getListingsForOwner(player.getUniqueId()).size() + "/" + profile.getListingSlots(), NamedTextColor.GRAY)
            ), "ce:stalls:mine"));
            inventory.setItem(13, this.button(Material.SPYGLASS, "Browse Stalls", NamedTextColor.YELLOW, List.of(
                    Component.text("See what other players are selling.", NamedTextColor.GRAY)
            ), "ce:stalls:browse:0:*"));
            inventory.setItem(15, this.button(Material.ANVIL, "Upgrades", NamedTextColor.AQUA, List.of(
                    Component.text("Expand your storefront and spotlight level.", NamedTextColor.GRAY)
            ), "ce:stalls:upgrades"));
        }
        inventory.setItem(22, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:open"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openMyStall(Player player) {
        StallProfile profile = this.getProfile(player.getUniqueId());
        if (profile == null) {
            this.openHub(player);
            return;
        }
        Inventory inventory = CrownsMenuHolder.create("stalls-mine", 54, Component.text(profile.getStallName(), NamedTextColor.GOLD));
        List<StallListing> ownedListings = this.getListingsForOwner(player.getUniqueId());
        int slot = 10;
        for (StallListing listing : ownedListings) {
            if (slot >= 44) {
                break;
            }
            ItemStack display = listing.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Price: " + Currency.format(listing.getPrice()), NamedTextColor.GREEN));
            lore.add(Component.text("Manage this listing with the buttons below.", NamedTextColor.GRAY));
            lore.add(Component.text("ce:stalls:none", NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            display.setItemMeta(meta);
            inventory.setItem(slot, display);
            inventory.setItem(slot + 9, this.button(Material.GOLD_INGOT, "Edit Price", NamedTextColor.AQUA, List.of(
                    Component.text("Current: " + Currency.format(listing.getPrice()), NamedTextColor.GRAY)
            ), "ce:stalls:edit:" + listing.getId()));
            inventory.setItem(slot + 18, this.button(Material.BARRIER, "Remove", NamedTextColor.RED, List.of(
                    Component.text("Return the listing item.", NamedTextColor.GRAY)
            ), "ce:stalls:remove:" + listing.getId()));
            slot++;
        }
        if (ownedListings.isEmpty()) {
            inventory.setItem(22, this.info(Material.BARRIER, "No Listings Yet", List.of(
                    Component.text("Hold an item and add your first listing.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(46, this.info(Material.NAME_TAG, "Category", List.of(Component.text(this.displayCategory(profile.getCategory()), NamedTextColor.WHITE))));
        inventory.setItem(47, this.info(Material.CHEST, "Listing Slots", List.of(Component.text(ownedListings.size() + "/" + profile.getListingSlots(), NamedTextColor.WHITE))));
        inventory.setItem(48, this.info(Material.GLOW_ITEM_FRAME, "Spotlight", List.of(Component.text("Level " + profile.getSpotlightLevel(), NamedTextColor.WHITE))));
        inventory.setItem(50, this.button(Material.EMERALD, "Add Held Item", NamedTextColor.GREEN, List.of(
                Component.text("Hold an item and type its price in chat.", NamedTextColor.GRAY)
        ), "ce:stalls:add"));
        inventory.setItem(51, this.button(Material.HOPPER, "Cycle Category", NamedTextColor.YELLOW, List.of(
                Component.text("Current: " + this.displayCategory(profile.getCategory()), NamedTextColor.GRAY)
        ), "ce:stalls:category"));
        inventory.setItem(52, this.button(Material.ANVIL, "Upgrades", NamedTextColor.AQUA, List.of(
                Component.text("Expand your storefront.", NamedTextColor.GRAY)
        ), "ce:stalls:upgrades"));
        inventory.setItem(53, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:stalls:hub"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openUpgrades(Player player) {
        StallProfile profile = this.getProfile(player.getUniqueId());
        if (profile == null) {
            this.openHub(player);
            return;
        }
        Inventory inventory = CrownsMenuHolder.create("stalls-upgrades", 27, Component.text("Stall Upgrades", NamedTextColor.GOLD));
        boolean maxSlots = profile.getListingSlots() >= this.getMaxListingSlots();
        boolean maxSpotlight = profile.getSpotlightLevel() >= this.getMaxSpotlightLevel();
        inventory.setItem(11, this.button(Material.CHEST, "Listing Slots", NamedTextColor.GREEN, List.of(
                Component.text("Current: " + profile.getListingSlots(), NamedTextColor.GRAY),
                Component.text(maxSlots ? "Already maxed." : "Cost: " + Currency.format(this.getSlotUpgradeCost(profile)), NamedTextColor.YELLOW),
                Component.text("Adds +" + this.getSlotUpgradeStep() + " sell slots.", NamedTextColor.GRAY)
        ), "ce:stalls:upgrade:slots"));
        inventory.setItem(15, this.button(Material.GLOW_ITEM_FRAME, "Spotlight Level", NamedTextColor.LIGHT_PURPLE, List.of(
                Component.text("Current: " + profile.getSpotlightLevel() + "/" + this.getMaxSpotlightLevel(), NamedTextColor.GRAY),
                Component.text(maxSpotlight ? "Already maxed." : "Cost: " + Currency.format(this.getSpotlightUpgradeCost(profile)), NamedTextColor.YELLOW),
                Component.text("Pushes your stall higher in browse order.", NamedTextColor.GRAY)
        ), "ce:stalls:upgrade:spotlight"));
        inventory.setItem(22, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:stalls:mine"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openBrowse(Player player, int page, String categoryFilter) {
        List<StallProfile> browse = this.getBrowseResults(categoryFilter);
        int pageSize = 28;
        int totalPages = Math.max(1, (int) Math.ceil((double) browse.size() / (double) pageSize));
        int currentPage = Math.max(0, Math.min(page, totalPages - 1));
        Inventory inventory = CrownsMenuHolder.create("stalls-browse", 54, Component.text("Browse Stalls", NamedTextColor.GOLD));
        int start = currentPage * pageSize;
        int slot = 10;
        for (int index = start; index < Math.min(start + pageSize, browse.size()); index++) {
            StallProfile profile = browse.get(index);
            inventory.setItem(slot, this.button(Material.CHEST, profile.getStallName(), NamedTextColor.YELLOW, List.of(
                    Component.text("Owner: " + profile.getOwnerName(), NamedTextColor.GRAY),
                    Component.text("Category: " + this.displayCategory(profile.getCategory()), NamedTextColor.GRAY),
                    Component.text("Listings: " + this.getListingsForOwner(profile.getOwnerUuid()).size(), NamedTextColor.GRAY),
                    Component.text("Spotlight: " + profile.getSpotlightLevel(), NamedTextColor.GRAY),
                    Component.text("Click to browse this stall.", NamedTextColor.GREEN)
            ), "ce:stalls:view:" + profile.getOwnerUuid()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        if (browse.isEmpty()) {
            inventory.setItem(22, this.info(Material.BARRIER, "No Matching Stalls", List.of(
                    Component.text("No stalls match the current category filter.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(45, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:stalls:hub"));
        if (currentPage > 0) {
            inventory.setItem(48, this.button(Material.ARROW, "Previous", NamedTextColor.AQUA, List.of(), "ce:stalls:browse:" + (currentPage - 1) + ":" + categoryFilter));
        }
        inventory.setItem(49, this.info(Material.PAPER, "Page", List.of(Component.text((currentPage + 1) + " / " + totalPages, NamedTextColor.GRAY))));
        if (currentPage < totalPages - 1) {
            inventory.setItem(50, this.button(Material.ARROW, "Next", NamedTextColor.AQUA, List.of(), "ce:stalls:browse:" + (currentPage + 1) + ":" + categoryFilter));
        }
        inventory.setItem(52, this.button(Material.HOPPER, "Category Filter", NamedTextColor.YELLOW, List.of(
                Component.text("Current: " + (ALL_FILTER.equals(categoryFilter) ? "All" : categoryFilter), NamedTextColor.GRAY),
                Component.text("Click to cycle categories.", NamedTextColor.AQUA)
        ), "ce:stalls:filter:" + this.nextCategoryFilter(categoryFilter)));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openStall(Player player, UUID ownerUuid) {
        StallProfile profile = this.profiles.get(ownerUuid);
        if (profile == null) {
            this.openBrowse(player, 0, ALL_FILTER);
            return;
        }
        boolean isOwner = ownerUuid.equals(player.getUniqueId());
        List<StallListing> stallListings = this.getListingsForOwner(ownerUuid);
        Inventory inventory = CrownsMenuHolder.create("stalls-view", 54, Component.text(profile.getStallName(), NamedTextColor.GOLD));
        int slot = 10;
        for (StallListing listing : stallListings) {
            if (slot >= 44) {
                break;
            }
            ItemStack display = listing.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Price: " + Currency.format(listing.getPrice()), NamedTextColor.GREEN));
            lore.add(Component.text("Category: " + this.displayCategory(profile.getCategory()), NamedTextColor.GRAY));
            lore.add(Component.text(isOwner ? "Manage this listing from your stall view." : "Click buy below.", NamedTextColor.YELLOW));
            lore.add(Component.text("ce:stalls:none", NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            display.setItemMeta(meta);
            inventory.setItem(slot, display);
            if (!isOwner) {
                inventory.setItem(slot + 9, this.button(Material.EMERALD, "Buy", NamedTextColor.GREEN, List.of(
                        Component.text("Price: " + Currency.format(listing.getPrice()), NamedTextColor.YELLOW)
                ), "ce:stalls:buy:" + listing.getId()));
            }
            slot++;
        }
        if (stallListings.isEmpty()) {
            inventory.setItem(22, this.info(Material.BARRIER, "No Listings", List.of(
                    Component.text("This stall is between stock right now.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(46, this.info(Material.NAME_TAG, "Owner", List.of(Component.text(profile.getOwnerName(), NamedTextColor.WHITE))));
        inventory.setItem(47, this.info(Material.PAPER, "Category", List.of(Component.text(this.displayCategory(profile.getCategory()), NamedTextColor.WHITE))));
        inventory.setItem(48, this.info(Material.GLOW_ITEM_FRAME, "Spotlight", List.of(Component.text("Level " + profile.getSpotlightLevel(), NamedTextColor.WHITE))));
        inventory.setItem(53, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), isOwner ? "ce:stalls:mine" : "ce:stalls:browse:0:*"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    private void ensureTables() {
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economy_stall_profiles (
                        owner_uuid TEXT PRIMARY KEY,
                        owner_name TEXT,
                        stall_name TEXT NOT NULL,
                        category TEXT,
                        purchased_at INTEGER NOT NULL,
                        listing_slots INTEGER NOT NULL DEFAULT 6,
                        spotlight_level INTEGER NOT NULL DEFAULT 0,
                        prestige_level INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economy_stall_listings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner_uuid TEXT NOT NULL,
                        item_data TEXT NOT NULL,
                        price INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Stalls] Table setup failed: " + exception.getMessage());
        }
    }

    private void loadProfiles() {
        this.profiles.clear();
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM economy_stall_profiles")) {
            while (resultSet.next()) {
                StallProfile profile = new StallProfile(
                        UUID.fromString(resultSet.getString("owner_uuid")),
                        resultSet.getString("owner_name"),
                        resultSet.getString("stall_name"),
                        resultSet.getString("category"),
                        resultSet.getLong("purchased_at"),
                        resultSet.getInt("listing_slots"),
                        resultSet.getInt("spotlight_level"),
                        resultSet.getInt("prestige_level")
                );
                this.profiles.put(profile.getOwnerUuid(), profile);
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Stalls] Load profiles failed: " + exception.getMessage());
        }
    }

    private void loadListings() {
        this.listings.clear();
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM economy_stall_listings")) {
            while (resultSet.next()) {
                this.listings.put(resultSet.getInt("id"), new StallListing(
                        resultSet.getInt("id"),
                        UUID.fromString(resultSet.getString("owner_uuid")),
                        ItemSerialization.deserialize(resultSet.getString("item_data")),
                        resultSet.getLong("price"),
                        resultSet.getLong("created_at")
                ));
            }
        } catch (Exception exception) {
            this.plugin.getLogger().warning("[Stalls] Load listings failed: " + exception.getMessage());
        }
    }

    private List<StallProfile> getBrowseResults(String categoryFilter) {
        return this.profiles.values().stream()
                .filter(profile -> ALL_FILTER.equals(categoryFilter) || this.displayCategory(profile.getCategory()).equalsIgnoreCase(categoryFilter))
                .sorted(Comparator.comparingInt(StallProfile::getSpotlightLevel).reversed()
                        .thenComparing((StallProfile profile) -> this.getListingsForOwner(profile.getOwnerUuid()).size(), Comparator.reverseOrder())
                        .thenComparing(StallProfile::getOwnerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<StallListing> getListingsForOwner(UUID ownerUuid) {
        return this.listings.values().stream()
                .filter(listing -> listing.getOwnerUuid().equals(ownerUuid))
                .sorted(Comparator.comparingLong(StallListing::getCreatedAt))
                .toList();
    }

    private void saveProfile(StallProfile profile) {
        String sql = """
                INSERT OR REPLACE INTO economy_stall_profiles (
                    owner_uuid, owner_name, stall_name, category, purchased_at, listing_slots, spotlight_level, prestige_level
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setString(1, profile.getOwnerUuid().toString());
            statement.setString(2, profile.getOwnerName());
            statement.setString(3, profile.getStallName());
            statement.setString(4, profile.getCategory());
            statement.setLong(5, profile.getPurchasedAt());
            statement.setInt(6, profile.getListingSlots());
            statement.setInt(7, profile.getSpotlightLevel());
            statement.setInt(8, profile.getPrestigeLevel());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Stalls] Save profile failed: " + exception.getMessage());
        }
    }

    private void saveListing(StallListing listing) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE economy_stall_listings SET price = ? WHERE id = ?")) {
            statement.setLong(1, listing.getPrice());
            statement.setInt(2, listing.getId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Stalls] Save listing failed: " + exception.getMessage());
        }
    }

    private void deleteListing(int listingId) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM economy_stall_listings WHERE id = ?")) {
            statement.setInt(1, listingId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Stalls] Delete listing failed: " + exception.getMessage());
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
        for (int index = 0; index < inventory.getSize(); index++) {
            if (inventory.getItem(index) == null && (index < 9 || index >= inventory.getSize() - 9 || index % 9 == 0 || index % 9 == 8)) {
                inventory.setItem(index, filler);
            }
        }
    }

    private void giveItem(Player player, ItemStack item) {
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack extra : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
    }

    private String displayCategory(String category) {
        return category == null || category.isBlank() ? "General" : category;
    }

    private String describeItem(ItemStack item) {
        return item.getAmount() + "x " + item.getType().name();
    }

    private String nextCategoryFilter(String current) {
        List<String> options = new ArrayList<>();
        options.add(ALL_FILTER);
        options.addAll(this.getConfiguredCategories());
        int index = Math.max(0, options.indexOf(current));
        return options.get((index + 1) % options.size());
    }
}
