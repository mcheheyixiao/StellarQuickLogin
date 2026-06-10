package cn.stellarworld.quicklogin.listener;

import cn.stellarworld.quicklogin.StellarQuickLoginPlugin;
import cn.stellarworld.quicklogin.auth.AuthMeHook;
import cn.stellarworld.quicklogin.config.StellarQuickLoginConfig;
import cn.stellarworld.quicklogin.ticket.QuickLoginTicket;
import cn.stellarworld.quicklogin.ticket.QuickLoginTicketCache;
import cn.stellarworld.quicklogin.website.WebsiteClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import fr.xephi.authme.api.v3.AuthMeApi;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerJoinListenerTest {

    private static final Object UNSAFE = initUnsafe();

    private Server originalBukkitServer;
    private HttpServer websiteServer;
    private CountDownLatch requestLatch;
    private AtomicInteger requestCount;
    private AtomicReference<String> requestBody;

    @BeforeEach
    void setUp() throws Exception {
        originalBukkitServer = getBukkitServer();
        setBukkitServer(createServerProxy());
        AuthMeApi.reset();
        requestLatch = new CountDownLatch(1);
        requestCount = new AtomicInteger();
        requestBody = new AtomicReference<>();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (websiteServer != null) {
            websiteServer.stop(0);
        }
        AuthMeApi.reset();
        setBukkitServer(originalBukkitServer);
    }

    @Test
    void ticketedConsumeIncludesClientIpAndForcesLogin() throws Exception {
        startWebsiteServer(exchange -> {
            captureRequest(exchange);
            respond(exchange, 200, "{\"ok\":true,\"status\":\"ticket_consumed\",\"message\":\"accepted\"}");
        });

        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        StellarQuickLoginPlugin plugin = createPlugin(true);
        plugin.getTicketCache().put(new QuickLoginTicket("ticket-123", "Alice", playerUuid, 42L, System.currentTimeMillis() + 60_000L, "req-1"));
        AuthMeApi.registerPlayer("Alice");
        AuthMeApi.expectForceLoginCalls(1);

        invokeAttemptQuickLogin(plugin, createPlayer("Alice", playerUuid, "203.0.113.42"));

        assertTrue(requestLatch.await(3, TimeUnit.SECONDS));
        assertTrue(AuthMeApi.awaitExpectedForceLogins(3, TimeUnit.SECONDS));
        assertEquals(1, requestCount.get());
        assertTrue(requestBody.get().contains("\"token\":\"ticket-123\""));
        assertTrue(requestBody.get().contains("\"clientIp\":\"203.0.113.42\""));
        assertEquals(1, AuthMeApi.forceLoginCalls());
    }

    @Test
    void directCheckWithoutTicketUsesWebsiteWhenEnabled() throws Exception {
        startWebsiteServer(exchange -> {
            captureRequest(exchange);
            respond(exchange, 200, "{\"ok\":true,\"status\":\"active_window\",\"message\":\"accepted\"}");
        });

        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
        StellarQuickLoginPlugin plugin = createPlugin(true);
        AuthMeApi.registerPlayer("Alice");
        AuthMeApi.expectForceLoginCalls(1);

        invokeAttemptQuickLogin(plugin, createPlayer("Alice", playerUuid, "203.0.113.43"));

        assertTrue(requestLatch.await(3, TimeUnit.SECONDS));
        assertTrue(AuthMeApi.awaitExpectedForceLogins(3, TimeUnit.SECONDS));
        assertEquals(1, requestCount.get());
        assertFalse(requestBody.get().contains("\"token\""));
        assertTrue(requestBody.get().contains("\"clientIp\":\"203.0.113.43\""));
        assertEquals(1, AuthMeApi.forceLoginCalls());
    }

    @Test
    void directCheckWithoutTicketSkipsWebsiteWhenDisabled() throws Exception {
        startWebsiteServer(exchange -> {
            captureRequest(exchange);
            respond(exchange, 200, "{\"ok\":true,\"status\":\"active_window\",\"message\":\"accepted\"}");
        });

        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174002");
        StellarQuickLoginPlugin plugin = createPlugin(false);
        AuthMeApi.registerPlayer("Alice");

        invokeAttemptQuickLogin(plugin, createPlayer("Alice", playerUuid, "203.0.113.44"));

        assertFalse(requestLatch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(0, requestCount.get());
        assertEquals(0, AuthMeApi.forceLoginCalls());
    }

    @Test
    void directCheckOmitsClientIpWhenPlayerAddressMissing() throws Exception {
        startWebsiteServer(exchange -> {
            captureRequest(exchange);
            respond(exchange, 200, "{\"ok\":true,\"status\":\"ip_trusted\",\"message\":\"accepted\"}");
        });

        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174003");
        StellarQuickLoginPlugin plugin = createPlugin(true);
        AuthMeApi.registerPlayer("Alice");
        AuthMeApi.expectForceLoginCalls(1);

        invokeAttemptQuickLogin(plugin, createPlayer("Alice", playerUuid, null));

        assertTrue(requestLatch.await(3, TimeUnit.SECONDS));
        assertTrue(AuthMeApi.awaitExpectedForceLogins(3, TimeUnit.SECONDS));
        assertFalse(requestBody.get().contains("\"clientIp\""));
        assertEquals(1, AuthMeApi.forceLoginCalls());
    }

    @Test
    void legacyOkResponseStillForcesLogin() throws Exception {
        startWebsiteServer(exchange -> {
            captureRequest(exchange);
            respond(exchange, 200, "{\"ok\":true}");
        });

        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174004");
        StellarQuickLoginPlugin plugin = createPlugin(true);
        plugin.getTicketCache().put(new QuickLoginTicket("ticket-legacy", "Alice", playerUuid, 42L, System.currentTimeMillis() + 60_000L, "req-legacy"));
        AuthMeApi.registerPlayer("Alice");
        AuthMeApi.expectForceLoginCalls(1);

        invokeAttemptQuickLogin(plugin, createPlayer("Alice", playerUuid, "203.0.113.45"));

        assertTrue(requestLatch.await(3, TimeUnit.SECONDS));
        assertTrue(AuthMeApi.awaitExpectedForceLogins(3, TimeUnit.SECONDS));
        assertEquals(1, AuthMeApi.forceLoginCalls());
    }

    @Test
    void rejectedWebsiteResponseDoesNotForceLogin() throws Exception {
        startWebsiteServer(exchange -> {
            captureRequest(exchange);
            respond(exchange, 401, "{\"ok\":false,\"status\":\"not_found\",\"message\":\"denied\"}");
        });

        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174005");
        StellarQuickLoginPlugin plugin = createPlugin(true);
        plugin.getTicketCache().put(new QuickLoginTicket("ticket-denied", "Alice", playerUuid, 42L, System.currentTimeMillis() + 60_000L, "req-denied"));
        AuthMeApi.registerPlayer("Alice");

        invokeAttemptQuickLogin(plugin, createPlayer("Alice", playerUuid, "203.0.113.46"));

        assertTrue(requestLatch.await(3, TimeUnit.SECONDS));
        assertEquals(1, requestCount.get());
        assertEquals(0, AuthMeApi.forceLoginCalls());
    }

    private StellarQuickLoginPlugin createPlugin(boolean enabledDirectCheck) throws Exception {
        StellarQuickLoginPlugin plugin = allocateWithoutConstructor(StellarQuickLoginPlugin.class);
        QuickLoginTicketCache ticketCache = new QuickLoginTicketCache(System::currentTimeMillis, Duration.ofMinutes(5).toMillis());
        WebsiteClient websiteClient = new WebsiteClient(
            HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:" + websiteServer.getAddress().getPort() + "/internal/quick-login/consume"),
            "internal-secret",
            Duration.ofSeconds(3)
        );

        setField(plugin, "pluginConfig", createConfig(enabledDirectCheck));
        setField(plugin, "ticketCache", ticketCache);
        setField(plugin, "authMeHook", new AuthMeHook(plugin));
        setField(plugin, "websiteClient", websiteClient);
        setField(plugin, "quickLoginFunctional", true);
        return plugin;
    }

    private StellarQuickLoginConfig createConfig(boolean enabledDirectCheck) {
        return new StellarQuickLoginConfig(
            "survival-1",
            new StellarQuickLoginConfig.AuthMeSettings(true, 0L, true),
            new StellarQuickLoginConfig.WebsiteSettings(
                enabledDirectCheck,
                "http://127.0.0.1:" + websiteServer.getAddress().getPort() + "/internal/quick-login/consume",
                "internal-secret",
                3_000
            ),
            new StellarQuickLoginConfig.RealtimeSettings(false, "ws://127.0.0.1/ws/plugin", "CHANGE_ME", 5, 30),
            new StellarQuickLoginConfig.SecuritySettings(300, true, true, false),
            new StellarQuickLoginConfig.MessageSettings("success", "expired", "failed")
        );
    }

    private void invokeAttemptQuickLogin(StellarQuickLoginPlugin plugin, Player player) throws Exception {
        PlayerJoinListener listener = new PlayerJoinListener(plugin);
        Method method = PlayerJoinListener.class.getDeclaredMethod("attemptQuickLogin", Player.class);
        method.setAccessible(true);
        method.invoke(listener, player);
    }

    private Player createPlayer(String name, UUID uuid, String hostAddress) throws Exception {
        InetSocketAddress address = hostAddress == null
            ? null
            : new InetSocketAddress(InetAddress.getByName(hostAddress), 25565);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "isOnline" -> true;
            case "getName" -> name;
            case "getUniqueId" -> uuid;
            case "getAddress" -> address;
            case "sendMessage" -> null;
            case "toString" -> "PlayerProxy[" + name + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    private void startWebsiteServer(HttpHandler handler) throws IOException {
        websiteServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        websiteServer.createContext("/internal/quick-login/consume", handler);
        websiteServer.start();
    }

    private void captureRequest(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        requestBody.set(readBody(exchange));
        requestLatch.countDown();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Server createServerProxy() {
        Plugin authMePlugin = (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "AuthMe";
                default -> defaultValue(method.getReturnType());
            }
        );

        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
            PluginManager.class.getClassLoader(),
            new Class<?>[]{PluginManager.class},
            (proxy, method, args) -> {
                if ("getPlugin".equals(method.getName()) && args != null && args.length == 1 && "AuthMe".equals(args[0])) {
                    return authMePlugin;
                }
                return defaultValue(method.getReturnType());
            }
        );

        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(
            BukkitScheduler.class.getClassLoader(),
            new Class<?>[]{BukkitScheduler.class},
            (proxy, method, args) -> {
                if ("runTask".equals(method.getName()) && args != null && args.length >= 2 && args[1] instanceof Runnable runnable) {
                    runnable.run();
                }
                return defaultValue(method.getReturnType());
            }
        );

        return (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(),
            new Class<?>[]{Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPluginManager" -> pluginManager;
                case "getScheduler" -> scheduler;
                case "isPrimaryThread" -> true;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Server getBukkitServer() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        return (Server) field.get(null);
    }

    private static void setBukkitServer(Server server) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocateWithoutConstructor(Class<T> type) {
        try {
            return (T) UNSAFE.getClass().getMethod("allocateInstance", Class.class).invoke(UNSAFE, type);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to allocate test instance.", exception);
        }
    }

    private static Object initUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to access Unsafe for tests.", exception);
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
