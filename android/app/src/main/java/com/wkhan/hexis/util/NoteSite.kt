package com.wkhan.hexis.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Wave 3 · Read-Anywhere — publish the vault as a self-contained, browsable **offline** website. Every
 * page is one plain `.html` file with inline CSS + JS and no external asset, so the bundle opens in ANY
 * browser on ANY device — a laptop, an iPhone — with no app, no account, and no server. This is the only
 * honest answer to the Android-only reach gap: the *other* device's browser does the rendering, so Hexis
 * never reaches out and the no-INTERNET promise holds. `[[wiki-links]]` become working anchors and the
 * index carries a tiny client-side search. Body HTML is produced by [NoteRichRenderer.bodyHtml] (pure
 * commonmark → HTML); heavy math/diagram/highlighter assets are intentionally omitted to keep the bundle
 * dependency-free, so those render as plain text.
 */
object NoteSite {
    data class Note(val id: String, val title: String, val markdown: String, val updatedAt: Long, val tags: List<String>)

    private val WIKI = Regex("\\[\\[([^\\[\\]\\n]+)]]")
    private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun pageName(id: String): String = "note-${safeId(id)}.html"
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun escAttr(s: String) = esc(s).replace("\"", "&quot;")

    /** Build the whole site as path → file-content. Always includes `index.html`. */
    fun build(notes: List<Note>, siteTitle: String = "My notes"): Map<String, String> {
        val byTitle = HashMap<String, String>()   // lowercased title → id (first wins)
        for (n in notes) byTitle.putIfAbsent(n.title.trim().lowercase(), n.id)

        val files = LinkedHashMap<String, String>()
        for (n in notes) {
            val linked = WIKI.replace(n.markdown) { m ->
                val raw = m.groupValues[1]
                val title = raw.substringBefore("#").trim()
                val id = byTitle[title.trim().lowercase()]
                if (id != null) "[$title](${pageName(id)})" else title
            }
            val body = NoteRichRenderer.bodyHtml(linked)
            files[pageName(n.id)] = notePage(n, body)
        }
        files["index.html"] = indexPage(notes, siteTitle)
        return files
    }

    private fun fmtDate(ms: Long): String = runCatching {
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
    }.getOrDefault("")

    private fun notePage(n: Note, bodyHtml: String): String {
        val tagsHtml = if (n.tags.isEmpty()) "" else
            "<div class=\"tags\">" + n.tags.joinToString("") { "<span>#${esc(it)}</span>" } + "</div>"
        return page(esc(n.title.ifBlank { "Untitled" })) {
            """
            <p class="crumb"><a href="index.html">← All notes</a></p>
            <h1>${esc(n.title.ifBlank { "Untitled" })}</h1>
            <p class="meta">Updated ${fmtDate(n.updatedAt)}</p>
            $tagsHtml
            <article>$bodyHtml</article>
            """.trimIndent()
        }
    }

    private fun indexPage(notes: List<Note>, siteTitle: String): String {
        // A compact client-side search index (title + a plain-text snippet + tags).
        val json = notes.joinToString(",", "[", "]") { n ->
            val snippet = n.markdown.replace(Regex("[#*_`>~\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().take(160)
            """{"h":"${jsStr(pageName(n.id))}","t":"${jsStr(n.title.ifBlank { "Untitled" })}","s":"${jsStr(snippet)}","g":"${jsStr(n.tags.joinToString(" "))}"}"""
        }
        return page(esc(siteTitle)) {
            """
            <h1>${esc(siteTitle)}</h1>
            <p class="meta">${notes.size} ${if (notes.size == 1) "note" else "notes"} · a self-contained offline copy — open in any browser</p>
            <input id="q" type="search" placeholder="Search notes…" autocomplete="off" aria-label="Search notes">
            <ul id="list"></ul>
            <p class="foot">Exported from Hexis · fully offline · no account, no server. Math and diagrams show as plain text in this portable copy.</p>
            <script>
              var IDX=$json;
              var list=document.getElementById('list'), q=document.getElementById('q');
              function esc(s){return s.replace(/[&<>]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}
              function render(items){
                list.innerHTML=items.map(function(n){
                  return '<li><a href="'+n.h+'"><span class="t">'+esc(n.t)+'</span>'+
                    (n.s?'<span class="s">'+esc(n.s)+'</span>':'')+'</a></li>';
                }).join('')||'<li class="empty">No matches</li>';
              }
              function filter(){
                var v=q.value.trim().toLowerCase();
                if(!v){render(IDX);return;}
                render(IDX.filter(function(n){return (n.t+' '+n.s+' '+n.g).toLowerCase().indexOf(v)>=0;}));
              }
              q.addEventListener('input',filter); render(IDX);
            </script>
            """.trimIndent()
        }
    }

    private fun jsStr(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", " ").replace("\r", " ").replace("\t", " ")

    /** The shared self-contained page shell — light + dark, inline everything. */
    private fun page(title: String, body: () -> String): String = """
        <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$title</title>
        <style>
          :root{--bg:#f7f5f0;--fg:#1c1e1b;--muted:#7c7f77;--accent:#0e7c6b;--line:#e2ded3;--card:#fffefb;}
          @media (prefers-color-scheme:dark){:root{--bg:#131512;--fg:#e9e7df;--muted:#83867b;--accent:#3cbfab;--line:#2f342a;--card:#1b1e19;}}
          *{box-sizing:border-box} body{margin:0;background:var(--bg);color:var(--fg);
            font-family:-apple-system,Segoe UI,Roboto,system-ui,sans-serif;line-height:1.6;}
          .wrap{max-width:720px;margin:0 auto;padding:24px 18px 60px;}
          a{color:var(--accent);text-decoration:none} a:hover{text-decoration:underline}
          h1{font-size:1.7em;line-height:1.2;margin:.2em 0 .3em} h2{font-size:1.35em} h3{font-size:1.15em}
          .crumb{margin:0 0 8px;font-size:.9em} .meta{color:var(--muted);font-size:.85em;margin:.2em 0 1em}
          .tags{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:14px}
          .tags span{background:var(--card);border:1px solid var(--line);border-radius:999px;padding:2px 9px;font-size:.8em;color:var(--muted)}
          article :is(p,ul,ol,blockquote,pre,table){margin:.6em 0}
          code{background:var(--card);border:1px solid var(--line);border-radius:5px;padding:.1em .35em;font-size:.9em}
          pre{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px;overflow-x:auto}
          pre code{background:none;border:0;padding:0}
          blockquote{border-inline-start:3px solid var(--line);margin:0;padding:.1em 1em;color:var(--muted)}
          table{border-collapse:collapse;display:block;overflow-x:auto} th,td{border:1px solid var(--line);padding:.4em .7em}
          img{max-width:100%;height:auto;border-radius:8px}
          input#q{width:100%;padding:11px 14px;border:1px solid var(--line);border-radius:12px;background:var(--card);
            color:var(--fg);font-size:15px;margin:6px 0 16px}
          ul#list{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:8px}
          ul#list li a{display:block;background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px 14px;color:var(--fg)}
          ul#list li a:hover{text-decoration:none;border-color:var(--accent)}
          ul#list .t{display:block;font-weight:600} ul#list .s{display:block;color:var(--muted);font-size:.85em;margin-top:3px}
          li.empty{color:var(--muted);padding:12px}
          .foot{color:var(--muted);font-size:.78em;margin-top:34px;border-top:1px solid var(--line);padding-top:14px}
        </style></head><body><div class="wrap">${body()}</div></body></html>
    """.trimIndent()

    /** Write the built files into a user-picked SAF tree (one folder). Returns files written, or -1 on failure. */
    fun writeToTree(context: Context, folderUri: String, files: Map<String, String>): Int {
        val dir = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) }.getOrNull()
            ?.takeIf { it.isDirectory } ?: return -1
        var written = 0
        for ((name, content) in files) {
            val ok = runCatching {
                val existing = dir.findFile(name)
                val file = existing ?: dir.createFile("text/html", name) ?: return@runCatching false
                context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(content.toByteArray()) }
                true
            }.getOrDefault(false)
            if (ok) written++
        }
        return written
    }
}
