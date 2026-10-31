package com.qgx1992.dshmobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which repository the "check for updates" button asks.
 *
 * This shipped as a real defect: the check pointed at the parent repository, so every phone on this
 * build was offered an APK signed with someone else's certificate the moment that project released
 * a version this fork had not reached. Android then refused the install, and the app looked broken.
 * The constant is asserted here so a future edit that "restores" the upstream URL fails a test
 * instead of a user's install.
 */
class UpdateSourceTest {

    @Test
    fun `updates are looked for in this fork`() {
        assertEquals("qgx1992/deepseek-harness-mobile", UpdateChecker.REPO)
    }

    @Test
    fun `the upstream repository is not asked for installable builds`() {
        assertNotEquals(UpdateChecker.UPSTREAM_REPO, UpdateChecker.REPO)
    }

    /** Both wire URLs follow the same repository, so the release page matches what was checked. */
    @Test
    fun `the release endpoints follow the repository constant`() {
        assertEquals(
            "https://api.github.com/repos/${UpdateChecker.REPO}/releases/latest",
            UpdateChecker.LATEST_RELEASE_API,
        )
        assertEquals(
            "https://github.com/${UpdateChecker.REPO}/releases/latest",
            UpdateChecker.RELEASES_URL,
        )
    }
}
