package cn.stellarworld.quicklogin.realtime;

import cn.stellarworld.quicklogin.ticket.QuickLoginTicket;

public record ParsedPreauthorizeMessage(
    ParseStatus status,
    String requestId,
    QuickLoginTicket ticket,
    String message
) {

    public boolean ok() {
        return status == ParseStatus.OK;
    }
}
