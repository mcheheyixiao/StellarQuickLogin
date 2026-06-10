package fr.xephi.authme.api.v3;

import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AuthMeApi {

    private static final AuthMeApi INSTANCE = new AuthMeApi();

    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> registeredPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicInteger forceLoginCalls = new AtomicInteger();
    private volatile CountDownLatch forceLoginLatch = new CountDownLatch(0);

    public static AuthMeApi getInstance() {
        return INSTANCE;
    }

    public static void reset() {
        INSTANCE.authenticatedPlayers.clear();
        INSTANCE.registeredPlayers.clear();
        INSTANCE.forceLoginCalls.set(0);
        INSTANCE.forceLoginLatch = new CountDownLatch(0);
    }

    public static void registerPlayer(String playerName) {
        INSTANCE.registeredPlayers.add(playerName);
    }

    public static void expectForceLoginCalls(int expectedCalls) {
        INSTANCE.forceLoginCalls.set(0);
        INSTANCE.forceLoginLatch = new CountDownLatch(expectedCalls);
    }

    public static boolean awaitExpectedForceLogins(long timeout, TimeUnit unit) throws InterruptedException {
        return INSTANCE.forceLoginLatch.await(timeout, unit);
    }

    public static int forceLoginCalls() {
        return INSTANCE.forceLoginCalls.get();
    }

    public boolean isAuthenticated(Player player) {
        return authenticatedPlayers.contains(player.getUniqueId());
    }

    public boolean isRegistered(String playerName) {
        return registeredPlayers.contains(playerName);
    }

    public void forceLogin(Player player) {
        authenticatedPlayers.add(player.getUniqueId());
        forceLoginCalls.incrementAndGet();
        forceLoginLatch.countDown();
    }
}
