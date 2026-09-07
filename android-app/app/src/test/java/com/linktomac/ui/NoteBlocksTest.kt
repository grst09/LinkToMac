package com.linktomac.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the HTML-format parser against the exact TipTap output shape confirmed in the task
 * notes (heading, marks, plain/numbered/task lists, inline image), and checks the legacy
 * markdown-ish parser/serializer path is completely unaffected — this is the regression check
 * called for since there's no emulator available in this environment.
 */
class NoteBlocksTest {

    private val sampleHtml = """
        <h1>My Heading</h1>
        <p>Some <strong>bold</strong> and <em>italic</em> and <s>struck</s> text.</p>
        <ul><li><p>bullet one</p></li><li><p>bullet two</p></li></ul>
        <ol><li><p>num one</p></li><li><p>num two</p></li></ol>
        <ul data-type="taskList">
        <li data-checked="true"><label contenteditable="false"><input aria-label="Task item checkbox for todo one" type="checkbox" checked><span style="position:absolute;">Task item checkbox for todo one</span></label><div><p>todo one</p></div></li>
        <li data-checked="false"><label contenteditable="false"><input aria-label="Task item checkbox for todo two" type="checkbox"><span style="position:absolute;">Task item checkbox for todo two</span></label><div><p>todo two</p></div></li>
        </ul>
        <img src="data:image/jpeg;base64,QUJD">
    """.trimIndent()

    @Test
    fun `parses every block kind from the confirmed TipTap HTML shape`() {
        val blocks = parseNoteBlocks(sampleHtml)
        val rich = blocks.filterIsInstance<NoteBlock.Rich>()

        assertTrue(rich.any { it.display is RichDisplay.Heading })
        assertTrue(rich.any { it.display is RichDisplay.Paragraph })
        assertTrue(rich.any { it.display is RichDisplay.BulletList })
        assertTrue(rich.any { it.display is RichDisplay.NumberedList })
        assertTrue(rich.any { it.display is RichDisplay.TaskList })
        assertTrue(blocks.any { it is NoteBlock.Image })
    }

    @Test
    fun `task item text comes from the div after label, not the hidden accessibility span`() {
        val block = parseNoteBlocks(sampleHtml)
            .filterIsInstance<NoteBlock.Rich>()
            .first { it.display is RichDisplay.TaskList }
        val items = (block.display as RichDisplay.TaskList).items

        assertEquals("todo one", items[0].runs.joinToString("") { it.text })
        assertEquals("todo two", items[1].runs.joinToString("") { it.text })
        assertTrue(items[0].checked)
        assertFalse(items[1].checked)
    }

    @Test
    fun `inline marks are detected independently and can combine`() {
        val block = parseNoteBlocks(sampleHtml)
            .filterIsInstance<NoteBlock.Rich>()
            .first { it.display is RichDisplay.Paragraph }
        val runs = (block.display as RichDisplay.Paragraph).runs

        assertTrue(runs.any { it.text == "bold" && it.bold && !it.italic && !it.strike })
        assertTrue(runs.any { it.text == "italic" && it.italic && !it.bold && !it.strike })
        assertTrue(runs.any { it.text == "struck" && it.strike && !it.bold && !it.italic })
    }

    @Test
    fun `toggling a task item flips only that item and leaves siblings untouched`() {
        val block = parseNoteBlocks(sampleHtml)
            .filterIsInstance<NoteBlock.Rich>()
            .first { it.display is RichDisplay.TaskList }

        val toggled = toggleTaskItem(block, 1)
        val items = (toggled.display as RichDisplay.TaskList).items

        assertTrue(items[0].checked) // untouched sibling
        assertTrue(items[1].checked) // flipped from false -> true
        assertEquals("todo one", items[0].runs.joinToString("") { it.text })
        assertEquals("todo two", items[1].runs.joinToString("") { it.text })

        // The <input> carries other attributes too (aria-label) — it must not be matched as a
        // fixed "<input type=\"checkbox\">" literal, or flipping the checkbox silently no-ops on
        // the input tag itself (data-checked still updates, since that's a separate, more
        // permissive regex — so a round-trip-only check wouldn't have caught this: toggling twice
        // would look symmetric even if the input tag was never actually touched in either
        // direction). Check the toggled item's own <input> tag directly instead.
        val secondLiHtml = toggled.rawHtml.lines().first { it.contains("todo two") }
        assertTrue(secondLiHtml.contains("<input aria-label=\"Task item checkbox for todo two\" type=\"checkbox\" checked>"))

        // Toggling back should restore the original raw HTML for the list exactly.
        val toggledBack = toggleTaskItem(toggled, 1)
        assertEquals(block.rawHtml, toggledBack.rawHtml)
    }

    @Test
    fun `html-mode serialization round-trips headings and images`() {
        val blocks = parseNoteBlocks(sampleHtml)
        val serialized = serializeNoteBlocks(blocks, true)

        assertTrue(serialized.contains("<h1>My Heading</h1>"))
        assertTrue(serialized.contains("data:image/jpeg;base64,QUJD"))
        assertTrue(serialized.contains("data-type=\"taskList\""))
    }

    @Test
    fun `a totally empty note body still yields exactly one editable text block`() {
        assertEquals(1, parseNoteBlocks("").size)
        assertEquals(1, parseNoteBlocks("<p></p>").size)
        assertTrue(parseNoteBlocks("<p></p>").single() is NoteBlock.Text)
    }

    @Test
    fun `legacy markdown-ish bodies are completely unaffected (regression check)`() {
        val legacyBody = "Some **bold** text\n- [ ] todo\n![](data:image/jpeg;base64,QUJD)"
        val blocks = parseNoteBlocks(legacyBody)

        assertTrue(blocks.none { it is NoteBlock.Rich })
        assertTrue(blocks.any { it is NoteBlock.Text && it.text.contains("**bold**") })
        assertTrue(blocks.any { it is NoteBlock.Image })

        val roundTripped = serializeNoteBlocks(blocks, false)
        assertEquals(legacyBody, roundTripped)
    }
}
