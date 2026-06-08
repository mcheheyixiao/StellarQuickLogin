package cn.stellarworld.quicklogin.realtime;

public enum ParseStatus {
    OK("cached"),
    INVALID_PAYLOAD("invalid_payload"),
    SERVER_MISMATCH("server_mismatch"),
    EXPIRED("expired");

    private final String wireStatus;

    ParseStatus(String wireStatus) {
        this.wireStatus = wireStatus;
    }

    public String wireStatus() {
        return wireStatus;
    }
}
