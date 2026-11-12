package fr.corsicajack.seekmind.core.common

/**
 * Result of an operation whose failures are expected (architecture §11): an unavailable tree, a refused
 * screenshot, an invalid token, an ad without fill. Expected failures are values, not exceptions.
 */
sealed interface Outcome<out T, out E> {
    data class Success<out T>(val value: T) : Outcome<T, Nothing>

    data class Failure<out E>(val error: E) : Outcome<Nothing, E>
}

inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, E, R> Outcome<T, E>.fold(onSuccess: (T) -> R, onFailure: (E) -> R): R = when (this) {
    is Outcome.Success -> onSuccess(value)
    is Outcome.Failure -> onFailure(error)
}

fun <T, E> Outcome<T, E>.getOrNull(): T? = (this as? Outcome.Success)?.value
