package cn.stellarworld.quicklogin.website;

public record ConsumeRequest(
    String token,
    String playerName,
    String playerUuid,
    String serverId,
    String clientIp
) {
}
