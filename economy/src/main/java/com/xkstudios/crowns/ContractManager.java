package com.xkstudios.crowns.economy;

import com.xkstudios.crowns.CrownsPlugin;
import com.xkstudios.crowns.api.CrownsAPI;
import com.xkstudios.crowns.gui.CrownsMenuHolder;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ContractManager {
    private final CrownsPlugin plugin;
    private final Map<Integer, PlayerCommission> commissions = new ConcurrentHashMap<>();
    private final Map<Integer, ServerContract> contracts = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingActions = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public ContractManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        this.ensureTables();
        this.load();
        this.refreshContracts();
    }

    public void refreshContracts() {
        this.deleteExpiredContracts();
        this.load();
        this.ensureServerContracts();
    }

    public String getAwaitingAction(UUID playerId) {
        return this.awaitingActions.get(playerId);
    }

    public void clearAwaiting(UUID playerId) {
        this.awaitingActions.remove(playerId);
    }

    public void setAwaitingCommission(UUID playerId) {
        this.awaitingActions.put(playerId, "commission:create");
    }

    public String handleChatInput(Player player, String action, String message) {
        if (!"commission:create".equals(action)) {
            return "That market action is no longer active.";
        }
        String[] parts = message.trim().split("\\s+");
        if (parts.length < 2) {
            return "Type the commission as <amount> <total payout>, like 32 5s.";
        }
        try {
            int amount = Integer.parseInt(parts[0]);
            long payout = Currency.parse(parts[1]);
            return this.createCommissionFromHand(player, amount, payout)
                    ? "Commission posted to the board."
                    : "Could not create that commission. Hold the item you want and make sure you can afford the payout.";
        } catch (NumberFormatException exception) {
            return "Invalid commission format. Use values like 32 5s.";
        }
    }

    public List<PlayerCommission> getCommissions() {
        return this.commissions.values().stream()
                .sorted(Comparator.comparingLong(PlayerCommission::createdAt).reversed())
                .toList();
    }

    public List<ServerContract> getContracts() {
        return this.contracts.values().stream()
                .filter(contract -> !contract.isExpired())
                .sorted(Comparator.comparing(ServerContract::theme).thenComparingLong(ServerContract::payout).reversed())
                .toList();
    }

    public boolean createCommissionFromHand(Player player, int amount, long payout) {
        if (amount <= 0 || payout <= 0L) {
            return false;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            return false;
        }
        if (!this.plugin.getEconomy().withdraw(player, payout, "player-commissions", "Posted a commission for " + held.getType().name())) {
            return false;
        }
        Material material = held.getType();
        String displayName = this.prettyMaterial(material) + " Buy Order";
        long createdAt = System.currentTimeMillis();
        int id = this.insertCommission(player.getUniqueId(), player.getName(), material, displayName, amount, payout, createdAt);
        if (id == -1) {
            this.plugin.getEconomy().deposit(player, payout, "player-commissions", "Refunded failed commission post");
            return false;
        }
        this.commissions.put(id, new PlayerCommission(id, player.getUniqueId(), player.getName(), material, displayName, amount, payout, createdAt));
        CrownsAPI.publishAlert("economy", "New player commission",
                player.getName() + " posted a buy order for " + amount + "x " + this.prettyMaterial(material) + ".",
                null, false);
        return true;
    }

    public boolean fulfillCommission(Player player, int commissionId) {
        PlayerCommission commission = this.commissions.get(commissionId);
        if (commission == null || commission.requesterUuid().equals(player.getUniqueId())) {
            return false;
        }
        if (!player.getInventory().containsAtLeast(new ItemStack(commission.material()), commission.amount())) {
            return false;
        }
        this.removeFromInventory(player, commission.material(), commission.amount());
        this.plugin.getEconomy().deposit(player, commission.payout(), "player-commissions", "Fulfilled commission #" + commission.id());
        this.markCommissionStatus(commission.id(), "filled");
        this.commissions.remove(commission.id());
        this.plugin.getInboxManager().push(commission.requesterUuid(), commission.requesterName(), "commission_filled",
                "Commission completed",
                player.getName() + " fulfilled your " + commission.displayName() + ".");
        CrownsAPI.publishActivity("economy", "commission_filled", "Player commission fulfilled",
                player.getName() + " fulfilled " + commission.amount() + "x " + this.prettyMaterial(commission.material()) + ".", player.getUniqueId());
        return true;
    }

    public boolean cancelCommission(Player player, int commissionId) {
        PlayerCommission commission = this.commissions.get(commissionId);
        if (commission == null || !commission.requesterUuid().equals(player.getUniqueId())) {
            return false;
        }
        this.plugin.getEconomy().deposit(player, commission.payout(), "player-commissions", "Refunded cancelled commission #" + commission.id());
        this.markCommissionStatus(commission.id(), "cancelled");
        this.commissions.remove(commission.id());
        return true;
    }

    public boolean fulfillContract(Player player, int contractId) {
        ServerContract contract = this.contracts.get(contractId);
        if (contract == null || contract.isExpired()) {
            return false;
        }
        if (!player.getInventory().containsAtLeast(new ItemStack(contract.material()), contract.amount())) {
            return false;
        }
        this.removeFromInventory(player, contract.material(), contract.amount());
        this.plugin.getEconomy().deposit(player, contract.payout(), "server-contracts", "Completed server contract #" + contract.id());
        contract.setRemainingClaims(contract.remainingClaims() - 1);
        this.saveContract(contract);
        this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "contract_completed",
                "Server contract completed",
                "You completed " + contract.displayName() + " for " + Currency.format(contract.payout()) + ".");
        CrownsAPI.publishActivity("economy", "server_contract_completed", "Server contract completed",
                player.getName() + " completed " + contract.displayName() + ".", player.getUniqueId());
        if (contract.remainingClaims() <= 0) {
            this.deleteContract(contract.id());
            this.contracts.remove(contract.id());
            this.ensureServerContracts();
        }
        return true;
    }

    public void openCommissionsMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("ce-commissions", 54, Component.text("Player Commissions", NamedTextColor.AQUA));
        inventory.setItem(4, this.button(Material.NAME_TAG, "Post From Held Item", NamedTextColor.GREEN, List.of(
                Component.text("Hold the target item and type <amount> <payout> in chat.", NamedTextColor.GRAY),
                Component.text("Example: 32 5s", NamedTextColor.DARK_GRAY)
        ), "ce:commissions:post"));
        int slot = 19;
        for (PlayerCommission commission : this.getCommissions()) {
            if (slot >= 44) {
                break;
            }
            boolean own = commission.requesterUuid().equals(player.getUniqueId());
            inventory.setItem(slot, this.button(commission.material(), commission.displayName(), own ? NamedTextColor.YELLOW : NamedTextColor.AQUA, List.of(
                    Component.text("Requester: " + commission.requesterName(), NamedTextColor.GRAY),
                    Component.text("Need: " + commission.amount() + "x " + this.prettyMaterial(commission.material()), NamedTextColor.GRAY),
                    Component.text("Payout: " + Currency.format(commission.payout()), NamedTextColor.YELLOW),
                    Component.text(own ? "Click to cancel and refund it." : "Click to fulfill it from inventory.", NamedTextColor.GREEN)
            ), own ? "ce:commissions:cancel:" + commission.id() : "ce:commissions:fill:" + commission.id()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        if (this.getCommissions().isEmpty()) {
            inventory.setItem(22, this.info(Material.BARRIER, "No Player Commissions", List.of(
                    Component.text("Post a buy order from the item in your hand.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:open"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public void openContractsMenu(Player player) {
        Inventory inventory = CrownsMenuHolder.create("ce-contracts", 54, Component.text("Server Contracts", NamedTextColor.YELLOW));
        int slot = 10;
        for (ServerContract contract : this.getContracts()) {
            if (slot >= 44) {
                break;
            }
            inventory.setItem(slot, this.button(contract.material(), contract.displayName(), NamedTextColor.GOLD, List.of(
                    Component.text("Theme: " + this.prettyKey(contract.theme()), NamedTextColor.GRAY),
                    Component.text("Turn in: " + contract.amount(), NamedTextColor.GRAY),
                    Component.text("Payout: " + Currency.format(contract.payout()), NamedTextColor.YELLOW),
                    Component.text("Slots left: " + contract.remainingClaims(), NamedTextColor.GRAY),
                    Component.text("Click to complete from inventory.", NamedTextColor.AQUA)
            ), "ce:contracts:complete:" + contract.id()));
            slot = slot % 9 == 7 ? slot + 3 : slot + 1;
        }
        if (this.getContracts().isEmpty()) {
            inventory.setItem(22, this.info(Material.BARRIER, "No Active Contracts", List.of(
                    Component.text("Fresh curated contracts will rotate in soon.", NamedTextColor.GRAY)
            )));
        }
        inventory.setItem(49, this.button(Material.ARROW, "Back", NamedTextColor.GRAY, List.of(), "ce:menu:open"));
        this.fillBorder(inventory);
        player.openInventory(inventory);
    }

    public String getSummary() {
        return this.getCommissions().size() + " commissions, " + this.getContracts().size() + " contracts live.";
    }

    private void ensureTables() {
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economy_player_commissions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        requester_uuid TEXT NOT NULL,
                        requester_name TEXT NOT NULL,
                        material TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        payout INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'open',
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economy_server_contracts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        material TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        payout INTEGER NOT NULL,
                        remaining_claims INTEGER NOT NULL,
                        theme TEXT NOT NULL DEFAULT 'general',
                        refresh_at INTEGER NOT NULL
                    )
                    """);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Contracts] Table setup failed: " + exception.getMessage());
        }
    }

    private void load() {
        this.commissions.clear();
        this.contracts.clear();
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM economy_player_commissions WHERE status = 'open'")) {
            while (resultSet.next()) {
                Material material = Material.matchMaterial(resultSet.getString("material"));
                if (material == null) {
                    continue;
                }
                PlayerCommission commission = new PlayerCommission(
                        resultSet.getInt("id"),
                        UUID.fromString(resultSet.getString("requester_uuid")),
                        resultSet.getString("requester_name"),
                        material,
                        resultSet.getString("display_name"),
                        resultSet.getInt("amount"),
                        resultSet.getLong("payout"),
                        resultSet.getLong("created_at")
                );
                this.commissions.put(commission.id(), commission);
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Contracts] Load commissions failed: " + exception.getMessage());
        }
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM economy_server_contracts")) {
            while (resultSet.next()) {
                Material material = Material.matchMaterial(resultSet.getString("material"));
                if (material == null) {
                    continue;
                }
                ServerContract contract = new ServerContract(
                        resultSet.getInt("id"),
                        material,
                        resultSet.getString("display_name"),
                        resultSet.getInt("amount"),
                        resultSet.getLong("payout"),
                        resultSet.getInt("remaining_claims"),
                        resultSet.getString("theme"),
                        resultSet.getLong("refresh_at")
                );
                if (!contract.isExpired()) {
                    this.contracts.put(contract.id(), contract);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Contracts] Load contracts failed: " + exception.getMessage());
        }
    }

    private void deleteExpiredContracts() {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM economy_server_contracts WHERE refresh_at <= ? OR remaining_claims <= 0")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Contracts] Delete expired contracts failed: " + exception.getMessage());
        }
    }

    private void ensureServerContracts() {
        int slots = Math.max(1, this.plugin.getConfig().getInt("server-contracts.slots", 3));
        List<ContractTemplate> templates = this.loadContractTemplates();
        while (this.contracts.size() < slots && !templates.isEmpty()) {
            ContractTemplate template = templates.get(this.random.nextInt(templates.size()));
            long refreshAt = System.currentTimeMillis() + Math.max(1, this.plugin.getConfig().getInt("server-contracts.refresh-hours", 18)) * 3600000L;
            int id = this.insertContract(template.material(), template.displayName(), template.amount(), template.payout(), template.claims(), template.theme(), refreshAt);
            if (id == -1) {
                break;
            }
            this.contracts.put(id, new ServerContract(id, template.material(), template.displayName(), template.amount(), template.payout(), template.claims(), template.theme(), refreshAt));
        }
    }

    private int insertCommission(UUID requesterUuid, String requesterName, Material material, String displayName, int amount, long payout, long createdAt) {
        String sql = "INSERT INTO economy_player_commissions (requester_uuid, requester_name, material, display_name, amount, payout, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'open', ?)";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, requesterUuid.toString());
            statement.setString(2, requesterName);
            statement.setString(3, material.name());
            statement.setString(4, displayName);
            statement.setInt(5, amount);
            statement.setLong(6, payout);
            statement.setLong(7, createdAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Contracts] Insert commission failed: " + exception.getMessage());
        }
        return -1;
    }

    private int insertContract(Material material, String displayName, int amount, long payout, int claims, String theme, long refreshAt) {
        String sql = "INSERT INTO economy_server_contracts (material, display_name, amount, payout, remaining_claims, theme, refresh_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, material.name());
            statement.setString(2, displayName);
            statement.setInt(3, amount);
            statement.setLong(4, payout);
            statement.setInt(5, claims);
            statement.setString(6, theme);
            statement.setLong(7, refreshAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Contracts] Insert contract failed: " + exception.getMessage());
        }
        return -1;
    }

    private void markCommissionStatus(int commissionId, String status) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE economy_player_commissions SET status = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setInt(2, commissionId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Contracts] Update commission failed: " + exception.getMessage());
        }
    }

    private void saveContract(ServerContract contract) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "UPDATE economy_server_contracts SET remaining_claims = ? WHERE id = ?")) {
            statement.setInt(1, contract.remainingClaims());
            statement.setInt(2, contract.id());
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Contracts] Save contract failed: " + exception.getMessage());
        }
    }

    private void deleteContract(int contractId) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(
                "DELETE FROM economy_server_contracts WHERE id = ?")) {
            statement.setInt(1, contractId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Contracts] Delete contract failed: " + exception.getMessage());
        }
    }

    private List<ContractTemplate> loadContractTemplates() {
        List<ContractTemplate> templates = new ArrayList<>();
        List<?> rawTemplates = this.plugin.getConfig().getList("server-contracts.templates");
        if (rawTemplates != null) {
            for (Object rawTemplate : rawTemplates) {
                if (!(rawTemplate instanceof Map<?, ?> map)) {
                    continue;
                }
                Object materialValue = map.containsKey("material") ? map.get("material") : "IRON_BLOCK";
                Material material = Material.matchMaterial(String.valueOf(materialValue));
                if (material == null) {
                    continue;
                }
                Object displayValue = map.containsKey("display-name") ? map.get("display-name") : this.prettyMaterial(material) + " Contract";
                Object amountValue = map.containsKey("amount") ? map.get("amount") : 12;
                Object payoutValue = map.containsKey("payout") ? map.get("payout") : 800L;
                Object claimsValue = map.containsKey("claims") ? map.get("claims") : 1;
                Object themeValue = map.containsKey("theme") ? map.get("theme") : "general";
                String displayName = String.valueOf(displayValue);
                int amount = ((Number) amountValue).intValue();
                long payout = ((Number) payoutValue).longValue();
                int claims = ((Number) claimsValue).intValue();
                String theme = String.valueOf(themeValue);
                templates.add(new ContractTemplate(material, displayName, Math.max(1, amount), Math.max(1L, payout), Math.max(1, claims), theme));
            }
        }
        if (!templates.isEmpty()) {
            return templates;
        }
        return List.of(
                new ContractTemplate(Material.IRON_BLOCK, "Builder's Iron Bulk Order", 12, 1400L, 2, "builder"),
                new ContractTemplate(Material.FIREWORK_ROCKET, "Traveler's Rocket Consignment", 24, 1200L, 2, "traveler"),
                new ContractTemplate(Material.EXPERIENCE_BOTTLE, "Scholar's XP Shipment", 24, 1600L, 1, "prestige"),
                new ContractTemplate(Material.ENDER_PEARL, "Expedition Pearl Reserve", 16, 1350L, 2, "utility")
        );
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

    private String prettyMaterial(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private String prettyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "General";
        }
        String[] parts = raw.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').split(" ");
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

    private record ContractTemplate(Material material, String displayName, int amount, long payout, int claims, String theme) {
    }

    public record PlayerCommission(int id, UUID requesterUuid, String requesterName, Material material,
                                   String displayName, int amount, long payout, long createdAt) {
    }

    public static final class ServerContract {
        private final int id;
        private final Material material;
        private final String displayName;
        private final int amount;
        private final long payout;
        private int remainingClaims;
        private final String theme;
        private final long refreshAt;

        public ServerContract(int id, Material material, String displayName, int amount, long payout, int remainingClaims, String theme, long refreshAt) {
            this.id = id;
            this.material = material;
            this.displayName = displayName;
            this.amount = amount;
            this.payout = payout;
            this.remainingClaims = remainingClaims;
            this.theme = theme;
            this.refreshAt = refreshAt;
        }

        public int id() { return this.id; }
        public Material material() { return this.material; }
        public String displayName() { return this.displayName; }
        public int amount() { return this.amount; }
        public long payout() { return this.payout; }
        public int remainingClaims() { return this.remainingClaims; }
        public void setRemainingClaims(int remainingClaims) { this.remainingClaims = remainingClaims; }
        public String theme() { return this.theme; }
        public long refreshAt() { return this.refreshAt; }
        public boolean isExpired() { return System.currentTimeMillis() >= this.refreshAt; }
    }
}
