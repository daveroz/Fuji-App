package com.fujiapp.remote.ui

import android.view.View
import android.widget.FrameLayout
import com.fujiapp.remote.ConnState
import com.fujiapp.remote.RecipesState
import com.fujiapp.remote.VitalsState
import com.fujiapp.remote.recipe.Recipe

/** Recipes: what's on the camera, the library, and the lab, one at a time. */
class RecipesScreen(private val a: MainActivity) {
    private val c = a
    val camera = CameraRecipes(a)
    val library = LibraryScreen(a)
    val lab = LabScreen(a)
    private val sections = listOf(camera.view, library.view, lab.view)
    private val names = listOf("Camera", "Library", "Lab")
    private var current = 0
    private val frame = FrameLayout(c)
    private val switcher = Kit.Segmented(c, names) { select(it) }

    val view: View = Kit.column(c).apply {
        addView(switcher.view, Kit.lp().apply {
            leftMargin = Deco.dpi(c, Kit.PAGE_MARGIN); rightMargin = Deco.dpi(c, Kit.PAGE_MARGIN); topMargin = Deco.dpi(c, 10)
        })
        addView(frame, Kit.lp(h = 0, weight = 1f))
    }

    init {
        select(0)
    }

    fun select(i: Int) {
        if (i != current) com.fujiapp.remote.util.AppLog.ui("recipes: ${names[i]}")
        current = i
        switcher.select(i)
        frame.removeAllViews()
        frame.addView(sections[i], FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
        if (i == 1) library.refresh()
    }

    fun openInLab(recipe: Recipe) {
        lab.load(recipe)
        select(2)
    }

    fun render(state: ConnState, recipes: RecipesState, vitals: VitalsState) {
        if (current == 0) camera.render(state, recipes, vitals)
    }
}
