package cn.stellarworld.quicklogin.website;

public record ConsumeResponse(
    boolean ok,
    String status,
    String message
) {

    public static ConsumeResponse failure(String status, String message) {
        return new ConsumeResponse(false, status, message);
    }

    public boolean isExpired() {
        return "expired".equalsIgnoreCase(status);
    }
}
