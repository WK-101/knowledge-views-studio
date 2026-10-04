# Encrypted vCard

Parley's **Encrypted vCard** (Settings › Contacts › Export contacts › Encrypted vCard, file name `contacts.vcf.parley`)
is an ordinary vCard 4.0 file, encrypted with a passphrase. It is meant to be opened by Parley on another phone, and by
any program that follows this page. It uses the same envelope as Parley's backups (`BackupCrypto`, "PARLEYB1"), with a
single passphrase key wrap; nothing in it was invented for it.

## What is inside

The plaintext is a vCard 4.0 file in UTF-8 (`BEGIN:VCARD` …), exactly what the plain vCard export writes. Parley's own
notes about each person are carried as properties of Parley's own, which other programs may keep or ignore:

| Property | Meaning |
|---|---|
| `X-PARLEY-PRIVATE:1` | A private contact. Parley imports it as private again, never into the address book |
| `X-PARLEY-NOTE-FOR-CALLS:<text>` | The note shown when they call |
| `X-PARLEY-CONTEXT:<text>` | A private contact's "Who is this" line |
| `X-PARLEY-KEEP-IN-TOUCH:<days>` | In the Circle, every so many days |
| `X-PARLEY-CALL-NOTE;X-WHEN=<instant>;X-LINE=<line key>:<text>` | A note written on a call; `X-LINE` is the number's line key (`+` and its E.164 digits, or `~` and digits) |
| `X-PARLEY-MOMENT;X-WHEN=<instant>;X-KIND=meet\|message\|video\|other:<note>` | A moment logged in the Circle |
| `X-PARLEY-PROMISE:<text>` | An open promise, read from the notes (informative: the notes bring it back) |
| `NOTE;X-PARLEY-SUMMARY=1:<text>` | All of the above in words, for programs that show only `NOTE`; Parley drops it on import |

Times are RFC 3339 instants in UTC (`2026-10-04T10:00:00.123Z`). Text values use vCard escaping (`\n`, `\\`).

## File layout

All integers are big-endian.

```
file    := header | segment*
header  := "PARLEYB1" | u8 version (1) | u32 bodyLen | body
body    := u8 kdfAlg | u32 kdfParam | u8 saltLen (16) | salt
           | u32 segmentSize (65536) | noncePrefix[7] | u8 wrapCount (1) | wrap
wrap    := u8 type (1 = passphrase) | u32 len | nonce[12] | AES-256-GCM(KEK, DEK)   aad = "PARLEYB1" | 0x01 | 0x01
segment := AES-256-GCM(DEK, chunk_i)   nonce = noncePrefix | u32 i | u8 last,   aad = the whole header
```

- **KEK.** The passphrase, NFC-normalised and encoded in UTF-8, through the KDF named by `kdfAlg`:
  - `2`, scrypt: `kdfParam` packs `log2(N) << 16 | r << 8 | p`. Parley writes N = 2^15, r = 8, p = 1 (32 MB).
  - `1`, PBKDF2-HMAC-SHA256: `kdfParam` is the iteration count (older files).

  32 bytes of output. Readers should refuse costs above scrypt N = 2^16, 64 MB or p = 4, and PBKDF2 above 2,000,000
  rounds: a file chooses its own cost.
- **DEK.** 32 random bytes, sealed in the wrap with AES-256-GCM under the KEK.
- **Segments.** The plaintext is cut into chunks of `segmentSize` bytes; the last chunk is 0 to `segmentSize` bytes
  (an empty file is one empty last segment). Each ciphertext segment is its chunk plus a 16-byte tag, so every segment
  but the last is `segmentSize + 16` bytes long. `i` counts segments from 0; `last` is 1 for the final segment only.
  The counter and the last flag in the nonce make a cut-off, reordered or extended file fail to open, and the header
  as associated data authenticates every header byte.
- **Telling it from a backup.** A Parley backup has the same magic but other key wraps (a public-key wrap, a recovery
  wrap), and its plaintext is a ZIP archive. An encrypted vCard has exactly one wrap, of type 1, and its plaintext
  starts with `BEGIN:VCARD` (after an optional byte-order mark).

## Reading one (Python reference)

```python
import hashlib, struct, unicodedata
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

def open_encrypted_vcard(path: str, passphrase: str) -> str:
    data = open(path, "rb").read()
    if data[:8] != b"PARLEYB1" or data[8] != 1:
        raise ValueError("not a Parley encrypted file")
    (body_len,) = struct.unpack(">I", data[9:13])
    header = data[:13 + body_len]
    b = header[13:]
    kdf_alg, (kdf_param,), salt_len = b[0], struct.unpack(">I", b[1:5]), b[5]
    salt, i = b[6:6 + salt_len], 6 + salt_len
    (seg_size,) = struct.unpack(">I", b[i:i + 4])
    prefix, wraps, i = b[i + 4:i + 11], b[i + 11], i + 12
    pw = unicodedata.normalize("NFC", passphrase).encode("utf-8")
    if kdf_alg == 2:
        n, r, p = 1 << (kdf_param >> 16), (kdf_param >> 8) & 0xFF, kdf_param & 0xFF
        kek = hashlib.scrypt(pw, salt=salt, n=n, r=r, p=p, maxmem=256 << 20, dklen=32)
    else:
        kek = hashlib.pbkdf2_hmac("sha256", pw, salt, kdf_param, 32)
    dek = None
    for _ in range(wraps):
        kind, (size,) = b[i], struct.unpack(">I", b[i + 1:i + 5])
        payload, i = b[i + 5:i + 5 + size], i + 5 + size
        if kind == 1:
            dek = AESGCM(kek).decrypt(payload[:12], payload[12:], b"PARLEYB1\x01\x01")  # raises on a wrong passphrase
    out, pos, seg = [], len(header), 0
    while True:
        chunk = data[pos:pos + seg_size + 16]
        pos += len(chunk)
        last = pos >= len(data)
        nonce = prefix + struct.pack(">IB", seg, 1 if last else 0)
        out.append(AESGCM(dek).decrypt(nonce, chunk, header))
        seg += 1
        if last:
            return b"".join(out).decode("utf-8")
```

Writing one is the mirror image: random salt, DEK and nonce prefix; the wrap; then the segments, with `last = 1` on the
final one (emit a full chunk only once more data follows it, so the final segment always carries the flag).

## In Parley

- `SealedVCard` (core:common) writes and opens the file with `BackupCrypto`; `ContactExport` (core:data) writes the
  export; `VCardIO.import` reads it back, sending private contacts to the vault.
- Import: Settings › Contacts › Import from file recognises the file, asks for the passphrase and checks it before
  anything is written. Private contacts need to be unlocked, as when opening one.
- Tests: `SealedVCardTest` (round trip, wrong passphrase, cut-off and changed files, a backup refused),
  `CardNotesTest` (the properties), `ContactExportTest` (export and import, private contacts landing private).
