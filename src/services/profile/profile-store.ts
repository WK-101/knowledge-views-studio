import { Emitter, type Unsubscribe } from "../../util/emitter";
import { debounce, type Debounced } from "../../util/debounce";
import {
  DEFAULT_DATA,
  createProfile,
  type GlobalSettings,
  type PluginData,
  type Profile,
} from "./profile";

export interface ProfileStoreOptions {
  readonly data: PluginData;
  /** Persist a snapshot (the plugin wires this to `saveData`). */
  readonly persist: (data: PluginData) => Promise<void>;
  /** Debounce window for coalescing writes (default 600ms). */
  readonly debounceMs?: number;
}

const clone = <T>(value: T): T => structuredClone(value);

/**
 * Owns the persisted plugin data and is the single writer to it. Mutations are
 * coalesced and debounced (the legacy code persisted on every change), and
 * subscribers are notified so open views can update.
 */
export class ProfileStore {
  private data: PluginData;
  private readonly emitter = new Emitter<void>();
  private readonly persist: (data: PluginData) => Promise<void>;
  private readonly debouncedPersist: Debounced<[]>;

  constructor(options: ProfileStoreOptions) {
    this.data = clone(options.data);
    this.persist = options.persist;
    this.debouncedPersist = debounce(() => void this.persist(clone(this.data)), options.debounceMs ?? 600);
  }

  // ---- reads ----
  listProfiles(): Profile[] {
    return [...this.data.profiles];
  }
  getProfile(id: string): Profile | undefined {
    return this.data.profiles.find((p) => p.id === id);
  }
  getActiveProfileId(): string | null {
    return this.data.activeProfileId;
  }
  getActiveProfile(): Profile | undefined {
    return this.data.activeProfileId ? this.getProfile(this.data.activeProfileId) : undefined;
  }
  getSettings(): GlobalSettings {
    return this.data.settings;
  }

  // ---- writes ----
  addProfile(profile: Profile): Profile {
    this.data = { ...this.data, profiles: [...this.data.profiles, profile] };
    this.markDirty();
    return profile;
  }

  patchProfile(id: string, patch: Partial<Profile>): void {
    this.data = {
      ...this.data,
      profiles: this.data.profiles.map((p) => (p.id === id ? { ...p, ...patch, id } : p)),
    };
    this.markDirty();
  }

  removeProfile(id: string): void {
    const profiles = this.data.profiles.filter((p) => p.id !== id);
    const activeProfileId = this.data.activeProfileId === id ? null : this.data.activeProfileId;
    this.data = { ...this.data, profiles, activeProfileId };
    this.markDirty();
  }

  reorderProfiles(orderedIds: readonly string[]): void {
    const byId = new Map(this.data.profiles.map((p) => [p.id, p]));
    const ordered: Profile[] = [];
    for (const id of orderedIds) {
      const profile = byId.get(id);
      if (profile) {
        ordered.push(profile);
        byId.delete(id);
      }
    }
    ordered.push(...byId.values()); // keep any not mentioned
    this.data = { ...this.data, profiles: ordered };
    this.markDirty();
  }

  setActiveProfile(id: string | null): void {
    this.data = { ...this.data, activeProfileId: id };
    this.markDirty();
  }

  updateSettings(patch: Partial<GlobalSettings>): void {
    this.data = { ...this.data, settings: { ...this.data.settings, ...patch } };
    this.markDirty();
  }

  // ---- import / export ----
  exportProfile(id: string): string {
    const profile = this.getProfile(id);
    if (!profile) throw new Error(`No profile with id "${id}"`);
    return JSON.stringify(profile, null, 2);
  }

  exportAll(): string {
    return JSON.stringify(this.data, null, 2);
  }

  /** Import a profile from JSON, assigning a fresh id so it never collides. */
  importProfile(json: string): Profile {
    const parsed: unknown = JSON.parse(json);
    if (typeof parsed !== "object" || parsed === null) {
      throw new Error("Invalid profile JSON");
    }
    const profile = createProfile({ ...(parsed as Partial<Profile>), id: undefined });
    return this.addProfile(profile);
  }

  // ---- lifecycle ----
  onChange(listener: () => void): Unsubscribe {
    return this.emitter.on(listener);
  }

  /** Persist immediately (e.g. on plugin unload), cancelling any pending debounce. */
  async flush(): Promise<void> {
    this.debouncedPersist.cancel();
    await this.persist(clone(this.data));
  }

  dispose(): void {
    this.debouncedPersist.cancel();
    this.emitter.clear();
  }

  private markDirty(): void {
    this.emitter.emit();
    this.debouncedPersist();
  }
}

export function createEmptyStore(persist: (data: PluginData) => Promise<void>): ProfileStore {
  return new ProfileStore({ data: DEFAULT_DATA, persist });
}
