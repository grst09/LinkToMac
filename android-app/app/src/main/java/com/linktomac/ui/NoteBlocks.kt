package com.linktomac.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/**
 * Mirrors `desktop-app/src/components/NotesView.tsx`'s block model. The Mac's note editor is a
 * real rich-text (TipTap/ProseMirror) editor now, so `body` is genuine HTML — headings, bold/
 * italic/strikethrough marks, bullet/numbered/task lists, inline images — rather than the old
 * markdown-ish plain text (`**bold**`, `- [ ] `, `# heading`) with `![](data:image/...)` image
 * tokens. A body from before this change (or one this phone created and never opened on the Mac)
 * still uses that old format, so both parsers have to keep working side by side.
 *
 * There is no separate format-marking field on either end — the heuristic (matching the Mac's
 * own `looksLikeHtml`) is: a legacy body never starts with `<`, because plain text/markdown never
 * does and every HTML block the Mac emits does. See [parseNoteBlocks].
 */
private val IMAGE_TOKEN_REGEX = Regex("""!\[]\((data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+)\)""")

/** One run of inline text sharing the same marks — the leaves of a paragraph/heading/list item. */
data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false
)

/** One task-list `<li>`. [rangeInBlockHtml] is the exact `start until end` span of this `<li>...
 *  </li>` within its owning [NoteBlock.Rich.rawHtml] — kept so a checkbox toggle can splice just
 *  that substring rather than rebuilding the list from the parsed model (see [toggleTaskItem]). */
data class TaskItem(
    val checked: Boolean,
    val runs: List<InlineRun>,
    val rangeInBlockHtml: IntRange
)

/** How a [NoteBlock.Rich] block should be rendered. */
sealed class RichDisplay {
    data class Heading(val runs: List<InlineRun>) : RichDisplay()
    data class Paragraph(val runs: List<InlineRun>) : RichDisplay()
    data class BulletList(val items: List<List<InlineRun>>) : RichDisplay()
    data class NumberedList(val items: List<List<InlineRun>>) : RichDisplay()
    data class TaskList(val items: List<TaskItem>) : RichDisplay()
}

/** Flattens a block's content to plain text — used to seed the text field when the user taps a
 *  rich block to edit it (see the trade-off note on [NoteBlock.Rich]), and for the card-grid
 *  preview snippet. */
fun RichDisplay.plainText(): String = when (this) {
    is RichDisplay.Heading -> runs.joinToString("") { it.text }
    is RichDisplay.Paragraph -> runs.joinToString("") { it.text }
    is RichDisplay.BulletList -> items.joinToString("\n") { item -> item.joinToString("") { it.text } }
    is RichDisplay.NumberedList -> items.joinToString("\n") { item -> item.joinToString("") { it.text } }
    is RichDisplay.TaskList -> items.joinToString("\n") { it.runs.joinToString("") { r -> r.text } }
}

sealed class NoteBlock {
    abstract val id: String

    /** A plain-text block: either from a legacy body, or a rich block the user tapped to edit —
     *  see the doc comment on [Rich] for why editing flattens formatting rather than preserving it. */
    data class Text(override val id: String, val text: String) : NoteBlock()
    data class Image(override val id: String, val dataUrl: String) : NoteBlock()

    /**
     * A block parsed out of an HTML-format body (heading/paragraph/list/task-list), rendered with
     * real Compose styling via [display]. [rawHtml] is the exact original substring for this
     * block, and is what gets written back out verbatim on save as long as the user never taps
     * into it — so every *other* block in the note is guaranteed byte-for-byte unaffected by
     * editing this one.
     *
     * Editing trade-off: Android's editor keeps the existing "tap a block, edit its plain text"
     * UX rather than growing a rich-text toolbar (out of scope — see task notes). Tapping a Rich
     * block's text converts it in place to a [Text] block seeded with [RichDisplay.plainText] —
     * i.e. editing a rich block flattens *that block's own* formatting back to plain text, which
     * then round-trips as a plain `<p>` on save. This never touches sibling blocks. The one
     * exception is a task-list checkbox: toggling it does NOT flatten the block, see
     * [toggleTaskItem].
     */
    data class Rich(override val id: String, val display: RichDisplay, val rawHtml: String) : NoteBlock()
}

private var blockIdCounter = 0
fun newNoteBlockId(): String {
    blockIdCounter += 1
    return "blk$blockIdCounter"
}

/** Picks the legacy or HTML parser based on whether `body` looks like HTML — same heuristic the
 *  Mac uses (`looksLikeHtml`): a legacy plain-text/markdown body never starts with `<`. */
fun parseNoteBlocks(body: String): List<NoteBlock> {
    return if (body.trimStart().startsWith("<")) {
        normalizeNoteBlocks(parseHtmlNoteBlocks(body))
    } else {
        normalizeNoteBlocks(parseLegacyNoteBlocks(body))
    }
}

/** The original markdown-ish/plain-text + `![](data:...)` image-token parser, unchanged. */
private fun parseLegacyNoteBlocks(body: String): List<NoteBlock> {
    val blocks = mutableListOf<NoteBlock>()
    var lastIndex = 0
    for (match in IMAGE_TOKEN_REGEX.findAll(body)) {
        val range = match.range
        if (range.first > lastIndex) {
            blocks += NoteBlock.Text(newNoteBlockId(), body.substring(lastIndex, range.first))
        }
        blocks += NoteBlock.Image(newNoteBlockId(), match.groupValues[1])
        lastIndex = range.last + 1
    }
    if (lastIndex < body.length || blocks.isEmpty()) {
        blocks += NoteBlock.Text(newNoteBlockId(), body.substring(lastIndex))
    }
    return blocks
}

/** Merges adjacent text blocks and guarantees there's always a trailing [NoteBlock.Text] to type
 *  into — same invariant as the Mac side's `normalizeBlocks`, extended to cover [NoteBlock.Rich]
 *  (a note ending in a heading/list, same as one ending in an image, still needs a blank line
 *  underneath for the cursor to land in). */
fun normalizeNoteBlocks(blocks: List<NoteBlock>): List<NoteBlock> {
    val merged = mutableListOf<NoteBlock>()
    for (block in blocks) {
        val prev = merged.lastOrNull()
        if (block is NoteBlock.Text && prev is NoteBlock.Text) {
            merged[merged.lastIndex] = prev.copy(text = prev.text + block.text)
        } else {
            merged += block
        }
    }
    if (merged.isEmpty() || merged.last() !is NoteBlock.Text) {
        merged += NoteBlock.Text(newNoteBlockId(), "")
    }
    return merged
}

/** Serializes blocks back to a `body` string. [isHtml] must match how the note was parsed (an
 *  editor session decides this once, from the original body, and keeps it for the whole session —
 *  see `NoteEditor` in NotesScreen.kt) so a note's format never flips mid-edit: a legacy note
 *  stays legacy plain text/image-tokens even if untouched, and an HTML note keeps emitting valid
 *  HTML (including for a plain-text block the user typed, and for any image) so it still opens
 *  correctly in the Mac's rich-text editor. */
fun serializeNoteBlocks(blocks: List<NoteBlock>, isHtml: Boolean): String {
    if (!isHtml) {
        return blocks.joinToString("") { block ->
            when (block) {
                is NoteBlock.Text -> block.text
                is NoteBlock.Image -> "![](${block.dataUrl})"
                // Shouldn't occur (a legacy-parsed body never yields a Rich block), but round-trip
                // safely rather than losing content if it ever does.
                is NoteBlock.Rich -> block.rawHtml
            }
        }
    }
    return blocks.joinToString("") { block ->
        when (block) {
            is NoteBlock.Rich -> block.rawHtml
            is NoteBlock.Image -> "<img src=\"${block.dataUrl}\">"
            is NoteBlock.Text -> block.text
                .split("\n")
                .filter { it.isNotBlank() }
                .joinToString("") { line -> "<p>${escapeHtml(line)}</p>" }
        }
    }
}

/** A legacy note's single cover image (`NoteEntry.imageBase64`, from before inline images
 *  existed) shows up as a leading image block the first time the note is opened — the next save
 *  folds it into `body` as a token via `serializeNoteBlocks`, matching the Mac's
 *  `initialBlocksFor`. */
fun initialNoteBlocksFor(body: String, legacyImageBase64: String?): List<NoteBlock> {
    val blocks = parseNoteBlocks(body)
    if (legacyImageBase64 == null) return blocks
    val leading = NoteBlock.Image(newNoteBlockId(), "data:image/jpeg;base64,$legacyImageBase64")
    return normalizeNoteBlocks(listOf(leading) + blocks)
}

/** Strips inline image tokens/tags for the card-grid preview text — the raw token/markup must
 *  never reach a `Text` composable (see the crash this file exists to fix, and why HTML tags must
 *  never show up as literal text either). */
fun noteBodyPreviewText(body: String): String {
    if (body.trimStart().startsWith("<")) {
        return parseHtmlNoteBlocks(body).joinToString(" ") { block ->
            when (block) {
                is NoteBlock.Rich -> block.display.plainText()
                is NoteBlock.Text -> block.text
                is NoteBlock.Image -> ""
            }
        }.trim()
    }
    return body.replace(IMAGE_TOKEN_REGEX, "").trim()
}

/** The card grid's cover thumbnail: the first inline image found anywhere in the body, falling
 *  back to the legacy cover field for a note that hasn't been opened (and thus migrated) yet. */
fun noteCoverImageDataUrl(body: String, legacyImageBase64: String?): String? {
    val fromBody = if (body.trimStart().startsWith("<")) {
        parseHtmlNoteBlocks(body).filterIsInstance<NoteBlock.Image>().firstOrNull()?.dataUrl
    } else {
        IMAGE_TOKEN_REGEX.find(body)?.groupValues?.get(1)
    }
    return fromBody ?: legacyImageBase64?.let { "data:image/jpeg;base64,$it" }
}

/** Decodes a `data:image/...;base64,...` token to a Bitmap, or null if it's malformed — images
 *  are pre-compressed to a max 1400px dimension before ever being synced (see
 *  `compressImageFile` on the Mac side), so a normal decode here is cheap, but this still
 *  shouldn't be able to crash the app on a corrupt/truncated token. */
fun decodeNoteImage(dataUrl: String): Bitmap? {
    val comma = dataUrl.indexOf(',')
    if (comma < 0) return null
    return try {
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

// ---------------------------------------------------------------------------------------------
// HTML parsing
//
// The confirmed TipTap output uses a small, fixed tag set (h1, p, strong/em/s, ul/ol/li, the
// data-type="taskList" shape with label/input/div/span/p, and bare <img>) but isn't valid XML —
// void elements aren't self-closed and boolean attributes appear without a value — so a strict
// parser (e.g. XmlPullParser) would need HTML-aware preprocessing anyway. A small hand-rolled
// scanner tailored to exactly this tag set is simpler and more robust than either pulling in an
// HTML library or normalizing the input to feed a strict XML parser.
// ---------------------------------------------------------------------------------------------

private val VOID_TAGS = setOf("img", "input", "br", "hr")

/** A minimal DOM node. [tag] is null for a text node (its content lives in [text]). [start]/[end]
 *  are absolute character offsets into the original HTML string, so any node's raw source is
 *  `html.substring(start, end)`. */
private class HNode(val tag: String?, val attrs: Map<String, String>, val start: Int) {
    var end: Int = -1
    var text: String = ""
    val children = mutableListOf<HNode>()
}

private fun buildHtmlTree(html: String): HNode {
    val root = HNode(null, emptyMap(), 0)
    val stack = mutableListOf(root)
    val len = html.length
    var pos = 0
    while (pos < len) {
        val lt = html.indexOf('<', pos)
        if (lt < 0) {
            val text = html.substring(pos)
            if (text.isNotEmpty()) {
                val node = HNode(null, emptyMap(), pos)
                node.text = unescapeHtml(text)
                node.end = len
                stack.last().children += node
            }
            break
        }
        if (lt > pos) {
            val text = html.substring(pos, lt)
            val node = HNode(null, emptyMap(), pos)
            node.text = unescapeHtml(text)
            node.end = lt
            stack.last().children += node
        }
        val gt = html.indexOf('>', lt)
        if (gt < 0) break // truncated/malformed tail — stop rather than loop forever
        val raw = html.substring(lt, gt + 1)
        pos = gt + 1
        if (raw.startsWith("</")) {
            val name = raw.removePrefix("</").removeSuffix(">").trim().lowercase()
            val idx = stack.indexOfLast { it.tag == name }
            if (idx > 0) {
                stack[idx].end = pos
                while (stack.size > idx) stack.removeAt(stack.size - 1)
            }
            continue
        }
        val parsed = parseTag(raw)
        val node = HNode(parsed.name, parsed.attrs, lt)
        stack.last().children += node
        if (parsed.selfClosing || parsed.name in VOID_TAGS) {
            node.end = pos
        } else {
            stack.add(node)
        }
    }
    while (stack.size > 1) {
        val node = stack.removeAt(stack.size - 1)
        if (node.end < 0) node.end = len
    }
    root.end = len
    return root
}

private class ParsedTag(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean)

private val ATTR_REGEX = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\s*=\s*"([^"]*)"|\s*=\s*'([^']*)')?""")

private fun parseTag(raw: String): ParsedTag {
    val inner = raw.removePrefix("<").removeSuffix(">").trim()
    val selfClosing = inner.endsWith("/")
    val body = (if (selfClosing) inner.removeSuffix("/") else inner).trim()
    val spaceIdx = body.indexOfFirst { it.isWhitespace() }
    val name = (if (spaceIdx == -1) body else body.substring(0, spaceIdx)).lowercase()
    val attrs = mutableMapOf<String, String>()
    if (spaceIdx != -1) {
        for (m in ATTR_REGEX.findAll(body.substring(spaceIdx + 1))) {
            val key = m.groupValues[1].lowercase()
            if (key.isEmpty()) continue
            val value = if (m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.groupValues[3]
            attrs[key] = value
        }
    }
    return ParsedTag(name, attrs, selfClosing)
}

private fun flattenInline(node: HNode, bold: Boolean, italic: Boolean, strike: Boolean, out: MutableList<InlineRun>) {
    if (node.tag == null) {
        if (node.text.isNotEmpty()) out += InlineRun(node.text, bold, italic, strike)
        return
    }
    when (node.tag) {
        "strong", "b" -> node.children.forEach { flattenInline(it, true, italic, strike, out) }
        "em", "i" -> node.children.forEach { flattenInline(it, bold, true, strike, out) }
        "s", "strike", "del" -> node.children.forEach { flattenInline(it, bold, italic, true, out) }
        "br" -> out += InlineRun("\n", bold, italic, strike)
        // Anything else (e.g. a span) contributes no mark of its own — just recurse for its text.
        else -> node.children.forEach { flattenInline(it, bold, italic, strike, out) }
    }
}

private fun inlineRunsOf(node: HNode): List<InlineRun> {
    val out = mutableListOf<InlineRun>()
    node.children.forEach { flattenInline(it, false, false, false, out) }
    return out
}

/** Parses an HTML-format body into top-level blocks: `<h1>`, `<p>`, `<ul>`/`<ol>` (plain or
 *  `data-type="taskList"`), and `<img>`. Each block keeps its exact source slice as `rawHtml`. */
private fun parseHtmlNoteBlocks(body: String): List<NoteBlock> {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || trimmed == "<p></p>") return emptyList()

    val root = buildHtmlTree(body)
    val blocks = mutableListOf<NoteBlock>()
    for (child in root.children) {
        val tag = child.tag ?: continue // stray whitespace between top-level elements
        val end = if (child.end >= 0) child.end else body.length
        val rawHtml = body.substring(child.start, end)
        when (tag) {
            "h1" -> blocks += NoteBlock.Rich(newNoteBlockId(), RichDisplay.Heading(inlineRunsOf(child)), rawHtml)
            "p" -> {
                val runs = inlineRunsOf(child)
                if (runs.isEmpty() || runs.all { it.text.isBlank() }) {
                    blocks += NoteBlock.Text(newNoteBlockId(), "")
                } else {
                    blocks += NoteBlock.Rich(newNoteBlockId(), RichDisplay.Paragraph(runs), rawHtml)
                }
            }
            "img" -> {
                val src = child.attrs["src"]
                if (src != null) blocks += NoteBlock.Image(newNoteBlockId(), src)
            }
            "ul" -> {
                if (child.attrs["data-type"] == "taskList") {
                    val items = child.children.filter { it.tag == "li" }.map { li -> parseTaskItem(li, child.start) }
                    blocks += NoteBlock.Rich(newNoteBlockId(), RichDisplay.TaskList(items), rawHtml)
                } else {
                    val items = child.children.filter { it.tag == "li" }.map { li -> listItemRuns(li) }
                    blocks += NoteBlock.Rich(newNoteBlockId(), RichDisplay.BulletList(items), rawHtml)
                }
            }
            "ol" -> {
                val items = child.children.filter { it.tag == "li" }.map { li -> listItemRuns(li) }
                blocks += NoteBlock.Rich(newNoteBlockId(), RichDisplay.NumberedList(items), rawHtml)
            }
            else -> {
                // Not one of the shapes the Mac emits — surface its text rather than dropping it.
                val text = inlineRunsOf(child).joinToString("") { it.text }
                if (text.isNotBlank()) blocks += NoteBlock.Text(newNoteBlockId(), text)
            }
        }
    }
    return blocks
}

private fun listItemRuns(li: HNode): List<InlineRun> {
    val p = li.children.firstOrNull { it.tag == "p" }
    return inlineRunsOf(p ?: li)
}

/** A task item's real text lives in the `<div><p>...</p></div>` sibling of `<label>`, NOT in the
 *  `<li>`'s full text content — the `<label>` also contains a visually-hidden accessibility `
 *  <span>` ("Task item checkbox for ...") whose text must not be picked up, exactly the bug the
 *  Mac side hit and fixed the same way. */
private fun parseTaskItem(li: HNode, ulStart: Int): TaskItem {
    val checked = li.attrs["data-checked"] == "true"
    val div = li.children.firstOrNull { it.tag == "div" }
    val p = div?.children?.firstOrNull { it.tag == "p" }
    val runs = p?.let { inlineRunsOf(it) } ?: emptyList()
    val liEnd = if (li.end >= 0) li.end else ulStart
    val range = (li.start - ulStart) until (liEnd - ulStart)
    return TaskItem(checked, runs, range)
}

/** Flips one task item's checked state by splicing the change directly into the raw HTML of just
 *  that `<li>`, then reparsing the block fresh from the result. Reparsing (rather than patching
 *  [RichDisplay.TaskList] in place) is what keeps every *other* item's [TaskItem.rangeInBlockHtml]
 *  valid afterwards — splicing can change the spliced item's length (e.g. adding/removing the
 *  bare `checked` attribute), which would otherwise silently shift every later item's stored
 *  range. This is the one interactive edit that does NOT flatten the block to plain text, since it
 *  never touches the item's own formatting or any sibling block. */
fun toggleTaskItem(block: NoteBlock.Rich, itemIndex: Int): NoteBlock.Rich {
    val display = block.display as? RichDisplay.TaskList ?: return block
    val item = display.items.getOrNull(itemIndex) ?: return block
    val liHtml = block.rawHtml.substring(item.rangeInBlockHtml.first, item.rangeInBlockHtml.last + 1)
    val newChecked = !item.checked

    var newLiHtml = liHtml.replaceFirst(
        Regex("""data-checked="(?:true|false)""""),
        "data-checked=\"$newChecked\""
    )
    // The confirmed TipTap output actually carries an aria-label attribute too — e.g.
    // <input aria-label="Task item checkbox for todo one" type="checkbox"> — so the <input> tag
    // can't be matched as a fixed literal string; match the whole tag and flip `checked` within
    // it regardless of what other attributes are present or what order they're in.
    newLiHtml = Regex("""<input\b[^>]*>""").replace(newLiHtml) { m ->
        val tag = m.value
        val hasChecked = Regex("""\bchecked\b""").containsMatchIn(tag)
        when {
            newChecked && !hasChecked -> tag.dropLast(1) + " checked>"
            !newChecked && hasChecked -> tag.replaceFirst(Regex("""\s+checked\b"""), "")
            else -> tag
        }
    }

    val newRawHtml = block.rawHtml.substring(0, item.rangeInBlockHtml.first) +
        newLiHtml +
        block.rawHtml.substring(item.rangeInBlockHtml.last + 1)

    val reparsed = parseHtmlNoteBlocks(newRawHtml).firstOrNull() as? NoteBlock.Rich ?: return block
    return reparsed.copy(id = block.id)
}

private fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private val ENTITY_REGEX = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " "
)

private fun unescapeHtml(s: String): String {
    if ('&' !in s) return s
    return ENTITY_REGEX.replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            body.startsWith("#") ->
                body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED_ENTITIES[body] ?: m.value
        }
    }
}
