package org.http4k.mcp.server.jsonrpc

import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.mcp.protocol.ClientCapabilities
import org.http4k.mcp.protocol.VersionedMcpEntity
import org.http4k.mcp.protocol.messages.McpInitialize
import org.http4k.mcp.server.protocol.McpProtocol
import org.http4k.mcp.server.protocol.Session.Invalid
import org.http4k.mcp.server.protocol.Session.Valid

/**
 * This SSE handler can be bound to whatever path is required by the server with
 * routes("/path" bind <HttpCommandHandler>
 */
fun JsonRpcCommandEndpoint(protocol: McpProtocol<Unit, Response>) = { req: Request ->
    when (val session = protocol.validate(req)) {
        is Valid -> {
            protocol.handleInitialize(
                McpInitialize.Request(
                    VersionedMcpEntity(protocol.metaData.entity.name, protocol.metaData.entity.version),
                    ClientCapabilities()
                ),
                session.sessionId
            )

            protocol.receive(Unit, session.sessionId, req)
        }

        is Invalid -> Response(BAD_REQUEST)
    }
}
