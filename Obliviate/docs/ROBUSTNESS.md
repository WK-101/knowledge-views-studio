# How robust is Obliviate's wiping? (and how to be recovery‑proof)

Short version: our free‑space wipe is **strong against software recovery** (the
realistic threat for a lost or resold phone) and is good ongoing hygiene, but on
flash storage it is **best‑effort, not recovery‑proof**. The only reliable way to
make a phone's data unrecoverable is a **factory reset (cryptographic erase)** on
an encrypted device — which the in‑app **Prepare for disposal** flow guides you
through.

## The threat model — three tiers of recovery

| Tier | Tools | Reads which layer |
|---|---|---|
| **1. Software undelete / carving** | Recuva, PhotoRec, DiskDigger, Dr.Fone, EaseUS, Autopsy (over MTP/ADB) | The **logical** filesystem the OS exposes |
| **2. Commercial mobile forensics** | Cellebrite UFED, Magnet AXIOM, Oxygen, MSAB XRY | Logical + full file‑system image; parses deleted SQLite rows, WAL/journals, thumbnails, caches |
| **3. Chip‑off / JTAG / ISP** | Desolder the NAND and read it directly | The **physical** flash, incl. spare/over‑provisioned & remapped cells the OS can't address |

## Where Obliviate lands

- **Tier 1 — Strong.** One random pass over free space overwrites exactly the
  logical blocks these tools scan. After it, undelete of files you deleted from
  the accessible volume finds nothing. The file shredder does the same for
  chosen files.
- **Tier 2 — Partial.** These read the same logical layer, so our overwritten
  free space reads back as noise — but most of what they recover is *live* data a
  non‑root app can't touch: other apps' `/data/data`, deleted rows still in
  SQLite DBs and their `-wal`/`-journal` siblings, thumbnail/media caches. Free‑
  space wiping doesn't remove those. (v1.1 does overwrite `.thumbnails` caches it
  can reach via All‑files access, closing one common leak.)
- **Tier 3 — Not achievable from userspace.** See below.

## Why free‑space overwrite can't be recovery‑proof on flash

This is physics, not a code limitation — it defeats *every* userspace wiper:

1. **Wear‑leveling + FTL indirection** — the controller writes your overwrite to
   *different physical cells* than the original data.
2. **Over‑provisioning (~7–28% hidden spare area)** and **remapped bad blocks** —
   permanently invisible to the OS, readable via chip‑off.
3. **Copy‑on‑write garbage collection** scatters stale copies.
4. **Multi‑pass is theater on flash.** Gutmann/DoD multi‑pass was for magnetic
   drives; on flash it only adds write‑wear.
5. **Scoped storage** — a normal app can't even see most of the data.

Reference: *In Search of Lost Data: A Study of Flash Sanitization Practices*
(arXiv 2505.14067) — file deletion, free‑space overwriting, and TRIM are all
unreliable on flash; only device‑level action is dependable.

## What actually IS recovery‑proof (ranked)

1. **Cryptographic erase = factory reset on an encrypted device.** 🥇 Destroys the
   encryption key, so every remaining byte — even the cells chip‑off reaches — is
   AES‑256 ciphertext with no key in existence. NIST SP 800‑88 "Purge". Beats
   Tier 3. Preconditions: device encrypted (default on Android 10+), a **strong
   lockscreen secret** (keys are entangled with it), real system reset.
2. **OEM/hardware secure erase** (eMMC/UFS `sanitize`/`purge`) — fastboot/OEM tool
   or root.
3. **Root raw‑device overwrite** (`dd`/`blkdiscard` over userdata) — still subject
   to over‑provisioning, so weaker than crypto‑erase; meaningful only combined
   with it. See `ROOT_MODE.md`.
4. **Physical destruction** of the NAND chip — the ultimate.

## What Obliviate does to get as close as possible

- **Free‑space wipe** across internal + shared volumes (deduped by filesystem),
  with optional **read‑back verification** and a **maximum‑coverage** mode.
- **File shredder** for specific sensitive files (overwrite‑then‑delete via SAF).
- **Junk cleaner** that now **overwrites** thumbnails/temp files before deleting.
- **Prepare for disposal** flow: checks encryption + screen lock, runs the wipe,
  then sends you to the factory‑reset (crypto‑erase) screen — the recovery‑proof
  step.
- **Optional root:** a non‑destructive `fstrim` button; raw‑device wipe is
  documented in `ROOT_MODE.md`, not one‑tap.

**Bottom line:** for privacy hygiene, run the wipe. To make a device
recovery‑proof before disposal, the wipe is step one and **factory reset
(crypto‑erase) is the step that actually does it.**
