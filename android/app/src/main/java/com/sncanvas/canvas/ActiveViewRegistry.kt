package com.sncanvas.canvas

/**
 * Tracks the one live view of a kind (the plugin mounts at most one canvas at a
 * time). [CanvasPackage], the native composition root, creates a single
 * instance and injects it into both [CanvasViewManager], whose views
 * [attach]/[detach] themselves as they enter and leave the window, and
 * [CanvasModule], which acts on the [current] one, or on the next one
 * to attach ([whenAttached]). This is an explicit, injected dependency in
 * place of a static "current instance" global. The module calls in from
 * background threads, so every call is synchronized.
 */
class ActiveViewRegistry<T : Any> {
    private var active: T? = null
    private val waiting = mutableListOf<(T) -> Unit>()

    /** [view] is live; whatever was waiting for a view runs on it now, in the order it asked. */
    fun attach(view: T) {
        val ready =
            synchronized(this) {
                active = view
                waiting.toList().also { waiting.clear() }
            }
        ready.forEach { it(view) }
    }

    /** Clears only if [view] is still the active one: a late detach of a replaced view must not clear its successor. */
    @Synchronized
    fun detach(view: T) {
        if (active === view) active = null
    }

    @Synchronized
    fun current(): T? = active

    /**
     * Runs [action] on the live view: now, when one is attached, or else as
     * the next one attaches. The host hides the canvas while another app's
     * screen shows, such as the file picker's, and shows it again only as that
     * screen closes, so what the picker returns can arrive a moment before the
     * canvas is back.
     */
    fun whenAttached(action: (T) -> Unit) {
        val view = synchronized(this) { active.also { if (it == null) waiting += action } }
        if (view != null) action(view)
    }
}
