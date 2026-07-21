import { describe, it, expect } from "vitest";
import { pluginIsCurrent, outdatedPluginMessage } from "../extension/src/lib/version";

describe("companion · plugin version check", () => {
  it("accepts the required version and anything newer", () => {
    expect(pluginIsCurrent("0.162.0", "0.162.0")).toBe(true);
    expect(pluginIsCurrent("0.163.0", "0.162.0")).toBe(true);
    expect(pluginIsCurrent("1.0.0", "0.162.0")).toBe(true);
  });

  it("rejects anything older", () => {
    expect(pluginIsCurrent("0.161.0", "0.162.0")).toBe(false);
    expect(pluginIsCurrent("0.140.0", "0.162.0")).toBe(false);
  });

  it("treats a missing or unreadable version as too old — old plugins don't report one at all", () => {
    // This is the case that actually happened: three sessions of endpoints calling into a plugin that
    // predates version reporting, with nothing anywhere saying so.
    expect(pluginIsCurrent(undefined, "0.162.0")).toBe(false);
    expect(pluginIsCurrent("", "0.162.0")).toBe(false);
    expect(pluginIsCurrent("nonsense", "0.162.0")).toBe(false);
  });

  it("compares numerically, not textually", () => {
    expect(pluginIsCurrent("0.200.0", "0.162.0")).toBe(true);
    expect(pluginIsCurrent("0.9.0", "0.162.0")).toBe(false);
  });

  it("names the reported version in the message, or says it's older", () => {
    expect(outdatedPluginMessage("0.140.0")).toContain("0.140.0");
    expect(outdatedPluginMessage(undefined)).toContain("older version");
  });
});
