package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;

public final class PingHandler implements CommandHandler {
    @Override
    public Response handle(Request request) {
        return Responses.string(request.requestId(), "PONG");
    }
}
