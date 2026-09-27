package com.wkhan.hexis.bridge;

/**
 * Oneway result channel for a streaming / long-lived bridge session.
 *
 * Contract: zero or more onEvent(...) callbacks (e.g. partial transcripts), then EXACTLY ONE
 * terminal callback — onResult(...) XOR onError(...). Oneway so the provider never blocks on the
 * consumer, and so new callback methods can be added later without breaking older peers.
 *
 * Every payload is a serialized bridge envelope (see BridgeCodec / EventEnvelope / ResponseEnvelope).
 */
oneway interface IHexisBridgeCallback {
    void onEvent(in byte[] eventEnvelope);
    void onResult(in byte[] resultEnvelope);
    void onError(in byte[] errorEnvelope);
}
