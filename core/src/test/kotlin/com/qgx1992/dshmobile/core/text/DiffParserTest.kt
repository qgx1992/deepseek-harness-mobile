package com.qgx1992.dshmobile.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Patch text in, hunks out.
 *
 * Every fixture is built by joining lines instead of embedding a raw string: a unified diff's
 * context lines begin with a single space, and a fixture whose indentation is part of the data is
 * one formatter run away from testing nothing.
 */
class DiffParserTest {

    private fun text(vararg lines: String): String = lines.joinToString("\n")

    private fun kinds(hunk: DiffHunk): List<Char> = hunk.lines.map {
        when (it) {
            is DiffLine.Context -> ' '
            is DiffLine.Add -> '+'
            is DiffLine.Remove -> '-'
        }
    }

    private fun bodies(hunk: DiffHunk): List<String> = hunk.lines.map { it.text }

    @Test fun unifiedDiffReadsEveryFileAndHunk() {
        val files = parse(
            text(
                "diff --git a/app/a.kt b/app/a.kt",
                "--- a/app/a.kt",
                "+++ b/app/a.kt",
                "@@ -1,3 +1,4 @@",
                " val a = 1",
                "-val b = 2",
                "+val b = 3",
                "+val c = 4",
                " val d = 5",
                "@@ -20,2 +21,2 @@",
                "-old b",
                "+new b",
                " tail",
                "--- a/app/b.kt",
                "+++ b/app/b.kt",
                "@@ -10,2 +10,2 @@",
                "-old line",
                "+new line",
                " same",
            ),
        )
        assertEquals(2, files.size)
        assertEquals("app/a.kt", files[0].path)
        assertEquals(2, files[0].hunks.size)
        assertEquals(1, files[1].hunks.size)
        assertEquals("app/b.kt", files[1].path)
        assertNull(files[0].oldPath)

        val first = files[0].hunks[0]
        assertEquals(1, first.oldStart)
        assertEquals(3, first.oldLines)
        assertEquals(1, first.newStart)
        assertEquals(4, first.newLines)
        assertEquals(listOf(' ', '-', '+', '+', ' '), kinds(first))
        assertEquals(listOf("val a = 1", "val b = 2", "val b = 3", "val c = 4", "val d = 5"), bodies(first))
    }

    @Test fun unifiedLineNumbersComeFromTheHunkHeaders() {
        val file = parse(
            text(
                "--- a/x.kt",
                "+++ b/x.kt",
                "@@ -10,2 +10,2 @@",
                "-old line",
                "+new line",
                " same",
            ),
        ).single()
        val hunk = file.hunks.single()
        // The header starts both sides at 10; a removed line advances only the old side and carries
        // the old position, an added line carries only the new one, and a context line carries both.
        assertEquals(
            listOf(10, null, 11),
            hunk.lines.map { (it as? DiffLine.Remove)?.oldNo ?: (it as? DiffLine.Context)?.oldNo },
        )
        assertEquals(
            listOf(null, 10, 11),
            hunk.lines.map { (it as? DiffLine.Add)?.newNo ?: (it as? DiffLine.Context)?.newNo },
        )
    }

    @Test fun unifiedHunkHeadingIsKept() {
        val file = parse(
            text(
                "--- a/x.kt",
                "+++ b/x.kt",
                "@@ -1,2 +1,2 @@ fun main()",
                " one",
                "-two",
                "+three",
            ),
        ).single()
        assertEquals("fun main()", file.hunks.single().heading)
    }

    @Test fun unifiedNewFileStartsAtLineOne() {
        val file = parse(
            text(
                "--- /dev/null",
                "+++ b/docs/new.md",
                "@@ -0,0 +1,2 @@",
                "+line one",
                "+line two",
            ),
        ).single()
        assertEquals("docs/new.md", file.path)
        val hunk = file.hunks.single()
        assertEquals(0, hunk.oldStart)
        assertEquals(1, hunk.newStart)
        assertEquals(listOf(1, 2), hunk.lines.map { (it as DiffLine.Add).newNo })
        assertEquals(2, hunk.newLines)
        assertEquals(0, hunk.oldLines)
    }

    @Test fun unifiedDeletionIsNamedByItsOldPath() {
        val file = parse(
            text(
                "--- a/old/gone.kt",
                "+++ /dev/null",
                "@@ -1,2 +0,0 @@",
                "-a",
                "-b",
            ),
        ).single()
        assertEquals("old/gone.kt", file.path)
        assertEquals(listOf('-', '-'), kinds(file.hunks.single()))
    }

    @Test fun binaryFilesAreFlaggedAndHaveNoHunks() {
        val files = parse(
            text(
                "diff --git a/assets/logo.png b/assets/logo.png",
                "new file mode 100644",
                "Binary files /dev/null and b/assets/logo.png differ",
            ),
        )
        assertEquals(1, files.size)
        assertEquals("assets/logo.png", files[0].path)
        assertTrue(files[0].binary)
        assertTrue(files[0].hunks.isEmpty())
    }

    @Test fun gitBinaryPatchIsFlagged() {
        val files = parse(
            text(
                "diff --git a/img/p.png b/img/p.png",
                "index 1234567..89abcde 100644",
                "GIT binary patch",
                "literal 12",
                "zcmZ?wbhEHbWMp7uXkcQk3|3OqF={;",
            ),
        ).single()
        assertEquals("img/p.png", files.path)
        assertTrue(files.binary)
        assertTrue(files.hunks.isEmpty())
    }

    @Test fun applyPatchUpdateAddAndDelete() {
        val files = parse(
            text(
                "*** Begin Patch",
                "*** Update File: a.kt",
                "@@ -1 +1 @@",
                "-x",
                "+y",
                "*** Add File: b.md",
                "+# Title",
                "+body",
                "*** Delete File: c.kt",
                "-gone",
                "*** End Patch",
            ),
        )
        assertEquals(3, files.size)

        assertEquals("a.kt", files[0].path)
        assertEquals(listOf('-', '+'), kinds(files[0].hunks.single()))

        assertEquals("b.md", files[1].path)
        assertNull(files[1].oldPath)
        val added = files[1].hunks.single()
        assertEquals(listOf("+", "+"), kinds(added).map { it.toString() })
        assertEquals(listOf("# Title", "body"), bodies(added))
        assertEquals(2, added.newLines)
        assertEquals(0, added.oldLines)

        assertEquals("c.kt", files[2].path)
        assertEquals(listOf('-'), kinds(files[2].hunks.single()))
    }

    @Test fun applyPatchNumberedHunkHeaderGivesLineNumbers() {
        val file = parse(
            text(
                "*** Begin Patch",
                "*** Update File: src/Main.kt",
                "@@ -10,2 +10,3 @@ class Main",
                " val a = 1",
                "-val a = 2",
                "+val a = 3",
                "+val b = 4",
                "*** End Patch",
            ),
        ).single()
        val hunk = file.hunks.single()
        assertEquals("class Main", hunk.heading)
        assertEquals(10, hunk.oldStart)
        assertEquals(10, hunk.newStart)
        assertEquals(2, hunk.oldLines)
        assertEquals(3, hunk.newLines)
        // Context 10/10, removal on the old side 11, additions on the new side 11 and 12.
        assertEquals(
            listOf(10, 11, null, null),
            hunk.lines.map { (it as? DiffLine.Context)?.oldNo ?: (it as? DiffLine.Remove)?.oldNo },
        )
        assertEquals(
            listOf(10, null, 11, 12),
            hunk.lines.map { (it as? DiffLine.Context)?.newNo ?: (it as? DiffLine.Add)?.newNo },
        )
    }

    @Test fun applyPatchSectionWithoutNumbersDoesNotInventThem() {
        val hunk = parse(
            text(
                "*** Begin Patch",
                "*** Update File: src/X.kt",
                "@@",
                " context",
                "-old",
                "+new",
                "*** End Patch",
            ),
        ).single().hunks.single()
        // A bare `@@` section states no position, so every position stays 0 and the view prints no
        // line number rather than a fabricated one.
        assertEquals(0, hunk.oldStart)
        assertEquals(0, hunk.newStart)
        assertEquals(listOf(' ', '-', '+'), kinds(hunk))
        assertEquals(0, (hunk.lines[0] as DiffLine.Context).oldNo)
        assertEquals(0, (hunk.lines[1] as DiffLine.Remove).oldNo)
        assertEquals(0, (hunk.lines[2] as DiffLine.Add).newNo)
        // Counted from the body: the context line and the removal make up the old side, the
        // context line and the addition the new one.
        assertEquals(2, hunk.oldLines)
        assertEquals(2, hunk.newLines)
    }

    @Test fun applyPatchAddFileCountsFromOne() {
        val hunk = parse(
            text(
                "*** Begin Patch",
                "*** Add File: docs/new.md",
                "+line one",
                "+line two",
                "*** End Patch",
            ),
        ).single().hunks.single()
        assertEquals(1, hunk.newStart)
        assertEquals(0, hunk.oldStart)
        assertEquals(listOf(1, 2), hunk.lines.map { (it as DiffLine.Add).newNo })
    }

    @Test fun applyPatchMoveToRenamesTheFile() {
        val file = parse(
            text(
                "*** Begin Patch",
                "*** Update File: old/name.kt",
                "*** Move to: new/name.kt",
                "@@ -1 +1 @@",
                "-a",
                "+b",
                "*** End Patch",
            ),
        ).single()
        assertEquals("new/name.kt", file.path)
        assertEquals("old/name.kt", file.oldPath)
    }

    @Test fun crlfInputParsesLikeLf() {
        val lf = listOf(
            "--- a/x.kt",
            "+++ b/x.kt",
            "@@ -1,2 +1,2 @@",
            " keep",
            "-old",
            "+new",
        )
        val fromLf = parse(text(*lf.toTypedArray()))
        val fromCrlf = parse(lf.joinToString("\r\n"))
        assertEquals(1, fromCrlf.size)
        assertEquals(fromLf[0].path, fromCrlf[0].path)
        assertEquals(kinds(fromLf[0].hunks.single()), kinds(fromCrlf[0].hunks.single()))
        // The carriage return must not survive into the line text.
        assertEquals(bodies(fromLf[0].hunks.single()), bodies(fromCrlf[0].hunks.single()))
        assertEquals(listOf("keep", "old", "new"), bodies(fromCrlf[0].hunks.single()))
    }

    @Test fun missingNewlineMarkerIsIgnored() {
        val file = parse(
            text(
                "--- a/x.txt",
                "+++ b/x.txt",
                "@@ -1,1 +1,1 @@",
                "-old",
                "+new",
                "\\ No newline at end of file",
            ),
        ).single()
        val hunk = file.hunks.single()
        assertEquals(listOf('-', '+'), kinds(hunk))
        assertEquals(listOf("old", "new"), bodies(hunk))
        assertEquals(1, hunk.oldLines)
        assertEquals(1, hunk.newLines)
    }

    @Test fun blankInputAndProseGiveAnEmptyList() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   \n\t\n").isEmpty())
        assertTrue(parse("this is not a patch\nit is just prose\nwith a + and a -").isEmpty())
        assertTrue(parse("*** Begin Patch").isEmpty())
        assertTrue(parse("@@ -1,2 +1,2 @@\n context").isEmpty())
    }

    @Test fun hunkBodyStopsAtTheDeclaredCounts() {
        // The blank line between the two files is a separator, not a context line: hunk 1's header
        // already accounts for all of its lines.
        val files = parse(
            text(
                "--- a/x.kt",
                "+++ b/x.kt",
                "@@ -1,2 +1,2 @@",
                " one",
                "-two",
                "+three",
                "",
                "--- a/y.kt",
                "+++ b/y.kt",
                "@@ -1 +1 @@",
                "-a",
                "+b",
            ),
        )
        assertEquals(2, files.size)
        assertEquals(3, files[0].hunks.single().lines.size)
        assertEquals(listOf("one", "two", "three"), bodies(files[0].hunks.single()))
        assertEquals("y.kt", files[1].path)
    }

    @Test fun trailingGarbageAfterThePatchIsNotRead() {
        val files = parse(
            text(
                "--- a/x.kt",
                "+++ b/x.kt",
                "@@ -1 +1 @@",
                "-a",
                "+b",
                "trailing chatter the parser should ignore",
            ),
        )
        assertEquals(1, files.size)
        assertEquals(listOf('-', '+'), kinds(files[0].hunks.single()))
    }
}
