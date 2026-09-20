package com.sncanvas.canvas

/**
 * What a touch starts: the controls drawn over the canvas answer first (the
 * minimap, a linked element's glyph), then the tool in hand. [GestureCommit]
 * says what it left behind; [CanvasView] follows it in between.
 *
 * Nothing here touches the Android view: the caller hands over the contact and
 * where it landed in world coordinates, and the few things a starting gesture
 * changes at once (the view centering on a minimap tap, the pencil's ink, the
 * eraser's first marks) go back through [Host].
 */
internal class GestureStart(
    private val state: () -> CanvasState,
    private val selectGestures: SelectGestures,
    private val host: Host,
) {
    /** What the canvas around the touch says, and the few things starting a gesture changes at once. */
    interface Host {
        /** The active tool, a [CanvasTools] id. */
        fun toolMode(): String

        /** The minimap's box while it is on screen, null when it is not showing or the canvas is too small for one. */
        fun minimapLayout(): MinimapLayout?

        /** Keeps the minimap up: a touch on it is navigation, and it has to stay for the drag. */
        fun showMinimap()

        /** Moves the view so [world] sits in the middle of it; the zoom is untouched. */
        fun centerViewOn(world: Point)

        /** The pencil landed: the firmware inks the stroke, or the canvas snapshots itself to draw it over. */
        fun beginStroke()

        /** Marks everything under [world] for the eraser (FR20). */
        fun eraseAt(
            erase: CanvasGesture.Erase,
            world: Point,
        )
    }

    /** The gesture [contact], which landed on [world], starts; null when it starts nothing (a resting palm). */
    fun at(
        contact: Contact,
        world: Point,
    ): CanvasGesture? = controlGestureAt(contact, world) ?: toolGestureAt(contact, world)

    /** What a touch on something drawn over the canvas starts: the minimap, or a linked element's glyph. */
    private fun controlGestureAt(
        contact: Contact,
        world: Point,
    ): CanvasGesture? {
        val onMinimap = if (navigatesByMinimap(contact)) host.minimapLayout() else null
        if (onMinimap != null && onMinimap.contains(contact.x.toDouble(), contact.y.toDouble())) {
            return startMinimapDrag(onMinimap, contact)
        }
        return linkGlyphAt(world)?.let { linked -> CanvasGesture.FollowLink(linked.id) }
    }

    /** What the tool in hand makes of a touch at [world]. */
    private fun toolGestureAt(
        contact: Contact,
        world: Point,
    ): CanvasGesture? {
        val tool = host.toolMode()
        return when {
            // The pen's eraser end erases with any tool, as does the eraser tool with the pen.
            contact.isEraser || (contact.isPen && tool == CanvasTools.ERASER) -> CanvasGesture.Erase().also { host.eraseAt(it, world) }
            !CanvasTools.usesPen(tool) -> selectGesture(world, contact)
            // Palm rejection: every other tool works with the pen alone, as in Supernote's own notes.
            !contact.isPen -> null
            tool == CanvasTools.DRAW -> startFreehand(contact, world)
            CanvasTools.placesText(tool) -> CanvasGesture.PlaceText
            else -> CanvasGesture.DrawShape
        }
    }

    /**
     * A linked element's glyph under [world], if the select tool is in hand: a
     * tap on it follows the link. Only the select tool, so the glyph never
     * swallows a stroke from a drawing tool.
     */
    private fun linkGlyphAt(world: Point): Element? {
        if (CanvasTools.usesPen(host.toolMode())) return null
        val shown = state()
        return CanvasCore.linkGlyphAt(world.x, world.y, shown.elements, shown.zoom, LINK_GLYPH_HIT_PX / shown.zoom)
    }

    /**
     * Whether [contact] navigates when it lands on the minimap. The pen goes on
     * drawing wherever it would have drawn: a stroke in that corner, moments
     * after a pan, must not be swallowed by the map, so with a pen tool in hand
     * only a finger navigates. The pen's eraser end always erases.
     */
    private fun navigatesByMinimap(contact: Contact): Boolean =
        !contact.isEraser && !(contact.isPen && CanvasTools.usesPen(host.toolMode()))

    /**
     * A touch on the minimap navigates (FR15): landing on the "you are here"
     * rectangle drags it from where it was grabbed, and landing anywhere else in
     * the box takes the view there at once, then drags on from it.
     */
    private fun startMinimapDrag(
        layout: MinimapLayout,
        contact: Contact,
    ): CanvasGesture {
        val world = layout.worldAt(contact.x.toDouble(), contact.y.toDouble())
        val grabbed = layout.holdsViewport(world)
        val center = layout.visible.let { Point((it.left + it.right) / 2, (it.top + it.bottom) / 2) }
        if (!grabbed) host.centerViewOn(world)
        host.showMinimap()
        return CanvasGesture.MinimapDrag(
            layout,
            grabX = if (grabbed) center.x - world.x else 0.0,
            grabY = if (grabbed) center.y - world.y else 0.0,
        )
    }

    /**
     * The select tool's gesture. Where it grabs nothing the pen drags a selection
     * rectangle out instead of panning (FR7): the device has no modifier key, so
     * the pen selects and the finger navigates, as everywhere else in the canvas.
     */
    private fun selectGesture(
        world: Point,
        contact: Contact,
    ): CanvasGesture {
        val gesture = selectGestures.startAt(world, HANDLE_HIT_RADIUS_PX / state().zoom)
        return if (gesture == CanvasGesture.Pan && contact.isPen) CanvasGesture.Marquee else gesture
    }

    /** The pencil lands: its first sample is where it landed, at the pressure it landed with. */
    private fun startFreehand(
        contact: Contact,
        world: Point,
    ): CanvasGesture {
        host.beginStroke()
        return CanvasGesture.Freehand(mutableListOf(StrokePoint(world.x, world.y, contact.pressure.coerceIn(0f, 1f).toDouble())))
    }

    companion object {
        /** A selection handle's reach, in screen pixels. */
        const val HANDLE_HIT_RADIUS_PX = 28.0

        /** A link glyph's tap radius; a touch this close to it follows the link (FR7). */
        const val LINK_GLYPH_HIT_PX = 40.0
    }
}
