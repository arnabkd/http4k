package org.http4k.mcp.server.http

import org.http4k.core.Method.DELETE
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.mcp.server.protocol.McpProtocol
import org.http4k.mcp.server.protocol.Session.Invalid
import org.http4k.mcp.server.protocol.Session.Valid
import org.http4k.routing.routes
import org.http4k.sse.Sse
import org.http4k.sse.SseMessage

fun HttpCommandEndpoint(protocol: McpProtocol<Sse, Response>) =
    routes(
        POST to { req ->
            when (val session = protocol.validate(req)) {
                is Valid -> protocol.receive(FakeSse(req), session.sessionId, req)

                is Invalid -> Response(BAD_REQUEST)
            }
        },
        DELETE to { req -> protocol.end(protocol.validate(req)) }
    )

private class FakeSse(override val connectRequest: Request) : Sse {
    override fun send(message: SseMessage) = this
    override fun close() {}
    override fun onClose(fn: () -> Unit): Sse = this
}

