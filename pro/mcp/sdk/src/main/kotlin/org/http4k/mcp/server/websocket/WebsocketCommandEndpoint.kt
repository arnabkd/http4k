package org.http4k.mcp.server.websocket

import org.http4k.core.Request
import org.http4k.mcp.server.protocol.McpProtocol
import org.http4k.mcp.server.protocol.Session.Valid
import org.http4k.mcp.server.protocol.Session.Invalid
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsResponse
import org.http4k.websocket.WsStatus.Companion.REFUSE

/**
 * This Websocket handler can be bound to whatever path is required by the server with
 * ws("/path" bind <WsCommandHandler>
 */
fun WebsocketCommandEndpoint(protocol: McpProtocol<Websocket, Unit>) = { req: Request ->
    when (val session = protocol.validate(req)) {
        is Valid -> WsResponse { ws ->
            with(protocol) {
                assign(session, ws)
                ws.onMessage { receive(ws, session.sessionId, req.body(it.bodyString())) }
            }
        }

        is Invalid -> WsResponse { it.close(REFUSE) }
    }
}
