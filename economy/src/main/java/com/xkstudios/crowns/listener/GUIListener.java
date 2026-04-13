package com.xkstudios.crowns.listener;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.economy.Currency;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import com.xkstudios.crowns.market.AuctionListing;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.ItemStack;

public class GUIListener implements Listener {
    private final CrownsPlugin plugin;

    public GUIListener(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!CrownsMenuHolder.isMenu(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        String action = this.findAction(clicked);
        if (action == null || action.endsWith(":none")) {
            return;
        }
        if (action.startsWith("ah:")) {
            this.handleAuctionAction(player, action);
        } else {
            this.handleCrownsAction(player, action);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!CrownsMenuHolder.isMenu(event.getView().getTopInventory())) {
            return;
        }
        int protectedSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < protectedSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String action = this.plugin.getAuctionManager().getAwaitingAction(player.getUniqueId());
        String stallAction = this.plugin.getStallManager().getAwaitingAction(player.getUniqueId());
        if (action == null && stallAction == null) {
            return;
        }
        event.setCancelled(true);
        if (action != null) {
            this.plugin.getAuctionManager().clearAwaiting(player.getUniqueId());
        } else {
            this.plugin.getStallManager().clearAwaiting(player.getUniqueId());
        }
        String msg = event.getMessage().trim();
        if (stallAction != null) {
            Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.getStallManager().handleChatInput(player, stallAction, msg));
            return;
        }
        if (msg.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("Cancelled.", NamedTextColor.GRAY));
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            try {
                long amount = Currency.parse(msg);
                if (action.startsWith("bid:")) {
                    String listingId = action.substring(4);
                    if (this.plugin.getAuctionManager().placeBid(player, listingId, amount)) {
                        player.sendMessage(Component.text("Bid placed: " + Currency.format(amount), NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Bid failed.", NamedTextColor.RED));
                    }
                } else if (action.startsWith("edit:")) {
                    String listingId = action.substring(5);
                    if (this.plugin.getAuctionManager().editBid(player, listingId, amount)) {
                        player.sendMessage(Component.text("Listing updated.", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Could not update that listing.", NamedTextColor.RED));
                    }
                } else if (action.equals("create")) {
                    this.plugin.getAuctionManager().openDurationPicker(player, amount);
                } else if (action.startsWith("createduration:")) {
                    long price = Long.parseLong(action.substring("createduration:".length()));
                    int hours = Integer.parseInt(msg);
                    if (!this.plugin.getAuctionManager().isDurationAllowed(hours)) {
                        player.sendMessage(Component.text("Duration must be between " + this.plugin.getAuctionManager().getMinDurationHours() + " and " + this.plugin.getAuctionManager().getMaxDurationHours() + " hours.", NamedTextColor.RED));
                        return;
                    }
                    this.createAuction(player, price, hours);
                }
            } catch (NumberFormatException exception) {
                player.sendMessage(Component.text("Invalid number.", NamedTextColor.RED));
            }
        });
    }

    private void handleAuctionAction(Player player, String action) {
        String[] parts = action.split(":");
        switch (parts[1]) {
            case "menu" -> this.plugin.getAuctionManager().openMainMenu(player);
            case "browse" -> this.plugin.getAuctionManager().openBrowse(player, Integer.parseInt(parts[2]));
            case "myauctions" -> this.plugin.getAuctionManager().openMyAuctions(player);
            case "create" -> this.plugin.getAuctionManager().openCreateConfirm(player);
            case "view" -> this.plugin.getAuctionManager().openItemView(player, parts[2]);
            case "viewbrowse" -> this.plugin.getAuctionManager().openItemView(player, parts[2], "ah:browse:" + parts[3]);
            case "viewmine" -> this.plugin.getAuctionManager().openItemView(player, parts[2], "ah:myauctions");
            case "bid" -> {
                long amount = Long.parseLong(parts[3]);
                player.closeInventory();
                if (this.plugin.getAuctionManager().placeBid(player, parts[2], amount)) {
                    player.sendMessage(Component.text("Bid placed: " + Currency.format(amount), NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Bid failed. Check balance or minimum bid.", NamedTextColor.RED));
                }
            }
            case "custombid" -> {
                player.closeInventory();
                AuctionListing listing = this.plugin.getAuctionManager().getListing(parts[2]);
                if (listing != null) {
                    player.sendMessage(Component.text("Type your bid amount (min: " + Currency.format(listing.getMinNextBid()) + "). Type 'cancel' to cancel.", NamedTextColor.AQUA));
                    this.plugin.getAuctionManager().setAwaitingBid(player.getUniqueId(), parts[2]);
                }
            }
            case "cancel" -> {
                player.closeInventory();
                boolean cancelled = this.plugin.getAuctionManager().cancelListing(player, parts[2]);
                player.sendMessage(Component.text(cancelled ? "Listing cancelled. Item returned." : "Could not cancel.", cancelled ? NamedTextColor.YELLOW : NamedTextColor.RED));
            }
            case "edit" -> {
                player.closeInventory();
                player.sendMessage(Component.text("Type the new starting bid. Type 'cancel' to cancel.", NamedTextColor.AQUA));
                this.plugin.getAuctionManager().setAwaitingEdit(player.getUniqueId(), parts[2]);
            }
            case "confirmcreate" -> this.plugin.getAuctionManager().openDurationPicker(player, Long.parseLong(parts[2]));
            case "customprice" -> {
                player.closeInventory();
                player.sendMessage(Component.text("Type your starting bid price. Type 'cancel' to cancel.", NamedTextColor.AQUA));
                this.plugin.getAuctionManager().setAwaitingCreate(player.getUniqueId());
            }
            case "duration" -> this.createAuction(player, Long.parseLong(parts[2]), Integer.parseInt(parts[3]));
            case "customduration" -> {
                player.closeInventory();
                player.sendMessage(Component.text("Type how many hours the listing should stay up. Type 'cancel' to cancel.", NamedTextColor.AQUA));
                this.plugin.getAuctionManager().setAwaitingCreateDuration(player.getUniqueId(), Long.parseLong(parts[2]));
            }
        }
    }

    private void handleCrownsAction(Player player, String action) {
        String[] parts = action.split(":");
        switch (parts[1]) {
            case "menu" -> {
                if (parts.length == 2 || parts[2].equals("open")) {
                    this.plugin.getMenuManager().openMainMenu(player);
                } else if (parts[2].equals("jobs")) {
                    this.plugin.getMenuManager().openJobsMenu(player);
                } else if (parts[2].equals("inbox")) {
                    this.plugin.getMenuManager().openInboxMenu(player);
                } else if (parts[2].equals("suite")) {
                    CrownsAPI.openSuiteHome(player);
                } else if (parts[2].equals("wallet")) {
                    player.closeInventory();
                    player.sendMessage(Component.text("Balance: " + Currency.format(this.plugin.getEconomy().getBalance(player)), NamedTextColor.GOLD));
                } else if (parts[2].equals("auction")) {
                    this.plugin.getAuctionManager().openMainMenu(player);
                } else if (parts[2].equals("stalls")) {
                    this.plugin.getStallManager().openHub(player);
                } else if (parts[2].equals("demand")) {
                    this.plugin.getDemandManager().openDemandMenu(player);
                } else if (parts[2].equals("trader")) {
                    this.plugin.getDemandManager().openTraderMenu(player);
                } else if (parts[2].equals("gambling")) {
                    this.plugin.getMenuManager().openGamblingMenu(player);
                } else if (parts[2].equals("top")) {
                    player.closeInventory();
                    player.performCommand("ce top");
                }
            }
            case "inbox" -> {
                if (parts[2].equals("read")) {
                    this.plugin.getInboxManager().markRead(player.getUniqueId(), Long.parseLong(parts[3]));
                    this.plugin.getMenuManager().openInboxMenu(player);
                } else if (parts[2].equals("readall")) {
                    this.plugin.getInboxManager().markAllRead(player.getUniqueId());
                    this.plugin.getMenuManager().openInboxMenu(player);
                }
            }
            case "jobs" -> {
                if (parts[2].equals("accept")) {
                    player.closeInventory();
                    int jobId = Integer.parseInt(parts[3]);
                    if (this.plugin.getJobManager().claimJob(jobId, player.getUniqueId())) {
                        player.sendMessage(Component.text("Job accepted.", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Couldn't claim that job.", NamedTextColor.RED));
                    }
                } else if (parts[2].equals("complete")) {
                    player.closeInventory();
                    int jobId = Integer.parseInt(parts[3]);
                    if (this.plugin.getJobManager().completeJob(player, jobId)) {
                        player.sendMessage(Component.text("Job completed.", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("You can't complete that job yet.", NamedTextColor.RED));
                    }
                }
            }
            case "demand" -> {
                if (parts[2].equals("sell")) {
                    player.closeInventory();
                    if (this.plugin.getDemandManager().fulfillOrder(player, Integer.parseInt(parts[3]))) {
                        player.sendMessage(Component.text("Demand order fulfilled.", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("You don't have the required items for that order.", NamedTextColor.RED));
                    }
                }
            }
            case "trader" -> {
                if (parts[2].equals("buy")) {
                    player.closeInventory();
                    if (this.plugin.getDemandManager().buyOffer(player, Integer.parseInt(parts[3]))) {
                        player.sendMessage(Component.text("Purchase complete.", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Could not buy that offer.", NamedTextColor.RED));
                    }
                }
            }
            case "gambling" -> {
                switch (parts[2]) {
                    case "lottery" -> this.plugin.getMenuManager().openLotteryMenu(player);
                    case "coinflip" -> this.plugin.getMenuManager().openCoinflipMenu(player);
                    case "slots" -> this.plugin.getSlotsManager().openMenu(player);
                    case "lotterybuy" -> {
                        player.closeInventory();
                        boolean bought = this.plugin.getLotteryManager().buyTicket(player);
                        player.sendMessage(Component.text(
                                bought ? "Lottery ticket purchased." : "Could not buy a ticket right now.",
                                bought ? NamedTextColor.GREEN : NamedTextColor.RED));
                    }
                    case "lotterydraw" -> {
                        player.closeInventory();
                        if (player.hasPermission("crowns.admin") || player.isOp()) {
                            this.plugin.getLotteryManager().drawWinner();
                        } else {
                            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                        }
                    }
                    case "coinflipaccept" -> {
                        player.closeInventory();
                        boolean accepted = this.plugin.getCoinflipManager().accept(player);
                        player.sendMessage(Component.text(
                                accepted ? "Coinflip accepted." : "You don't have a pending challenge.",
                                accepted ? NamedTextColor.GREEN : NamedTextColor.RED));
                    }
                    case "coinflipdeny" -> {
                        player.closeInventory();
                        boolean denied = this.plugin.getCoinflipManager().deny(player);
                        player.sendMessage(Component.text(
                                denied ? "Coinflip denied." : "You don't have a pending challenge.",
                                denied ? NamedTextColor.YELLOW : NamedTextColor.RED));
                    }
                    case "coinflipprompt" -> {
                        player.closeInventory();
                        player.sendMessage(Component.text("Use /ce coinflip <player> <amount> to challenge someone.", NamedTextColor.AQUA));
                    }
                }
            }
            case "slots" -> {
                if (parts[2].equals("spin")) {
                    player.closeInventory();
                    String result = this.plugin.getSlotsManager().spin(player, parts[3]);
                    player.sendMessage(Component.text(result,
                            result.contains("won") ? NamedTextColor.GREEN : result.contains("does not exist") || result.contains("enough") ? NamedTextColor.RED : NamedTextColor.YELLOW));
                }
            }
            case "stalls" -> this.handleStallAction(player, parts);
        }
    }

    private void handleStallAction(Player player, String[] parts) {
        switch (parts[2]) {
            case "hub" -> this.plugin.getStallManager().openHub(player);
            case "buyunlock" -> {
                player.closeInventory();
                boolean bought = this.plugin.getStallManager().purchaseStall(player);
                player.sendMessage(Component.text(bought ? "Permanent stall unlocked." : "Could not unlock a stall.", bought ? NamedTextColor.GREEN : NamedTextColor.RED));
                this.plugin.getStallManager().openHub(player);
            }
            case "mine" -> this.plugin.getStallManager().openMyStall(player);
            case "browse" -> this.plugin.getStallManager().openBrowse(player, Integer.parseInt(parts[3]), parts[4]);
            case "view" -> this.plugin.getStallManager().openStall(player, java.util.UUID.fromString(parts[3]));
            case "buy" -> {
                player.closeInventory();
                if (this.plugin.getStallManager().buyListing(player, Integer.parseInt(parts[3]))) {
                    player.sendMessage(Component.text("Purchase complete.", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Purchase failed.", NamedTextColor.RED));
                }
            }
            case "add" -> {
                player.closeInventory();
                player.sendMessage(Component.text("Type the price for the held item. Type 'cancel' to cancel.", NamedTextColor.AQUA));
                this.plugin.getStallManager().setAwaitingCreate(player.getUniqueId());
            }
            case "edit" -> {
                player.closeInventory();
                player.sendMessage(Component.text("Type the new price. Type 'cancel' to cancel.", NamedTextColor.AQUA));
                this.plugin.getStallManager().setAwaitingEdit(player.getUniqueId(), Integer.parseInt(parts[3]));
            }
            case "remove" -> {
                player.closeInventory();
                this.plugin.getStallManager().removeListing(player, Integer.parseInt(parts[3]));
            }
            case "category" -> {
                player.closeInventory();
                this.plugin.getStallManager().cycleCategory(player);
                this.plugin.getStallManager().openMyStall(player);
            }
            case "upgrades" -> this.plugin.getStallManager().openUpgrades(player);
            case "upgrade" -> {
                player.closeInventory();
                boolean success = parts[3].equals("slots")
                        ? this.plugin.getStallManager().upgradeListingSlots(player)
                        : this.plugin.getStallManager().upgradeSpotlight(player);
                player.sendMessage(Component.text(success ? "Upgrade purchased." : "Could not buy that upgrade.", success ? NamedTextColor.GREEN : NamedTextColor.RED));
                this.plugin.getStallManager().openUpgrades(player);
            }
            case "filter" -> this.plugin.getStallManager().openBrowse(player, 0, parts[3]);
            case "about" -> {
                player.closeInventory();
                player.sendMessage(Component.text("Buy your stall once, then spend money on extra listing slots and spotlight upgrades.", NamedTextColor.GOLD));
            }
        }
    }

    private void createAuction(Player player, long price, int hours) {
        player.closeInventory();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            player.sendMessage(Component.text("Hold the item you want to list.", NamedTextColor.RED));
            return;
        }
        if (this.plugin.getAuctionManager().createListing(player, held, price, hours)) {
            player.sendMessage(Component.text("Auction created.", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Could not create that auction.", NamedTextColor.RED));
        }
    }

    private String findAction(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta() || stack.getItemMeta().lore() == null) {
            return null;
        }
        List<Component> lore = stack.getItemMeta().lore();
        Component last = lore.get(lore.size() - 1);
        String text = PlainTextComponentSerializer.plainText().serialize(last);
        return text.startsWith("ce:") || text.startsWith("ah:") ? text : null;
    }
}
