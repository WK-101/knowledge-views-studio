/**
 * Password-based encryption for backups, using the platform Web Crypto API: a key is derived
 * from the password with PBKDF2 (SHA-256), and the payload is encrypted with AES-256-GCM
 * (authenticated, so a wrong password fails cleanly). The result is a small JSON envelope, so
 * an encrypted `.kvspack`/`.kvsarchive` is still a single self-describing file.
 *
 * NOTE (preservation): encryption protects confidentiality but works against long-term openness —
 * if the password is lost the data is unrecoverable. Encrypt copies, not your only master.
 */
const PBKDF2_ITERATIONS = 210000;

export interface EncryptionEnvelope {
  readonly kvsEncrypted: number;
  readonly alg: "AES-GCM";
  readonly kdf: "PBKDF2";
  readonly hash: "SHA-256";
  readonly iterations: number;
  readonly salt: string;
  readonly iv: string;
  readonly ciphertext: string;
}

/** Copy a view into a standalone ArrayBuffer (avoids TypedArray/BufferSource generic issues). */
function toBuffer(bytes: Uint8Array): ArrayBuffer {
  const buf = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buf).set(bytes);
  return buf;
}

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  return btoa(binary);
}

function fromBase64(b64: string): Uint8Array {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function deriveKey(password: string, salt: Uint8Array, iterations: number): Promise<CryptoKey> {
  const baseKey = await crypto.subtle.importKey("raw", toBuffer(new TextEncoder().encode(password)), "PBKDF2", false, [
    "deriveKey",
  ]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: toBuffer(salt), iterations, hash: "SHA-256" },
    baseKey,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

/** Encrypt bytes with a password, returning a JSON envelope string. */
export async function encryptToEnvelope(data: Uint8Array, password: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(password, salt, PBKDF2_ITERATIONS);
  const ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv: toBuffer(iv) }, key, toBuffer(data));
  const envelope: EncryptionEnvelope = {
    kvsEncrypted: 1,
    alg: "AES-GCM",
    kdf: "PBKDF2",
    hash: "SHA-256",
    iterations: PBKDF2_ITERATIONS,
    salt: toBase64(salt),
    iv: toBase64(iv),
    ciphertext: toBase64(new Uint8Array(ciphertext)),
  };
  return `${JSON.stringify(envelope, null, 2)}\n`;
}

/** Decrypt a JSON envelope with a password. Throws if the password is wrong or data is corrupt. */
export async function decryptFromEnvelope(text: string, password: string): Promise<Uint8Array> {
  const envelope = JSON.parse(text) as EncryptionEnvelope;
  const salt = fromBase64(envelope.salt);
  const iv = fromBase64(envelope.iv);
  const ciphertext = fromBase64(envelope.ciphertext);
  const key = await deriveKey(password, salt, envelope.iterations || PBKDF2_ITERATIONS);
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: toBuffer(iv) }, key, toBuffer(ciphertext));
  return new Uint8Array(plain);
}

/** True if the text looks like a KVS encryption envelope. */
export function isEncryptedEnvelope(text: string): boolean {
  const trimmed = text.trimStart();
  if (!trimmed.startsWith("{")) return false;
  try {
    const obj = JSON.parse(trimmed) as Record<string, unknown>;
    return typeof obj === "object" && obj !== null && "kvsEncrypted" in obj && "ciphertext" in obj;
  } catch {
    return false;
  }
}
