package app.parley.common

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.zip.ZipException

/**
 * What went wrong, in terms a person can act on. Raw exception messages are for logs and crash reports; screens show
 * the text for one of these (the app maps each kind to a string), never `e.message`.
 */
enum class UserError {
    /** An [ExplainedFailure]: its message was written for people, and is shown as it is. */
    EXPLAINED,

    /** The phone (or the chosen storage) is full. */
    NO_SPACE,

    /** The file or folder is gone: moved, deleted, or on storage that was removed. */
    FILE_GONE,

    /** Parley may not open or write it (permission withdrawn, a provider refusing). */
    NO_ACCESS,

    /** Private contacts or the vault are locked. */
    LOCKED,

    /** The file isn't in a format Parley reads, or is damaged. */
    DAMAGED,

    /** Anything else. */
    UNKNOWN,
    ;

    companion object {
        /** The kind of [error], looking through its causes (a wrapped "no space" is still "no space"). */
        fun of(error: Throwable): UserError {
            var e: Throwable? = error
            var depth = 0
            while (e != null && depth < MAX_DEPTH) {
                classify(e)?.let { return it }
                e = e.cause
                depth++
            }
            return UNKNOWN
        }

        private fun classify(e: Throwable): UserError? {
            val name = e.javaClass.simpleName
            val message = e.message.orEmpty()
            return when {
                e is ExplainedFailure -> EXPLAINED
                // Matched by name: the vault's exception lives in core:data, and Android's in the framework.
                name in LOCKED_NAMES -> LOCKED
                e is IOException && (message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true)) -> NO_SPACE
                e is FileNotFoundException || message.contains("ENOENT") -> FILE_GONE
                e is SecurityException || message.contains("EACCES") || message.contains("EPERM") -> NO_ACCESS
                isDamaged(e, name) -> DAMAGED
                else -> null
            }
        }

        private fun isDamaged(e: Throwable, name: String): Boolean =
            e is EOFException || e is ZipException || e is GeneralSecurityException || e is IllegalArgumentException ||
                name.endsWith("SerializationException") || name == "VCardParseException" || name == "CannotParseException"

        private val LOCKED_NAMES = setOf("LockedException", "UserNotAuthenticatedException", "KeyPermanentlyInvalidatedException")

        private const val MAX_DEPTH = 8
    }
}

/** A failure whose [message] is already in words for the person ("This file is too large"), not a technical text. */
open class ExplainedFailure(message: String, cause: Throwable? = null) : IOException(message, cause)
