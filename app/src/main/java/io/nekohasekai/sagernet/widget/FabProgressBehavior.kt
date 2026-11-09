package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.progressindicator.CircularProgressIndicator

class FabProgressBehavior(
    context: Context,
    attrs: AttributeSet?,
) : CoordinatorLayout.Behavior<CircularProgressIndicator>(context, attrs) {
    override fun layoutDependsOn(
        parent: CoordinatorLayout,
        child: CircularProgressIndicator,
        dependency: View,
    ): Boolean = dependency.id == (child.layoutParams as CoordinatorLayout.LayoutParams).anchorId

    override fun onLayoutChild(
        parent: CoordinatorLayout,
        child: CircularProgressIndicator,
        layoutDirection: Int,
    ): Boolean {
        val fab = parent.getDependencies(child).single()
        // Mirror the FAB's translation on layout as well, not only in onDependentViewChanged.
        // The change-only callback does not fire on the initial layout, after a theme recreate,
        // or across fragment swaps, so without this the ring could sit at a stale offset.
        child.translationX = fab.translationX
        child.translationY = fab.translationY
        val size = fab.measuredHeight + child.trackThickness
        return if (child.indicatorSize != size) {
            child.indicatorSize = size
            true
        } else {
            false
        }
    }

    override fun onDependentViewChanged(
        parent: CoordinatorLayout,
        child: CircularProgressIndicator,
        dependency: View,
    ): Boolean {
        child.translationX = dependency.translationX
        child.translationY = dependency.translationY
        return true
    }
}
