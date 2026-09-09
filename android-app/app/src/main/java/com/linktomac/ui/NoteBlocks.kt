package com.linktomac.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration

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

// ---------------------------------------------------------------------------------------------
// Rich-text editing (formatting toolbar)
//
// The block model above is deliberately display/parse-only — [NoteBlock.Rich]'s own doc comment
// documents the original trade-off of flattening a rich block to plain text on tap rather than
// building a toolbar. Everything below reverses that: while a block is being edited, its content
// lives as an [AnnotatedString]-backed [TextFieldValue] (so bold/italic/strike render live, and a
// toolbar can toggle them on the current selection) instead of a plain [String]. Ending an edit
// serializes that back to HTML and hands it straight to the existing [parseHtmlNoteBlocks] to
// rebuild a real [NoteBlock.Rich] — the same "build HTML, then reparse it" pattern [toggleTaskItem]
// above already uses, so rendering and serialization can never drift out of sync with each other.
// ---------------------------------------------------------------------------------------------

/** What kind of block is currently being edited. The list kinds hold one item per line of
 *  [EditingBlock.value] — pressing Enter starts a new item, and clearing a line's text removes
 *  one — rather than growing separate per-item text fields. */
enum class EditKind { PARAGRAPH, HEADING, BULLET_LIST, NUMBERED_LIST, TASK_LIST }

/** Transient in-editing state for exactly one block — see the section doc comment above. Never
 *  persisted or serialized directly; [commitEditingToBlock] converts it back to a real
 *  [NoteBlock] when editing ends. [taskChecked] only means anything for [EditKind.TASK_LIST], one
 *  entry per line of [value] — the caller keeps it in sync as lines are added/removed. */
data class EditingBlock(
    val blockId: String,
    val kind: EditKind,
    val value: TextFieldValue,
    val taskChecked: List<Boolean> = emptyList(),
    // Marks toggled with the selection collapsed (just a cursor, nothing highlighted) — there's
    // no existing range to flip a mark across yet, so the toggle instead "arms" it for whatever
    // gets typed next at the cursor (see `applyMarkToggle`/`EditingBlockField`) rather than
    // silently doing nothing, the same way clicking Bold before typing works in Word/Docs/Notion
    // rather than requiring text to already exist and be selected first. Stays armed across
    // keystrokes until toggled off — `EditingBlockField` is what actually stamps it onto newly
    // typed text and never needs a remembered position for that (see its doc comment for why).
    val pendingMarks: Set<EditMark> = emptySet()
)

/** Seeds an [EditingBlock] from whatever's currently in the block — preserving bold/italic/
 *  strike (unlike the old flatten-to-plain-text tap handler) by converting each run to a real
 *  [SpanStyle], and joining a list block's items one per line. */
fun startEditing(block: NoteBlock): EditingBlock = when (block) {
    is NoteBlock.Text -> EditingBlock(block.id, EditKind.PARAGRAPH, TextFieldValue(AnnotatedString(block.text)))
    is NoteBlock.Image -> EditingBlock(block.id, EditKind.PARAGRAPH, TextFieldValue(AnnotatedString("")))
    is NoteBlock.Rich -> when (val display = block.display) {
        is RichDisplay.Heading ->
            EditingBlock(block.id, EditKind.HEADING, TextFieldValue(runsToAnnotatedString(display.runs)))
        is RichDisplay.Paragraph ->
            EditingBlock(block.id, EditKind.PARAGRAPH, TextFieldValue(runsToAnnotatedString(display.runs)))
        is RichDisplay.BulletList ->
            EditingBlock(block.id, EditKind.BULLET_LIST, TextFieldValue(joinRunsLines(display.items)))
        is RichDisplay.NumberedList ->
            EditingBlock(block.id, EditKind.NUMBERED_LIST, TextFieldValue(joinRunsLines(display.items)))
        is RichDisplay.TaskList -> EditingBlock(
            block.id,
            EditKind.TASK_LIST,
            TextFieldValue(joinRunsLines(display.items.map { it.runs })),
            display.items.map { it.checked }
        )
    }
}

private fun joinRunsLines(items: List<List<InlineRun>>): AnnotatedString = buildAnnotatedString {
    items.forEachIndexed { index, runs ->
        if (index > 0) append("\n")
        append(runsToAnnotatedString(runs))
    }
}

/** Same conversion [NotesScreen.kt]'s display-only version does, kept here too so the editing
 *  code above doesn't depend on a `private` function in another file. */
fun runsToAnnotatedString(runs: List<InlineRun>): AnnotatedString = buildAnnotatedString {
    for (run in runs) {
        val start = length
        append(run.text)
        if (run.bold || run.italic || run.strike) {
            addStyle(
                SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    textDecoration = if (run.strike) TextDecoration.LineThrough else null
                ),
                start,
                length
            )
        }
    }
}

/** Ends an edit session, turning [editing] back into a real [NoteBlock] with the same id. Builds
 *  HTML matching the shapes [parseHtmlNoteBlocks] already understands, then reparses it through
 *  that same function — see the section doc comment for why. An emptied-out block degrades to a
 *  plain empty [NoteBlock.Text], since reparsing an empty `<p></p>` on its own yields no blocks at
 *  all (see [parseHtmlNoteBlocks]'s empty-body guard) — matching how the rest of this file already
 *  treats an empty paragraph. */
fun commitEditingToBlock(editing: EditingBlock): NoteBlock {
    val lines = splitAnnotatedStringLines(editing.value.annotatedString)
    if (lines.none { it.text.isNotBlank() }) return NoteBlock.Text(editing.blockId, "")

    val html = when (editing.kind) {
        EditKind.PARAGRAPH -> "<p>${annotatedStringToHtml(lines.first())}</p>"
        EditKind.HEADING -> "<h1>${annotatedStringToHtml(lines.first())}</h1>"
        EditKind.BULLET_LIST ->
            "<ul>" + lines.joinToString("") { "<li><p>${annotatedStringToHtml(it)}</p></li>" } + "</ul>"
        EditKind.NUMBERED_LIST ->
            "<ol>" + lines.joinToString("") { "<li><p>${annotatedStringToHtml(it)}</p></li>" } + "</ol>"
        EditKind.TASK_LIST -> {
            "<ul data-type=\"taskList\">" + lines.mapIndexed { i, line ->
                val checked = editing.taskChecked.getOrElse(i) { false }
                "<li data-checked=\"$checked\"><label><input type=\"checkbox\"${if (checked) " checked" else ""}></label>" +
                    "<div><p>${annotatedStringToHtml(line)}</p></div></li>"
            }.joinToString("") + "</ul>"
        }
    }

    val reparsed = parseHtmlNoteBlocks(html).firstOrNull() ?: return NoteBlock.Text(editing.blockId, "")
    return when (reparsed) {
        is NoteBlock.Rich -> reparsed.copy(id = editing.blockId)
        is NoteBlock.Text -> reparsed.copy(id = editing.blockId)
        is NoteBlock.Image -> reparsed
    }
}

/** Splits an [AnnotatedString] into per-line [AnnotatedString]s at `\n`, preserving each
 *  character's [SpanStyle]s — [AnnotatedString] has no built-in line-split that keeps styling, so
 *  this walks the text manually. */
private fun splitAnnotatedStringLines(text: AnnotatedString): List<AnnotatedString> {
    val lines = mutableListOf<AnnotatedString>()
    var start = 0
    val full = text.text
    for (i in full.indices) {
        if (full[i] == '\n') {
            lines += text.subSequence(start, i)
            start = i + 1
        }
    }
    lines += text.subSequence(start, full.length)
    return lines
}

/** (bold, italic, strike) in effect at one character index of [text] — spans can overlap, so this
 *  is an OR across every matching range, not a lookup of a single "the" style. Shared by
 *  [annotatedStringToHtml] (which reads it) and [toggleMarkInRange] (which needs the same reading
 *  before it can rebuild the string with one mark flipped). */
private fun charStyleAt(text: AnnotatedString, index: Int): Triple<Boolean, Boolean, Boolean> {
    var bold = false
    var italic = false
    var strike = false
    for (range in text.spanStyles) {
        if (index in range.start until range.end) {
            if (range.item.fontWeight == FontWeight.Bold) bold = true
            if (range.item.fontStyle == FontStyle.Italic) italic = true
            if (range.item.textDecoration == TextDecoration.LineThrough) strike = true
        }
    }
    return Triple(bold, italic, strike)
}

/** Rebuilds an [AnnotatedString] for [text] from scratch, asking [styleAt] for each character's
 *  (bold, italic, strike) and merging consecutive identical characters into one [SpanStyle] run.
 *  Building fresh like this (rather than layering a new span on top of the existing ones) is what
 *  [toggleMarkInRange] needs to actually *remove* a mark — see its doc comment. */
private fun rebuildAnnotatedString(text: String, styleAt: (Int) -> Triple<Boolean, Boolean, Boolean>): AnnotatedString =
    buildAnnotatedString {
        append(text)
        if (text.isEmpty()) return@buildAnnotatedString
        var runStart = 0
        var current = styleAt(0)
        fun flush(end: Int) {
            val (bold, italic, strike) = current
            if (bold || italic || strike) {
                addStyle(
                    SpanStyle(
                        fontWeight = if (bold) FontWeight.Bold else null,
                        fontStyle = if (italic) FontStyle.Italic else null,
                        textDecoration = if (strike) TextDecoration.LineThrough else null
                    ),
                    runStart,
                    end
                )
            }
        }
        for (i in 1..text.length) {
            val style = if (i < text.length) styleAt(i) else Triple(false, false, false)
            if (i == text.length || style != current) {
                flush(i)
                runStart = i
                current = style
            }
        }
    }

/** The inverse of [runsToAnnotatedString] — walks an [AnnotatedString] character by character,
 *  grouping consecutive characters that share the same bold/italic/strike combination into one
 *  run, and wraps each run in the matching TipTap-compatible tags. */
private fun annotatedStringToHtml(text: AnnotatedString): String {
    if (text.text.isEmpty()) return ""
    val html = StringBuilder()
    var runStart = 0
    var current = charStyleAt(text, 0)
    fun flush(end: Int) {
        if (end <= runStart) return
        var chunk = escapeHtml(text.text.substring(runStart, end))
        val (bold, italic, strike) = current
        if (strike) chunk = "<s>$chunk</s>"
        if (italic) chunk = "<em>$chunk</em>"
        if (bold) chunk = "<strong>$chunk</strong>"
        html.append(chunk)
    }
    for (i in 1..text.text.length) {
        val styles = if (i < text.text.length) charStyleAt(text, i) else Triple(false, false, false)
        if (i == text.text.length || styles != current) {
            flush(i)
            runStart = i
            current = styles
        }
    }
    return html.toString()
}

/** Which inline mark a formatting-toolbar button toggles. */
enum class EditMark { BOLD, ITALIC, STRIKE }

/** Toggles one formatting mark across `[range]` of `text` the way a rich-text editor's toolbar
 *  button normally does: "apply to all" if any character in the range doesn't already have it,
 *  "remove from all" if every character already does. Only meaningful for a real (non-collapsed)
 *  selection — see [applyMarkToggle] for the collapsed-cursor case, which this no longer needs to
 *  handle since callers branch before reaching here; the guard below is just defensive.
 *
 *  [AnnotatedString]'s spans are additive/mergeable but not subtractive — a later span with a
 *  `null` field doesn't clear an earlier span's non-null value over the same range, it's simply
 *  silent on that field. So "remove bold from this selection" can't be done by layering one more
 *  span on top; this rebuilds the whole string's styling from a fresh per-character read instead
 *  (via [rebuildAnnotatedString]), which is unambiguous either way. */
fun toggleMarkInRange(text: AnnotatedString, range: TextRange, mark: EditMark): AnnotatedString {
    if (range.collapsed) return text
    val chars = text.text.indices.map { charStyleAt(text, it) }
    fun has(i: Int) = when (mark) {
        EditMark.BOLD -> chars[i].first
        EditMark.ITALIC -> chars[i].second
        EditMark.STRIKE -> chars[i].third
    }
    val allSet = (range.min until range.max).all { has(it) }
    fun updated(i: Int): Triple<Boolean, Boolean, Boolean> {
        val (bold, italic, strike) = chars[i]
        if (i !in range.min until range.max) return Triple(bold, italic, strike)
        return when (mark) {
            EditMark.BOLD -> Triple(!allSet, italic, strike)
            EditMark.ITALIC -> Triple(bold, !allSet, strike)
            EditMark.STRIKE -> Triple(bold, italic, !allSet)
        }
    }
    return rebuildAnnotatedString(text.text, ::updated)
}

/** Whether [mark] should show "on" in the toolbar right now. For a real selection, whether every
 *  character in it already has the mark (matching [toggleMarkInRange]'s own "all vs. any" rule).
 *  For a collapsed selection (just a cursor), whether it's armed in [EditingBlock.pendingMarks] —
 *  there's no character at the cursor itself to read a style off of, so [pendingMarks] is the
 *  only source of truth for what typing right now would produce. */
fun isMarkActive(editing: EditingBlock, mark: EditMark): Boolean {
    val range = editing.value.selection
    if (range.collapsed) return mark in editing.pendingMarks
    val text = editing.value.annotatedString
    return (range.min until range.max).all { i ->
        val (bold, italic, strike) = charStyleAt(text, i)
        when (mark) {
            EditMark.BOLD -> bold
            EditMark.ITALIC -> italic
            EditMark.STRIKE -> strike
        }
    }
}

/** Toggles [mark] the way the formatting toolbar button does: across the current selection when
 *  there is one (delegates straight to [toggleMarkInRange], unchanged), or — for a collapsed
 *  selection — arms/disarms it in [EditingBlock.pendingMarks] instead of the previous silent
 *  no-op. [EditingBlockField] reads `pendingMarks` back out on text actually typed next and
 *  stamps it onto just the newly-inserted characters (see its `onValueChange`), and leaves the
 *  mark armed afterwards (sticky) so a whole run of typing picks it up, not just one character —
 *  matching a tap of the same button again being what turns it back off. */
fun applyMarkToggle(editing: EditingBlock, mark: EditMark): EditingBlock {
    val range = editing.value.selection
    if (!range.collapsed) {
        return editing.copy(
            value = editing.value.copy(
                annotatedString = toggleMarkInRange(editing.value.annotatedString, range, mark)
            )
        )
    }
    val pending = if (mark in editing.pendingMarks) editing.pendingMarks - mark else editing.pendingMarks + mark
    return editing.copy(pendingMarks = pending)
}

/** Switches [editing] to [target], or back to plain [EditKind.PARAGRAPH] if it's already
 *  [target] — the same toggle semantics as the desktop toolbar's `toggleHeading`/`toggleBulletList`/
 *  etc. Resets [EditingBlock.taskChecked] to all-unchecked when switching *into*
 *  [EditKind.TASK_LIST] (there's no prior per-item checked state to preserve when the block wasn't
 *  a task list a moment ago); switching away from it just drops the list, same as the others. */
fun toggleEditKind(editing: EditingBlock, target: EditKind): EditingBlock {
    val newKind = if (editing.kind == target) EditKind.PARAGRAPH else target
    val taskChecked = if (newKind == EditKind.TASK_LIST && editing.kind != EditKind.TASK_LIST) {
        val lineCount = editing.value.text.count { it == '\n' } + 1
        List(lineCount) { false }
    } else {
        editing.taskChecked
    }
    return editing.copy(kind = newKind, taskChecked = taskChecked)
}
