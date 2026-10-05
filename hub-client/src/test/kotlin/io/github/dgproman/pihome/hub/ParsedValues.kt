package io.github.dgproman.pihome.hub

internal fun <T> Parsed<T>.valid(): T =
    when (this) {
        is Parsed.Valid -> value
        is Parsed.Invalid -> throw AssertionError("expected a valid value, got $problem")
    }
