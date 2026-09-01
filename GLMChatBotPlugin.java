/*
 * GLMChatBot / DeepSeekBridge Unified Plugin
 * Supports both modes:
 *   - bridgeUrl (legacy): POST to local bridge, which drives browser CDP
 *   - apiBase + apiKey (direct): call OpenAI-compatible API directly
 *
 * Config: config.json in plugin data folder
 *   bridgeUrl: "http://localhost:5117/chat" (optional, legacy)
 *   apiBase: "https://ai.rjk66.cn/v1" (optional, direct mode)
 *   apiKey: "sk-..." (optional, direct mode)
 *   model: "deepseek-chat" (optional, default for direct mode)
 *   systemPrompt: "..." (optional)
 *   enableTeamChat: true/false
 *   enablePrivateChat: true/false
 */
package com.nousresearch.glmchatbot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.plugin.Plugin;
import emu.grasscutter.server.event.EventHandler;
import emu.grasscutter.server.event.HandlerPriority;
import emu.grasscutter.server.event.player.PlayerChatEvent;
import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GLMChatBotPlugin extends Plugin {
    private String bridgeUrl;
    private String apiBase;
    private String apiKey;
    private String model;
    private String systemPrompt;
    private boolean enableTeamChat;
    private boolean enablePrivateChat;
    private HttpClient httpClient;
    private final Gson gson = new Gson();
    private ExecutorService aiThreadPool;
    private final Map<Integer, JsonArray> conversationHistory = new ConcurrentHashMap<Integer, JsonArray>();
    private final Map<Integer, Long> lastMessageTime = new ConcurrentHashMap<Integer, Long>();
    private static final int MAX_HISTORY = 20;
    private static final int SERVER_UID = 99;
    private static final long COOLDOWN_MS = 2000;

    public void onLoad() {
        this.getLogger().info("[GLMChatBot] Loading GLM Chat Bot plugin...");
        this.loadConfiguration();
        System.setProperty("http.proxyHost", "");
        System.setProperty("https.proxyHost", "");
        System.setProperty("http.nonProxyHosts", "*");
        System.setProperty("https.nonProxyHosts", "*");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).proxy(new ProxySelector() {

            @Override
            public List<Proxy> select(URI uRI) {
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uRI, SocketAddress socketAddress, IOException iOException) {
            }
        }).build();
        this.aiThreadPool = Executors.newCachedThreadPool();
        this.getLogger().info("[GLMChatBot] Configuration loaded:");
        this.getLogger().info("[GLMChatBot]   Mode: " + (this.apiKey != null && !this.apiKey.isEmpty() ? "direct" : (this.bridgeUrl != null ? "bridge" : "none")));
        this.getLogger().info("[GLMChatBot]   API Base: " + this.apiBase);
        this.getLogger().info("[GLMChatBot]   Model: " + this.model);
        this.getLogger().info("[GLMChatBot]   TeamChat: " + this.enableTeamChat);
        this.getLogger().info("[GLMChatBot]   PrivateChat: " + this.enablePrivateChat);
    }

    public void onEnable() {
        this.getLogger().info("[GLMChatBot] Registering chat event handler...");
        EventHandler.newHandler((Plugin)this, PlayerChatEvent.class, this::onPlayerChat, (HandlerPriority)HandlerPriority.NORMAL, (boolean)false);
        this.getLogger().info("[GLMChatBot] GLM Chat Bot enabled! Players can now chat with AI.");
        this.getLogger().info("[GLMChatBot] Send a private message to the server account (or use team chat) to talk to GLM.");
    }

    public void onDisable() {
        this.getLogger().info("[GLMChatBot] GLM Chat Bot disabled.");
        if (this.aiThreadPool != null) {
            this.aiThreadPool.shutdown();
        }
        this.conversationHistory.clear();
        this.lastMessageTime.clear();
    }

    private void loadConfiguration() {
        this.bridgeUrl = "http://localhost:5117/chat";
        this.apiBase = "https://tokenrhythm.studio/v1";
        this.apiKey = System.getenv("HERMES_CUSTOM_GLM_API_KEY");
        this.model = "glm-5.2";
        this.systemPrompt = "\u4f60\u662f\u4e00\u4e2a\u539f\u795e\u6e38\u620f\u5185\u7684AI\u52a9\u624b\uff0c\u540d\u53ebLuna\u3002\u8bf7\u7528\u4e2d\u6587\u56de\u590d\uff0c\u8bed\u6c14\u53cb\u597d\u4eb2\u5207\uff0c\u50cf\u4e00\u4e2a\u6e38\u620f\u5185\u7684NPC\u89d2\u8272\u3002\u56de\u590d\u8981\u7b80\u6d01\u6709\u8da3\uff0c\u9002\u5408\u6e38\u620f\u5185\u804a\u5929\u7a97\u53e3\u663e\u793a\u3002\u4e0d\u8981\u8fc7\u5ea6\u601d\u8003\uff0c\u76f4\u63a5\u56de\u590d\u3002";
        this.enableTeamChat = true;
        this.enablePrivateChat = true;
        File file = new File(this.getDataFolder(), "config.json");
        if (file.exists()) {
            try {
                String string = Files.readString((Path)file.toPath());
                JsonObject jsonObject = JsonParser.parseString((String)string).getAsJsonObject();
                if (jsonObject.has("bridgeUrl")) {
                    this.bridgeUrl = jsonObject.get("bridgeUrl").getAsString();
                }
                if (jsonObject.has("apiBase")) {
                    this.apiBase = jsonObject.get("apiBase").getAsString();
                }
                if (jsonObject.has("apiKey") && !jsonObject.get("apiKey").getAsString().isEmpty()) {
                    this.apiKey = jsonObject.get("apiKey").getAsString();
                }
                if (jsonObject.has("model")) {
                    this.model = jsonObject.get("model").getAsString();
                }
                if (jsonObject.has("systemPrompt")) {
                    this.systemPrompt = jsonObject.get("systemPrompt").getAsString();
                }
                if (jsonObject.has("enableTeamChat")) {
                    this.enableTeamChat = jsonObject.get("enableTeamChat").getAsBoolean();
                }
                if (jsonObject.has("enablePrivateChat")) {
                    this.enablePrivateChat = jsonObject.get("enablePrivateChat").getAsBoolean();
                }
            }
            catch (Exception exception) {
                this.getLogger().warn("[GLMChatBot] Failed to load config.json, using defaults: " + exception.getMessage());
            }
        }
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            this.getLogger().warn("[GLMChatBot] No API key found! Set apiKey in config.json or HERMES_CUSTOM_GLM_API_KEY env var.");
        }
    }

    private boolean isDirectMode() {
        return this.apiKey != null && !this.apiKey.isEmpty();
    }

    private boolean isBridgeMode() {
        return !isDirectMode() && this.bridgeUrl != null && !this.bridgeUrl.isEmpty();
    }

    private String getModeName() {
        if (isDirectMode()) return "direct";
        if (isBridgeMode()) return "bridge";
        return "none";
    }

    private void onPlayerChat(PlayerChatEvent playerChatEvent) {
        if (playerChatEvent.getMessage() == null || playerChatEvent.getMessage().isEmpty()) {
            return;
        }
        Player player = playerChatEvent.getPlayer();
        if (player == null) {
            return;
        }
        String message = playerChatEvent.getMessage();
        int uid = player.getUid();
        String nickname = player.getNickname();
        Integer channelId = playerChatEvent.getChannelId();
        boolean isPrivate = this.enablePrivateChat && channelId == null;
        boolean isTeam = this.enableTeamChat && channelId != null;
        if (!isPrivate && !isTeam) {
            return;
        }
        if (uid == SERVER_UID) {
            return;
        }
        if (message.startsWith("!") || message.startsWith("/")) {
            return;
        }
        long now = System.currentTimeMillis();
        Long lastTime = this.lastMessageTime.get(uid);
        if (lastTime != null && now - lastTime < COOLDOWN_MS) {
            return;
        }
        this.lastMessageTime.put(uid, now);
        this.getLogger().info("[GLMChatBot] " + nickname + " (UID:" + uid + "): " + message);
        final String fMessage = message;
        final String fNickname = nickname;
        final int fUid = uid;
        this.aiThreadPool.submit(() -> {
            try {
                String reply;
                if (isDirectMode()) {
                    reply = this.callDirectApi(fUid, fNickname, fMessage);
                } else if (isBridgeMode()) {
                    reply = this.callBridgeApi(fMessage);
                } else {
                    reply = "[AI Error] No API key or bridge URL configured.";
                }
                if (reply != null && !reply.isEmpty()) {
                    this.getServer().getChatSystem().sendPrivateMessageFromServer(fUid, reply);
                    this.getLogger().info("[GLMChatBot] AI -> " + fNickname + " (UID:" + fUid + "): " + reply);
                }
            }
            catch (Exception exception) {
                this.getLogger().error("[GLMChatBot] Failed to get response: " + exception.getMessage(), (Throwable)exception);
                try {
                    this.getServer().getChatSystem().sendPrivateMessageFromServer(fUid, "[AI Error] \u8bf7\u6c42\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5: " + exception.getMessage());
                } catch (Exception e) {
                    // ignore send failure
                }
            }
        });
    }

    private String callBridgeApi(String message) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("message", message);
        String requestBody = this.gson.toJson((JsonElement)payload);
        HttpRequest httpRequest = HttpRequest.newBuilder().uri(URI.create(this.bridgeUrl)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString((String)requestBody)).timeout(Duration.ofSeconds(120L)).build();
        HttpResponse httpResponse = this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() != 200) {
            this.getLogger().error("[GLMChatBot] Bridge returned status " + httpResponse.statusCode() + ": " + (String)httpResponse.body());
            return "[AI Error] Bridge service error (HTTP " + httpResponse.statusCode() + ")";
        }
        String responseBody = (String)httpResponse.body();
        JsonObject jsonResponse = JsonParser.parseString((String)responseBody).getAsJsonObject();
        if (jsonResponse.has("error")) {
            return "[AI Error] " + jsonResponse.get("error").getAsString();
        }
        String reply = jsonResponse.has("reply") ? jsonResponse.get("reply").getAsString() : null;
        if (reply == null || reply.isEmpty()) {
            return "[AI Error] Empty reply from bridge.";
        }
        int uid = jsonResponse.has("session_id") ? jsonResponse.get("session_id").getAsInt() : -1;
        if (uid > 0) {
            this.conversationHistory.remove(uid);
        }
        return reply;
    }

    private String callDirectApi(int uid, String nickname, String message) {
        try {
            JsonArray history = this.conversationHistory.computeIfAbsent(uid, n -> {
                JsonArray arr = new JsonArray();
                JsonObject systemMsg = new JsonObject();
                systemMsg.addProperty("role", "system");
                systemMsg.addProperty("content", this.systemPrompt);
                arr.add((JsonElement)systemMsg);
                return arr;
            });
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", "[" + message + "] " + nickname);
            synchronized (history) {
                history.add((JsonElement)userMsg);
                while (history.size() > MAX_HISTORY + 1) {
                    history.remove(1);
                }
            }
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", this.model);
            requestBody.add("messages", (JsonElement)history);
            requestBody.addProperty("temperature", (Number)0.8);
            requestBody.addProperty("max_tokens", (Number)1024);
            requestBody.addProperty("stream", Boolean.valueOf(false));
            String requestBodyStr = this.gson.toJson((JsonElement)requestBody);
            String url = this.apiBase.endsWith("/chat/completions") ? this.apiBase : this.apiBase + "/chat/completions";
            HttpRequest httpRequest = HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json").header("Authorization", "Bearer " + this.apiKey).POST(HttpRequest.BodyPublishers.ofString((String)requestBodyStr)).timeout(Duration.ofSeconds(30L)).build();
            HttpResponse httpResponse = this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() != 200) {
                this.getLogger().error("[GLMChatBot] API returned status " + httpResponse.statusCode() + ": " + (String)httpResponse.body());
                return "AI\u670d\u52a1\u6682\u65f6\u4e0d\u53ef\u7528 (HTTP " + httpResponse.statusCode() + ")";
            }
            JsonObject jsonResponse = JsonParser.parseString((String)((String)httpResponse.body())).getAsJsonObject();
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject msgObj = choice.getAsJsonObject("message");
            String reply = null;
            if (msgObj.has("content") && !msgObj.get("content").isJsonNull()) {
                reply = msgObj.get("content").getAsString();
            }
            if ((reply == null || reply.isEmpty()) && msgObj.has("reasoning_content") && !msgObj.get("reasoning_content").isJsonNull()) {
                reply = msgObj.get("reasoning_content").getAsString();
            }
            if (reply == null || reply.isEmpty()) {
                return "[AI Error] Empty response from API.";
            }
            JsonObject assistantMsg = new JsonObject();
            assistantMsg.addProperty("role", "assistant");
            assistantMsg.addProperty("content", reply);
            synchronized (history) {
                history.add((JsonElement)assistantMsg);
                while (history.size() > MAX_HISTORY + 1) {
                    history.remove(1);
                }
            }
            return reply;
        }
        catch (Exception exception) {
            this.getLogger().error("[GLMChatBot] Error calling API: " + exception.getMessage(), (Throwable)exception);
            return "[AI Error] \u8bf7\u6c42\u5931\u8d25: " + exception.getMessage();
        }
    }

    public void clearHistory(int uid) {
        this.conversationHistory.remove(uid);
        this.getLogger().info("[GLMChatBot] Cleared conversation history for UID " + uid);
    }

    public void clearAllHistory() {
        this.conversationHistory.clear();
        this.getLogger().info("[GLMChatBot] Cleared all conversation histories.");
    }
}
