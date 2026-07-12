import { Modal, Notice, Setting, type App, type ToggleComponent } from "obsidian";

export interface BackupExportOptions {
  format: "pack" | "archive";
  scope: "all" | "page";
  includeAttachments: boolean;
  includeExternal: boolean;
  encrypt: boolean;
  password: string;
  dateStamp: boolean;
  folder: string;
  filename: string;
}

/** Configure a backup / archive export: format, row scope, attachments, encryption, destination. */
export class BackupExportModal extends Modal {
  private readonly opts: BackupExportOptions;
  private confirm = "";
  private readonly hasPages: boolean;

  constructor(
    app: App,
    defaults: BackupExportOptions,
    hasPages: boolean,
    private readonly onSubmit: (options: BackupExportOptions) => void,
    private readonly allViews = false,
  ) {
    super(app);
    this.opts = { ...defaults };
    this.hasPages = hasPages;
  }

  override onOpen(): void {
    const { contentEl } = this;
    contentEl.empty();
    contentEl.addClass("kvs-export-modal");
    contentEl.createEl("h3", { text: this.allViews ? "Back up all views" : "Export backup", cls: "kvs-export-title" });

    new Setting(contentEl)
      .setName("Format")
      .setDesc("Portable package for everyday transfer, or archival package for long-term preservation.")
      .addDropdown((d) => {
        d.addOption("pack", "Portable package (.kvspack)");
        d.addOption("archive", "Archival package (.kvsarchive)");
        d.setValue(this.opts.format).onChange((v) => {
          this.opts.format = v as "pack" | "archive";
          formatHint.setText(this.formatHint());
        });
      });
    const formatHint = contentEl.createDiv({ cls: "kvs-export-hint", text: this.formatHint() });

    if (!this.allViews) {
      new Setting(contentEl)
        .setName("Rows")
        .setDesc("Back up the whole view, or only the page you're looking at.")
        .addDropdown((d) => {
          d.addOption("all", "All rows in the view");
          d.addOption("page", this.hasPages ? "Current page only" : "Current page (paging off — same as all)");
          d.setValue(this.opts.scope).onChange((v) => (this.opts.scope = v as "all" | "page"));
        });
    }

    let externalToggle: ToggleComponent | null = null;
    new Setting(contentEl)
      .setName("Include attachments")
      .setDesc("Bundle embedded images, PDFs and files so the backup is self-contained.")
      .addToggle((t) =>
        t.setValue(this.opts.includeAttachments).onChange((v) => {
          this.opts.includeAttachments = v;
          externalToggle?.setDisabled(!v);
        }),
      );
    new Setting(contentEl)
      .setName("Fetch external images")
      .setDesc("Download images referenced by web URL (requires internet at export time).")
      .addToggle((t) => {
        externalToggle = t;
        t.setValue(this.opts.includeExternal)
          .setDisabled(!this.opts.includeAttachments)
          .onChange((v) => (this.opts.includeExternal = v));
      });

    new Setting(contentEl)
      .setName("Encrypt with password")
      .setDesc("AES-256 encryption. Keep the password safe — without it the backup cannot be opened.")
      .addToggle((t) =>
        t.setValue(this.opts.encrypt).onChange((v) => {
          this.opts.encrypt = v;
          pwWrap.style.display = v ? "" : "none";
        }),
      );
    const pwWrap = contentEl.createDiv({ cls: "kvs-export-pw" });
    pwWrap.style.display = this.opts.encrypt ? "" : "none";
    new Setting(pwWrap).setName("Password").addText((t) => {
      t.inputEl.type = "password";
      t.onChange((v) => (this.opts.password = v));
    });
    new Setting(pwWrap).setName("Confirm password").addText((t) => {
      t.inputEl.type = "password";
      t.onChange((v) => (this.confirm = v));
    });

    new Setting(contentEl)
      .setName("Date-stamped filename")
      .setDesc("Append today's date so backups accumulate as versioned snapshots.")
      .addToggle((t) => t.setValue(this.opts.dateStamp).onChange((v) => (this.opts.dateStamp = v)));

    new Setting(contentEl)
      .setName(this.allViews ? "Backups folder" : "Destination folder")
      .setDesc(
        this.allViews
          ? "Each view is saved as its own file inside a dated subfolder here."
          : "Vault-relative folder. Blank saves to the vault root.",
      )
      .addText((t) => t.setPlaceholder(this.allViews ? "KVS Backups" : "Backups").setValue(this.opts.folder).onChange((v) => (this.opts.folder = v)));
    if (!this.allViews) {
      new Setting(contentEl)
        .setName("File name")
        .addText((t) => t.setValue(this.opts.filename).onChange((v) => (this.opts.filename = v)));
    }

    const foot = contentEl.createDiv({ cls: "kvs-export-foot" });
    foot.createEl("button", { text: "Cancel" }).addEventListener("click", () => this.close());
    foot.createEl("button", { cls: "mod-cta", text: this.allViews ? "Back up all" : "Export" }).addEventListener("click", () => this.submit());
  }

  private formatHint(): string {
    return this.opts.format === "archive"
      ? "ZIP with open CSV + JSON data, an HTML view, real attachment files, a README and SHA-256 checksums."
      : "A single self-contained JSON file — quick to share and re-import.";
  }

  private submit(): void {
    if (!this.allViews && this.opts.filename.trim() === "") {
      new Notice("Please enter a file name.");
      return;
    }
    if (this.opts.encrypt) {
      if (this.opts.password === "") {
        new Notice("Please enter a password, or turn off encryption.");
        return;
      }
      if (this.opts.password !== this.confirm) {
        new Notice("Passwords don't match.");
        return;
      }
    }
    this.close();
    this.onSubmit({ ...this.opts });
  }

  override onClose(): void {
    this.contentEl.empty();
  }
}
