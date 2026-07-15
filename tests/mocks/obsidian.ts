// Minimal stand-ins for the Obsidian runtime, so services/views modules that make a *value* import
// from "obsidian" (e.g. `Notice`) can be unit-tested in Node. Type-only imports need nothing here.
export class Notice {
  constructor(_message?: string, _timeout?: number) {}
  setMessage(): this {
    return this;
  }
  hide(): void {}
}

export class Component {
  load(): void {}
  unload(): void {}
  registerEvent(): void {}
}

export class Modal {
  contentEl = { empty(): void {}, createEl(): unknown { return {}; }, createDiv(): unknown { return {}; } };
  open(): void {}
  close(): void {}
}

export function setIcon(): void {}
export function setTooltip(): void {}
export function normalizePath(p: string): string {
  return p.replace(/\\/g, "/").replace(/\/+/g, "/").replace(/^\/|\/$/g, "");
}
export class TFile {
  path = "";
  name = "";
  basename = "";
  extension = "md";
}
