package com.frederikberg.pai.kit.transcript

/**
 * Whether the list keeps itself at the live edge, decided by the reader and nothing else.
 *
 * Reaching the end is not a distance: the latch is on while the reader is at the very end, and a
 * deliberate gesture away from it switches it off at once. Only the reader's own movement hands it
 * back. A position sample produced by the app's own write (following a new row, a jump to a target
 * in the last screen that gets clamped onto the end) reads "at the end" too, and re-arming from it
 * would carry the reader off whatever they were just taken to; so a sample counts only while the
 * reader's gesture or fling is moving the list.
 *
 * The latch answers one question, whether new rows should be followed right now. Where the reader
 * was is read from the list's geometry at the moment it is saved, never from here.
 */
class FollowLatch(
    following: Boolean = true,
) {
    var following: Boolean = following
        private set

    private var readerMoving = false

    /** A finger went down on the list, or a fling the reader started is carrying it. */
    fun readerBeganMoving() {
        readerMoving = true
    }

    /** The list came to rest. */
    fun readerStoppedMoving() {
        readerMoving = false
    }

    /**
     * The app is about to write the scroll position itself: whatever fling was running is over, and the
     * samples this write produces are not the reader's.
     */
    fun appWillScroll() {
        readerMoving = false
    }

    /** A scroll sample. Returns true when it changed [following]. */
    fun sample(atEnd: Boolean): Boolean {
        if (!readerMoving || following == atEnd) return false
        following = atEnd
        return true
    }

    /** The list was put back where the reader left it, away from the end: following stays off until they come back. */
    fun restoredAwayFromEnd() {
        following = false
        readerMoving = false
    }

    /** The reader asked to go to the end; the jump itself is one immediate write. */
    fun engage() {
        following = true
        readerMoving = false
    }
}
