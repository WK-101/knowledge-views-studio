package org.opentranscribe.api;

// Open Transcribe (vendor-neutral offline-STT contract) — reimplemented from the public spec so the
// core can act as a client of any compatible transcriber (e.g. Scrib). Enum constant order is fixed
// for wire compatibility.
enum ErrorType {
    MODEL_NOT_AVAILABLE,
    DECODE_FAILED,
    UNSUPPORTED_LANGUAGE,
    UNEXPECTED,
    CANCELLED,
}
