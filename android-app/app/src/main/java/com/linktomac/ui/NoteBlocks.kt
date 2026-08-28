package com.linktomac.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/**
 * Mirrors `desktop-app/src/components/NotesView.tsx`'s block model — a note body is plain text
 * with inline images embedded as `![](data:image/...;base64,...)` tokens. The Mac renders these
 * directly; the phone previously just dumped `body` into a plain `Text`/`OutlinedTextField`
 * verbatim, which both showed the raw base64 as garbage text AND crashed Compose's text layout
 * (a many-KB single "word" with no break opportunities blows up `StaticLayout`). Parsing into
 * blocks lets the phone render actual images, same as the Mac.
 */
private val IMAGE_TOKEN_REGEX = Regex("""!\[]\((data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+)\)""")

sealed class NoteBlock {
    abstract val id: String
    data class Text(override val id: String, val text: String) : NoteBlock()
    data class Image(override val id: String, val dataUrl: String) : NoteBlock()
}

private var blockIdCounter = 0
fun newNoteBlockId(): String {
    blockIdCounter += 1
    return "blk$blockIdCounter"
}

fun parseNoteBlocks(body: String): List<NoteBlock> {
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
    return normalizeNoteBlocks(blocks)
}

/** Merges adjacent text blocks and guarantees there's always at least one text block to type
 *  into — same invariant as the Mac side's `normalizeBlocks`. */
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
    if (merged.isEmpty() || merged.last() is NoteBlock.Image) {
        merged += NoteBlock.Text(newNoteBlockId(), "")
    }
    return merged
}

fun serializeNoteBlocks(blocks: List<NoteBlock>): String =
    blocks.joinToString("") { block ->
        when (block) {
            is NoteBlock.Text -> block.text
            is NoteBlock.Image -> "![](${block.dataUrl})"
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

/** Strips inline image tokens for the card-grid preview text — the raw token must never reach a
 *  `Text` composable (see the crash this file exists to fix). */
fun noteBodyPreviewText(body: String): String = body.replace(IMAGE_TOKEN_REGEX, "").trim()

/** The card grid's cover thumbnail: the first inline image found anywhere in the body, falling
 *  back to the legacy cover field for a note that hasn't been opened (and thus migrated) yet. */
fun noteCoverImageDataUrl(body: String, legacyImageBase64: String?): String? {
    val match = IMAGE_TOKEN_REGEX.find(body)
    if (match != null) return match.groupValues[1]
    return legacyImageBase64?.let { "data:image/jpeg;base64,$it" }
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
