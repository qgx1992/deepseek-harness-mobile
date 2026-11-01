package com.qgx1992.dshmobile.ui.screens.main

/**
 * Whether a workspace group in the drawer is open, and whether its preview is widened.
 *
 * Both answers used to be remembered inside the drawer itself. The drawer's content leaves the
 * composition every time it closes, so the work you did arranging it — opening the group you were
 * about to work in, widening the one you were scanning — was gone by the next time you looked:
 * reopening showed every group folded except whatever held the session you happened to be in.
 * The answers come from a stored preference now, and only the unanswered groups fall back to the
 * current session, which is what keeps a fresh install opening where you are.
 */
internal object DrawerTreeState {
    /**
     * @param saved what the user answered before, by workspace id — folded or opened.
     * @param holdsCurrent whether this group holds a session on the path to the one on screen.
     */
    fun isCollapsed(saved: Map<String, Boolean>, workspaceId: String, holdsCurrent: Boolean): Boolean =
        saved[workspaceId] ?: !holdsCurrent

    /** Only a widened group is listed, so membership is the whole answer. */
    fun isWidened(saved: Set<String>, workspaceId: String): Boolean = workspaceId in saved
}
