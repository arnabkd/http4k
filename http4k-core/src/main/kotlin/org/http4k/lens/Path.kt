package org.http4k.lens

import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.encode
import org.http4k.core.with
import org.http4k.lens.Header.X_URI_TEMPLATE
import org.http4k.lens.ParamMeta.BooleanParam
import org.http4k.lens.ParamMeta.NumberParam
import org.http4k.lens.ParamMeta.StringParam
import org.http4k.routing.path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

open class PathLens<out FINAL>(meta: Meta, get: (Request) -> FINAL) : Lens<Request, FINAL>(meta, get) {

    @Throws(LensFailure::class)
    operator fun invoke(target: String) = super.invoke(Request(GET, target).with(X_URI_TEMPLATE of "{${meta.name}}"))

    override fun toString(): String = "{${meta.name}}"
}

class BiDiPathLens<FINAL>(meta: Meta, get: (Request) -> FINAL, private val set: (FINAL, Request) -> Request) : PathLens<FINAL>(meta, get) {

    /**
     * Lens operation to set the value into the target url
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <R : Request> invoke(value: FINAL, target: R): R = set(value, target) as R

    /**
     * Bind this Lens to a value, so we can set it into a target
     */
    infix fun <R : Request> of(value: FINAL): (R) -> R = { invoke(value, it) }
}

/**
 * Represents a uni-directional extraction of an entity from a target path segment.
 */
open class PathLensSpec<out OUT>(protected val paramMeta: ParamMeta, internal val get: LensGet<Request, String, OUT>) {
    open fun of(name: String, description: String? = null): PathLens<OUT> {
        val getLens = get(name)
        return PathLens(Meta(true, "path", paramMeta, name, description),
            { getLens(it).firstOrNull() ?: throw LensFailure() })
    }

    /**
     * Create another PathLensSpec which applies the uni-directional transformation to the result. Any resultant Lens can only be
     * used to extract the final type from a target path segment.
     */
    fun <NEXT> map(nextIn: (OUT) -> NEXT): PathLensSpec<NEXT> = PathLensSpec(paramMeta, get.map(nextIn))
}

open class BiDiPathLensSpec<OUT>(paramMeta: ParamMeta,
                                 get: LensGet<Request, String, OUT>,
                                 private val set: LensSet<Request, String, OUT>) : PathLensSpec<OUT>(paramMeta, get) {

    /**
     * Create another BiDiPathLensSpec which applies the bi-directional transformations to the result. Any resultant Lens can be
     * used to extract or insert the final type from/into a path segment.
     */
    fun <NEXT> map(nextIn: (OUT) -> NEXT, nextOut: (NEXT) -> OUT) = BiDiPathLensSpec(paramMeta, get.map(nextIn), set.map(nextOut))

    internal fun <NEXT> mapWithNewMeta(nextIn: (OUT) -> NEXT, nextOut: (NEXT) -> OUT, newMeta: ParamMeta): BiDiPathLensSpec<NEXT> = BiDiPathLensSpec(newMeta, get.map(nextIn), set.map(nextOut))

    /**
     * Create a lens for this Spec
     */
    override fun of(name: String, description: String?): BiDiPathLens<OUT> {
        val getLens = get(name)
        val setLens = set(name)

        return BiDiPathLens(Meta(true, "path", paramMeta, name, description),
            { getLens(it).firstOrNull() ?: throw LensFailure() },
            { it: OUT, target: Request -> setLens(listOf(it), target) })
    }
}

object Path : BiDiPathLensSpec<String>(StringParam,
    LensGet { name, target -> target.path(name)?.let(::listOf) ?: emptyList() },
    LensSet { name, values, target -> target.uri(target.uri.path(target.uri.path.replaceFirst("{$name}", values.first().encode()))) }) {

    fun fixed(name: String): PathLens<String> {
        val getLens = get(name)
        return object : PathLens<String>(Meta(true, "path", StringParam, name),
            { getLens(it).find { it == name } ?: throw LensFailure() }) {
            override fun toString(): String = name

            override fun iterator(): Iterator<Meta> = emptyList<Meta>().iterator()
        }
    }
}

fun Path.string() = this
fun Path.int() = string().mapWithNewMeta(String::toInt, Int::toString, NumberParam)
fun Path.long() = string().mapWithNewMeta(String::toLong, Long::toString, NumberParam)
fun Path.double() = string().mapWithNewMeta(String::toDouble, Double::toString, NumberParam)
fun Path.float() = string().mapWithNewMeta(String::toFloat, Float::toString, NumberParam)
fun Path.boolean() = string().mapWithNewMeta(::safeBooleanFrom, Boolean::toString, BooleanParam)
fun Path.localDate() = string().map(LocalDate::parse, DateTimeFormatter.ISO_LOCAL_DATE::format)
fun Path.dateTime() = string().map(LocalDateTime::parse, DateTimeFormatter.ISO_LOCAL_DATE_TIME::format)
fun Path.zonedDateTime() = string().map(ZonedDateTime::parse, DateTimeFormatter.ISO_ZONED_DATE_TIME::format)
fun Path.uuid() = string().map(UUID::fromString, java.util.UUID::toString)
fun Path.regex(pattern: String, group: Int = 1): PathLensSpec<String> {
    val toRegex = pattern.toRegex()
    return string().map { toRegex.matchEntire(it)?.groupValues?.get(group)!! }
}
