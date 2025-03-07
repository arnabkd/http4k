package org.http4k.mcp.server.protocol

import org.http4k.mcp.model.CompletionStatus
import org.http4k.mcp.protocol.messages.McpRoot

/**
 * Handles protocol traffic for client provided roots.
 */
interface Roots {
    fun update(req: McpRoot.List.Response): CompletionStatus
}
