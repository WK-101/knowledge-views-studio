import { describe, it, expect } from "vitest";
import {
  renderTemplate,
  templateVariables,
  splitExpression,
  formatDate,
  safeName,
  FILTERS,
  STANDARD_VARIABLES,
} from "../shared/template";

describe("template · variables", () => {
  it("substitutes what it's given", () => {
    expect(renderTemplate("# {{title}}", { title: "A Paper" })).toBe("# A Paper");
  });

  it("is forgiving about case and spacing, since templates are typed by hand", () => {
    expect(renderTemplate("{{ Title }}", { title: "X" })).toBe("X");
  });

  it("resolves an unknown variable to nothing rather than failing the capture", () => {
    expect(renderTemplate("a{{nope}}b", {})).toBe("ab");
  });

  it("leaves an empty expression alone rather than eating it", () => {
    expect(renderTemplate("{{}}", {})).toBe("{{}}");
  });

  it("substitutes the same variable everywhere it appears", () => {
    expect(renderTemplate("{{t}} — {{t}}", { t: "X" })).toBe("X — X");
  });

  it("lists the variables a template refers to", () => {
    expect(templateVariables("{{title}} {{author|upper}} {{title}}").sort()).toEqual(["author", "title"]);
  });

  it("names the variables every capture provides", () => {
    for (const name of ["title", "url", "content", "selection"]) {
      expect(STANDARD_VARIABLES).toContain(name);
    }
  });
});

describe("template · filters", () => {
  it("applies a single filter", () => {
    expect(renderTemplate("{{t|upper}}", { t: "abc" })).toBe("ABC");
  });

  it("chains filters left to right", () => {
    // The chained value must carry through each step, not restart from the original.
    expect(renderTemplate("{{t|trim|upper}}", { t: "  abc  " })).toBe("ABC");
    expect(renderTemplate("{{t|lower|capitalize}}", { t: "HELLO WORLD" })).toBe("Hello world");
  });

  it("passes an unknown filter through instead of failing", () => {
    expect(renderTemplate("{{t|nosuchfilter}}", { t: "abc" })).toBe("abc");
  });

  it("truncates with an ellipsis", () => {
    expect(renderTemplate("{{t|truncate:5}}", { t: "abcdefghij" })).toBe("abcde…");
    expect(renderTemplate("{{t|truncate:50}}", { t: "short" })).toBe("short");
  });

  it("replaces quoted pairs", () => {
    expect(renderTemplate('{{t|replace:"-"," "}}', { t: "a-b-c" })).toBe("a b c");
  });

  it("doesn't tear an expression apart on a pipe inside quotes", () => {
    // replace:"a|b","c" is legitimate; naive splitting would break it in half.
    expect(splitExpression('t|replace:"a|b","c"')).toEqual(['t', 'replace:"a|b","c"']);
  });

  it("makes a markdown list from a separated value", () => {
    expect(renderTemplate("{{a|list}}", { a: "Ada, Grace" })).toBe("- Ada\n- Grace");
  });

  it("splits on the separators author lists actually use", () => {
    expect(renderTemplate("{{a|first}}", { a: "Ada; Grace" })).toBe("Ada");
    expect(renderTemplate("{{a|first}}", { a: "アダ、グレース" })).toBe("アダ");
  });

  it("blockquotes every line, not just the first", () => {
    expect(renderTemplate("{{t|blockquote}}", { t: "one\ntwo" })).toBe("> one\n> two");
  });

  it("makes tags from a keyword list", () => {
    expect(renderTemplate("{{k|tags}}", { k: "machine learning, ai" })).toBe("#machine-learning #ai");
  });

  it("doesn't double a hash that's already there", () => {
    expect(renderTemplate("{{k|tags}}", { k: "#done" })).toBe("#done");
  });

  it("makes a wikilink, and nothing from nothing", () => {
    expect(renderTemplate("{{t|wikilink}}", { t: "A Paper" })).toBe("[[A Paper]]");
    expect(renderTemplate("{{t|wikilink}}", { t: "  " })).toBe("");
  });

  it("quotes values that would break YAML", () => {
    expect(renderTemplate("{{t|yaml}}", { t: "Rethinking: a study" })).toBe('"Rethinking: a study"');
    expect(renderTemplate("{{t|yaml}}", { t: "plain" })).toBe("plain");
  });

  it("strips markdown for a plain description", () => {
    expect(renderTemplate("{{t|plain}}", { t: "**bold** and [link](http://x)" })).toBe("bold and link");
  });

  it("slugs a title, including non-Latin input", () => {
    expect(renderTemplate("{{t|slug}}", { t: "Hello, World!" })).toBe("hello-world");
    expect(renderTemplate("{{t|slug}}", { t: "Café Life" })).toBe("cafe-life");
  });

  it("accepts a custom filter that overrides a standard one", () => {
    const out = renderTemplate("{{t|upper}}", { t: "abc" }, { filters: { upper: () => "custom" } });
    expect(out).toBe("custom");
  });

  it("chains a custom filter with standard ones", () => {
    const out = renderTemplate("{{t|shout|trim}}", { t: "hi" }, { filters: { shout: (v) => `${v}!!! ` } });
    expect(out).toBe("hi!!!");
  });
});

describe("template · dates", () => {
  it("formats with the tokens clipping tools use", () => {
    expect(formatDate("2026-07-18T14:05:09Z", "YYYY-MM-DD")).toBe("2026-07-18");
  });

  it("supports a filename-friendly pattern", () => {
    expect(formatDate("2026-01-02T00:00:00", "YYYY/MM/DD")).toBe("2026/01/02");
  });

  it("returns the input unchanged when it isn't a date", () => {
    expect(formatDate("not a date", "YYYY")).toBe("not a date");
  });

  it("is reachable as a filter with a quoted pattern", () => {
    expect(renderTemplate('{{d|date:"YYYY"}}', { d: "2026-07-18" })).toBe("2026");
  });
});

describe("template · safeName", () => {
  it("removes characters a path can't hold", () => {
    expect(safeName('a/b:c*d?"e')).toBe("abcde");
  });

  it("keeps non-Latin titles intact", () => {
    expect(safeName("日本語のタイトル")).toBe("日本語のタイトル");
  });

  it("falls back rather than producing an empty filename", () => {
    expect(safeName("///")).toBe("Untitled");
  });

  it("is available as a filter", () => {
    expect(renderTemplate("{{t|safe_name}}", { t: "a/b" })).toBe("ab");
  });
});

describe("template · realistic use", () => {
  it("renders a filename pattern", () => {
    const out = renderTemplate('{{published|date:"YYYY-MM-DD"}} {{title|safe_name|truncate:40}}', {
      published: "2026-07-18",
      title: "On the Origin of: Things",
    });
    expect(out).toBe("2026-07-18 On the Origin of Things");
  });

  it("renders a note with frontmatter without breaking on awkward values", () => {
    const template = ["---", "title: {{title|yaml}}", "url: {{url}}", "tags: {{keywords|tags}}", "---", "", "{{content}}"].join("\n");
    const out = renderTemplate(template, {
      title: "A study: part two",
      url: "https://example.com",
      keywords: "ai, research",
      content: "Body text.",
    });
    expect(out).toContain('title: "A study: part two"');
    expect(out).toContain("tags: #ai #research");
    expect(out).toContain("Body text.");
  });

  it("leaves a template with no variables untouched", () => {
    expect(renderTemplate("plain text", {})).toBe("plain text");
  });

  it("has every filter it advertises", () => {
    for (const name of ["upper", "date", "safe_name", "wikilink", "tags", "yaml", "list"]) {
      expect(typeof FILTERS[name]).toBe("function");
    }
  });
});
