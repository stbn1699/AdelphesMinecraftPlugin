package fr.esteban.adelphesminecraftplugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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

    private String botUrl;
    private String bridgeSecret;
    private String discordPrefix;

    private boolean relayMinecraftToDiscord;
    private boolean relayDiscordToMinecraft;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBridgeConfiguration();

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
                    "Impossible de démarrer le serveur HTTP du bridge.",
                    exception
            );

            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("AdelphesMinecraftPlugin activé.");
        getLogger().info("Minecraft -> Discord : " + this.botUrl);
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

        this.botUrl = getConfig().getString(
                "bot-url",
                "http://192.168.1.89:3009/minecraft/chat"
        );

        this.bridgeSecret = getConfig().getString("bridge-secret", "");

        this.discordPrefix = getConfig().getString(
                "discord-prefix",
                "[Discord]"
        );

        this.relayMinecraftToDiscord = getConfig().getBoolean(
                "relay-minecraft-to-discord",
                true
        );

        this.relayDiscordToMinecraft = getConfig().getBoolean(
                "relay-discord-to-minecraft",
                true
        );

        if (this.bridgeSecret.isBlank()
                || this.bridgeSecret.equals("REMPLACE_PAR_TA_CLE_SECRETE")) {
            throw new IllegalStateException(
                    "Configure bridge-secret dans plugins/AdelphesMinecraftPlugin/config.yml"
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        if (!this.relayMinecraftToDiscord) {
            return;
        }

        Player player = event.getPlayer();

        if (!player.hasPermission("adelphesminecraftplugin.chat")
                && !player.isOp()) {
            return;
        }

        String username = player.getName();
        String message = PLAIN_TEXT.serialize(event.message()).trim();

        if (message.isBlank()) {
            return;
        }

        sendMinecraftMessageToDiscord(username, message);
    }

    private void sendMinecraftMessageToDiscord(String username, String message) {
        String json = "{"
                + "\"username\":\"" + escapeJson(username) + "\","
                + "\"message\":\"" + escapeJson(message) + "\""
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(this.botUrl))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + this.bridgeSecret)
                .POST(HttpRequest.BodyPublishers.ofString(
                        json,
                        StandardCharsets.UTF_8
                ))
                .build();

        this.httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        ).thenAccept(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                getLogger().warning(
                        "Le bot Discord a répondu "
                                + response.statusCode()
                                + " : "
                                + response.body()
                );
            }
        }).exceptionally(error -> {
            getLogger().warning(
                    "Impossible d'envoyer le message Minecraft vers Discord : "
                            + error.getMessage()
            );
            return null;
        });
    }

    private void startHttpServer() throws IOException {
        int port = getConfig().getInt("http-port", 8080);

        this.httpServer = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port),
                0
        );

        this.httpServer.createContext(
                "/discord-message",
                this::handleDiscordMessage
        );

        this.httpServer.setExecutor(this.httpExecutor);
        this.httpServer.start();

        getLogger().info(
                "Discord -> Minecraft : http://0.0.0.0:"
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

            String authorization = exchange.getRequestHeaders()
                    .getFirst("Authorization");

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

    private static void sendJson(
            HttpExchange exchange,
            int status,
            String body
    ) throws IOException {
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