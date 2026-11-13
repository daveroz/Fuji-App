package com.fujiapp.remote.ui

import android.view.View
import com.fujiapp.remote.recipe.LibraryRecipe
import com.fujiapp.remote.recipe.RecipeLibrary

/** Recipes after film stocks and films, plus the ones made in the Lab. */
class LibraryScreen(private val a: MainActivity) {
    private val c = a
    private val groups = listOf(null, LibraryRecipe.Group.FILM, LibraryRecipe.Group.CINEMA, LibraryRecipe.Group.MINE)
    private var group: LibraryRecipe.Group? = null
    private val filter = Kit.Chips(c, listOf("All", "Film stocks", "Cinema", "My recipes")) { i ->
        group = groups[i]
        refresh()
    }
    private val list = Kit.column(c)
    private val expanded = HashSet<String>()

    val view: View = Kit.page(c, Kit.column(c).apply {
        addView(Kit.label(c, "Library", Deco.DIM), Kit.lp(top = Deco.dpi(c, 4)))
        addView(Kit.text(c, "Film and cinema", 30f), Kit.lp(top = Deco.dpi(c, 12)))
        addView(
            Kit.text(
                c,
                "Recipes written for this app from the way each film stock and each film handles color: skin, skies, greens, contrast and grain. " +
                    "Try one in the Lab, or load it into one of the camera's slots.",
                14f, Deco.MUTED,
            ),
            Kit.lp(top = Deco.dpi(c, 12)),
        )
        addView(filter.view, Kit.lp(top = Deco.dpi(c, 18)))
        addView(list, Kit.lp(top = Deco.dpi(c, 8)))
    })

    init {
        filter.select(0)
        refresh()
    }

    fun refresh() {
        val items = (RecipeLibrary.all + a.myRecipes.list()).filter { group == null || it.group == group }
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(Kit.text(c, "Nothing here yet. Recipes you save in the Lab show up here.", 14f, Deco.MUTED), Kit.lp(top = Deco.dpi(c, 16)))
            return
        }
        var lastGroup: LibraryRecipe.Group? = null
        for (item in items) {
            if (group == null && item.group != lastGroup) {
                list.addView(Kit.sectionTitle(c, item.group.label))
                lastGroup = item.group
            }
            list.addView(card(item), Kit.lp(bottom = Deco.dpi(c, 12)))
        }
    }

    /** A recipe as a ticket: its palette, name and source, the film and range as fields, a tear line, then actions. */
    private fun card(item: LibraryRecipe): View = Kit.panel(c, padH = CARD_PAD, padV = 18).apply {
        val open = item.id in expanded
        if (item.palette.isNotEmpty()) addView(Kit.palette(c, item.palette), Kit.lp(bottom = Deco.dpi(c, 14)))
        addView(Kit.text(c, item.title, 18f, Deco.TEXT, bold = true))
        addView(Kit.label(c, item.source, Deco.DIM, 10f), Kit.lp(top = Deco.dpi(c, 6)))
        addView(Kit.row(c).apply {
            addView(Layouts.field(c, "FILM SIMULATION", item.recipe.filmSim.label), Kit.lp(0, weight = 1.4f))
            addView(Layouts.field(c, "DYNAMIC RANGE", item.recipe.dynamicRange.label, end = true), Kit.lp(0, weight = 1f))
        }, Kit.lp(top = Deco.dpi(c, 14)))
        if (item.look.isNotBlank()) addView(Kit.text(c, item.look, 14f, Deco.TEXT_SOFT), Kit.lp(top = Deco.dpi(c, 12)))
        if (open) {
            addView(Kit.hairline(c), Kit.lp(top = Deco.dpi(c, 14)))
            addView(Layouts.fieldGrid(c, item.recipe.rows()))
            if (item.how.isNotBlank()) {
                addView(Kit.label(c, "How it gets there", Deco.AMBER_LIGHT, 10f), Kit.lp(top = Deco.dpi(c, 14)))
                addView(Kit.text(c, item.how, 14f, Deco.MUTED), Kit.lp(top = Deco.dpi(c, 6)))
            }
            if (item.tips.isNotBlank()) {
                addView(Kit.label(c, "Shooting it", Deco.AMBER_LIGHT, 10f), Kit.lp(top = Deco.dpi(c, 14)))
                addView(Kit.text(c, item.tips, 14f, Deco.MUTED), Kit.lp(top = Deco.dpi(c, 6)))
            }
        }
        addView(TicketDivider(c), android.widget.LinearLayout.LayoutParams(Kit.MATCH, Deco.dpi(c, 18)).apply {
            topMargin = Deco.dpi(c, 14)
            marginStart = -Deco.dpi(c, CARD_PAD)
            marginEnd = -Deco.dpi(c, CARD_PAD)
        })
        addView(Kit.row(c).apply {
            addView(Kit.button(c, if (open) "Less" else "Details", Kit.Style.QUIET) {
                if (!expanded.remove(item.id)) expanded.add(item.id)
                refresh()
            }, Kit.lp(Kit.WRAP))
            addView(Kit.button(c, "Lab", Kit.Style.SECONDARY, LineIcon.Kind.LAB) { a.recipeFlow.openInLab(item.recipe) }, Kit.lp(0, weight = 1f).apply { marginStart = Deco.dpi(c, 8) })
            addView(Kit.button(c, "Load", icon = LineIcon.Kind.UPLOAD) { a.recipeFlow.load(item.recipe) }, Kit.lp(0, weight = 1f).apply { marginStart = Deco.dpi(c, 8) })
        }, Kit.lp(top = Deco.dpi(c, 14)))
        if (item.group == LibraryRecipe.Group.MINE) {
            addView(Kit.button(c, "Delete", Kit.Style.QUIET, LineIcon.Kind.TRASH) { a.recipeFlow.deleteMine(item) }, Kit.lp(top = Deco.dpi(c, 6)))
        }
    }

    private companion object {
        const val CARD_PAD = 18
    }
}
