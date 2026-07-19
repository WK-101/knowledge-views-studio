import { next, retryDelayMs } from "../../shared/queue";
import { BridgeError, capture, loadConnection } from "./lib/bridge-client";
import { dropFromQueue, pruneQueue, readQueue, recordFailure } from "./lib/queue-store";

/**
 * Draining what couldn't be delivered.
 *
 * Runs on a modest alarm rather than a tight loop: the usual reason a capture is waiting is that Obsidian
 * simply isn't open yet, which resolves on the scale of minutes. Retrying harder wouldn't make it resolve
 * sooner, and would spend the machine's battery insisting.
 *
 * One capture is attempted per wake-up. If the vault is unreachable the rest are certainly unreachable too,
 * so there's nothing to gain from trying them all — and it keeps the failure count honest, since a queue of
 * twenty shouldn't burn through its retry budget in a single offline moment.
 */

interface AlarmsApi {
  create(name: string, info: { periodInMinutes?: number; delayInMinutes?: number }): void;
  onAlarm: { addListener(fn: (alarm: { name: string }) => void): void };
}
interface RuntimeApi {
  onInstalled: { addListener(fn: () => void): void };
  onStartup?: { addListener(fn: () => void): void };
}

const ALARM = "kvs-drain";

const alarms = (): AlarmsApi | null => {
  const g = globalThis as unknown as { browser?: { alarms?: AlarmsApi }; chrome?: { alarms?: AlarmsApi } };
  return g.browser?.alarms ?? g.chrome?.alarms ?? null;
};
const runtime = (): RuntimeApi | null => {
  const g = globalThis as unknown as { browser?: { runtime?: RuntimeApi }; chrome?: { runtime?: RuntimeApi } };
  return g.browser?.runtime ?? g.chrome?.runtime ?? null;
};

async function drain(): Promise<void> {
  await pruneQueue();
  const queue = await readQueue();
  const entry = next(queue);
  if (entry === null) return;

  // Respect the backoff: an entry that just failed shouldn't be retried on the very next wake-up.
  const due = entry.attempts === 0 || Date.now() - entry.queuedAt > retryDelayMs(entry.attempts);
  if (!due) return;

  try {
    const connection = await loadConnection();
    if (connection.token === null) return;
    const result = await capture(connection, entry.request);
    if (result.ok) {
      await dropFromQueue(entry.id);
      return;
    }
    // A refusal the vault understood — retrying won't change the answer, so stop asking.
    await dropFromQueue(entry.id);
  } catch (error) {
    const message = error instanceof BridgeError ? error.message : "Unknown error";
    if (error instanceof BridgeError && !error.offline) {
      // The vault answered and said no. Keep the record but don't retry forever.
      await recordFailure(entry.id, message);
      return;
    }
    await recordFailure(entry.id, message);
  }
}

const alarmApi = alarms();
if (alarmApi !== null) {
  alarmApi.create(ALARM, { periodInMinutes: 1 });
  alarmApi.onAlarm.addListener((alarm) => {
    if (alarm.name === ALARM) void drain();
  });
}

const runtimeApi = runtime();
runtimeApi?.onInstalled.addListener(() => void drain());
runtimeApi?.onStartup?.addListener(() => void drain());
