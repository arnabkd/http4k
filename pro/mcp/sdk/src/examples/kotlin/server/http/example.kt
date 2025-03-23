package server.http

import org.http4k.client.JavaHttpClient
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.filter.debug
import org.http4k.lens.contentType
import org.http4k.mcp.model.McpEntity
import org.http4k.mcp.protocol.ProtocolCapability
import org.http4k.mcp.protocol.ServerMetaData
import org.http4k.mcp.protocol.Version
import org.http4k.routing.mcpHttpStreaming
import org.http4k.server.Helidon
import org.http4k.server.asServer
import server.completions
import server.prompts
import server.resources
import server.tools

/**
 * This example demonstrates how to create an MCP server using the draft HTTP Streaming protocol.
 */
fun main() {
    val mcpServer = mcpHttpStreaming(
        ServerMetaData(
            McpEntity.of("http4k mcp via SSE"), Version.of("0.1.0"),
            *ProtocolCapability.entries.toTypedArray()
        ),
        prompts(),
        resources(),
        tools(),
        completions()
    )

    mcpServer.asServer(Helidon(4001)).start()

    // you can use straight HTTP to interact with the server
    JavaHttpClient().debug()(
        Request(POST, "http://localhost:4001/mcp")
            .contentType(APPLICATION_JSON)
            .body("""{"jsonrpc":"2.0","method":"tools/list","params":{"_meta":{}},"id":1}""")
    )
}
