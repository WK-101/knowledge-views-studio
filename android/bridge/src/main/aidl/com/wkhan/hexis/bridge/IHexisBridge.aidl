package com.wkhan.hexis.bridge;

import com.wkhan.hexis.bridge.IHexisBridgeCallback;

/**
 * The fixed transport spine. Every capability rides these four methods as serialized envelopes;
 * the spine itself never changes when a new capability (addon) is added.
 *
 * Security: the provider MUST verify the caller (Binder.getCallingUid -> package -> pinned signing
 * keyset) and the scoped token in each envelope's header before acting. See SignatureVerifier.
 */
interface IHexisBridge {
    /** Bind-time mutual introduction. Takes a serialized HandshakeHello, returns a HandshakeResult. */
    byte[] handshake(in byte[] helloEnvelope);

    /** One-shot request/response. Takes a serialized RequestEnvelope, returns a ResponseEnvelope. */
    byte[] invoke(in byte[] requestEnvelope);

    /**
     * Open a streaming / long-lived session. Results arrive on the callback; returns a serialized
     * SessionHandle used to control it. Bulk binary never flows here — it uses a side channel.
     */
    byte[] openStream(in byte[] requestEnvelope, IHexisBridgeCallback callback);

    /** Control a live session (STOP / CANCEL). Takes a serialized SessionControl. */
    void controlSession(in byte[] controlEnvelope);
}
