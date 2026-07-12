import { describe, it, expect } from "vitest";
import { encryptToEnvelope, decryptFromEnvelope, isEncryptedEnvelope } from "../src/services/crypto";

const enc = new TextEncoder();
const dec = new TextDecoder();

describe("backup encryption", () => {
  it("round-trips data with the correct password", async () => {
    const data = enc.encode("secret backup contents 🗄️");
    const envelope = await encryptToEnvelope(data, "correct horse");
    expect(isEncryptedEnvelope(envelope)).toBe(true);
    const back = await decryptFromEnvelope(envelope, "correct horse");
    expect(dec.decode(back)).toBe("secret backup contents 🗄️");
  });

  it("fails with the wrong password", async () => {
    const envelope = await encryptToEnvelope(enc.encode("data"), "right");
    await expect(decryptFromEnvelope(envelope, "wrong")).rejects.toBeDefined();
  });

  it("produces different ciphertext each time (random salt/iv)", async () => {
    const a = await encryptToEnvelope(enc.encode("data"), "pw");
    const b = await encryptToEnvelope(enc.encode("data"), "pw");
    expect(a).not.toBe(b);
  });

  it("detects envelopes vs plain content", () => {
    expect(isEncryptedEnvelope('{"kvsEncrypted":1,"ciphertext":"x"}')).toBe(true);
    expect(isEncryptedEnvelope('{"kvsPack":1,"profile":{}}')).toBe(false);
    expect(isEncryptedEnvelope("PK\u0003\u0004 binary zip")).toBe(false);
    expect(isEncryptedEnvelope("not json")).toBe(false);
  });
});
