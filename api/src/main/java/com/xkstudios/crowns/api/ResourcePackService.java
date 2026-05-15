package com.xkstudios.crowns.api;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class ResourcePackService implements Listener {
    private static final Pattern DOWNLOAD_URL_PATTERN = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ASSET_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_FROM_ZIP = Pattern.compile("CrownsSuite-ResourcePack-([0-9][A-Za-z0-9_.-]*)\\.zip");

    private final JavaPlugin plugin;
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public ResourcePackService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("resource-pack.enabled", false);
    }

    public boolean isRequired() {
        return this.plugin.getConfig().getBoolean("resource-pack.required", true);
    }

    public String getVersion() {
        return this.plugin.getConfig().getString("resource-pack.version", "1.6.0");
    }

    public String getUrl() {
        return this.plugin.getConfig().getString("resource-pack.url",
                "https://github.com/All-The-Game-Servers/CrownsSuite/releases/download/resource-pack-1.6.0/CrownsSuite-ResourcePack-1.6.0.zip");
    }

    public String getSha1() {
        return this.plugin.getConfig().getString("resource-pack.sha1", "");
    }

    public String getDownloadUrl() {
        return this.plugin.getConfig().getString("resource-pack.download-url", this.getUrl());
    }

    public String getGitHubPageUrl() {
        return this.plugin.getConfig().getString("resource-pack.github-page-url",
                "https://github.com/All-The-Game-Servers/CrownsSuite/releases");
    }

    public String getPrompt() {
        return this.plugin.getConfig().getString("resource-pack.prompt", "Lowlight SMP requires the Crowns Suite resource pack for custom items and menus.");
    }

    public boolean allowClientPromptWithValhalla() {
        return this.plugin.getConfig().getBoolean("resource-pack.allow-client-prompt-with-valhalla", true);
    }

    public String getGitHubOwner() {
        return this.plugin.getConfig().getString("resource-pack.github.owner", "All-The-Game-Servers");
    }

    public String getGitHubRepo() {
        return this.plugin.getConfig().getString("resource-pack.github.repo", "CrownsSuite");
    }

    public String getGitHubReleaseTag() {
        return this.plugin.getConfig().getString("resource-pack.github.release-tag", "latest");
    }

    public String getZipAssetPattern() {
        return this.plugin.getConfig().getString("resource-pack.github.asset-pattern", "CrownsSuite-ResourcePack-*.zip");
    }

    public String getSha1AssetPattern() {
        return this.plugin.getConfig().getString("resource-pack.github.sha1-pattern", "CrownsSuite-ResourcePack-*.sha1");
    }

    public boolean isValhallaPresent() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("ValhallaMMO");
        if (plugin != null && plugin.isEnabled()) {
            return true;
        }
        plugin = Bukkit.getPluginManager().getPlugin("ValhallaMMO Premium");
        return plugin != null && plugin.isEnabled();
    }

    public boolean canShare() {
        return this.isEnabled() && !this.getUrl().isBlank();
    }

    public boolean canPromptPlayers() {
        return this.canShare();
    }

    public String getPromptBlockReason() {
        if (!this.isEnabled()) {
            return "CrownsAPI resource-pack support is disabled in config.";
        }
        if (this.getUrl().isBlank()) {
            return "A resource-pack URL is not configured yet.";
        }
        return "";
    }

    public Path getLocalPackPath() {
        return this.plugin.getDataFolder().toPath().resolve("resource-pack-cache")
                .resolve("CrownsSuite-ResourcePack-" + this.getVersion() + ".zip");
    }

    public boolean requestDownload(Player player) {
        if (player == null || !this.canPromptPlayers()) {
            return false;
        }
        Component prompt = Component.text(this.getPrompt(), NamedTextColor.GRAY);
        String sha1 = this.getSha1();
        if (!sha1.isBlank()) {
            player.setResourcePack(this.getUrl(), sha1, this.isRequired(), prompt);
        } else {
            player.setResourcePack(this.getUrl(), "", this.isRequired(), prompt);
        }
        player.sendMessage(Component.text(this.isRequired()
                ? "Crowns Suite resource pack is required for this server."
                : "Sent the Crowns Suite resource-pack prompt.", NamedTextColor.GREEN));
        return true;
    }

    public void downloadPackToServer(CommandSender sender) {
        if (!this.isEnabled()) {
            sender.sendMessage("CrownsAPI resource-pack support is disabled in config.");
            return;
        }
        String downloadUrl = this.getDownloadUrl();
        if (downloadUrl.isBlank()) {
            sender.sendMessage("A pack download URL is not configured yet.");
            return;
        }
        Path target = this.getLocalPackPath();
        sender.sendMessage("Downloading Crowns Suite resource pack to " + target + " ...");
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                Files.createDirectories(target.getParent());
                this.downloadTo(downloadUrl, target);
                String computed = this.sha1(target);
                if (!this.getSha1().isBlank() && !this.getSha1().equalsIgnoreCase(computed)) {
                    throw new IllegalStateException("SHA1 mismatch. Expected " + this.getSha1() + " but downloaded " + computed + ".");
                }
                long size = Files.size(target);
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    sender.sendMessage("Downloaded Crowns Suite resource pack to " + target + " (" + size + " bytes).");
                    if (this.isValhallaPresent()) {
                        sender.sendMessage("ValhallaMMO is installed. CrownsAPI is now the required pack owner; disable Valhalla's separate pack prompt or publish a merged pack.");
                    }
                    CrownsAPI.publishAlert("api", "Resource Pack Downloaded",
                            "Crowns Suite resource pack " + this.getVersion() + " was downloaded to the server cache.",
                            null, false);
                });
            } catch (Exception exception) {
                this.plugin.getLogger().warning("Failed to download resource pack: " + exception.getMessage());
                Bukkit.getScheduler().runTask(this.plugin, () ->
                        sender.sendMessage("Failed to download the Crowns Suite resource pack: " + exception.getMessage()));
            }
        });
    }

    public void refreshFromGitHubRelease(CommandSender sender) {
        if (!this.isEnabled()) {
            sender.sendMessage("CrownsAPI resource-pack support is disabled in config.");
            return;
        }
        sender.sendMessage("Resolving Crowns Suite resource pack from GitHub Releases...");
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                ReleaseAsset asset = this.resolveReleaseAsset();
                Path target = this.plugin.getDataFolder().toPath().resolve("resource-pack-cache")
                        .resolve("CrownsSuite-ResourcePack-" + asset.version() + ".zip");
                Files.createDirectories(target.getParent());
                this.downloadTo(asset.zipUrl(), target);
                String computed = this.sha1(target);
                if (!computed.equalsIgnoreCase(asset.sha1())) {
                    throw new IllegalStateException("SHA1 mismatch for GitHub Release pack. Expected " + asset.sha1() + " but downloaded " + computed + ".");
                }
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    this.updateConfig(asset.zipUrl(), asset.sha1(), asset.version());
                    FileConfiguration config = this.plugin.getConfig();
                    config.set("resource-pack.github.resolved-release-tag", asset.releaseTag());
                    config.set("resource-pack.github.last-refresh", Instant.now().toString());
                    this.plugin.saveConfig();
                    sender.sendMessage("Resolved Crowns resource pack " + asset.version() + " from GitHub Release " + asset.releaseTag() + ".");
                    sender.sendMessage("Downloaded and verified SHA1: " + asset.sha1());
                    CrownsAPI.publishAlert("api", "Resource Pack Refreshed",
                            "Crowns Suite resource pack " + asset.version() + " was resolved from GitHub Releases.",
                            null, false);
                });
            } catch (Exception exception) {
                this.plugin.getLogger().warning("Failed to refresh resource pack from GitHub Releases: " + exception.getMessage());
                Bukkit.getScheduler().runTask(this.plugin, () ->
                        sender.sendMessage("Failed to refresh from GitHub Releases: " + exception.getMessage()));
            }
        });
    }

    public void sendManualInstall(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(Component.text("Crowns Suite Resource Pack", NamedTextColor.GOLD));
        player.sendMessage(Component.text(this.getPrompt(), NamedTextColor.GRAY));
        player.sendMessage(Component.text("Version: " + this.getVersion(), NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Required: " + this.isRequired(), this.isRequired() ? NamedTextColor.RED : NamedTextColor.YELLOW));
        if (this.getUrl().isBlank()) {
            player.sendMessage(Component.text("A resource-pack URL is not configured yet.", NamedTextColor.RED));
            return;
        }
        Component link = Component.text("Open resource-pack download", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(this.getUrl()));
        player.sendMessage(link);
        if (!this.getSha1().isBlank()) {
            player.sendMessage(Component.text("SHA1: " + this.getSha1(), NamedTextColor.DARK_GRAY));
        }
        player.sendMessage(Component.text("GitHub: " + this.getGitHubPageUrl(), NamedTextColor.DARK_GRAY));
    }

    public boolean shareDownload(Player target, Player actor) {
        if (target == null || !this.requestDownload(target)) {
            return false;
        }
        if (actor != null && !actor.getUniqueId().equals(target.getUniqueId())) {
            actor.sendMessage(Component.text("Sent the resource-pack prompt to " + target.getName() + ".", NamedTextColor.GREEN));
        }
        CrownsAPI.publishAlert("api", "Resource Pack Shared",
                actor == null
                        ? "A resource-pack prompt was sent to " + target.getName() + "."
                        : actor.getName() + " sent a resource-pack prompt to " + target.getName() + ".",
                target.getUniqueId(), false);
        return true;
    }

    public void broadcastDownloadRequest() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.requestDownload(player);
        }
        CrownsAPI.publishAlert("api", "Resource Pack Prompt",
                "Crowns Suite resource-pack prompts were sent to all online players.",
                null, false);
    }

    public void broadcastManualInstall() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.sendManualInstall(player);
        }
        CrownsAPI.publishAlert("api", "Resource Pack Reminder",
                "Crowns Suite resource-pack details were broadcast to all online players.",
                null, false);
    }

    public void applyRequiredPack(CommandSender sender) {
        if (!this.canPromptPlayers()) {
            sender.sendMessage(this.getPromptBlockReason().isBlank() ? "The resource pack is not ready to apply." : this.getPromptBlockReason());
            return;
        }
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.requestDownload(player)) {
                count++;
            }
        }
        sender.sendMessage("Applied Crowns Suite resource pack prompt to " + count + " online players. Required: " + this.isRequired());
    }

    public String getStatusSummary() {
        if (!this.isEnabled()) {
            return "Required pack delivery is disabled.";
        }
        if (this.getUrl().isBlank()) {
            return "Pack delivery is enabled, but the download URL is not configured.";
        }
        String valhalla = this.isValhallaPresent() ? " ValhallaMMO is present; disable its separate pack prompt or use a merged pack." : "";
        return "Version " + this.getVersion() + " is " + (this.isRequired() ? "required" : "optional") + " via GitHub Releases." + valhalla;
    }

    public void updateConfig(String url, String sha1, String version) {
        FileConfiguration config = this.plugin.getConfig();
        config.set("resource-pack.url", url);
        config.set("resource-pack.download-url", url);
        config.set("resource-pack.sha1", sha1);
        config.set("resource-pack.version", version);
        this.plugin.saveConfig();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!this.isEnabled() || !this.isRequired()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.requestDownload(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!this.isEnabled() || !this.isRequired()) {
            return;
        }
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.DECLINED
                || status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD
                || status == PlayerResourcePackStatusEvent.Status.INVALID_URL
                || status == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD) {
            event.getPlayer().kick(Component.text("Lowlight SMP requires the Crowns Suite resource pack. Please rejoin and accept the pack download.", NamedTextColor.RED));
        }
    }

    private ReleaseAsset resolveReleaseAsset() throws Exception {
        String tag = this.getGitHubReleaseTag();
        String endpoint = tag.equalsIgnoreCase("latest")
                ? "https://api.github.com/repos/" + this.getGitHubOwner() + "/" + this.getGitHubRepo() + "/releases/latest"
                : "https://api.github.com/repos/" + this.getGitHubOwner() + "/" + this.getGitHubRepo() + "/releases/tags/" + URLEncoder.encode(tag, StandardCharsets.UTF_8);
        String json = this.downloadString(endpoint);
        String releaseTag = this.firstMatch(TAG_PATTERN, json, tag);
        List<Asset> assets = this.parseAssets(json);
        Asset zip = this.findAsset(assets, this.getZipAssetPattern(), ".zip");
        Asset sha = this.findAsset(assets, this.getSha1AssetPattern(), ".sha1");
        if (zip == null) {
            throw new IllegalStateException("No release asset matched " + this.getZipAssetPattern() + ".");
        }
        if (sha == null) {
            throw new IllegalStateException("No release SHA1 asset matched " + this.getSha1AssetPattern() + ".");
        }
        String shaText = this.downloadString(sha.url()).trim();
        Matcher shaMatcher = Pattern.compile("([a-fA-F0-9]{40})").matcher(shaText);
        if (!shaMatcher.find()) {
            throw new IllegalStateException("SHA1 asset did not contain a 40-character SHA1 hash.");
        }
        String version = this.versionFromZip(zip.name(), releaseTag);
        return new ReleaseAsset(releaseTag, version, zip.url(), shaMatcher.group(1).toLowerCase(Locale.ROOT));
    }

    private List<Asset> parseAssets(String json) {
        List<Asset> assets = new ArrayList<>();
        Matcher urlMatcher = DOWNLOAD_URL_PATTERN.matcher(json);
        while (urlMatcher.find()) {
            int start = Math.max(0, urlMatcher.start() - 2500);
            String beforeUrl = json.substring(start, urlMatcher.start());
            Matcher nameMatcher = ASSET_NAME_PATTERN.matcher(beforeUrl);
            String name = "";
            while (nameMatcher.find()) {
                name = nameMatcher.group(1);
            }
            if (!name.isBlank()) {
                assets.add(new Asset(name, urlMatcher.group(1).replace("\\/", "/")));
            }
        }
        return assets;
    }

    private Asset findAsset(List<Asset> assets, String wildcard, String suffix) {
        Pattern pattern = Pattern.compile("^" + this.wildcardToRegex(wildcard) + "$", Pattern.CASE_INSENSITIVE);
        return assets.stream()
                .filter(asset -> pattern.matcher(asset.name()).matches() || asset.name().toLowerCase(Locale.ROOT).endsWith(suffix))
                .findFirst()
                .orElse(null);
    }

    private String wildcardToRegex(String wildcard) {
        StringBuilder builder = new StringBuilder();
        for (char character : wildcard.toCharArray()) {
            if (character == '*') {
                builder.append(".*");
            } else {
                builder.append(Pattern.quote(String.valueOf(character)));
            }
        }
        return builder.toString();
    }

    private String versionFromZip(String name, String fallback) {
        Matcher matcher = VERSION_FROM_ZIP.matcher(name);
        return matcher.find() ? matcher.group(1) : fallback.replaceFirst("^v", "");
    }

    private String firstMatch(Pattern pattern, String input, String fallback) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private String downloadString(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "CrownsAPI/" + this.plugin.getDescription().getVersion())
                .header("Accept", "application/vnd.github+json, text/plain;q=0.9, */*;q=0.8")
                .GET()
                .build();
        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private void downloadTo(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "CrownsAPI/" + this.plugin.getDescription().getVersion())
                .GET()
                .build();
        HttpResponse<InputStream> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        try (InputStream input = response.body()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String sha1(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte b : digest.digest()) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private record Asset(String name, String url) {
    }

    private record ReleaseAsset(String releaseTag, String version, String zipUrl, String sha1) {
    }
}
