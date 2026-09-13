package fr.esteban.adelphesminecraftplugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public final class MinecraftDiscordBridgePlugin extends JavaPlugin implements Listener {

    private static final PlainTextComponentSerializer PLAIN_TEXT =
            PlainTextComponentSerializer.plainText();

    private HttpClient httpClient;
    private ExecutorService httpExecutor;
    private HttpServer httpServer;

    private String chatBotUrl;
    private String deathBotUrl;
    private String advancementBotUrl;
    private String bridgeSecret;
    private String discordPrefix;

    private boolean relayMinecraftToDiscord;
    private boolean relayDiscordToMinecraft;
    private boolean relayDeathsToDiscord;
    private boolean relayAdvancementsToDiscord;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            loadBridgeConfiguration();
        } catch (IllegalStateException exception) {
            getLogger().log(Level.SEVERE, exception.getMessage(), exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.httpExecutor = Executors.newFixedThreadPool(4);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(this.httpExecutor)
                .build();

        Bukkit.getPluginManager().registerEvents(this, this);

        try {
            startHttpServer();
        } catch (IOException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Impossible de démarrer le serveur HTTP Discord -> Minecraft.",
                    exception
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("AdelphesMinecraftPlugin activé.");
        getLogger().info("Chat Minecraft -> Discord : " + this.chatBotUrl);
        getLogger().info("Morts Minecraft -> Discord : " + this.deathBotUrl);
        getLogger().info("Advancements Minecraft -> Discord : " + this.advancementBotUrl);
    }

    @Override
    public void onDisable() {
        if (this.httpServer != null) {
            this.httpServer.stop(0);
            this.httpServer = null;
        }

        if (this.httpExecutor != null) {
            this.httpExecutor.shutdownNow();
            this.httpExecutor = null;
        }

        getLogger().info("AdelphesMinecraftPlugin désactivé.");
    }

    private void loadBridgeConfiguration() {
        reloadConfig();

        this.chatBotUrl = getConfig().getString(
                "chat-bot-url",
                "http://192.168.1.89:3009/minecraft/chat"
        );

        this.deathBotUrl = getConfig().getString(
                "death-bot-url",
                "http://192.168.1.89:3009/minecraft/death"
        );

        this.advancementBotUrl = getConfig().getString(
                "advancement-bot-url",
                "http://192.168.1.89:3009/minecraft/advancement"
        );

        this.bridgeSecret = getConfig().getString("bridge-secret", "");
        this.discordPrefix = getConfig().getString("discord-prefix", "[Discord]");

        this.relayMinecraftToDiscord = getConfig().getBoolean(
                "relay-minecraft-to-discord",
                true
        );

        this.relayDiscordToMinecraft = getConfig().getBoolean(
                "relay-discord-to-minecraft",
                true
        );

        this.relayDeathsToDiscord = getConfig().getBoolean(
                "relay-deaths-to-discord",
                true
        );

        this.relayAdvancementsToDiscord = getConfig().getBoolean(
                "relay-advancements-to-discord",
                true
        );

        if (this.bridgeSecret == null
                || this.bridgeSecret.isBlank()
                || this.bridgeSecret.equals("REMPLACE_PAR_TA_CLE_SECRETE")) {
            throw new IllegalStateException(
                    "Configure bridge-secret dans plugins/AdelphesMinecraftPlugin/config.yml"
            );
        }

        validateUrl("chat-bot-url", this.chatBotUrl);
        validateUrl("death-bot-url", this.deathBotUrl);
        validateUrl("advancement-bot-url", this.advancementBotUrl);
    }

    private void validateUrl(String configKey, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("La valeur " + configKey + " est manquante dans config.yml");
        }

        try {
            URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "La valeur " + configKey + " n'est pas une URL valide : " + url,
                    exception
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        if (!this.relayMinecraftToDiscord) {
            return;
        }

        Player player = event.getPlayer();

        if (!player.hasPermission("adelphesminecraftplugin.chat") && !player.isOp()) {
            return;
        }

        String username = player.getName();
        String message = PLAIN_TEXT.serialize(event.message()).trim();

        if (message.isBlank()) {
            return;
        }

        String json = "{"
                + "\"username\":\"" + escapeJson(username) + "\","
                + "\"message\":\"" + escapeJson(message) + "\""
                + "}";

        sendToDiscordBot("chat", this.chatBotUrl, json);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!this.relayDeathsToDiscord) {
            return;
        }

        Component deathMessageComponent = event.deathMessage();

        if (deathMessageComponent == null) {
            return;
        }

        String username = event.getPlayer().getName();
        String deathMessage = PLAIN_TEXT.serialize(deathMessageComponent).trim();

        if (deathMessage.isBlank()) {
            return;
        }

        String json = "{"
                + "\"username\":\"" + escapeJson(username) + "\","
                + "\"message\":\"" + escapeJson(deathMessage) + "\""
                + "}";

        sendToDiscordBot("mort", this.deathBotUrl, json);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAdvancementDone(PlayerAdvancementDoneEvent event) {
        if (!this.relayAdvancementsToDiscord) {
            return;
        }

        Advancement advancement = event.getAdvancement();

        if (advancement.getDisplay() == null) {
            return;
        }

        String advancementName = PLAIN_TEXT.serialize(
                advancement.getDisplay().displayName()
        ).trim();

        if (advancementName.isBlank()) {
            return;
        }

        String username = event.getPlayer().getName();

        String json = "{"
                + "\"username\":\"" + escapeJson(username) + "\","
                + "\"advancement\":\"" + escapeJson(advancementName) + "\""
                + "}";

        sendToDiscordBot("advancement", this.advancementBotUrl, json);
    }

    private void sendToDiscordBot(String eventType, String url, String json) {
        HttpRequest request;

        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + this.bridgeSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json,
                            StandardCharsets.UTF_8
                    ))
                    .build();
        } catch (IllegalArgumentException exception) {
            getLogger().warning(
                    "URL invalide pour l'endpoint " + eventType + " : " + url
            );
            return;
        }

        this.httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        ).thenAccept(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                getLogger().warning(
                        "Le bot Discord a répondu "
                                + response.statusCode()
                                + " pour "
                                + eventType
                                + " : "
                                + response.body()
                );
            }
        }).exceptionally(error -> {
            getLogger().warning(
                    "Impossible d'envoyer "
                            + eventType
                            + " vers le bot Discord : "
                            + error.getMessage()
            );
            return null;
        });
    }

    private void startHttpServer() throws IOException {
        int port = getConfig().getInt("http-port", 8080);
        String bindAddress = getConfig().getString("http-bind-address", "0.0.0.0");

        this.httpServer = HttpServer.create(
                new InetSocketAddress(bindAddress, port),
                0
        );

        this.httpServer.createContext(
                "/discord-message",
                this::handleDiscordMessage
        );

        this.httpServer.setExecutor(this.httpExecutor);
        this.httpServer.start();

        getLogger().info(
                "Discord -> Minecraft : http://"
                        + bindAddress
                        + ":"
                        + port
                        + "/discord-message"
        );
    }

    private void handleDiscordMessage(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            if (!this.relayDiscordToMinecraft) {
                sendJson(exchange, 403, "{\"error\":\"Bridge disabled\"}");
                return;
            }

            String authorization = exchange.getRequestHeaders().getFirst("Authorization");

            if (!("Bearer " + this.bridgeSecret).equals(authorization)) {
                sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            String requestBody = readRequestBody(exchange.getRequestBody());
            String username = readJsonString(requestBody, "username");
            String message = readJsonString(requestBody, "message");

            if (username == null || username.isBlank()
                    || message == null || message.isBlank()) {
                sendJson(
                        exchange,
                        400,
                        "{\"error\":\"username et message sont obligatoires\"}"
                );
                return;
            }

            username = sanitizeForMinecraft(username, 32);
            message = sanitizeForMinecraft(message, 256);

            if (username.isBlank() || message.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"Message invalide\"}");
                return;
            }

            String finalUsername = username;
            String finalMessage = message;

            Bukkit.getScheduler().runTask(this, () -> {
                Component output = Component.text(
                        this.discordPrefix
                                + " "
                                + finalUsername
                                + ": "
                                + finalMessage
                );

                Bukkit.broadcast(output);
            });

            getLogger().info(
                    "Discord -> Minecraft : "
                            + username
                            + ": "
                            + message
            );

            sendJson(exchange, 200, "{\"ok\":true}");
        } catch (Exception exception) {
            getLogger().log(
                    Level.WARNING,
                    "Erreur pendant le traitement d'un message Discord.",
                    exception
            );

            sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        } finally {
            exchange.close();
        }
    }

    private static String readRequestBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=utf-8"
        );

        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static String escapeJson(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }

        return result.toString();
    }

    private static String readJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIndex = json.indexOf(search);

        if (keyIndex == -1) {
            return null;
        }

        int colonIndex = json.indexOf(':', keyIndex + search.length());

        if (colonIndex == -1) {
            return null;
        }

        int openingQuoteIndex = json.indexOf('"', colonIndex + 1);

        if (openingQuoteIndex == -1) {
            return null;
        }

        StringBuilder value = new StringBuilder();
        boolean escaped = false;

        for (int index = openingQuoteIndex + 1; index < json.length(); index++) {
            char character = json.charAt(index);

            if (escaped) {
                switch (character) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    default -> value.append(character);
                }

                escaped = false;
                continue;
            }

            if (character == '\\') {
                escaped = true;
                continue;
            }

            if (character == '"') {
                return value.toString();
            }

            value.append(character);
        }

        return null;
    }

    private static String sanitizeForMinecraft(String value, int maxLength) {
        String cleaned = value
                .replaceAll("[\\r\\n]+", " ")
                .replace('§', ' ')
                .trim();

        return cleaned.substring(0, Math.min(cleaned.length(), maxLength));
    }
}