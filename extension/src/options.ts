import { DEFAULT_BASE_URL, loadConnection, pair, saveConnection } from "./lib/bridge-client";
import { readQueue, writeQueue } from "./lib/queue-store";

/**
 * Pairing and connection settings.
 *
 * The pairing flow is deliberately plain: a code shown in Obsidian, typed here once. No account, no service,
 * nothing leaves the machine. The wording tries to make that legible rather than merely true — someone
 * granting a browser extension access to their notes deserves to understand exactly what they're granting.
 */

const byId = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

function status(message: string, kind: "info" | "error" | "ok" = "info"): void {
  const el = byId("status");
  el.textContent = message;
  el.className = `status ${kind}`;
}

async function refresh(): Promise<void> {
  const connection = await loadConnection();
  byId<HTMLInputElement>("baseUrl").value = connection.baseUrl;
  byId("paired").textContent = connection.token === null ? "Not paired" : "Paired with a vault";
  byId("unpair").toggleAttribute("hidden", connection.token === null);

  const queue = await readQueue();
  byId("queue").textContent =
    queue.length === 0
      ? "Nothing waiting."
      : `${String(queue.length)} capture(s) waiting for your vault.`;
  byId("clearQueue").toggleAttribute("hidden", queue.length === 0);
}

async function doPair(): Promise<void> {
  const baseUrl = byId<HTMLInputElement>("baseUrl").value.trim() || DEFAULT_BASE_URL;
  const code = byId<HTMLInputElement>("code").value.trim();
  if (code === "") {
    status("Enter the code shown in Obsidian's settings.", "error");
    return;
  }
  status("Pairing…");
  try {
    const result = await pair(baseUrl, code);
    await saveConnection({ baseUrl, token: result.token });
    byId<HTMLInputElement>("code").value = "";
    status(`Paired with “${result.vault}”.`, "ok");
    await refresh();
  } catch (error) {
    status(error instanceof Error ? error.message : "Pairing failed.", "error");
  }
}

document.addEventListener("DOMContentLoaded", () => {
  void refresh();
  byId("pair").addEventListener("click", () => void doPair());
  byId("unpair").addEventListener("click", () => {
    void saveConnection({ token: "" }).then(() => {
      status("Unpaired. Your vault still holds its own token — revoke it there too if you want it gone.", "info");
      return refresh();
    });
  });
  byId("clearQueue").addEventListener("click", () => {
    void writeQueue([]).then(() => {
      status("Cleared what was waiting.", "info");
      return refresh();
    });
  });
});
