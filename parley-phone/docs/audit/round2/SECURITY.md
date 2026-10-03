# Track C: security and privacy audit of Parley 5.3.0 (HEAD e085d27)

Scope: threat-model-driven, read-only review of `parley-phone/` (app, telecom, core/common, core/data, lists-updater,
build files). Baselines: `docs/SECURITY_MODEL.md`, `docs/audit/SECURITY.md` (SEC-01…28), `docs/COMPETITIVE_ANALYSIS_7.md` §5.
I opened and read every file:line cited below. **[uncertain]** marks findings where part of the claim rests on platform
behaviour I couldn't check from the repo.

## Summary (≤300 words)

Most of the 28 earlier findings are fixed in code, and fixed well. The fixes cover:
- a linear wildcard matcher;
- a genuine-companion check;
- authentication-required notification actions;
- sensitive clipboard flags;
- `LockedActivity`, with lock at `onStop` and recents protection;
- a confirm-credential fallback for the lock;
- vault key generations with no destructive re-seal;
- ECDSA archive signatures endorsed by the bundle;
- scrypt with a strength gate;
- `Bounded` readers and exact QR KDF settings;
- `RecordCrypto` sealing;
- overlay guards, `SharedUris`, a scrubbed crash store and a confirm step for JOIN_CONTACT.

The crypto primitives look correct:
- AES-GCM with random 96-bit nonces;
- the STREAM construction with header AAD;
- KDF caps applied on read;
- constant-time PIN comparisons;
- a strict signed-card parser;
- signed shared-label tickets, journals and header changes.

The new weak spot is the **duress PIN**. Its promise is "a session's changes don't stick, and nothing shows". Three paths
don't go through `DuressPolicy`:
- **Private-name settings and approvals** are written straight to storage.
- **Backup restore** writes stored settings through `importMap`.
- **Delete all data** reads the unconcealed vault. It prompts "Unlock private contacts" and then destroys the hidden
  contacts.

So a coercer in a duress session can see that private contacts exist. They can also leave lasting access for a
stalkerware app, or switch off protections for good.

Other findings:
- **Exported entry point.** Any app can start MainActivity with the internal action `SHOW_MISSED`. That silently marks
  every missed call read in the system call log.
- **Notification approval.** Approving private-name access from the notification needs only the phone's unlock, not
  Parley's PIN. The notification shows only a label the requesting app chooses.
- **Release signing.** The release keystore still sits on an ephemeral `/tmp` path, with plaintext passwords and the
  same store and key password.

Smaller items: private calls appear in the system call log for a few seconds; lock-screen call details show by default;
plaintext files are left in `cache/share`; restored privacy settings that aren't treated as security keys; device-protected
storage is not excluded from device-to-device transfer; 64-bit key fingerprints; dependency verification is SHA-256
trust-on-first-use with an alpha library.

## Ranked top list

| # | ID | Sev | Title | Effort |
|---|---|---|---|---|
| 1 | C-01 | **High** | A duress session can make lasting private-name changes (enable, approve apps, Directory) | S |
| 2 | C-02 | **High** | "Delete all data" in a duress session reveals the vault and destroys hidden contacts | M |
| 3 | C-03 | **High** (ops) | Release keystore: ephemeral `/tmp` path, plaintext passwords, store password = key password | S |
| 4 | C-04 | Medium | Restore writes stored settings past `DuressPolicy` (a duress session's changes stick) | S |
| 5 | C-05 | Medium | Private-name "Allow" from the notification bypasses Parley's PIN and app lock; only a spoofable label is shown | S |
| 6 | C-06 | Low–Med | The exported MainActivity takes internal actions: any app can mark all missed calls read | S |
| 7 | C-07 | Low–Med | Private contacts' calls sit in the system call log for 2.5–7.5 s (longer if the process dies) | S |
| 8 | C-08 | Low | Lock-screen call screen shows the pinned note, "who is this" and the last call by default | S |
| 9 | C-09 | Low | Plaintext vCards and voicemail audio linger in `cache/share` (never cleaned) | S |
| 10 | C-10 | Low | Privacy settings restored silently from any archive, forged or unsigned ones included | S |
| 11 | C-11 | Low | `data_extraction_rules` misses the device-protected domains (spam lists go in device-to-device transfer) | S |
| 12 | C-12 | Low | With a Parley PIN set, in-app "confirm it's you" accepts the phone's fingerprint | S |
| 13 | C-13 | Low | Supply chain: SHA-256 trust-on-first-use, `verify-signatures=false`, Material 3 alpha in the release | S–M |
| 14 | C-14 | Low | Widgets show names while Parley is app-locked (phone unlocked) | S |
| 15 | C-15 | Info | 64-bit key fingerprints for card and member trust ceremonies | S |
| 16 | C-16 | Info | Vault and record AES-GCM have no associated data binding the blob to its row | M |
| 17 | C-17 | Info | VIEW / EDIT / QUICK_CONTACT URIs queried with Parley's identity without `SharedUris` | S |
| 18 | C-18 | Info | Pure Ed25519 verifier accepts small-order public keys | S |

---

## Findings

### C-01 (High): a duress session can make lasting private-name changes

**Evidence**
- `app/.../ui/people/PrivacyScreens.kt:258` calls `access.setEnabled(it)`.
- `:271` calls `PrivateDirectoryProvider.setEnabled(...)`.
- `:281,287` call `access.setApproval(...)`.
- `core/data/.../people/PrivateNameAccess.kt:44-73` writes SharedPreferences directly. There is no `Concealment` or
  duress check anywhere in this file or in `privatenames/*` (grep).
- `LockTransitions.kt:21-35` starts a duress session but nothing scopes these stores.
- While hiding, the providers answer "hidden" (`PrivateNameProvider.kt:58`). That holds only until the real PIN is
  entered.

**Scenario**
1. An abusive partner makes the victim open Parley and the victim types the duress PIN.
2. The partner installs a small app, or uses stalkerware already present, that requests `LOOKUP_PRIVATE_NAME`. They
   grant the permission in Android Settings.
3. In Parley › Privacy › Private names they switch the feature on (and the Directory) and approve their app. They can
   also approve it from the request notification (C-05).
4. Everything shows as normal. Once the victim later unlocks with the real PIN, the partner's app can resolve private
   contacts' names, number by number, indefinitely.

This contradicts SECURITY_MODEL §Duress ("the stored settings are untouched", "the session's changes are forgotten at
the next lock").

**Fix**
- Treat the private-names switch, the Directory switch and approvals as duress-scoped safety switches. During a
  session, keep changes in an in-memory overlay that is dropped at lock, the way `SettingsRepository.update` does with
  `DuressPolicy.split`.
- Refuse `setApproval(ALLOWED)` from the notification receiver while `Concealment.hiding`.
- Add a regression test that drives each `PrivacyScreens` setter while hiding and asserts the stored prefs are unchanged.

**Effort:** S.

### C-02 (High): "Delete all data" in a duress session reveals the vault and destroys hidden contacts

**Evidence**
- `app/.../ui/settings/DeleteAllData.kt:171-176` checks `vm.c.vault.summariesNow().isNotEmpty()` and
  `VaultCrypto.detailNeedsUnlock()`. Both are true while hiding with "Keep private details locked", because
  `detailLocked` is set.
- It then calls `AppLock.authenticateForVault`, which shows a system prompt titled **"Unlock private contacts"**
  (`strings_data.xml:912`).
- `:221` shows `WipeStep.VaultLocked` when the backup left the vault out, which it always does while hiding
  (`BackupRepository.kt:389-392`). The dialog text reads "Private contacts can only go into the backup while they're
  unlocked…" (`strings_settings.xml:862`).
- `summariesNow()` reads the database unconcealed (`VaultRepository.kt:1001`).
- `DataWipe.wipe` (`DataWipe.kt:36-76`) deletes every store and every Keystore alias. That includes the vault, the
  `app_pin` record and `app_lock_state`.

**Scenario**
1. The coercer in a duress session opens Privacy › Advanced › Delete all Parley data and types the word.
2. With backups configured, "Back up first" is ticked by default. Parley immediately asks to "Unlock private contacts",
   then offers "Delete without backing up private contacts". The duress PIN is exposed: the coercer now knows hidden
   contacts exist.
3. Either way, completing the wipe permanently destroys the hidden private contacts.
4. SECURITY_MODEL:253-256 says "Everything here hides; nothing destroys".

Android's own "Clear storage" can also destroy the data, so the reveal in step 2 is the real defect.

**Fix**
- While `Concealment.hiding`, make the wipe flow use the concealed view: `hasPrivate = false`, no vault prompt, no
  VaultLocked step.
- Choose a destruction policy and document it. Options:
  - a decoy wipe that resets everything except the vault tables and keys and `app_lock_state`, so the app looks new
    and the vault returns after the real PIN;
  - or refuse with a neutral error that is indistinguishable from a transient failure.
- Audit every other `summariesNow()` and `vault.contacts.value` reader for the same tell (`ContactDetailViewModel.kt:511`,
  `TemporaryContacts.kt:189`).

**Effort:** M.

### C-03 (High, operational): release signing key hygiene (SEC-01 partly fixed)

**Evidence**
- `keystore.properties:1-4`: `storeFile=/tmp/claude-0/.../scratchpad/signing/parley-release.jks`, passwords in
  plaintext (24 characters each), and **store password = key password** (checked by comparison; values not printed).
- `docs/RELEASING.md:120` itself says never to keep the key "in /tmp".

Improvements since the last audit:
- `dist/` no longer holds the `.jks` or `parley-signing.txt`;
- the keystore is mode 0600;
- nothing is in git history (`git log --all -- keystore.properties '*.jks'` is empty).

**Failure scenario:** the scratchpad is wiped, and no update can ever be installed over existing installs (the
`READ_LISTS` signature permission breaks too). Or the plaintext passwords next to the key leak with a workspace copy,
and someone can ship malicious updates to both apps.

**Fix**
- Move the keystore to offline encrypted storage, or to CI secrets (RELEASING.md Option A).
- Use separate passwords.
- Rotate now: the passwords have been visible to tooling. Add a v3 signing lineage.
- Delete `keystore.properties` from the tree.

**Effort:** S.

### C-04 (Medium): restore writes stored settings past DuressPolicy

**Evidence**
- `core/data/.../SettingsRepository.kt:108-122`: `importMap()` writes keys straight into the DataStore.
- Contrast `update()` at `:134-142`, which splits while `duressSession || Concealment.hiding`.
- Restore calls `importMap` for non-security keys at `BackupRepository.kt:670-675`.
- `applyPendingRestore()` at `:200-205` writes app lock, lock-after, secure screen and discreet mode after
  `AppLock.authenticate`. That call returns `true` on the phone's fingerprint even with a Parley PIN set
  (`AppLock.kt:192-203`).
- There is no hiding check in `ui/backup/RestoreFlow.kt` (grep).

**Scenario:** in a duress session the coercer restores any backup whose settings differ (an older one, from before the
app lock), confirms "Apply safety settings" with the victim's fingerprint, and stores `appLock=false`,
`hideVault=false` and `private_vault_history=false`. These persist after the real PIN. The last one means private calls
go to the system call log from then on.

**Fix**
- Route `importMap` through the same split while hiding, or skip the settings part during a session with a neutral
  message.
- Never apply pending security settings while hiding.

**Effort:** S.

### C-05 (Medium): private-name approval from the notification bypasses Parley's app lock (SEC-04 residual)

**Evidence**
- `PrivateNameProvider.kt:94-121`: Allow and Don't allow are `setAuthenticationRequired(true)` broadcasts on Android 12+.
  On 10–11 they are an invisible activity. Both need only the **keyguard** unlock.
- `decide()` (`:168-176`) stores `ALLOWED` immediately.
- The notification names the app by `getApplicationLabel` (`:96-97`), which the requesting app controls. No package name
  or certificate fingerprint is shown.
- It also works during a duress session (C-01).

**Scenario:** someone holding an unlocked phone, with Parley locked behind its PIN, taps "Allow" for an app labelled
"Phone" or "Signal". That app then gets lasting access to private names. The app lock is the documented protection
here ("the app lock is what protects you then").

**Fix**
- Make "Allow" open a `LockedActivity` sheet behind the app lock and PIN that shows the package name, installer and
  certificate SHA-256.
- Leave only "Don't allow" as a direct action.
- Refuse while hiding.

**Effort:** S.

### C-06 (Low–Med): the exported MainActivity accepts internal actions from any app

**Evidence**
- `MainActivity` is exported (manifest).
- `IntentRoutes.resolve` handles custom actions whatever the caller (`IntentRoutes.kt:120-157`).
- `ACTION_SHOW_MISSED` sets `missedSeen = true` (`:143`), and `handleIntent` runs `vm.markMissedSeen()` **before and
  regardless of the app lock** (`MainActivity.kt:166`).
- `markMissedSeen` stops the re-alert, sets `NEW=0, IS_READ=1` on every missed call in the **system** call log, and
  cancels Telecom's missed-call notification (`AppViewModel.kt:289-298`, `CallLogRepository.kt:205-214`).

**Scenario:** a zero-permission app sends `Intent().setClassName("app.parley", "app.parley.MainActivity")
.setAction("app.parley.SHOW_MISSED")`. It can do this while in the foreground, from its own notification, or from a
widget. Missed-call evidence and reminders disappear, a confused deputy for WRITE_CALL_LOG. The other internal actions
(`POST_CALL` BLOCK and REPORT prefill, `SHOW_CALLER` with any contact id, `OPEN_*`) only navigate behind the lock, but
they let any app drive Parley's UI with prefilled data.

**Fix:** move internal actions to a non-exported `activity-alias` (as `MissedCallBack` already does) and ignore them
when `intent.component` is the exported MainActivity. Mark seen only after the screen actually showed unlocked.

**Effort:** S.

### C-07 (Low–Med): private calls are briefly in the system call log

**Evidence**
- `AppTelecomDependencies.kt:296-303`: after a call with a private contact, three sweeps run at 2.5 s, 5 s and 7.5 s
  in `c.scope`.
- If the process dies, only `MaintenanceWorker` (`:182`) catches up later.
- Telecom inserts the row with the number, duration and SIM.

**Scenario:** any app with `READ_CALL_LOG`, such as a caller-ID app or stalkerware, registers a `ContentObserver` on
`CallLog.Calls` and records each private contact's call before the sweep. SECURITY_MODEL:177 says "Private call history
keeps private contacts' calls out of the system call log". Telecom offers no "skip call log" for connected calls, so
this is partly inherent. **[uncertain]** whether OEM call-log cloud backups snapshot within that window.

**Fix**
- Register Parley's own `ContentObserver` and delete on insert, at sub-second latency.
- Persist a pending-sweep marker so a cold start sweeps immediately.
- Document the residual window.

**Effort:** S.

### C-08 (Low): lock-screen call screen shows notes by default (SEC-20 partly fixed)

**Evidence**
- `common/Settings.kt:80`: the default `lockScreenCaller = NAME`.
- `telecom/CallModels.kt:162-165`: `forLockScreen` returns the call unchanged for `NAME`, so `note`, `context`,
  `lastCall` and `subtitle` stay.
- `CallerHeader.kt:470-482` shows them; only `memory` is gated by keyguard.

**Scenario:** anyone can ring a locked phone and read the pinned "note for calls" (for example "Therapist", "owes £500").

**Fix:** gate note, context and lastCall on keyguard whatever the name mode, or make "initials" the default for saved
callers.

**Effort:** S.

### C-09 (Low): plaintext files linger in `cache/share`

**Evidence**
- `QrResultSheet.kt:268-272` writes `share/scanned-contacts.vcf`. This can be a contact decrypted from a passcode-protected
  Secure QR.
- `CardSharing.kt:132-135` writes `share/my-card.vcf`.
- `VoicemailRepository.kt:204-215` writes voicemail audio, removed only when the next voicemail is shared.
- `TemplatesScreen.kt:138` and `TransferScreen.kt:190` write rule exports.
- `ExportFiles.cleanup` (`ExportFiles.kt:105-108`), called at startup and hourly, cleans only `transfer/export`.

**Risk:** retention beyond need. The files stay readable through the FileProvider for as long as a receiving app holds
its grant, and they survive "deleted" voicemails and contacts.

**Fix:** clean `share/` (and `transfer/`) in the same sweeps; delete `scanned-contacts.vcf` right after the import reads
it.

**Effort:** S.

### C-10 (Low): privacy settings restored silently from any archive

**Evidence**
- `SettingsRepository.kt:318`: `SECURITY_KEYS` covers only appLock, lockAfter, secure and hideVault.
- `private_vault_history`, `lock_screen_caller` and the like are imported at once (`BackupRepository.kt:674`).
- Archives with `UNCONFIRMED_PHONE`, `UNSIGNED` or `BAD_SIGNATURE` origins are restorable after a warning
  (`RestoreFlow.kt:256-259`).
- `UNCONFIRMED_PHONE` (a forger's own signature without an endorsement) is "worded like an unsigned backup"
  (`ArchiveSignatures.kt:50-56`).

**Scenario:** someone with write access to the backup folder plants an archive. It is wrapped to the embedded genuine
bundle, so it opens with the user's passphrase (SEC-09's residual). It turns private call history off.

**Fix:** extend `SECURITY_KEYS` to every privacy switch, and default the Settings part to off for any origin other than
this phone or another of your phones.

**Effort:** S.

### C-11 (Low): data extraction rules miss the device-protected domains

**Evidence**
- `app/src/main/res/xml/data_extraction_rules.xml` excludes only `root`, `file`, `database` and `sharedpref`, the
  credential-encrypted domains.
- `SpamListStore.kt:61` keeps packs and `state.json` in device-protected storage (`PersistentStores.kt:194`).
- On Android 12+, `allowBackup=false` does not stop device-to-device transfer.

**Risk:** imported or user-made block lists and their state move to a new phone outside Parley's encrypted backup.
**[uncertain]** whether a given OEM's transfer tool includes device-protected data.

**Fix:** add `device_root`, `device_file`, `device_database`, `device_sharedpref` and `external` excludes, in both apps.

**Effort:** S.

### C-12 (Low): with a Parley PIN set, in-app re-confirmation accepts the phone's fingerprint

**Evidence**
- `AppLock.kt:192-203`: `unlockedByDevice` always runs `then()`, so `onResult(true)`, even when a PIN is required.
- These callers use it:
  - turning off the app lock (`SettingsPages.kt:643`);
  - applying restored safety settings (`RestoreFlow.kt:233`);
  - delete all (`DeleteAllData.kt:182`);
  - revealing a family safe word (`FamilySafetyScreens.kt:104`);
  - leaving simple mode (`SimpleHome.kt:184`);
  - supervised call-time edits (`CallTimeEditors.kt:49`).

**Scenario:** someone who knows the phone's credential, or a sleeping partner's finger, but not the Parley PIN, gets past
these confirmations whenever Parley is within its unlock timeout.

**Fix:** a `confirmSensitive()` that asks for the Parley PIN when one is set.

**Effort:** S.

### C-13 (Low): supply chain (SEC-12 partly fixed)

**Evidence**
- `gradle/verification-metadata.xml:4-5`: `verify-metadata=true`, `verify-signatures=false`, with 839 components whose
  hashes are "Generated by Gradle", i.e. trusted on first download.
- `gradle/libs.versions.toml:19`: `material3 = "1.5.0-alpha14"` ships in the release.

**Fix:** add PGP verification with `trusted-keys` for Google, JetBrains and Square; pin to stable Material 3 or document
why the alpha is acceptable; regenerate the hashes on a clean, trusted machine.

**Effort:** S–M.

### C-14 (Low): widgets show names while Parley is locked (SEC-22 residual)

**Evidence:** `CircleWidget.kt:122` and `FavoritesWidget.kt:165`. Names are hidden only when
`appLock && isDeviceLocked`. On an unlocked phone with Parley app-locked, or in a duress session, the home screen shows
Circle members, due dates and favourites.

**Fix:** an option "Hide names while Parley is locked", using `AppLock.locked` and `Concealment.hiding`.

**Effort:** S.

### C-15 (Info): 64-bit fingerprints

`Ed25519.fingerprint` (`Ed25519.kt:181-182`) is the first 8 bytes of SHA-256. It is used in "Trust the new card" and
in the label member lists. A second preimage costs about 2^64: fine against casual attackers, thin for a stated
"compare fingerprints" ceremony.

**Fix:** use 16 bytes, or a word list.

### C-16 (Info): no AAD binding for vault and record blobs

`VaultCrypto.gcmSeal/gcmOpen` (`:300-312`) and `HistoryCrypto.seal/open` (`:96-107`) pass no associated data. A blob
can be swapped between rows, or between the main and extra parts, by anyone who can write the app database. That is out
of scope (root), so this is defence in depth only.

**Fix:** add `entryId | kind | generation` as AAD in the next blob version.

### C-17 (Info): URIs queried with Parley's identity without `SharedUris`

`IntentRoutes.kt:131` (VIEW → `resolveContact`), `:156` (EDIT) and `QUICK_CONTACT` reach
`ContactsRepository.resolveContactId` (`:1363-1372`). That code queries **any** content URI, Parley's own included. The
result is only an id used for navigation, so no exfiltration path was found.

**Fix:** apply `SharedUris.acceptable` and restrict to `ContactsContract.AUTHORITY`.

### C-18 (Info): small-order Ed25519 keys

`verifyPure` (`Ed25519.kt:166-175`) doesn't reject small-order `A` or `R`. With `A` the identity, `(R = identity,
s = 0)` verifies for every message. Keys are attacker-chosen anyway, so the impact is negligible. The behaviour may also
differ from the platform verifier on Android 13+.

**Fix:** reject small-order `A` and `R` (check `8·P ≠ identity`).

---

## Verified fixed since `docs/audit/SECURITY.md`

| Earlier finding | Now |
|---|---|
| SEC-02 | `WildcardPattern` two-pointer matcher |
| SEC-03 | `ListsUpdaterClient.isGenuine` checks the package, readPermission and `checkSignatures` |
| SEC-04 | `setAuthenticationRequired` / activity path (residual: C-05) |
| SEC-05 | `Intents.copy(sensitive = true)` by default |
| SEC-06 | `LockedActivity` lock at `onStop`, `protectWindow`, `setRecentsScreenshotEnabled` |
| SEC-07 | Fails open only when `!isDeviceSecure`, otherwise `createConfirmDeviceCredentialIntent` |
| SEC-08 | Detail-key generations, `setUnlockedDeviceRequired`, StrongBox, KeyInfo audit and upgrade; `KeyLost` only on invalidation or provable absence; blob set aside, never overwritten |
| SEC-09 | ECDSA P-256 device signature plus RSA-PSS bundle endorsement (residual: C-10) |
| SEC-10 | scrypt 2^15 by default, readers capped |
| SEC-13 | NumberActionActivity asks for the unlock |
| SEC-14 | DiscreetRevealActivity |
| SEC-15 | `Bounded` |
| SEC-16 | `KdfPolicy.exactly` for QR, simple mode and invites |
| SEC-17 | `RecordCrypto` |
| SEC-18 | `OverlayGuard` / `SensitiveScreen` |
| SEC-19 | `SharedUris` for SEND / VIEW of vCards (residual: C-17) |
| SEC-21 | `WebAddressSheet` for http links in notes |
| SEC-23 | R8 strips every `Log` level; `CrashStore` keeps message-free stacks |
| SEC-25 | Platform Ed25519 on Android 13+ |
| SEC-26 | JOIN_CONTACT confirmation |

Also verified sound:
- **Backup envelope:** STREAM nonce = prefix ‖ counter ‖ last flag, header as AAD, bundle AAD binds the public key.
- **AppPin:** scrypt with a shared salt, `MessageDigest.isEqual` on both hashes, backoff in elapsed time, try counted
  before checking.
- **SignedCards strict parser.**
- **Shared labels:**
  - per-file AAD on label and name;
  - signed tickets, journals and header changes;
  - only versions that grow;
  - re-sign after removal (H2 / M1 / M3);
  - `plausibleVersion` cap.
- **PrivateDirectoryProvider:** trusts `callerPackage` only from CP2. **[uncertain]** on OEM ContactsProviders.
- **PendingIntents:** all immutable and explicit. Callback aliases are non-exported.

Reviewed with no issue found:
- **Contacts-provider writes:** Parley's custom mimetypes (pronouns, custom field, name parts, language) and DATA11
  (address parts) / DATA14 (calendar) carry only user-entered contact data. Google sync uses `GOOGLE_CUSTOM_FIELD`
  deliberately.
- **Shortcuts and favourites:** they read only `directory.contacts`, so private contacts never reach the launcher.

Not verified in this track: the strength gate in the backup setup UI, and `ExportedComponentsTest` coverage.
