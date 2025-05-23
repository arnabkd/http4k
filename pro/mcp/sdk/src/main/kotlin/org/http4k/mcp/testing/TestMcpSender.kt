package org.http4k.mcp.testing

import org.http4k.core.PolyHandler
import org.http4k.core.Request
import org.http4k.core.Status.Companion.ACCEPTED
import org.http4k.format.renderRequest
import org.http4k.format.renderResult
import org.http4k.mcp.model.McpMessageId
import org.http4k.mcp.protocol.messages.ClientMessage
import org.http4k.mcp.protocol.messages.McpRpc
import org.http4k.mcp.util.McpJson
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TestMcpSender(private val poly: PolyHandler, private val messageRequest: AtomicReference<Request>) {

    private var id = AtomicInteger(0)

    operator fun invoke(hasMethod: McpRpc, input: ClientMessage.Request) {
        this(with(McpJson) {
            compact(renderRequest(hasMethod.Method.value, asJsonObject(input), number(id.incrementAndGet())))
        })
    }

    operator fun invoke(input: ClientMessage.Response, messageId: McpMessageId) {
        this(with(McpJson) {
            compact(renderResult(asJsonObject(input), number(messageId.value)))
        })
    }

    operator fun invoke(hasMethod: McpRpc, input: ClientMessage.Notification) {
        this(with(McpJson) {
            compact(renderRequest(hasMethod.Method.value, asJsonObject(input), number(id.incrementAndGet())))
        })
    }

    operator fun invoke(body: String) {
        val response = poly.http!!(messageRequest.get().body(body))
        require(response.status == ACCEPTED, { "Failed to send MCP request: ${response.status}" })
    }
}
