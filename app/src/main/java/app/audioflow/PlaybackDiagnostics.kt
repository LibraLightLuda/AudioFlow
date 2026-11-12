package app.audioflow

internal fun safeFailureTypes(error: Throwable): String = generateSequence(error) { it.cause }.take(12)
    .map { it.javaClass.simpleName }.filter { it.isNotBlank() }.distinct().toList().takeLast(4).joinToString(" → ")
