package app.audioflow

internal fun safeFailureTypes(error: Throwable): String = generateSequence(error) { it.cause }.take(12)
    .map { it.javaClass.simpleName }.filter { it.isNotBlank() }.distinct().toList().takeLast(4).joinToString(" → ")

internal fun safeFailureLocation(error: Throwable): String = generateSequence(error) { it.cause }.take(12).last()
    .stackTrace.take(2).joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
