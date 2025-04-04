package org.http4k.mcp.server.protocol

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.get
import org.http4k.core.Request
import org.http4k.format.MoshiArray
import org.http4k.format.MoshiNode
import org.http4k.format.MoshiObject
import org.http4k.jsonrpc.ErrorMessage.Companion.InternalError
import org.http4k.jsonrpc.ErrorMessage.Companion.InvalidRequest
import org.http4k.jsonrpc.ErrorMessage.Companion.MethodNotFound
import org.http4k.jsonrpc.JsonRpcRequest
import org.http4k.jsonrpc.JsonRpcResult
import org.http4k.mcp.model.CompletionStatus
import org.http4k.mcp.model.CompletionStatus.Finished
import org.http4k.mcp.model.LogLevel
import org.http4k.mcp.model.McpMessageId
import org.http4k.mcp.protocol.McpException
import org.http4k.mcp.protocol.McpRpcMethod
import org.http4k.mcp.protocol.ServerMetaData
import org.http4k.mcp.protocol.messages.Cancelled
import org.http4k.mcp.protocol.messages.ClientMessage
import org.http4k.mcp.protocol.messages.McpCompletion
import org.http4k.mcp.protocol.messages.McpInitialize
import org.http4k.mcp.protocol.messages.McpLogging
import org.http4k.mcp.protocol.messages.McpPing
import org.http4k.mcp.protocol.messages.McpProgress
import org.http4k.mcp.protocol.messages.McpPrompt
import org.http4k.mcp.protocol.messages.McpResource
import org.http4k.mcp.protocol.messages.McpRoot
import org.http4k.mcp.protocol.messages.McpSampling
import org.http4k.mcp.protocol.messages.McpTool
import org.http4k.mcp.protocol.messages.ServerMessage
import org.http4k.mcp.protocol.messages.fromJsonRpc
import org.http4k.mcp.protocol.messages.toJsonRpc
import org.http4k.mcp.server.capability.CompletionCapability
import org.http4k.mcp.server.capability.PromptCapability
import org.http4k.mcp.server.capability.ResourceCapability
import org.http4k.mcp.server.capability.ServerCapability
import org.http4k.mcp.server.capability.ServerCompletions
import org.http4k.mcp.server.capability.ServerPrompts
import org.http4k.mcp.server.capability.ServerRequestProgress
import org.http4k.mcp.server.capability.ServerResources
import org.http4k.mcp.server.capability.ServerRoots
import org.http4k.mcp.server.capability.ServerSampling
import org.http4k.mcp.server.capability.ServerTools
import org.http4k.mcp.server.capability.ToolCapability
import org.http4k.mcp.server.protocol.ClientRequestContext.Stream
import org.http4k.mcp.server.protocol.ClientRequestContext.ToolCall
import org.http4k.mcp.server.protocol.ClientRequestTarget.Entity
import org.http4k.mcp.util.McpJson
import org.http4k.mcp.util.McpJson.asJsonObject
import org.http4k.mcp.util.McpJson.nullNode
import org.http4k.mcp.util.McpJson.parse
import org.http4k.mcp.util.McpNodeType
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Models the MCP protocol in terms of message handling and session management.
 */
class McpProtocol<Transport>(
    internal val metaData: ServerMetaData,
    private val sessions: Sessions<Transport>,
    private val tools: Tools = ServerTools(),
    private val resources: Resources = ServerResources(),
    private val prompts: Prompts = ServerPrompts(),
    private val completions: Completions = ServerCompletions(),
    private val sampling: Sampling = ServerSampling(Random),
    private val logger: Logger = ServerLogger(),
    private val roots: Roots = ServerRoots(),
    private val progress: RequestProgress = ServerRequestProgress(),
    private val random: Random = Random
) {
    constructor(
        serverMetaData: ServerMetaData,
        sessions: Sessions<Transport>,
        vararg capabilities: ServerCapability
    ) : this(
        serverMetaData,
        sessions,
        ServerTools(capabilities.flatMap { it }.filterIsInstance<ToolCapability>()),
        ServerResources(capabilities.flatMap { it }.filterIsInstance<ResourceCapability>()),
        ServerPrompts(capabilities.flatMap { it }.filterIsInstance<PromptCapability>()),
        ServerCompletions(capabilities.flatMap { it }.filterIsInstance<CompletionCapability>()),
    )

    private val clientRequests = ConcurrentHashMap<Session, ClientTracking>()

    fun receive(transport: Transport, session: Session, httpReq: Request): Result4k<McpNodeType, McpNodeType> {
        val rawPayload = runCatching { parse(httpReq.bodyString()) }.getOrElse { return error() }

        return when (rawPayload) {
            is MoshiArray -> {
                Success(
                    MoshiArray(
                        rawPayload.elements
                            .filterIsInstance<MoshiObject>()
                            .map { processMessage(it, transport, session, httpReq) }
                            .map { it.get() }
                    )
                )
            }

            is MoshiObject -> processMessage(rawPayload, transport, session, httpReq)
            else -> error()
        }
    }

    private fun ok() = Success(nullNode())
    private fun error() = Failure(nullNode())

    private fun processMessage(
        rawPayload: MoshiObject,
        transport: Transport,
        session: Session,
        httpReq: Request
    ): Result4k<McpNodeType, McpNodeType> {
        val payload = McpJson.fields(rawPayload).toMap()
        return when {
            payload["method"] != null -> {
                val jsonReq = JsonRpcRequest(McpJson, payload)

                when (McpRpcMethod.of(jsonReq.method)) {
                    McpInitialize.Method ->
                        sessions.respond(
                            transport,
                            session,
                            jsonReq.respondTo<McpInitialize.Request> {
                                assign(Stream(session), transport, httpReq)
                                handleInitialize(it, session)
                            })

                    McpCompletion.Method ->
                        sessions.respond(
                            transport,
                            session,
                            jsonReq.respondTo<McpCompletion.Request> { completions.complete(it, httpReq) }
                        )

                    McpPing.Method -> sessions.respond(
                        transport,
                        session,
                        jsonReq.respondTo<McpPing.Request> { ServerMessage.Response.Empty }
                    )

                    McpPrompt.Get.Method ->
                        sessions.respond(
                            transport,
                            session,
                            jsonReq.respondTo<McpPrompt.Get.Request> { prompts.get(it, httpReq) })

                    McpPrompt.List.Method ->
                        sessions.respond(
                            transport,
                            session,
                            jsonReq.respondTo<McpPrompt.List.Request> { prompts.list(it, httpReq) })

                    McpResource.Template.List.Method ->
                        sessions.respond(transport, session, jsonReq.respondTo<McpResource.Template.List.Request> {
                            resources.listTemplates(it, httpReq)
                        })

                    McpResource.List.Method ->
                        sessions.respond(transport, session, jsonReq.respondTo<McpResource.List.Request> {
                            resources.listResources(
                                it,
                                httpReq
                            )
                        })

                    McpResource.Read.Method ->
                        sessions.respond(
                            transport,
                            session,
                            jsonReq.respondTo<McpResource.Read.Request> { resources.read(it, httpReq) })

                    McpResource.Subscribe.Method -> {
                        val subscribeRequest = jsonReq.fromJsonRpc<McpResource.Subscribe.Request>()
                        resources.subscribe(session, subscribeRequest) {
                            sessions.respond(
                                transport,
                                session,
                                McpResource.Updated.Notification(subscribeRequest.uri).toJsonRpc(McpResource.Updated)
                            )
                        }
                        ok()
                    }

                    McpLogging.SetLevel.Method -> {
                        logger.setLevel(session, jsonReq.fromJsonRpc<McpLogging.SetLevel.Request>().level)
                        ok()
                    }

                    McpResource.Unsubscribe.Method -> {
                        resources.unsubscribe(session, jsonReq.fromJsonRpc())
                        ok()
                    }

                    McpInitialize.Initialized.Method -> ok()

                    Cancelled.Method -> ok()

                    McpProgress.Method -> ok()

                    McpRoot.Changed.Method -> {
                        val messageId = McpMessageId.random(random)
                        clientRequests[session]?.trackRequest(messageId) { roots.update(it.fromJsonRpc()) }
                        sessions.respond(
                            transport,
                            session,
                            McpRoot.List.Request().toJsonRpc(McpRoot.List, asJsonObject(messageId))
                        )
                        ok()
                    }

                    McpTool.Call.Method -> {
                        sessions.respond(
                            transport,
                            session,
                            jsonReq.respondTo<McpTool.Call.Request> {
                                val contextAndTarget = it._meta.progress?.let {
                                    ToolCall(it, session) to ClientRequestTarget.Request(it)
                                }
                                contextAndTarget?.let { (method, target) ->
                                    sessions.assign(method, transport, httpReq)
                                    progress.onProgress(target) {
                                        sessions.request(
                                            method,
                                            McpProgress.Notification(it.progress, it.total, it.progressToken)
                                                .toJsonRpc(McpProgress)
                                        )
                                    }
                                    sampling.onSampleClient(target) { req, id ->
                                        clientRequests[session]?.trackRequest(id) {
                                            sampling.receive(id, it.fromJsonRpc())
                                        }
                                        sessions.request(method, req.toJsonRpc(McpSampling, asJsonObject(id)))
                                    }

                                }
                                tools.call(it, httpReq)
                                    .also {
                                        if (contextAndTarget != null) {
                                            sampling.remove(contextAndTarget.second)
                                            progress.remove(contextAndTarget.second)
                                            sessions.end(contextAndTarget.first)
                                            ok()
                                        }
                                    }
                            }
                        )
                    }

                    McpTool.List.Method -> sessions.respond(
                        transport,
                        session,
                        jsonReq.respondTo<McpTool.List.Request> { tools.list(it, httpReq) }
                    )

                    else -> sessions.respond(transport, session, MethodNotFound.toJsonRpc(jsonReq.id))
                }
            }

            else -> {
                val jsonResult = JsonRpcResult(McpJson, payload)
                when {
                    jsonResult.isError() -> ok()
                    else -> with(McpJson) {
                        val id = jsonResult.id?.let { McpMessageId.parse(compact(it)) }
                        when (id) {
                            null -> error()
                            else -> clientRequests[session]?.processResult(id, jsonResult)?.let { ok() }
                                ?: error()
                        }
                    }
                }
            }
        }
    }

    fun handleInitialize(request: McpInitialize.Request, session: Session): McpInitialize.Response {
        val entity = (clientRequests[session] ?: ClientTracking(request).also { clientRequests[session] = it }).entity

        logger.subscribe(session, LogLevel.error) { level, logger, data ->
            sessions.request(
                Stream(session),
                McpLogging.LoggingMessage.Notification(level, logger, data).toJsonRpc(McpLogging.LoggingMessage)
            )
        }
        prompts.onChange(session) {
            sessions.request(Stream(session), McpPrompt.List.Changed.Notification.toJsonRpc(McpPrompt.List.Changed))
        }
        resources.onChange(session) {
            sessions.request(
                Stream(session),
                McpResource.List.Changed.Notification.toJsonRpc(McpResource.List.Changed)
            )
        }
        tools.onChange(session) {
            sessions.request(
                Stream(session),
                McpTool.List.Changed.Notification.toJsonRpc(McpTool.List.Changed)
            )
        }
        sampling.onSampleClient(Entity(entity)) { req, id ->
            clientRequests[session]?.trackRequest(id) {
                sampling.receive(id, it.fromJsonRpc())
            }
            sessions.request(Stream(session), req.toJsonRpc(McpSampling, asJsonObject(id)))
        }
        progress.onProgress(Entity(entity)) {
            sessions.request(Stream(session), it.toJsonRpc(McpProgress))
        }

        sessions.onClose(session) {
            prompts.remove(session)
            resources.remove(session)
            tools.remove(session)
            logger.unsubscribe(session)
            sampling.remove(Entity(entity))
            progress.remove(Entity(entity))
        }

        return McpInitialize.Response(metaData.entity, metaData.capabilities, metaData.protocolVersion)
    }

    fun retrieveSession(req: Request) = sessions.retrieveSession(req)

    fun end(method: ClientRequestContext) {
        if (method is Stream) clientRequests.remove(method.session)
        sessions.end(method)
    }

    fun assign(method: ClientRequestContext, transport: Transport, connectRequest: Request) =
        sessions.assign(method, transport, connectRequest)

    fun transportFor(session: Session) = sessions.transportFor(session)

    private class ClientTracking(initialize: McpInitialize.Request) {
        val entity = initialize.clientInfo.name
        private val calls = ConcurrentHashMap<McpMessageId, (JsonRpcResult<McpNodeType>) -> CompletionStatus>()

        fun trackRequest(id: McpMessageId, callback: (JsonRpcResult<McpNodeType>) -> CompletionStatus) {
            calls[id] = callback
        }

        fun processResult(id: McpMessageId, result: JsonRpcResult<MoshiNode>) {
            val done = calls[id]?.invoke(result) ?: Finished
            if (done == Finished) calls.remove(id)
        }
    }
}

private inline fun <reified IN : ClientMessage.Request> JsonRpcRequest<McpNodeType>.respondTo(fn: (IN) -> ServerMessage.Response) =
    runCatching { fromJsonRpc<IN>() }
        .mapCatching(fn)
        .map { it.toJsonRpc(id) }
        .recover {
            when (it) {
                is McpException -> it.error.toJsonRpc(id)
                else -> InternalError.toJsonRpc(id)
            }
        }
        .getOrElse { InvalidRequest.toJsonRpc(id) }
