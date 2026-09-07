# Root mode — design & manual procedures

Obliviate is a **non‑root** app by design, so it installs and runs on any phone.
When an `su` binary is present, it can *optionally* do a bit more. This document
specifies what root mode does, and — deliberately — keeps the destructive parts
as documented manual commands rather than one‑tap buttons, because a wrong device
path can permanently brick a phone and cannot be validated in CI.

## What's implemented (safe, wired to the UI)

`core/root/RootManager.kt`:

- **`isRootAvailable()`** — passive detection (checks for an `su` binary in the
  usual locations). It never invokes `su`, so it won't trigger a root prompt.
- **`fstrim()`** — runs `fstrim -v /data` and `/storage/emulated/0`. This is
  **non‑destructive**: it asks the storage controller to TRIM already‑free
  blocks so the flash can erase them sooner. Surfaced in **Prepare for disposal →
  Advanced** when root is detected.

`runAsRoot(cmd)` executes `su -c "<cmd>"` with a timeout and returns exit code +
output. This *does* trigger the device's root prompt.

## What's intentionally NOT one‑tap (do it yourself, carefully)

These get much closer to defeating Tier‑3 (chip‑off) recovery, but only
**cryptographic erase (factory reset) is the real guarantee** — raw overwrite is
still subject to over‑provisioning. Run from an adb root shell or a root terminal.

### 1. Identify the userdata block device
```sh
su
ls -l /dev/block/by-name/ | grep -i userdata
# e.g. userdata -> /dev/block/sda27   (varies wildly by device!)
```

### 2. TRIM/discard the whole free area (fast, non‑destructive)
```sh
fstrim -v /data
# or, for the raw device (discards unmapped blocks):
blkdiscard -v /dev/block/by-name/userdata   # DANGER: only on an already-wiped/spare device
```

### 3. Raw overwrite of free space (via a fill file, safest root option)
```sh
# Same idea as the app's free-space wipe, but on /data directly:
dd if=/dev/urandom of=/data/zzz.fill bs=16M 2>/dev/null; sync; rm -f /data/zzz.fill; fstrim -v /data
```

### 4. Full raw device overwrite (LAST RESORT — can brick the device)
```sh
# ONLY if you understand the risk and the device path is correct.
# This overwrites the entire userdata partition, encryption metadata included.
dd if=/dev/urandom of=/dev/block/by-name/userdata bs=16M status=progress; sync
```
> A wrong `of=` path (e.g. the boot or system partition) will brick the phone.
> There is no undo. Prefer the factory‑reset crypto‑erase below.

## The recommended root‑user procedure

1. Ensure the device is **encrypted** with a **strong lockscreen secret**.
2. `fstrim -v /data` (or the app's fstrim button).
3. Run the app's free‑space wipe (Both volumes, verify, maximum coverage).
4. **Factory reset** → this destroys the encryption key (cryptographic erase),
   which is the step that actually makes the data unrecoverable — even to
   chip‑off — regardless of what physical cells still hold.

## Why we don't automate the destructive path

- Device/partition layouts differ by OEM, model, and Android version; there is no
  portable, safe way to auto‑detect the correct target on every device.
- It cannot be tested in CI or on an emulator without risking data loss.
- Crypto‑erase (factory reset) is both safer **and** stronger, so the marginal
  benefit of an automated raw wipe is small and the downside (a bricked phone) is
  catastrophic.
