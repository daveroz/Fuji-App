package com.fujiapp.remote.recipe

import com.fujiapp.remote.recipe.DynamicRange.DR100
import com.fujiapp.remote.recipe.DynamicRange.DR200
import com.fujiapp.remote.recipe.DynamicRange.DR400
import com.fujiapp.remote.recipe.Effect.OFF
import com.fujiapp.remote.recipe.Effect.STRONG
import com.fujiapp.remote.recipe.Effect.WEAK
import com.fujiapp.remote.recipe.FilmSim.ACROS
import com.fujiapp.remote.recipe.FilmSim.ACROS_R
import com.fujiapp.remote.recipe.FilmSim.ACROS_YE
import com.fujiapp.remote.recipe.FilmSim.ASTIA
import com.fujiapp.remote.recipe.FilmSim.BLEACH_BYPASS
import com.fujiapp.remote.recipe.FilmSim.CLASSIC_CHROME
import com.fujiapp.remote.recipe.FilmSim.CLASSIC_NEG
import com.fujiapp.remote.recipe.FilmSim.ETERNA
import com.fujiapp.remote.recipe.FilmSim.NOSTALGIC_NEG
import com.fujiapp.remote.recipe.FilmSim.PROVIA
import com.fujiapp.remote.recipe.FilmSim.PRO_NEG_STD
import com.fujiapp.remote.recipe.FilmSim.REALA_ACE
import com.fujiapp.remote.recipe.FilmSim.VELVIA
import com.fujiapp.remote.recipe.Grain.OFF as GRAIN_OFF
import com.fujiapp.remote.recipe.Grain.STRONG_LARGE
import com.fujiapp.remote.recipe.Grain.STRONG_SMALL
import com.fujiapp.remote.recipe.Grain.WEAK_SMALL
import com.fujiapp.remote.recipe.WhiteBalance.AUTO
import com.fujiapp.remote.recipe.WhiteBalance.DAYLIGHT
import com.fujiapp.remote.recipe.WhiteBalance.KELVIN

/** A recipe in the library: the settings, and what the look is and how the settings get there. */
data class LibraryRecipe(
    val id: String,
    val title: String,
    val group: Group,
    /** One line under the title: the film or the scene the look comes from. */
    val source: String,
    /** How the original looks and handles color. */
    val look: String,
    /** How the settings get there on a Fujifilm camera. */
    val how: String,
    /** Shooting advice that isn't part of a slot (exposure, ISO). */
    val tips: String,
    /** A few colors that sum up the palette, for the card. */
    val palette: List<Int>,
    val recipe: Recipe,
) {
    enum class Group(val label: String) { FILM("Film stocks"), CINEMA("Cinema"), MINE("My recipes") }
}

/**
 * Recipes written for this app, after studying each film stock's and each film's color: how it
 * renders skin, greens, blues and reds, its contrast and grain, and the mood it carries. They're
 * interpretations, set within what an X100VI's custom settings can hold; none is copied from a
 * published recipe. Halation, gate weave and lab-specific scanning can't be made in camera.
 * Sharpness and clarity stay at 0 in all of them: the look comes from color and tone, and detail
 * is left to taste.
 */
object RecipeLibrary {
    private fun c(hex: Long) = (0xFF000000 or hex).toInt()

    /** DR200 and DR400 need ISO 250 / 500 or higher (or ISO Auto) in the slot. */
    private const val ISO_NOTE = "Set the slot's ISO to Auto (up to 6400) so DR400 is allowed."

    val all: List<LibraryRecipe> = listOf(
        // ---------- Film stocks ----------
        LibraryRecipe(
            "portra400", "Kodak Portra 400", LibraryRecipe.Group.FILM, "Color negative, ISO 400",
            look = "The portrait film everyone knows: warm, honest skin, soft contrast that forgives over-exposure, " +
                "creamy highlights and gentle, slightly muted greens. Colors feel natural rather than punchy, with a golden glow in sun.",
            how = "Reala Ace keeps skin faithful; a warm shift (R+2, B−4) adds Portra's golden cast. Soft tones (Highlight −1, Shadow −1) " +
                "give the gentle roll-off of a negative scan, with weak small grain.",
            tips = "Expose +1/3 to +2/3. $ISO_NOTE",
            palette = listOf(c(0xE8C4A0), c(0xC98B5E), c(0x8E9B6B), c(0x6F8FA6)),
            recipe = Recipe("PORTRA 400", REALA_ACE, WEAK_SMALL, WEAK, OFF, AUTO, wbRed = 2, wbBlue = -4, dynamicRange = DR400,
                highlight = -1.0, shadow = -1.0, color = 0, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "portra160", "Kodak Portra 160", LibraryRecipe.Group.FILM, "Color negative, ISO 160",
            look = "Portra's quieter sibling: lower saturation, finer grain and a slightly cooler, cleaner balance. Pastel and calm, made for daylight portraits and weddings.",
            how = "Pro Neg. Std gives the soft, low-saturation base; Daylight with R+1, B−2 keeps it just warm of neutral. " +
                "Highlight −1 and Shadow −0.5 for a long tonal scale, Color +1 so it doesn't go grey, no grain.",
            tips = "Bright light, expose +2/3. Works best in open shade.",
            palette = listOf(c(0xF0D9C4), c(0xD7B49A), c(0xA3B3A0), c(0x8FA7B8)),
            recipe = Recipe("PORTRA 160", PRO_NEG_STD, GRAIN_OFF, WEAK, OFF, DAYLIGHT, wbRed = 1, wbBlue = -2, dynamicRange = DR200,
                highlight = -1.0, shadow = -0.5, color = 1, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "ektar100", "Kodak Ektar 100", LibraryRecipe.Group.FILM, "Color negative, ISO 100",
            look = "The most saturated negative Kodak makes: vivid reds, deep blue skies, rich greens and a fine, almost invisible grain. Punchy, clean, a landscape film.",
            how = "Velvia supplies the saturation and contrast; Color Chrome Strong and FX Blue Strong deepen reds and skies without clipping. " +
                "A touch of warmth (R+1, B−1) keeps skin from going pink.",
            tips = "Daylight and landscapes; expose for the highlights.",
            palette = listOf(c(0xC8102E), c(0x1F5FA8), c(0x3E7D3A), c(0xF2B134)),
            recipe = Recipe("EKTAR 100", VELVIA, GRAIN_OFF, STRONG, STRONG, DAYLIGHT, wbRed = 1, wbBlue = -1, dynamicRange = DR200,
                highlight = 0.0, shadow = 0.0, color = 1, sharpness = 0, noiseReduction = -2, clarity = 0),
        ),
        LibraryRecipe(
            "gold200", "Kodak Gold 200", LibraryRecipe.Group.FILM, "Consumer color negative, ISO 200",
            look = "Summer on film: a yellow-golden cast, warm skin, saturated but soft colors and visible grain. Nostalgic, sunny, a little imperfect.",
            how = "Nostalgic Neg. brings amber highlights; a strong warm shift (R+3, B−5) adds Gold's yellow. Color +2 and Color Chrome Strong for its saturation, " +
                "Shadow +0.5 for consumer-film depth, weak grain.",
            tips = "Sunny days, golden hour. Expose +1/3. $ISO_NOTE",
            palette = listOf(c(0xF4C26B), c(0xE07A3F), c(0x7FA35B), c(0x5B8DB8)),
            recipe = Recipe("GOLD 200", NOSTALGIC_NEG, WEAK_SMALL, STRONG, WEAK, DAYLIGHT, wbRed = 3, wbBlue = -5, dynamicRange = DR400,
                highlight = -0.5, shadow = 0.5, color = 2, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "ultramax400", "Kodak UltraMax 400", LibraryRecipe.Group.FILM, "Consumer color negative, ISO 400",
            look = "The everyday drugstore film: punchy, warm, saturated, with chunky grain and contrast that makes snapshots pop.",
            how = "Provia as a neutral base pushed warm (R+3, B−4), Color +2 with Color Chrome Strong for saturation, Highlight +0.5 and Shadow +1 for the punch, strong small grain.",
            tips = "Snapshots, travel, flash at night. $ISO_NOTE",
            palette = listOf(c(0xE94F37), c(0xF6AE2D), c(0x4C9F70), c(0x2F6690)),
            recipe = Recipe("ULTRAMAX 400", PROVIA, STRONG_SMALL, STRONG, WEAK, AUTO, wbRed = 3, wbBlue = -4, dynamicRange = DR400,
                highlight = 0.5, shadow = 1.0, color = 2, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "kodachrome64", "Kodachrome 64", LibraryRecipe.Group.FILM, "Color slide film, ISO 64 (discontinued 2009)",
            look = "Deep, dense color: saturated reds and yellows, dark cyan-blue skies, strong contrast and blacks that stay black. The look of mid-century magazines.",
            how = "Classic Chrome gives Kodachrome's dense, slightly cool blues; a warm shift (R+2, B−5) restores its rich reds and yellows. " +
                "Shadow +1.5 for slide-film density, Color +2 and Color Chrome Strong for depth.",
            tips = "Expose −1/3 to keep the color dense. Bright sun.",
            palette = listOf(c(0xB22222), c(0xE3A82B), c(0x1D4E89), c(0x3B5E2B)),
            recipe = Recipe("KODACHROME 64", CLASSIC_CHROME, WEAK_SMALL, STRONG, WEAK, DAYLIGHT, wbRed = 2, wbBlue = -5, dynamicRange = DR200,
                highlight = 0.0, shadow = 1.5, color = 2, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "ektachrome", "Kodak Ektachrome E100", LibraryRecipe.Group.FILM, "Color slide film, ISO 100",
            look = "A clean, cool-neutral slide film: crisp, fine-grained, with clear blues and accurate, moderately saturated color.",
            how = "Provia with a slightly cool balance (R−1, B+1), Color +2 and FX Blue Strong for clear skies and water, Shadow +1 for slide contrast, no grain.",
            tips = "Expose carefully for highlights, like slide film.",
            palette = listOf(c(0x2E86C1), c(0x48C9B0), c(0xF5F5F0), c(0xD35400)),
            recipe = Recipe("EKTACHROME E100", PROVIA, GRAIN_OFF, WEAK, STRONG, DAYLIGHT, wbRed = -1, wbBlue = 1, dynamicRange = DR100,
                highlight = 0.0, shadow = 1.0, color = 2, sharpness = 0, noiseReduction = -2, clarity = 0),
        ),
        LibraryRecipe(
            "superia400", "Fujicolor Superia 400", LibraryRecipe.Group.FILM, "Consumer color negative, ISO 400",
            look = "Fujifilm's everyday film: cool greens, magenta-leaning reds, hard contrast and visible grain. Fujifilm modelled Classic Neg. on it.",
            how = "Classic Neg. carries Superia's color separation and hard tone; a slight green shift (R−1, B−1) adds its cool cast. Shadow +0.5, Color +1, strong small grain.",
            tips = "Street and travel. $ISO_NOTE",
            palette = listOf(c(0x4F8A6B), c(0xC2456E), c(0xE8D9B5), c(0x34495E)),
            recipe = Recipe("SUPERIA 400", CLASSIC_NEG, STRONG_SMALL, WEAK, OFF, AUTO, wbRed = -1, wbBlue = -1, dynamicRange = DR400,
                highlight = 0.0, shadow = 0.5, color = 1, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "pro400h", "Fujicolor Pro 400H", LibraryRecipe.Group.FILM, "Professional color negative, ISO 400 (discontinued 2021)",
            look = "Airy and pastel: minty greens, cool clean whites, soft contrast and skin that glows when over-exposed. The wedding film.",
            how = "Pro Neg. Std for the soft base, a cool, slightly cyan shift (R−2, B+1) for the mint. Highlight −1 and Shadow −1 lift and soften, Color −1 keeps it pastel.",
            tips = "Expose +2/3 to +1 for the airy look. $ISO_NOTE",
            palette = listOf(c(0xDDEFE6), c(0xA8D5BA), c(0xF2D7C9), c(0xB8C9D9)),
            recipe = Recipe("PRO 400H", PRO_NEG_STD, WEAK_SMALL, OFF, WEAK, AUTO, wbRed = -2, wbBlue = 1, dynamicRange = DR400,
                highlight = -1.0, shadow = -1.0, color = -1, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "velvia50", "Fujichrome Velvia 50", LibraryRecipe.Group.FILM, "Color slide film, ISO 50",
            look = "Extreme saturation and contrast: electric greens, deep blues, glowing sunsets with a hint of magenta. The landscape photographer's slide film.",
            how = "Velvia at Color +3 with both Color Chrome effects Strong, a slight magenta shift (R+1, B+1), Highlight +0.5 and Shadow +1 for slide punch.",
            tips = "Landscapes in soft light; it's too much for faces.",
            palette = listOf(c(0x1E8449), c(0x154360), c(0xE74C3C), c(0x8E44AD)),
            recipe = Recipe("VELVIA 50", VELVIA, GRAIN_OFF, STRONG, STRONG, DAYLIGHT, wbRed = 1, wbBlue = 1, dynamicRange = DR100,
                highlight = 0.5, shadow = 1.0, color = 3, sharpness = 0, noiseReduction = -2, clarity = 0),
        ),
        LibraryRecipe(
            "cinestill800t", "CineStill 800T", LibraryRecipe.Group.FILM, "Tungsten cinema film for stills, ISO 800",
            look = "Motion-picture film balanced for tungsten light: neutral under street lamps, cool blue in daylight, teal shadows and glowing lights at night.",
            how = "Eterna for the cinema tonality, white balance fixed at 3200K like a tungsten stock, with R+2, B−1 to keep skin alive. Color +2 and Shadow +1 put back density; strong large grain.",
            tips = "Made for night and neon. In daylight the scene turns blue, as with the real film. $ISO_NOTE (the red halation of the real film can't be made in camera).",
            palette = listOf(c(0x0B3C5D), c(0x1D7874), c(0xE2725B), c(0xF3C677)),
            recipe = Recipe("CINESTILL 800T", ETERNA, STRONG_LARGE, WEAK, OFF, KELVIN, kelvin = 3200, wbRed = 2, wbBlue = -1, dynamicRange = DR400,
                highlight = 0.0, shadow = 1.0, color = 2, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "cinestill50d", "CineStill 50D", LibraryRecipe.Group.FILM, "Daylight cinema film for stills, ISO 50",
            look = "Fine-grained daylight motion-picture film: clean, slightly cool, smooth tonality and gentle saturation. Cinematic without looking graded.",
            how = "Reala Ace for natural color, Daylight a touch cool (B+1), Highlight −1 for a filmic shoulder, Color +1 and weak small grain.",
            tips = "Bright daylight, landscapes and beaches.",
            palette = listOf(c(0x8EC5D6), c(0xE9E4D4), c(0xC79B6D), c(0x587B7F)),
            recipe = Recipe("CINESTILL 50D", REALA_ACE, WEAK_SMALL, WEAK, WEAK, DAYLIGHT, wbRed = 0, wbBlue = 1, dynamicRange = DR200,
                highlight = -1.0, shadow = 0.0, color = 1, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "trix400", "Kodak Tri-X 400", LibraryRecipe.Group.FILM, "Black-and-white negative, ISO 400",
            look = "The photojournalist's black and white: punchy contrast, deep blacks, gritty grain and bright, slightly glowing highlights.",
            how = "Acros + Ye darkens blue skies a little, like the yellow filter many shot it with. Highlight +1 and Shadow +2 for the punch; strong large grain.",
            tips = "Street and documentary. Push to ISO 1600–3200 for more grit.",
            palette = listOf(c(0x111111), c(0x555555), c(0xAAAAAA), c(0xF2F2F2)),
            recipe = Recipe("TRI-X 400", ACROS_YE, STRONG_LARGE, OFF, OFF, AUTO, dynamicRange = DR200,
                highlight = 1.0, shadow = 2.0, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "hp5", "Ilford HP5 Plus", LibraryRecipe.Group.FILM, "Black-and-white negative, ISO 400",
            look = "Smoother than Tri-X: wide latitude, rich midtones, softer contrast and an even grain. Classic and forgiving.",
            how = "Plain Acros with gentle tones (Highlight −1, Shadow +1), strong small grain and a hint of warmth in the toning (WC +1).",
            tips = "Portraits and everyday black and white. $ISO_NOTE",
            palette = listOf(c(0x1A1A1A), c(0x6B6B6B), c(0xB5B5B5), c(0xEDEDED)),
            recipe = Recipe("HP5 PLUS", ACROS, STRONG_SMALL, OFF, OFF, AUTO, dynamicRange = DR400,
                highlight = -1.0, shadow = 1.0, sharpness = 0, noiseReduction = -4, clarity = 0, monoWarmCool = 1),
        ),

        // ---------- Cinema ----------
        LibraryRecipe(
            "br2049vegas", "Blade Runner 2049 · Las Vegas", LibraryRecipe.Group.CINEMA, "Roger Deakins, 2017: the orange ruins",
            look = "A world drowned in sodium-orange dust: nearly monochromatic amber, very low contrast, everything seen through haze. " +
                "Shapes dissolve into the air; the only color is the orange.",
            how = "Eterna for flat cinema tonality; a strong amber shift (R+7, B−9) floods the frame with orange; Color −2 pushes it toward a single hue. " +
                "Lifted, soft tones (Highlight −1, Shadow −1.5) make the haze.",
            tips = "Backlight, dust, fog or a dirty window. Expose +1/3.",
            palette = listOf(c(0xE0802F), c(0xB5531B), c(0xF2B872), c(0x4A2C17)),
            recipe = Recipe("BR2049 LAS VEGAS", ETERNA, WEAK_SMALL, OFF, OFF, DAYLIGHT, wbRed = 7, wbBlue = -9, dynamicRange = DR400,
                highlight = -1.0, shadow = -1.5, color = -2, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "br2049la", "Blade Runner 2049 · Los Angeles", LibraryRecipe.Group.CINEMA, "Roger Deakins, 2017: the cold city",
            look = "Rain, concrete and cold blue light, with neon as the only warmth. Desaturated, steely and dense in the shadows.",
            how = "Eterna at 4200K turns daylight steel-blue; FX Blue Strong deepens it. Shadow +1 for density, Color 0 keeps neon from overwhelming.",
            tips = "Overcast, rain, night streets with neon. $ISO_NOTE",
            palette = listOf(c(0x2C3E50), c(0x5D6D7E), c(0x85C1E9), c(0xE74C8B)),
            recipe = Recipe("BR2049 LA", ETERNA, WEAK_SMALL, WEAK, STRONG, KELVIN, kelvin = 4200, wbRed = -1, wbBlue = 1, dynamicRange = DR400,
                highlight = -1.0, shadow = 1.0, color = 0, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "skyfallshanghai", "Skyfall · Shanghai", LibraryRecipe.Group.CINEMA, "Roger Deakins, 2012: the glass tower",
            look = "Night lit by giant screens: deep blue dominating, magenta and aqua neon, glossy reflections and figures reduced to silhouettes.",
            how = "Classic Neg. for its magenta/cyan separation; 3600K with a magenta shift (R+2, B+3) makes the blue night. " +
                "Shadow +2 crushes blacks into silhouettes; Color +2 and both Color Chrome effects Strong keep neon rich.",
            tips = "Night, neon, glass. Expose −2/3 to keep the blacks.",
            palette = listOf(c(0x0A1F44), c(0x1F6FB2), c(0xD12E8A), c(0x29D3C8)),
            recipe = Recipe("SKYFALL SHANGHAI", CLASSIC_NEG, GRAIN_OFF, STRONG, STRONG, KELVIN, kelvin = 3600, wbRed = 2, wbBlue = 3, dynamicRange = DR200,
                highlight = 0.0, shadow = 2.0, color = 2, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "skyfallscotland", "Skyfall · Scotland", LibraryRecipe.Group.CINEMA, "Roger Deakins, 2012: the moors",
            look = "Grey, cold and weathered: muted earth tones, a pale overcast sky and a restrained, desaturated palette with firm contrast.",
            how = "Classic Chrome desaturated further (Color −2) and cooled (R−2, B+2); Shadow +1 gives the hard, weathered texture.",
            tips = "Overcast landscapes, stone, wind. $ISO_NOTE",
            palette = listOf(c(0x6E7B74), c(0x9AA39F), c(0x5B4A3A), c(0xC9CCC6)),
            recipe = Recipe("SKYFALL SCOTLAND", CLASSIC_CHROME, WEAK_SMALL, STRONG, WEAK, DAYLIGHT, wbRed = -2, wbBlue = 2, dynamicRange = DR400,
                highlight = -0.5, shadow = 1.0, color = -2, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "matrix", "The Matrix", LibraryRecipe.Group.CINEMA, "Bill Pope, 1999: inside the simulation",
            look = "A sickly green cast over everything, cool skin, hard contrast and crisp detail. Digital and unnatural on purpose.",
            how = "Classic Chrome with a strong green shift (R−4, B−4); Shadow +2 and Highlight +1 for the hard look.",
            tips = "City, offices, fluorescent light.",
            palette = listOf(c(0x0F2A1D), c(0x3B7A57), c(0xA3C9A8), c(0x1C1C1C)),
            recipe = Recipe("THE MATRIX", CLASSIC_CHROME, WEAK_SMALL, OFF, OFF, AUTO, wbRed = -4, wbBlue = -4, dynamicRange = DR200,
                highlight = 1.0, shadow = 2.0, color = -1, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "madmax", "Mad Max: Fury Road", LibraryRecipe.Group.CINEMA, "John Seale, 2015: the desert chase",
            look = "Blazing orange desert against saturated teal-blue sky: extreme saturation, crunchy contrast, a comic-book intensity.",
            how = "Velvia at Color +3 warmed hard (R+4, B−5); FX Blue Strong pulls the sky to teal-blue against the orange. Highlight +1, Shadow +1.5.",
            tips = "Harsh midday sun, desert, dust.",
            palette = listOf(c(0xE36414), c(0xFB8B24), c(0x0F4C5C), c(0x5F9EA0)),
            recipe = Recipe("FURY ROAD", VELVIA, GRAIN_OFF, STRONG, STRONG, DAYLIGHT, wbRed = 4, wbBlue = -5, dynamicRange = DR200,
                highlight = 1.0, shadow = 1.5, color = 3, sharpness = 0, noiseReduction = -2, clarity = 0),
        ),
        LibraryRecipe(
            "drive", "Drive", LibraryRecipe.Group.CINEMA, "Newton Thomas Sigel, 2011: night in LA",
            look = "Neon-soaked night: pink and magenta against teal, soft glowing highlights and an '80s synth mood.",
            how = "Eterna at 3200K with a magenta shift (R+3, B+2) for pink-blue night; Color +3 restores neon saturation.",
            tips = "Night streets, car interiors, neon signs. $ISO_NOTE",
            palette = listOf(c(0xFF4F9A), c(0x1B998B), c(0x2E1F47), c(0xF9C80E)),
            recipe = Recipe("DRIVE", ETERNA, WEAK_SMALL, WEAK, WEAK, KELVIN, kelvin = 3200, wbRed = 3, wbBlue = 2, dynamicRange = DR400,
                highlight = -1.0, shadow = 1.0, color = 3, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "amelie", "Amélie", LibraryRecipe.Group.CINEMA, "Bruno Delbonnel, 2001: Montmartre",
            look = "Storybook Paris: saturated reds and greens, a warm golden-yellow wash, blues kept out. Joyful and nostalgic.",
            how = "Provia at Color +3 with Color Chrome Strong; a yellow-green shift (R+2, B−6) gives the golden wash and keeps blues down. Shadow +0.5.",
            tips = "Cafés, markets, warm interiors.",
            palette = listOf(c(0xC0392B), c(0x27AE60), c(0xF1C40F), c(0x7E5109)),
            recipe = Recipe("AMELIE", PROVIA, WEAK_SMALL, STRONG, OFF, DAYLIGHT, wbRed = 2, wbBlue = -6, dynamicRange = DR200,
                highlight = 0.0, shadow = 0.5, color = 3, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "moodforlove", "In the Mood for Love", LibraryRecipe.Group.CINEMA, "Christopher Doyle and Mark Lee Ping-bing, 2000: Hong Kong",
            look = "Lush and melancholic: deep reds, emerald greens, warm tungsten pools of light and rich, velvety shadows.",
            how = "Astia keeps skin soft while Color +3 and Color Chrome Strong deepen reds and greens; a warm shift (R+3, B−3), Shadow +1.5 for velvet blacks.",
            tips = "Night interiors, lamps, rain. Expose −1/3.",
            palette = listOf(c(0x8B0000), c(0x0B6E4F), c(0xD4A017), c(0x2B1B17)),
            recipe = Recipe("MOOD FOR LOVE", ASTIA, WEAK_SMALL, STRONG, OFF, DAYLIGHT, wbRed = 3, wbBlue = -3, dynamicRange = DR200,
                highlight = -0.5, shadow = 1.5, color = 3, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "budapest", "The Grand Budapest Hotel", LibraryRecipe.Group.CINEMA, "Robert Yeoman, 2014: the pink hotel",
            look = "A confection: pastel pinks and lavenders, soft contrast and gentle, candy-like saturation.",
            how = "Astia, softened (Highlight −1, Shadow −1), with a pink-magenta shift (R+2, B+1) and Color +2; no grain.",
            tips = "Symmetry, pastel walls, bright overcast light. $ISO_NOTE",
            palette = listOf(c(0xF4A6B7), c(0xB57EDC), c(0xF7DAD9), c(0x8C1C3E)),
            recipe = Recipe("GRAND BUDAPEST", ASTIA, GRAIN_OFF, WEAK, OFF, AUTO, wbRed = 2, wbBlue = 1, dynamicRange = DR400,
                highlight = -1.0, shadow = -1.0, color = 2, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "joker", "Joker", LibraryRecipe.Group.CINEMA, "Lawrence Sher, 2019: Gotham",
            look = "Grimy city: sickly green-yellow fluorescent light, warm tungsten, dirty saturation and gritty grain.",
            how = "Classic Chrome with a green-yellow shift (R−2, B−5); Color −1 for dirtiness; Shadow +1; strong small grain.",
            tips = "Subways, stairs, fluorescent interiors.",
            palette = listOf(c(0x6B8E23), c(0xC9A227), c(0xA23B1F), c(0x2F3B2F)),
            recipe = Recipe("JOKER", CLASSIC_CHROME, STRONG_SMALL, WEAK, OFF, AUTO, wbRed = -2, wbBlue = -5, dynamicRange = DR200,
                highlight = 0.5, shadow = 1.0, color = -1, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "dune", "Dune", LibraryRecipe.Group.CINEMA, "Greig Fraser, 2021: Arrakis",
            look = "Monumental and dusty: muted sand tones, soft haze, low saturation and huge, quiet skies.",
            how = "Eterna warmed (R+3, B−4) with Color −1 for desaturated sand; Highlight −1 for haze.",
            tips = "Dunes, beaches, concrete, harsh sun. $ISO_NOTE",
            palette = listOf(c(0xC2A27A), c(0x8C6A4F), c(0xE5D3B3), c(0x5E5A55)),
            recipe = Recipe("DUNE", ETERNA, WEAK_SMALL, OFF, OFF, DAYLIGHT, wbRed = 3, wbBlue = -4, dynamicRange = DR400,
                highlight = -1.0, shadow = 0.0, color = -1, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "moonlight", "Moonlight", LibraryRecipe.Group.CINEMA, "James Laxton, 2016: Miami",
            look = "Saturated teal and magenta, glowing skin and humid Miami light. Intimate and vivid.",
            how = "Classic Neg. with a magenta-blue shift (R+1, B+2); Color +2 and FX Blue Strong for teal; softened tones (Highlight −0.5).",
            tips = "Dusk, beach, night streets. $ISO_NOTE",
            palette = listOf(c(0x0E7C7B), c(0xC2185B), c(0x1A237E), c(0xF6BD60)),
            recipe = Recipe("MOONLIGHT", CLASSIC_NEG, WEAK_SMALL, WEAK, STRONG, AUTO, wbRed = 1, wbBlue = 2, dynamicRange = DR400,
                highlight = -0.5, shadow = 0.5, color = 2, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "sicario", "Sicario", LibraryRecipe.Group.CINEMA, "Roger Deakins, 2015: the border",
            look = "Menacing and bleached: desaturated, hard contrast, amber dusk and heat haze.",
            how = "Eterna Bleach Bypass for silvery low saturation and contrast; warmed (R+2, B−3); Highlight +0.5, Shadow +1.",
            tips = "Harsh sun, dusk silhouettes, desert roads.",
            palette = listOf(c(0xB08D57), c(0x5C4B3B), c(0xD9C8A9), c(0x1E1E1E)),
            recipe = Recipe("SICARIO", BLEACH_BYPASS, WEAK_SMALL, OFF, OFF, DAYLIGHT, wbRed = 2, wbBlue = -3, dynamicRange = DR200,
                highlight = 0.5, shadow = 1.0, color = 0, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "her", "Her", LibraryRecipe.Group.CINEMA, "Hoyte van Hoytema, 2013: soft future",
            look = "Warm and tender: coral reds, pastel pinks, soft light and gentle, hazy contrast.",
            how = "Pro Neg. Std warmed (R+3, B−2), very soft tones (Highlight −1.5, Shadow −1) for the haze; Color +1.",
            tips = "Window light, golden hour, portraits. $ISO_NOTE",
            palette = listOf(c(0xE76F51), c(0xF4A261), c(0xFFE8D6), c(0xB5838D)),
            recipe = Recipe("HER", PRO_NEG_STD, GRAIN_OFF, WEAK, OFF, AUTO, wbRed = 3, wbBlue = -2, dynamicRange = DR400,
                highlight = -1.5, shadow = -1.0, color = 1, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
        LibraryRecipe(
            "noir", "Film noir", LibraryRecipe.Group.CINEMA, "1940s crime films: light and shadow",
            look = "Hard light, deep black shadows, bright highlights and no color. Venetian blinds and wet streets.",
            how = "Acros + R for dark skies and strong separation, Highlight +2 and Shadow +2 for hard contrast, strong small grain.",
            tips = "One hard light source; expose for the highlights.",
            palette = listOf(c(0x000000), c(0x3A3A3A), c(0x9E9E9E), c(0xFFFFFF)),
            recipe = Recipe("FILM NOIR", ACROS_R, STRONG_SMALL, OFF, OFF, AUTO, dynamicRange = DR100,
                highlight = 2.0, shadow = 2.0, sharpness = 0, noiseReduction = -4, clarity = 0),
        ),
    )

    fun byId(id: String): LibraryRecipe? = all.firstOrNull { it.id == id }
}
