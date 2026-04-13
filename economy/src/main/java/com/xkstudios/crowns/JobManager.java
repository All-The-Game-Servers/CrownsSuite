package com.xkstudios.crowns.economy;

import com.xkstudios.crowns.CrownsPlugin;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class JobManager {
    private final CrownsPlugin plugin;
    private final Random random = new Random();

    public JobManager(CrownsPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        this.refreshJobs();
    }

    public void refreshJobs() {
        if (!this.plugin.getConfig().getBoolean("jobs.enabled", true)) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Statement statement = this.plugin.getDataManager().getConnection().createStatement()) {
            statement.executeUpdate("DELETE FROM jobs WHERE expires_at <= " + now);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Jobs] Cleanup failed: " + exception.getMessage());
        }

        int slots = Math.max(1, this.plugin.getConfig().getInt("jobs.slots", 5));
        int available = this.getAvailableJobs().size();
        for (int index = available; index < slots; index++) {
            JobTemplate template = this.pickTemplate();
            if (template == null) {
                break;
            }
            int amount = this.vary(template.amount(), Math.max(1, template.amount() / 4));
            long reward = Math.max(1L, template.reward() + this.random.nextInt(81) - 40L);
            long expiresAt = now + this.getRefreshHours() * 3600000L;
            this.insertJob(template, amount, reward, expiresAt);
        }
    }

    public List<Job> getAvailableJobs() {
        List<Job> jobs = new ArrayList<>();
        String sql = "SELECT * FROM jobs WHERE claimed_by IS NULL AND expires_at > ? ORDER BY reward DESC, id ASC";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    jobs.add(this.readJob(resultSet));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Jobs] Load available failed: " + exception.getMessage());
        }
        return jobs;
    }

    public List<Job> getClaimedJobs(UUID playerUuid) {
        List<Job> jobs = new ArrayList<>();
        String sql = "SELECT * FROM jobs WHERE claimed_by = ? AND expires_at > ? ORDER BY expires_at ASC, id ASC";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setLong(2, System.currentTimeMillis());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    jobs.add(this.readJob(resultSet));
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Jobs] Load claimed failed: " + exception.getMessage());
        }
        return jobs;
    }

    public boolean claimJob(int jobId, UUID playerUuid) {
        String sql = "UPDATE jobs SET claimed_by = ? WHERE id = ? AND claimed_by IS NULL AND expires_at > ?";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, jobId);
            statement.setLong(3, System.currentTimeMillis());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Jobs] Claim failed: " + exception.getMessage());
            return false;
        }
    }

    public boolean completeJob(Player player, int jobId) {
        Job job = this.getJob(jobId);
        if (job == null || job.isExpired() || !player.getUniqueId().toString().equalsIgnoreCase(job.claimedBy())) {
            return false;
        }
        Material material = Material.matchMaterial(job.target());
        if (material == null || !player.getInventory().containsAtLeast(new ItemStack(material), job.amount())) {
            return false;
        }
        this.removeFromInventory(player, material, job.amount());
        this.plugin.getEconomy().deposit(player, job.reward(), "job-rewards", "Completed job #" + job.id());
        this.plugin.getInboxManager().push(player.getUniqueId(), player.getName(), "job_completed",
                "Contract complete: " + Currency.format(job.reward()),
                "You completed " + job.description() + ".");
        this.deleteJob(jobId);
        this.refreshJobs();
        return true;
    }

    public Job getJob(int jobId) {
        String sql = "SELECT * FROM jobs WHERE id = ?";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setInt(1, jobId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return this.readJob(resultSet);
                }
            }
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Jobs] Load failed: " + exception.getMessage());
        }
        return null;
    }

    private int getRefreshHours() {
        return Math.max(1, this.plugin.getConfig().getInt("jobs.refresh-hours", 24));
    }

    private void insertJob(JobTemplate template, int amount, long reward, long expiresAt) {
        String sql = "INSERT INTO jobs (description, type, target, amount, reward, expires_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement(sql)) {
            statement.setString(1, template.description().replace("{amount}", Integer.toString(amount)));
            statement.setString(2, template.type());
            statement.setString(3, template.target());
            statement.setInt(4, amount);
            statement.setLong(5, reward);
            statement.setLong(6, expiresAt);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Jobs] Insert failed: " + exception.getMessage());
        }
    }

    private void deleteJob(int jobId) {
        try (PreparedStatement statement = this.plugin.getDataManager().getConnection().prepareStatement("DELETE FROM jobs WHERE id = ?")) {
            statement.setInt(1, jobId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("[Jobs] Delete failed: " + exception.getMessage());
        }
    }

    private Job readJob(ResultSet resultSet) throws SQLException {
        return new Job(
                resultSet.getInt("id"),
                resultSet.getString("description"),
                resultSet.getString("type"),
                resultSet.getString("target"),
                resultSet.getInt("amount"),
                resultSet.getLong("reward"),
                resultSet.getLong("expires_at"),
                resultSet.getString("claimed_by")
        );
    }

    private JobTemplate pickTemplate() {
        List<JobTemplate> templates = this.loadTemplates();
        if (templates.isEmpty()) {
            return null;
        }
        return templates.get(this.random.nextInt(templates.size()));
    }

    private List<JobTemplate> loadTemplates() {
        List<JobTemplate> templates = new ArrayList<>();
        List<?> rawTemplates = this.plugin.getConfig().getList("jobs.templates");
        if (rawTemplates != null) {
            for (Object rawTemplate : rawTemplates) {
                if (!(rawTemplate instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                Object materialValue = map.containsKey("material") ? map.get("material") : "IRON_INGOT";
                Object typeValue = map.containsKey("type") ? map.get("type") : "deliver";
                Object amountValue = map.containsKey("amount") ? map.get("amount") : 24;
                Object rewardValue = map.containsKey("reward") ? map.get("reward") : 250L;
                Object descriptionValue = map.containsKey("description") ? map.get("description") : "Deliver {amount} " + materialValue.toString().replace('_', ' ');
                String target = materialValue.toString().toUpperCase();
                if (Material.matchMaterial(target) != null) {
                    templates.add(new JobTemplate(
                            typeValue.toString(),
                            target,
                            ((Number) amountValue).intValue(),
                            ((Number) rewardValue).longValue(),
                            descriptionValue.toString()
                    ));
                }
            }
        }
        if (!templates.isEmpty()) {
            return templates;
        }
        templates.add(new JobTemplate("deliver", "IRON_INGOT", 24, 300L, "Deliver {amount} Iron Ingots"));
        templates.add(new JobTemplate("deliver", "GOLD_INGOT", 16, 360L, "Deliver {amount} Gold Ingots"));
        templates.add(new JobTemplate("deliver", "COAL", 48, 220L, "Deliver {amount} Coal"));
        templates.add(new JobTemplate("deliver", "COPPER_INGOT", 32, 260L, "Deliver {amount} Copper Ingots"));
        templates.add(new JobTemplate("deliver", "BREAD", 24, 180L, "Deliver {amount} Bread Rations"));
        templates.add(new JobTemplate("deliver", "COD", 20, 240L, "Deliver {amount} Fresh Cod"));
        return templates;
    }

    private int vary(int base, int variance) {
        return Math.max(1, base + this.random.nextInt(variance * 2 + 1) - variance);
    }

    private void removeFromInventory(Player player, Material material, int amount) {
        int remaining = amount;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != material) {
                continue;
            }
            int taken = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - taken);
            remaining -= taken;
            if (remaining <= 0) {
                return;
            }
        }
    }

    private record JobTemplate(String type, String target, int amount, long reward, String description) {
    }

    public record Job(int id, String description, String type, String target, int amount, long reward, long expiresAt, String claimedBy) {
        public boolean isExpired() {
            return System.currentTimeMillis() >= this.expiresAt;
        }
    }
}
