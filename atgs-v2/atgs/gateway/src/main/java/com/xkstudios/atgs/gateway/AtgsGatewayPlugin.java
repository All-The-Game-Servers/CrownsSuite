package com.xkstudios.atgs.gateway;

import com.google.gson.Gson;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import javax.inject.Inject;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

@Plugin(
    id = "atgs-gateway",
    name = "ATGS Gateway",
    version = "1.0.0",
    authors = {"XKStudios"}
)
public final class AtgsGatewayPlugin {
  private final Logger logger;
  private final HttpClient httpClient;
  private final Gson gson;
  private final PluginConfig config;

  @Inject
  public AtgsGatewayPlugin(Logger logger, @DataDirectory Path dataDirectory) {
    this.logger = logger;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.gson = new Gson();
    this.config = loadConfig(dataDirectory);
    this.logger.info("ATGS Gateway plugin loaded for {}", this.config.panelBaseUrl);
  }

  @Subscribe
  public EventTask onPreLogin(PreLoginEvent event) {
    return EventTask.async(() -> {
      try {
        GatewayStatus status = fetchStatus("/internal/gateway/status");
        if (status == null || status.ready) {
          return;
        }

        wakeServer();
        long deadline = System.currentTimeMillis() + config.timeoutMs;
        while (System.currentTimeMillis() < deadline) {
          Thread.sleep(config.pollMs);
          status = fetchStatus("/internal/gateway/status");
          if (status != null && status.ready) {
            return;
          }
        }

        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
            Component.text("Server is waking up. Rejoin in a few seconds.")
        ));
      } catch (Exception exception) {
        logger.warn("Wake flow failed: {}", exception.getMessage());
        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
            Component.text("Server control plane is unavailable. Please try again shortly.")
        ));
      }
    });
  }

  @Subscribe
  public EventTask onProxyPing(ProxyPingEvent event) {
    return EventTask.async(() -> {
      try {
        GatewayStatus status = fetchStatus("/internal/gateway/ping");
        if (status == null) {
          return;
        }

        event.setPing(event.getPing().asBuilder()
            .description(Component.text(status.motd != null ? status.motd : "ATGS Gateway"))
            .onlinePlayers(status.players)
            .maximumPlayers(status.maxPlayers > 0 ? status.maxPlayers : 50)
            .build());
      } catch (Exception exception) {
        logger.debug("Ping update failed: {}", exception.getMessage());
      }
    });
  }

  private void wakeServer() throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(config.panelBaseUrl + "/internal/gateway/wake"))
        .header("x-gateway-secret", config.sharedSecret)
        .POST(HttpRequest.BodyPublishers.noBody())
        .timeout(Duration.ofSeconds(20))
        .build();
    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private GatewayStatus fetchStatus(String route) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(config.panelBaseUrl + route))
        .header("x-gateway-secret", config.sharedSecret)
        .GET()
        .timeout(Duration.ofSeconds(10))
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 300) {
      return null;
    }
    return gson.fromJson(response.body(), GatewayStatus.class);
  }

  private PluginConfig loadConfig(Path dataDirectory) {
    try {
      Files.createDirectories(dataDirectory);
      Path configFile = dataDirectory.resolve("atgs-gateway.properties");
      Properties properties = new Properties();
      if (Files.exists(configFile)) {
        try (Reader reader = Files.newBufferedReader(configFile)) {
          properties.load(reader);
        }
      }
      return new PluginConfig(
          properties.getProperty("panelBaseUrl", "http://panel:8080"),
          properties.getProperty("sharedSecret", "change-me-too"),
          Long.parseLong(properties.getProperty("pollMs", "2000")),
          Long.parseLong(properties.getProperty("timeoutMs", "45000"))
      );
    } catch (Exception exception) {
      logger.warn("Falling back to default gateway config: {}", exception.getMessage());
      return new PluginConfig("http://panel:8080", "change-me-too", 2000L, 45000L);
    }
  }

  private record PluginConfig(String panelBaseUrl, String sharedSecret, long pollMs, long timeoutMs) {}

  private static final class GatewayStatus {
    boolean ready;
    int players;
    int maxPlayers;
    String motd;
  }
}
