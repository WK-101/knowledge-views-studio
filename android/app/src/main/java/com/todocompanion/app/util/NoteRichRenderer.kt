package com.todocompanion.app.util

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Wave L — the offline rich canvas. Builds a fully self-contained HTML document for a note, rendered in
 * a WebView against bundled `file:///android_asset/rich/` assets — KaTeX (math), Mermaid (diagrams) and
 * Prism (syntax highlighting), all local. Nothing is fetched over a wire (the app holds no INTERNET
 * permission), so the assets MUST be bundled and remote `<img src="http…">` can never load — we replace
 * those with a visible "blocked" glyph as defence-in-depth.
 *
 * This object is pure (no Android): the caller passes the note's Markdown, a [Theme] built from the
 * Material 3 colour scheme, and an [images] map from a note's image reference to a resolved `file://`
 * path, so the document assembly, the math/diagram/code gating, and the image/callout rewriting are all
 * unit-testable. Heavy scripts are included only when the note actually needs them.
 */
object NoteRichRenderer {

    /** The colours the rendered note should adopt, as CSS hex strings, so notes match app theming. */
    data class Theme(
        val bg: String, val fg: String, val muted: String, val accent: String,
        val codeBg: String, val border: String, val quoteBar: String, val dark: Boolean,
        // Wave Q — typography, so a reading theme + the user's type settings flow into the WebView too.
        val fontFamily: String = "system",   // system | serif | sans | mono
        val fontScalePct: Int = 100,
        val lineHeight: Float = 1.62f,
        val measureCh: Int = 0,               // 0 = full width; >0 caps the reading measure
    )

    private fun fontStack(f: String): String = when (f) {
        "serif" -> "Georgia,'Times New Roman','Noto Serif',serif"
        "sans" -> "'Segoe UI',Roboto,system-ui,-apple-system,sans-serif"
        "mono" -> "ui-monospace,'JetBrains Mono',Menlo,Consolas,monospace"
        else -> "-apple-system,Roboto,'Segoe UI',system-ui,sans-serif"
    }

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
        .build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
        .build()

    // ── content gating ──────────────────────────────────────────────────────────
    private val mathInline = Regex("(?<!\\\\)\\$[^\\$\\n]+\\$")
    private val mathBlock = Regex("\\$\\$[\\s\\S]+?\\$\\$")
    private val mathParen = Regex("\\\\\\(|\\\\\\[")
    private val mermaidFence = Regex("(?m)^```\\s*mermaid\\b")
    private val langFence = Regex("(?m)^```\\s*[A-Za-z0-9_+-]+")

    fun hasMath(md: String): Boolean =
        mathBlock.containsMatchIn(md) || mathInline.containsMatchIn(md) || mathParen.containsMatchIn(md)
    fun hasMermaid(md: String): Boolean = mermaidFence.containsMatchIn(md)
    /** Any fenced block with a language token (mermaid excluded — it's a diagram, not highlighted code). */
    fun hasCode(md: String): Boolean =
        langFence.findAll(md).any { !it.value.substringAfter("```").trim().equals("mermaid", true) }

    /** Render just the note body to HTML (commonmark → HTML, GFM extensions). Public for reuse (export). */
    fun bodyHtml(md: String): String = runCatching { renderer.render(parser.parse(md)) }.getOrElse { escape(md) }

    /**
     * Assemble the complete HTML document. [images] maps a Markdown image ref (the `src` as written) to a
     * resolved local `file://` path; refs not in the map that look remote (`http`) render as blocked.
     */
    fun buildDocument(source: String, theme: Theme, images: Map<String, String> = emptyMap()): String {
        val md = csvToTables(source)   // Wave M — a ```csv block becomes a GFM table before parsing
        val math = hasMath(md); val mermaid = hasMermaid(md); val code = hasCode(md)
        val body = rewriteImages(bodyHtml(md), images)
        val prismTheme = if (theme.dark) "prism/prism-dark.css" else "prism/prism-light.css"
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=5\">")
            if (math) append("<link rel=\"stylesheet\" href=\"katex/katex.min.css\">")
            if (code) append("<link rel=\"stylesheet\" href=\"$prismTheme\">")
            append("<style>").append(css(theme)).append("</style>")
            append("</head><body><div class=\"kairo-note\">").append(body).append("</div>")
            append("<script>").append(calloutJs()).append("</script>")
            if (math) {
                append("<script src=\"katex/katex.min.js\"></script>")
                append("<script src=\"katex/auto-render.min.js\"></script>")
                append("<script>").append(katexInit()).append("</script>")
            }
            if (code) {
                append("<script src=\"prism/prism-core.min.js\"></script>")
                for (c in listOf("markup", "css", "clike", "javascript", "typescript", "c", "cpp",
                        "java", "kotlin", "python", "bash", "json", "yaml", "sql", "rust", "go"))
                    append("<script src=\"prism/prism-$c.min.js\"></script>")
                append("<script>if(window.Prism)Prism.highlightAll();</script>")
            }
            if (mermaid) {
                append("<script src=\"mermaid.min.js\"></script>")
                append("<script>").append(mermaidInit(theme.dark)).append("</script>")
            }
            append("</body></html>")
        }
    }

    // ── image rewriting: local refs → file://, remote → blocked glyph ──
    private val imgTag = Regex("<img\\s+([^>]*?)src=\"([^\"]*)\"([^>]*)>", RegexOption.IGNORE_CASE)
    private fun rewriteImages(html: String, images: Map<String, String>): String =
        imgTag.replace(html) { m ->
            val pre = m.groupValues[1]; val src = m.groupValues[2]; val post = m.groupValues[3]
            val local = images[src]
            when {
                local != null -> "<img ${pre}src=\"${escapeAttr(local)}\"$post>"
                src.startsWith("file://") -> m.value
                src.startsWith("http://", true) || src.startsWith("https://", true) ->
                    "<span class=\"img-blocked\">🚫 remote image blocked (offline)</span>"
                else -> "<span class=\"img-missing\">🖼️ ${escape(src)}</span>"
            }
        }

    private fun katexInit() = """
        document.addEventListener('DOMContentLoaded',function(){
          try{renderMathInElement(document.body,{delimiters:[
            {left:'${'$'}${'$'}',right:'${'$'}${'$'}',display:true},
            {left:'${'$'}',right:'${'$'}',display:false},
            {left:'\\(',right:'\\)',display:false},
            {left:'\\[',right:'\\]',display:true}],throwOnError:false});}catch(e){}
        });
    """.trimIndent()

    private fun mermaidInit(dark: Boolean) = """
        (function(){
          document.querySelectorAll('pre > code.language-mermaid').forEach(function(c){
            var pre=c.parentElement, d=document.createElement('pre');
            d.className='mermaid'; d.textContent=c.textContent; pre.parentElement.replaceChild(d,pre);
          });
          try{mermaid.initialize({startOnLoad:true,securityLevel:'strict',theme:'${if (dark) "dark" else "default"}'});}catch(e){}
        })();
    """.trimIndent()

    /** Transform GitHub-style callout blockquotes `> [!NOTE]` into typed, coloured admonitions. */
    private fun calloutJs() = """
        (function(){
          var kinds={NOTE:['🛈','#3C82F6'],TIP:['💡','#1AA179'],IMPORTANT:['❗','#8A5CF6'],WARNING:['⚠️','#C08400'],CAUTION:['⛔','#D2453D']};
          document.querySelectorAll('blockquote').forEach(function(q){
            var p=q.querySelector('p'); if(!p)return;
            var m=(p.textContent||'').match(/^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*/i);
            if(!m)return; var k=m[1].toUpperCase(), info=kinds[k];
            p.innerHTML=p.innerHTML.replace(/^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*(<br\s*\/?>)?/i,'');
            q.classList.add('callout'); q.style.borderInlineStartColor=info[1];
            var h=document.createElement('div'); h.className='callout-h'; h.style.color=info[1];
            h.textContent=info[0]+'  '+k.charAt(0)+k.slice(1).toLowerCase();
            q.insertBefore(h,q.firstChild);
          });
        })();
    """.trimIndent()

    private fun css(t: Theme) = """
        html,body{margin:0;padding:0;background:${t.bg};color:${t.fg};
          font-family:${fontStack(t.fontFamily)};line-height:${t.lineHeight};font-size:${16 * t.fontScalePct / 100}px;
          -webkit-text-size-adjust:100%;overflow-wrap:break-word;word-break:break-word;}
        .kairo-note{padding:14px 16px 40px;${if (t.measureCh > 0) "max-width:${t.measureCh}ch;margin:0 auto;" else ""}}
        h1,h2,h3,h4{line-height:1.25;margin:1.1em 0 .5em;font-weight:650;}
        h1{font-size:1.7em} h2{font-size:1.4em} h3{font-size:1.2em}
        a{color:${t.accent};text-decoration:none} a:active{opacity:.6}
        p{margin:.6em 0}
        ul,ol{padding-inline-start:1.4em;margin:.5em 0}
        li{margin:.2em 0}
        input[type=checkbox]{margin-inline-end:.4em;transform:scale(1.15)}
        code{font-family:ui-monospace,'JetBrains Mono',monospace;font-size:.9em;
          background:${t.codeBg};padding:.1em .35em;border-radius:5px}
        pre{background:${t.codeBg};padding:.85em 1em;border-radius:12px;overflow-x:auto;margin:.7em 0}
        pre code{background:none;padding:0;font-size:.86em;line-height:1.5}
        pre.mermaid{background:transparent;text-align:center;overflow-x:auto}
        blockquote{border-inline-start:3px solid ${t.quoteBar};margin:.7em 0;padding:.1em 1em;color:${t.muted}}
        blockquote.callout{border-inline-start-width:4px;background:${t.codeBg};border-radius:0 10px 10px 0;padding:.6em 1em}
        .callout-h{font-weight:700;margin-bottom:.2em;font-size:.92em;letter-spacing:.01em}
        table{border-collapse:collapse;margin:.7em 0;display:block;overflow-x:auto}
        th,td{border:1px solid ${t.border};padding:.45em .8em;text-align:start}
        th{background:${t.codeBg};font-weight:650}
        hr{border:0;border-top:1px solid ${t.border};margin:1.4em 0}
        img{max-width:100%;height:auto;border-radius:10px;margin:.3em 0}
        del{opacity:.6}
        .img-blocked,.img-missing{display:inline-block;padding:.3em .6em;border:1px dashed ${t.border};
          border-radius:8px;color:${t.muted};font-size:.85em}
        .katex-display{overflow-x:auto;overflow-y:hidden;padding:.2em 0}
    """.trimIndent()

    // ── Wave M · CSV → GFM table (a ```csv fenced block renders as a real table) ──
    private val csvFence = Regex("(?ms)^```csv[^\\n]*\\n(.*?)\\n```[ \\t]*$")
    internal fun csvToTables(md: String): String = csvFence.replace(md) { m -> csvToGfm(m.groupValues[1]) }

    private fun csvToGfm(block: String): String {
        val rows = block.trim('\n').split("\n").filter { it.isNotBlank() }.map { splitCsv(it) }
        if (rows.isEmpty()) return block
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val sb = StringBuilder("\n")
        fun row(cells: List<String>) {
            sb.append("|")
            for (i in 0 until cols) sb.append(' ').append((cells.getOrNull(i) ?: "").trim().replace("|", "\\|")).append(" |")
            sb.append("\n")
        }
        row(rows[0]); sb.append("|"); repeat(cols) { sb.append(" --- |") }; sb.append("\n")
        rows.drop(1).forEach { row(it) }
        return sb.toString()
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> if (q && i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else q = !q
                ch == ',' && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString()); return out
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun escapeAttr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;")
}
