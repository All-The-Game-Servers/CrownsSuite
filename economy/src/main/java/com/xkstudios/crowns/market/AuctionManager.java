/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.TextComponent
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.entity.Player
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 */
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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class AuctionManager {
    private final CrownsPlugin plugin;
    private final Map<String, AuctionListing> listings = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingActions = new ConcurrentHashMap<>();

    public AuctionManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM auction_listings WHERE sold = 0")) {
            while (rs.next()) {
                String highBidderValue = rs.getString("high_bidder");
                UUID highBidder = highBidderValue == null || highBidderValue.isEmpty() ? null : UUID.fromString(highBidderValue);
                AuctionListing listing = new AuctionListing(
                        rs.getString("id"),
                        UUID.fromString(rs.getString("seller_uuid")),
                        ItemSerialization.deserialize(rs.getString("item_data")),
                        rs.getLong("price"),
                        rs.getLong("listed_at"),
                        rs.getLong("expires_at"),
                        highBidder,
                        rs.getLong("high_bid"),
                        false
                );
                this.listings.put(listing.getId(), listing);
            }
        } catch (Exception e) {
            this.plugin.getLogger().warning("[Auction] Load: " + e.getMessage());
        }
        this.plugin.getLogger().info("[Auction] " + this.listings.size() + " listing(s)");
    }

    public boolean createListing(Player seller, ItemStack item, long startingBid) {
        return this.createListing(seller, item, startingBid, this.getDefaultDurationHours());
    }

    public boolean createListing(Player seller, ItemStack item, long startingBid, int durationHours) {
        int max = this.plugin.getConfig().getInt("auction.max-listings", 10);
        if (this.listings.values().stream().filter(l -> l.getSellerUuid().equals(seller.getUniqueId()) && !l.isEnded()).count() >= max) {
            return false;
        }
        long minP = this.plugin.getConfig().getLong("auction.min-price", 10L);
        long maxP = this.plugin.getConfig().getLong("auction.max-price", 5000000L);
        if (startingBid < minP || startingBid > maxP || !this.isDurationAllowed(durationHours)) {
            return false;
        }
        long fee = this.plugin.getConfig().getLong("economy.taxes.auction-listing-fee", 100L);
        if (!this.plugin.getEconomy().withdraw(seller, fee, "auction-fees", "Listing fee for " + item.getType().name())) {
            return false;
        }
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString().substring(0, 8);
        AuctionListing listing = new AuctionListing(id, seller.getUniqueId(), item.clone(), startingBid, now, now + (long) durationHours * 3600000L, null, 0L, false);
        this.listings.put(id, listing);
        seller.getInventory().removeItem(item);
        this.saveListing(listing);
        return true;
    }

    public boolean cancelListing(Player seller, String id) {
        AuctionListing l = this.listings.get(id);
        if (l == null || l.isEnded() || !l.getSellerUuid().equals(seller.getUniqueId())) {
            return false;
        }
        if (l.hasBids()) {
            long penalty = (long)((double)l.getHighBid() * 0.1);
            if (!this.plugin.getEconomy().withdraw(seller, penalty, "auction-cancel-penalties", "Cancelled listing " + id)) {
                return false;
            }
            String bidderName = Bukkit.getOfflinePlayer((UUID)l.getHighBidder()).getName();
            this.plugin.getEconomy().deposit(l.getHighBidder(), bidderName, l.getHighBid(), "auction-refunds", "Refund for cancelled listing " + id);
            Player bidder = Bukkit.getPlayer((UUID)l.getHighBidder());
            if (bidder != null) {
                bidder.sendMessage((Component)Component.text((String)("Auction canceled. " + Currency.format(l.getHighBid()) + " refunded."), (TextColor)NamedTextColor.YELLOW));
            }
            this.plugin.getInboxManager().push(l.getHighBidder(), bidderName, "auction_refund",
                    "Auction cancelled: " + Currency.format(l.getHighBid()) + " refunded",
                    "Listing " + id + " was cancelled by the seller.");
        }
        HashMap<Integer, ItemStack> ov = seller.getInventory().addItem(l.getItem().clone());
        for (ItemStack drop : ov.values()) {
            seller.getWorld().dropItemNaturally(seller.getLocation(), drop);
        }
        l.setEnded(true);
        this.listings.remove(id);
        this.saveListing(l);
        return true;
    }

    public boolean editBid(Player seller, String id, long newBid) {
        AuctionListing l = this.listings.get(id);
        if (l == null || l.isEnded() || !l.getSellerUuid().equals(seller.getUniqueId()) || l.hasBids()) {
            return false;
        }
        l.setStartingBid(newBid);
        this.saveListing(l);
        return true;
    }

    public boolean placeBid(Player bidder, String id, long amount) {
        AuctionListing l = this.listings.get(id);
        if (l == null || l.isEnded() || l.isExpired()) {
            return false;
        }
        if (l.getSellerUuid().equals(bidder.getUniqueId())) {
            return false;
        }
        if (amount < l.getMinNextBid()) {
            return false;
        }
        if (this.plugin.getEconomy().getBalance(bidder) < amount) {
            return false;
        }
        if (l.hasBids()) {
            String previousBidderName = Bukkit.getOfflinePlayer((UUID)l.getHighBidder()).getName();
            this.plugin.getEconomy().deposit(l.getHighBidder(), previousBidderName, l.getHighBid(), "auction-refunds", "Outbid refund for listing " + id);
            Player prev = Bukkit.getPlayer((UUID)l.getHighBidder());
            if (prev != null) {
                prev.sendMessage((Component)Component.text((String)("Outbid on " + l.getItem().getType().name() + "! New bid: " + Currency.format(amount)), (TextColor)NamedTextColor.RED));
            }
            this.plugin.getInboxManager().push(l.getHighBidder(), previousBidderName, "auction_outbid",
                    "Outbid on auction",
                    "You were outbid on " + l.getItem().getType().name() + ". New bid: " + Currency.format(amount) + ".");
        }
        this.plugin.getEconomy().withdraw(bidder, amount, "auction-bids", "Bid on listing " + id);
        l.setHighBidder(bidder.getUniqueId());
        l.setHighBid(amount);
        this.saveListing(l);
        Player seller = Bukkit.getPlayer((UUID)l.getSellerUuid());
        if (seller != null) {
            seller.sendMessage((Component)Component.text((String)("New bid on " + l.getItem().getType().name() + ": " + Currency.format(amount)), (TextColor)NamedTextColor.GREEN));
        }
        String sellerName = Bukkit.getOfflinePlayer((UUID)l.getSellerUuid()).getName();
        this.plugin.getInboxManager().push(l.getSellerUuid(), sellerName, "auction_bid",
                "New auction bid",
                bidder.getName() + " bid " + Currency.format(amount) + " on " + l.getItem().getType().name() + ".");
        return true;
    }

    public void checkExpired() {
        for (AuctionListing l : new ArrayList<AuctionListing>(this.listings.values())) {
            if (!l.isExpired() || l.isEnded()) continue;
            l.setEnded(true);
            this.listings.remove(l.getId());
            if (l.hasBids()) {
                Player winner = Bukkit.getPlayer((UUID)l.getHighBidder());
                if (winner != null) {
                    HashMap<Integer, ItemStack> ov = winner.getInventory().addItem(l.getItem().clone());
                    for (ItemStack d : ov.values()) {
                        winner.getWorld().dropItemNaturally(winner.getLocation(), d);
                    }
                    winner.sendMessage((Component)Component.text((String)("You won: " + l.getItem().getType().name() + "!"), (TextColor)NamedTextColor.GREEN));
                }
                double tax = this.plugin.getConfig().getDouble("economy.taxes.auction-sale-tax", 0.03);
                long payout = l.getHighBid() - (long)((double)l.getHighBid() * tax);
                String winnerName = Bukkit.getOfflinePlayer((UUID)l.getHighBidder()).getName();
                String sellerName = Bukkit.getOfflinePlayer((UUID)l.getSellerUuid()).getName();
                this.plugin.getEconomy().deposit(l.getSellerUuid(), sellerName, payout, "auction-sales", "Sold listing " + l.getId());
                long taxAmount = l.getHighBid() - payout;
                if (taxAmount > 0L) {
                    this.plugin.getEconomyLedgerManager().recordSink(l.getSellerUuid(), sellerName, "auction-tax", taxAmount, "Tax on listing " + l.getId());
                }
                Player seller = Bukkit.getPlayer((UUID)l.getSellerUuid());
                if (seller != null) {
                    seller.sendMessage((Component)Component.text((String)("Auction sold! +" + Currency.format(payout)), (TextColor)NamedTextColor.GREEN));
                }
                this.plugin.getInboxManager().push(l.getSellerUuid(), sellerName, "auction_sold",
                        "Auction sold: " + Currency.format(payout),
                        "Your " + l.getItem().getType().name() + " sold to " + winnerName + ".");
                this.plugin.getInboxManager().push(l.getHighBidder(), winnerName, "auction_won",
                        "Auction won",
                        "You won " + l.getItem().getType().name() + " for " + Currency.format(l.getHighBid()) + ".");
            } else {
                Player seller = Bukkit.getPlayer((UUID)l.getSellerUuid());
                if (seller != null) {
                    HashMap<Integer, ItemStack> ov = seller.getInventory().addItem(l.getItem().clone());
                    for (ItemStack d : ov.values()) {
                        seller.getWorld().dropItemNaturally(seller.getLocation(), d);
                    }
                    seller.sendMessage((Component)Component.text((String)"Auction expired. Item returned.", (TextColor)NamedTextColor.YELLOW));
                }
                String sellerName = Bukkit.getOfflinePlayer((UUID)l.getSellerUuid()).getName();
                this.plugin.getInboxManager().push(l.getSellerUuid(), sellerName, "auction_expired",
                        "Auction expired",
                        "Your " + l.getItem().getType().name() + " expired without any bids.");
            }
            this.saveListing(l);
        }
    }

    public void openMainMenu(Player player) {
        Inventory gui = CrownsMenuHolder.create("auction-main", 27, Component.text("Auction House", NamedTextColor.GOLD));
        gui.setItem(11, this.makeButton(Material.CHEST, "Browse Auctions", NamedTextColor.GREEN,
                List.of(Component.text("View all active listings", NamedTextColor.GRAY)), "ah:browse:0"));
        gui.setItem(13, this.makeButton(Material.BOOK, "My Auctions", NamedTextColor.AQUA,
                List.of(Component.text("Manage your listings", NamedTextColor.GRAY)), "ah:myauctions"));
        gui.setItem(15, this.makeButton(Material.GOLDEN_SWORD, "Create Auction", NamedTextColor.YELLOW,
                List.of(
                        Component.text("Sell an item from your hand", NamedTextColor.GRAY),
                        Component.text("Choose price and duration in the next menus.", NamedTextColor.DARK_GRAY)
                ), "ah:create"));
        gui.setItem(22, this.makeButton(Material.ARROW, "Back", NamedTextColor.GRAY,
                List.of(Component.text("Return to Crowns Economy.", NamedTextColor.GRAY)), "ce:menu:open"));
        this.fillBorder(gui, 27);
        player.openInventory(gui);
    }

    public void openBrowse(Player player, int page) {
        int start;
        List<AuctionListing> active = this.listings.values().stream().filter(l -> !l.isEnded() && !l.isExpired()).sorted(Comparator.comparingLong(AuctionListing::getExpiresAt)).toList();
        int pageSize = 36;
        int totalPages = Math.max(1, (int)Math.ceil((double)active.size() / (double)pageSize));
        page = Math.max(0, Math.min(page, totalPages - 1));
        Inventory gui = CrownsMenuHolder.create("auction-browse", 54, Component.text("Browse Auctions", NamedTextColor.GOLD));
        for (int i = start = page * pageSize; i < Math.min(start + pageSize, active.size()); ++i) {
            AuctionListing l2 = active.get(i);
            ItemStack display = l2.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            ArrayList<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            if (l2.hasBids()) {
                lore.add(Component.text("Current Bid: " + Currency.format(l2.getHighBid()), NamedTextColor.GREEN));
                String bn = Bukkit.getOfflinePlayer((UUID)l2.getHighBidder()).getName();
                lore.add(Component.text("Top Bidder: " + (bn != null ? bn : "?"), NamedTextColor.GRAY));
            } else {
                lore.add(Component.text("Starting Bid: " + Currency.format(l2.getStartingBid()), NamedTextColor.GREEN));
                lore.add(Component.text("No bids yet", NamedTextColor.GRAY));
            }
            String sn = Bukkit.getOfflinePlayer((UUID)l2.getSellerUuid()).getName();
            lore.add(Component.text("Seller: " + (sn != null ? sn : "?"), NamedTextColor.GRAY));
            long mins = Math.max(0L, (l2.getExpiresAt() - System.currentTimeMillis()) / 60000L);
            lore.add(Component.text("Ends in: " + mins / 60L + "h " + mins % 60L + "m", NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(Component.text("Click to view!", NamedTextColor.YELLOW));
            lore.add(Component.text("ah:viewbrowse:" + l2.getId() + ":" + page, NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            display.setItemMeta(meta);
            gui.addItem(display);
        }
        gui.setItem(45, this.makeButton(Material.ARROW, "Back to Menu", NamedTextColor.GRAY, List.of(), "ah:menu"));
        if (page > 0) {
            gui.setItem(48, this.makeButton(Material.ARROW, "Previous Page", NamedTextColor.AQUA, List.of(), "ah:browse:" + (page - 1)));
        }
        gui.setItem(49, this.makeButton(Material.PAPER, "Page " + (page + 1) + "/" + totalPages, NamedTextColor.GRAY, List.of(), "ah:none"));
        if (page < totalPages - 1) {
            gui.setItem(50, this.makeButton(Material.ARROW, "Next Page", NamedTextColor.AQUA, List.of(), "ah:browse:" + (page + 1)));
        }
        player.openInventory(gui);
    }

    public void openItemView(Player player, String listingId) {
        this.openItemView(player, listingId, "ah:browse:0");
    }

    public void openItemView(Player player, String listingId, String backAction) {
        AuctionListing l = this.listings.get(listingId);
        if (l == null || l.isEnded()) {
            this.openBrowse(player, 0);
            return;
        }
        boolean isSeller = l.getSellerUuid().equals(player.getUniqueId());
        Inventory gui = CrownsMenuHolder.create("auction-view", 54, Component.text(isSeller ? "Manage Listing" : "Place Bid", NamedTextColor.GOLD));
        gui.setItem(4, l.getItem().clone());
        long minBid = l.getMinNextBid();
        String sn = Bukkit.getOfflinePlayer((UUID)l.getSellerUuid()).getName();
        long mins = Math.max(0L, (l.getExpiresAt() - System.currentTimeMillis()) / 60000L);
        gui.setItem(20, this.makeInfo(Material.GOLD_INGOT, "Current Price", l.hasBids() ? Currency.format(l.getHighBid()) : Currency.format(l.getStartingBid()) + " (starting)"));
        gui.setItem(21, this.makeInfo(Material.CLOCK, "Time Left", mins / 60L + "h " + mins % 60L + "m"));
        gui.setItem(22, this.makeInfo(Material.NAME_TAG, "Seller", sn != null ? sn : "Unknown"));
        if (l.hasBids()) {
            String bn = Bukkit.getOfflinePlayer((UUID)l.getHighBidder()).getName();
            gui.setItem(23, this.makeInfo(Material.DIAMOND, "Top Bidder", bn != null ? bn : "Unknown"));
            gui.setItem(24, this.makeInfo(Material.EMERALD, "Min Next Bid", Currency.format(minBid)));
        }
        if (isSeller) {
            if (!l.hasBids()) {
                gui.setItem(37, this.makeButton(Material.NAME_TAG, "Edit Starting Bid", NamedTextColor.AQUA,
                        List.of(
                                Component.text("Current: " + Currency.format(l.getStartingBid()), NamedTextColor.GRAY),
                                Component.text("Click to change", NamedTextColor.YELLOW)
                        ), "ah:edit:" + l.getId()));
            }
            gui.setItem(39, this.makeButton(Material.BARRIER, "Cancel Listing", NamedTextColor.RED,
                    l.hasBids() ? List.of(
                            Component.text("Penalty: " + Currency.format((long) (l.getHighBid() * 0.1)), NamedTextColor.RED),
                            Component.text("Bidder will be refunded", NamedTextColor.GRAY)
                    ) : List.of(Component.text("Free cancel - no bids", NamedTextColor.GREEN)),
                    "ah:cancel:" + l.getId()));
        } else {
            long[] presets = new long[]{minBid, minBid + minBid / 10L, minBid + minBid / 5L, minBid + minBid / 2L};
            Material[] mats = new Material[]{Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK, Material.DIAMOND};
            String[] labels = new String[]{"Min Bid", "+10%", "+20%", "+50%"};
            for (int i = 0; i < 4; ++i) {
                gui.setItem(37 + i, this.makeButton(mats[i], labels[i] + ": " + Currency.format(presets[i]), NamedTextColor.GREEN,
                        List.of(Component.text("Click to bid " + Currency.format(presets[i]), NamedTextColor.YELLOW)),
                        "ah:bid:" + l.getId() + ":" + presets[i]));
            }
            gui.setItem(41, this.makeButton(Material.ANVIL, "Custom Bid", NamedTextColor.AQUA,
                    List.of(
                            Component.text("Type your own amount", NamedTextColor.GRAY),
                            Component.text("Min: " + Currency.format(minBid), NamedTextColor.YELLOW)
                    ), "ah:custombid:" + l.getId()));
        }
        gui.setItem(49, this.makeButton(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), backAction));
        this.fillBorder(gui, 54);
        player.openInventory(gui);
    }

    public void openMyAuctions(Player player) {
        List<AuctionListing> mine = this.listings.values().stream().filter(l -> l.getSellerUuid().equals(player.getUniqueId()) && !l.isEnded()).sorted(Comparator.comparingLong(AuctionListing::getExpiresAt)).toList();
        Inventory gui = CrownsMenuHolder.create("auction-my", 54, Component.text("My Auctions", NamedTextColor.GOLD));
        for (AuctionListing l2 : mine) {
            ItemStack display = l2.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            ArrayList<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            if (l2.hasBids()) {
                lore.add(Component.text("Current Bid: " + Currency.format(l2.getHighBid()), NamedTextColor.GREEN));
                String bn = Bukkit.getOfflinePlayer((UUID)l2.getHighBidder()).getName();
                lore.add(Component.text("Bidder: " + (bn != null ? bn : "?"), NamedTextColor.GRAY));
            } else {
                lore.add(Component.text("Starting: " + Currency.format(l2.getStartingBid()), NamedTextColor.GREEN));
                lore.add(Component.text("No bids", NamedTextColor.GRAY));
            }
            long mins = Math.max(0L, (l2.getExpiresAt() - System.currentTimeMillis()) / 60000L);
            lore.add(Component.text("Ends in: " + mins / 60L + "h " + mins % 60L + "m", NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(Component.text("Click to manage", NamedTextColor.YELLOW));
            lore.add(Component.text("ah:viewmine:" + l2.getId(), NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            display.setItemMeta(meta);
            gui.addItem(display);
        }
        if (mine.isEmpty()) {
            gui.setItem(22, this.makeButton(Material.BARRIER, "No Active Listings", NamedTextColor.GRAY,
                    List.of(Component.text("Use 'Create Auction' to list items", NamedTextColor.GRAY)), "ah:none"));
        }
        gui.setItem(49, this.makeButton(Material.ARROW, "Back to Menu", NamedTextColor.GRAY, List.of(), "ah:menu"));
        player.openInventory(gui);
    }

    public void openCreateConfirm(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            player.sendMessage(Component.text("Hold the item you want to auction.", NamedTextColor.RED));
            return;
        }
        Inventory gui = CrownsMenuHolder.create("auction-create", 27, Component.text("Create Auction", NamedTextColor.GOLD));
        gui.setItem(4, held.clone());
        long[] prices = new long[]{100L, 500L, 1000L, 5000L, 10000L};
        String[] labels = new String[]{"1 Shilling", "5 Shillings", "10 Shillings", "50 Shillings", "1 Crown"};
        Material[] mats = new Material[]{Material.IRON_NUGGET, Material.IRON_INGOT, Material.GOLD_INGOT, Material.GOLD_BLOCK, Material.DIAMOND};
        for (int i = 0; i < 5; ++i) {
            gui.setItem(11 + i, this.makeButton(mats[i], "Start at " + labels[i], NamedTextColor.GREEN,
                    List.of(Component.text(Currency.format(prices[i]), NamedTextColor.YELLOW)), "ah:confirmcreate:" + prices[i]));
        }
        gui.setItem(16, this.makeButton(Material.ANVIL, "Custom Price", NamedTextColor.AQUA,
                List.of(Component.text("Type your own starting bid", NamedTextColor.GRAY)), "ah:customprice"));
        gui.setItem(22, this.makeButton(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ah:menu"));
        player.openInventory(gui);
    }

    public void openDurationPicker(Player player, long startingBid) {
        Inventory gui = CrownsMenuHolder.create("auction-duration", 27, Component.text("Auction Duration", NamedTextColor.GOLD));
        gui.setItem(4, this.makeInfo(Material.GOLD_INGOT, "Starting Bid", Currency.format(startingBid)));
        int minHours = this.getMinDurationHours();
        int maxHours = this.getMaxDurationHours();
        int defaultHours = this.getDefaultDurationHours();
        int[] options = new int[]{minHours, defaultHours, Math.min(maxHours, Math.max(minHours, 24)), Math.min(maxHours, Math.max(minHours, 48)), maxHours};
        for (int i = 0; i < options.length; i++) {
            int hours = options[i];
            gui.setItem(10 + i, this.makeButton(Material.CLOCK, hours + " Hour" + (hours == 1 ? "" : "s"), NamedTextColor.AQUA,
                    List.of(Component.text("Listing length", NamedTextColor.GRAY)), "ah:duration:" + startingBid + ":" + hours));
        }
        gui.setItem(16, this.makeButton(Material.ANVIL, "Custom Hours", NamedTextColor.YELLOW,
                List.of(Component.text("Enter any duration between " + minHours + "h and " + maxHours + "h.", NamedTextColor.GRAY)),
                "ah:customduration:" + startingBid));
        gui.setItem(22, this.makeButton(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ah:create"));
        this.fillBorder(gui, 27);
        player.openInventory(gui);
    }

    public void setAwaitingBid(UUID p, String id) {
        this.awaitingActions.put(p, id);
    }

    public void setAwaitingEdit(UUID p, String id) {
        this.awaitingActions.put(p, "edit:" + id);
    }

    public void setAwaitingCreate(UUID p) {
        this.awaitingActions.put(p, "create");
    }

    public void setAwaitingCreateDuration(UUID playerId, long price) {
        this.awaitingActions.put(playerId, "createduration:" + price);
    }

    public String getAwaitingAction(UUID p) {
        return this.awaitingActions.get(p);
    }

    public void clearAwaiting(UUID p) {
        this.awaitingActions.remove(p);
    }

    public AuctionListing getListing(String id) {
        return this.listings.get(id);
    }

    private ItemStack makeButton(Material mat, String name, NamedTextColor color, List<Component> lore, String action) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        ArrayList<Component> fullLore = new ArrayList<>(lore);
        fullLore.add(Component.text(action, NamedTextColor.DARK_GRAY));
        meta.lore(fullLore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeInfo(Material mat, String label, String value) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.GRAY));
        meta.lore(List.of(Component.text(value, NamedTextColor.WHITE)));
        item.setItemMeta(meta);
        return item;
    }

    private void fillBorder(Inventory gui, int size) {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.text(" "));
        filler.setItemMeta(fm);
        int rows = size / 9;
        for (int i = (rows - 1) * 9; i < size; ++i) {
            if (gui.getItem(i) != null) continue;
            gui.setItem(i, filler);
        }
    }

    private void saveListing(AuctionListing l) {
        try (PreparedStatement ps = this.plugin.getDataManager().getConnection().prepareStatement("INSERT OR REPLACE INTO auction_listings (id, seller_uuid, item_data, price, listed_at, expires_at, sold, buyer_uuid, high_bidder, high_bid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");){
            ps.setString(1, l.getId());
            ps.setString(2, l.getSellerUuid().toString());
            ps.setString(3, ItemSerialization.serialize(l.getItem()));
            ps.setLong(4, l.getStartingBid());
            ps.setLong(5, l.getListedAt());
            ps.setLong(6, l.getExpiresAt());
            ps.setInt(7, l.isEnded() ? 1 : 0);
            ps.setString(8, null);
            ps.setString(9, l.getHighBidder() != null ? l.getHighBidder().toString() : null);
            ps.setLong(10, l.getHighBid());
            ps.executeUpdate();
        }
        catch (Exception e) {
            this.plugin.getLogger().warning("[Auction] Save: " + e.getMessage());
        }
    }

    public int getDefaultDurationHours() {
        return this.plugin.getConfig().getInt("auction.default-duration-hours", this.plugin.getConfig().getInt("auction.duration-hours", 48));
    }

    public int getMinDurationHours() {
        return this.plugin.getConfig().getInt("auction.min-duration-hours", 6);
    }

    public int getMaxDurationHours() {
        return this.plugin.getConfig().getInt("auction.max-duration-hours", 72);
    }

    public boolean isDurationAllowed(int hours) {
        return hours >= this.getMinDurationHours() && hours <= this.getMaxDurationHours();
    }
}
