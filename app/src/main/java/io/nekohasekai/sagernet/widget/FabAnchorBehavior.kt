package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Pins a cradled [FloatingActionButton] to its resting position over the [BottomAppBar].
 *
 * The connect button must sit in the bar's cradle at all times while the bar is relevant, while the
 * bar itself slides up and down independently (it is `performHide()`-ed at Idle so only the button
 * is shown, and `performShow()`-ed once connected). The FAB therefore must NOT ride the bar's
 * translation — doing so hides the button together with the bar and makes connecting impossible.
 *
 * The earlier drift ("snaps", "stays at the bottom", "random position after theme change") came
 * from the FAB's `translationY` being left at a stale value: `BottomAppBar` only resets it during a
 * layout pass (`setCutoutStateAndTranslateFab`), so an interrupted show/hide motion spec or a
 * recreate could strand it off-position. Forcing the value back to the cradle resting offset on
 * every layout and on every bar change removes that window entirely.
 *
 * Only `translationY` is constrained; the bar still owns `translationX` (fab alignment) and the
 * cradle cutout, and `FloatingActionButton.Behavior` (via `super`) still owns snackbar dodging and
 * inset-aware layout.
 */
class FabAnchorBehavior(
    context: Context,
    attrs: AttributeSet?,
) : FloatingActionButton.Behavior(context, attrs) {
    private fun CoordinatorLayout.boundAppBar(child: View): BottomAppBar? =
        getDependencies(child).filterIsInstance<BottomAppBar>().firstOrNull()

    private val BottomAppBar.restingFabTranslationY: Float
        get() = (this as? StatsBar)?.fabRestingTranslationY ?: 0f

    override fun onLayoutChild(
        parent: CoordinatorLayout,
        child: FloatingActionButton,
        layoutDirection: Int,
    ): Boolean {
        val handled = super.onLayoutChild(parent, child, layoutDirection)
        // Re-seed on layout: onDependentViewChanged does not fire on the first layout or right
        // after a recreate/fragment swap, so without this the FAB could rest off-position.
        parent.boundAppBar(child)?.let { appBar ->
            child.translationY = appBar.restingFabTranslationY
        }
        return handled
    }

    override fun onDependentViewChanged(
        parent: CoordinatorLayout,
        child: FloatingActionButton,
        dependency: View,
    ): Boolean {
        if (dependency is BottomAppBar) {
            // Counter any drift every frame, without following the bar's own slide.
            child.translationY = dependency.restingFabTranslationY
        }
        return super.onDependentViewChanged(parent, child, dependency)
    }
}
