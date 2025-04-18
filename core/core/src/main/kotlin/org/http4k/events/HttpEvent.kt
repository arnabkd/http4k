package org.http4k.events

import org.http4k.core.HttpTransaction
import org.http4k.core.Method
import org.http4k.core.Status
import org.http4k.core.Uri
import org.http4k.events.ProtocolEvent.Incoming
import org.http4k.events.ProtocolEvent.Outgoing
import org.http4k.routing.RoutedMessage

object HttpEvent {

    fun Incoming(
        uri: Uri,
        method: Method,
        status: Status,
        latency: Long,
        xUriTemplate: String,
    ) = Incoming(uri, method, status, latency, xUriTemplate, "http")

    fun Incoming(tx: HttpTransaction) = Incoming(
        tx.request.uri,
        tx.request.method,
        tx.response.status,
        tx.duration.toMillis(),
        if (tx.request is RoutedMessage && tx.request.xUriTemplate != null)
            tx.request.xUriTemplate.toString() else tx.request.uri.path.trimStart('/')
    )

    fun Outgoing(
        uri: Uri,
        method: Method,
        status: Status,
        latency: Long,
        xUriTemplate: String,
    ) = Outgoing(uri, method, status, latency, xUriTemplate, "http")

    fun Outgoing(tx: HttpTransaction) = Outgoing(
        tx.request.uri,
        tx.request.method,
        tx.response.status,
        tx.duration.toMillis(),
        if (tx.response is RoutedMessage && tx.response.xUriTemplate != null)
            tx.response.xUriTemplate.toString() else tx.request.uri.path.trimStart('/')
    )
}
