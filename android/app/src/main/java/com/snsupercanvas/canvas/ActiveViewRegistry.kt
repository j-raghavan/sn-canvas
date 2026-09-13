package com.snsupercanvas.canvas

/**
 * Tracks the one live view of a kind (the plugin mounts at most one canvas at a
 * time). [SuperCanvasPackage], the native composition root, creates a single
 * instance and injects it into both [SuperCanvasViewManager], whose views
 * [attach]/[detach] themselves as they enter and leave the window, and
 * [SuperCanvasModule], which acts on the [current] one. This is an explicit,
 * injected dependency in place of a static "current instance" global.
 */
class ActiveViewRegistry<T : Any> {
    @Volatile
    private var active: T? = null

    fun attach(view: T) {
        active = view
    }

    /** Clears only if [view] is still the active one: a late detach of a replaced view must not clear its successor. */
    fun detach(view: T) {
        if (active === view) active = null
    }

    fun current(): T? = active
}
