import type { CaptureRequest } from "../../../shared/protocol";
import { enqueueUnique, markFailed, prune, remove, type QueuedCapture } from "../../../shared/queue";
import { api } from "./bridge-client";

/**
 * Where queued captures live between attempts.
 *
 * A thin adapter over extension storage: the rules about what to retry and what to give up on are in
 * shared/queue.ts, tested there. This only reads and writes.
 */

const KEY = "queue";

export async function readQueue(): Promise<QueuedCapture[]> {
  const stored = await api().storage.local.get([KEY]);
  const raw = stored[KEY];
  return Array.isArray(raw) ? (raw as QueuedCapture[]) : [];
}

export async function writeQueue(queue: readonly QueuedCapture[]): Promise<void> {
  await api().storage.local.set({ [KEY]: queue });
}

function newId(): string {
  return `q-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/** Hold a capture the vault couldn't take. */
export async function queueCapture(request: CaptureRequest): Promise<void> {
  const queue = await readQueue();
  await writeQueue(enqueueUnique(queue, request, Date.now(), newId()));
}

export async function dropFromQueue(id: string): Promise<void> {
  await writeQueue(remove(await readQueue(), id));
}

export async function recordFailure(id: string, error: string): Promise<void> {
  await writeQueue(markFailed(await readQueue(), id, error));
}

/** Discard what's too old or has failed too often. Returns how many were given up on. */
export async function pruneQueue(): Promise<number> {
  const { kept, dropped } = prune(await readQueue(), Date.now());
  if (dropped.length > 0) await writeQueue(kept);
  return dropped.length;
}
