package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import com.itsluminous.clearmovie.testing.SourceTree
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File

/**
 * cm-preset-motion-defaults on the model: a preset's default animation for new
 * photos and default transition between new items, read, written and applied.
 */
class PresetMotionDefaultsTest {
    private val bundledText = File(SourceTree.mainKotlinDir.parentFile, "assets/presets/event-frame/preset.json").readText()
    private val bundled = (PresetParser.parse(bundledText) as PresetParseResult.Ok).preset

    private fun parse(text: String): Preset {
        val r = PresetParser.parse(text)
        assertThat(r).isInstanceOf(PresetParseResult.Ok::class.java)
        return (r as PresetParseResult.Ok).preset
    }

    /** The bundled preset's file with its `effects` object replaced by [effects] (removed when null). */
    private fun withEffects(effects: String?): String {
        val obj = Json.parseToJsonElement(bundledText).jsonObject
        val edited = if (effects == null) obj - "effects" else obj + ("effects" to Json.parseToJsonElement(effects))
        return JsonObject(edited).toString()
    }

    private fun roundTrip(p: Preset): Preset = parse(PresetWriter.write(p))

    @Test
    fun `a preset file without the photo animation or the transition reads with neither, as before`() {
        // A file written before these were offered, with only a caption animation, or with no effects at all.
        assertThat(parse(withEffects("""{ "textStart": "fadeIn" }""")).effects).isEqualTo(EffectDefaults(textStart = AnimKind.FADE_IN))
        assertThat(parse(withEffects(null)).effects).isEqualTo(EffectDefaults())
        // A length without a transition is no transition.
        assertThat(parse(withEffects("""{ "transitionMs": 800 }""")).effects.mainTransition).isNull()
        // The template every new preset starts from keeps its gentle zoom and 0.6 s crossfade.
        assertThat(bundled.effects.photoDuring).isEqualTo(AnimKind.KEN_BURNS_IN)
        assertThat(bundled.effects.mainTransition).isEqualTo(Transition(TransitionKind.CROSSFADE, 600))
    }

    @Test
    fun `every photo animation and every transition, None included, is written and reads back unchanged`() {
        val photo = listOf(null) + AnimKind.entries.filter { AnimSlot.DURING in it.slots && !it.textOnly }
        assertThat(photo).containsExactly(null, AnimKind.KEN_BURNS_IN, AnimKind.KEN_BURNS_OUT)
        for (during in photo) {
            for (kind in TransitionKind.entries) {
                val transition = if (kind == TransitionKind.NONE) null else Transition(kind, 600)
                val p = bundled.copy(effects = bundled.effects.copy(photoDuring = during, mainTransition = transition))
                assertThat(roundTrip(p)).isEqualTo(p)
            }
        }
        // None is written as an explicit null, so it never reads back as the template's.
        val none = PresetWriter.write(bundled.copy(effects = EffectDefaults()))
        assertThat(none).contains("\"photoDuring\": null")
        assertThat(none).contains("\"mainTransition\": null")
        assertThat(none).doesNotContain("transitionMs")
    }

    @Test
    fun `a photo animation a photo cannot have all through is refused, never quietly replaced`() {
        for (kind in listOf("fadeIn", "pulse", "zoomOut")) {
            assertThat(
                PresetParser.parse(withEffects("""{ "photoDuring": "$kind" }""")),
            ).isInstanceOf(PresetParseResult.Invalid::class.java)
        }
    }

    /** A project made from a preset with [effects], with a photo, a video and a photo added the way the picker adds them. */
    private fun madeWith(effects: EffectDefaults): Project {
        val preset = Fixtures.preset.copy(effects = effects)
        val items = listOf(Fixtures.photo("a"), Fixtures.video("b"), Fixtures.photo("c"))
        return ProjectFactory
            .fromPreset(Fixtures.PROJECT_ID, preset, Fixtures.presetMedia, SequentialIds("x"))
            .addToMain(items, ids = SequentialIds("fx"))
            .withEffectDefaultsFor(items.map { it.id })
            .assertValid()
    }

    private fun Project.during(id: String) = requireClip(ClipId(id)).animations.during?.kind

    private fun Project.transitions() = mainClips.dropLast(1).map { it.transitionToNext }

    @Test
    fun `new photos get the chosen animation and every new junction the chosen transition`() {
        val p = madeWith(EffectDefaults(KEN_OUT, WIPE, AnimKind.POP))
        assertThat(p.during("a")).isEqualTo(AnimKind.KEN_BURNS_OUT)
        assertThat(p.during("c")).isEqualTo(AnimKind.KEN_BURNS_OUT)
        // A video gets no animation: the gentle zooms are for photos.
        assertThat(p.requireClip(ClipId("b")).animations.isEmpty).isTrue()
        // Intro, a, b, c, ending: four junctions, each with the wipe.
        assertThat(p.transitions()).containsExactly(WIPE, WIPE, WIPE, WIPE)
    }

    @Test
    fun `None leaves new items still and joins them with cuts`() {
        val p = madeWith(EffectDefaults(photoDuring = null, mainTransition = null))
        assertThat(p.mainClips.all { it.animations.isEmpty }).isTrue()
        assertThat(p.transitions().all { it == null }).isTrue()
    }

    @Test
    fun `a project keeps the defaults its preset had when it was made, for the items it has and the ones added later`() {
        val made = madeWith(EffectDefaults(KEN_OUT, WIPE))
        // The project has its own copy of the defaults, saved with it: what its preset says later is not read.
        val saved = ProjectCodec().run { decode(encode(made)) as ProjectDecodeResult.Ok }.project
        assertThat(saved.effectDefaults).isEqualTo(EffectDefaults(KEN_OUT, WIPE))
        val later = Fixtures.photo("d")
        val added = saved.addToMain(listOf(later), ids = SequentialIds("fy")).withEffectDefaultsFor(listOf(later.id))
        assertThat(added.during("d")).isEqualTo(AnimKind.KEN_BURNS_OUT)
        assertThat(added.during("a")).isEqualTo(AnimKind.KEN_BURNS_OUT)
        assertThat(
            added.mainClips
                .dropLast(1)
                .map { it.transitionToNext }
                .distinct(),
        ).containsExactly(WIPE)
    }

    private companion object {
        val KEN_OUT = AnimKind.KEN_BURNS_OUT
        val WIPE = Transition(TransitionKind.WIPE_LEFT, 600)
    }
}
