package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;

public interface CommandHandler {
    Response handle(Request request);
}
