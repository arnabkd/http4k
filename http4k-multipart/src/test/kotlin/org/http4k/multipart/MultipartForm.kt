package org.http4k.multipart

import org.http4k.core.Body
import org.http4k.core.ContentType
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.*

sealed class Multipart {
    abstract val name: String
    abstract fun applyTo(builder: ValidMultipartFormBuilder): ValidMultipartFormBuilder


    data class FormField(override val name: String, val value: String) : Multipart() {
        override fun applyTo(builder: ValidMultipartFormBuilder): ValidMultipartFormBuilder = builder.field(name, value)
    }

    data class FormFile(override val name: String, val filename: String, val contentType: ContentType, val content: String) : Multipart() {
        override fun applyTo(builder: ValidMultipartFormBuilder): ValidMultipartFormBuilder = builder.file(name, filename, contentType.value, content)
    }
}


data class MultipartForm(val formParts: List<Multipart>, val boundary: String = UUID.randomUUID().toString()) {
    constructor(vararg formParts: Multipart, boundary: String = UUID.randomUUID().toString()) : this(formParts.toList(), boundary)

    fun toBody(): Body =
        Body(ByteBuffer.wrap(
            formParts.fold(ValidMultipartFormBuilder(boundary.toByteArray())) { memo, next ->
                next.applyTo(memo)
            }.build()))

    companion object {
        fun toMultipartForm(body: Body, boundary: String): MultipartForm {
            val form = StreamingMultipartFormParts.parse(boundary.toByteArray(StandardCharsets.UTF_8), body.stream, StandardCharsets.UTF_8)
            return MultipartForm(form.map {
                if (it.isFormField) Multipart.FormField(it.fieldName!!, it.contentsAsString)
                else Multipart.FormFile(it.fieldName!!, it.fileName!!, ContentType(it.contentType!!, ContentType.TEXT_HTML.directive), it.contentsAsString)
            }, boundary)
        }
    }
}