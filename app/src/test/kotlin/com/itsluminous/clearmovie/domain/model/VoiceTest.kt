package com.itsluminous.clearmovie.domain.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.domain.model.Fixtures.assertValid
import org.junit.Test

/** The voice choice in the model (cm-voice-choice): the voice list, a voice item's voice and words, and how they are stored. */
class VoiceTest {
    private val ids = SequentialIds("v")
    private val recording = MediaRef.Copied(VoiceRecordingFiles.nameFor("abcd1234efgh5678"))
    private val made = MediaRef.Copied(VoiceRecordingFiles.nameFor("made1234made5678"))
    private val remade = MediaRef.Copied(VoiceRecordingFiles.nameFor("again123again456"))
    private val words = listOf(VoicePhrase(500, "Welcome to the annual day"), VoicePhrase(4000, "Please take your seats"))

    private fun withRecording(): Project {
        val clip = requireNotNull(recordingClip(ClipId("rec"), recording, startMs = 1_000, recordedMs = 8_000, videoEndMs = 20_000))
        return Fixtures
            .blankWith(Fixtures.photo("a", 20_000), ids = ids)
            .addRow(TrackType.AUDIO, AudioRole.VOICE, id = TrackId("voice1"))
            .addRecording(clip, TrackId("voice1"))
            .assertValid()
    }

    private fun Project.rec(): AudioClip = clip(ClipId("rec")) as AudioClip

    @Test
    fun `the voice list starts with No change and has British, Indian and American voices of each gender`() {
        assertThat(VoiceChoice.MENU.first()).isEqualTo(VoiceChoice.NONE)
        assertThat(VoiceChoice.MENU.map { it.id })
            .containsExactly("none", "british_male", "british_female", "indian_male", "indian_female", "american_male", "american_female")
            .inOrder()
        for (accent in VoiceChoice.Accent.entries) {
            assertThat(VoiceChoice.MENU.filter { it.accent == accent }.map { it.gender })
                .containsExactly(VoiceChoice.Gender.MALE, VoiceChoice.Gender.FEMALE)
        }
        assertThat(
            VoiceChoice.MENU
                .filter { it != VoiceChoice.NONE }
                .map { it.speaker }
                .toSet(),
        ).hasSize(6)
        assertThat(VoiceChoice.fromId("indian_female")).isEqualTo(VoiceChoice.INDIAN_FEMALE)
        assertThat(VoiceChoice.fromId("klingon")).isNull()
        assertThat(SpokenLanguage.fromId("hi")).isEqualTo(SpokenLanguage.HINDI)
        assertThat(SpokenLanguage.fromId("fr")).isNull()
    }

    @Test
    fun `Hindi words are said only by the Indian voices, English by all`() {
        assertThat(VoiceChoice.forLanguage(SpokenLanguage.HINDI))
            .containsExactly(VoiceChoice.NONE, VoiceChoice.INDIAN_MALE, VoiceChoice.INDIAN_FEMALE)
            .inOrder()
        assertThat(VoiceChoice.forLanguage(SpokenLanguage.ENGLISH)).isEqualTo(VoiceChoice.MENU)
        assertThat(VoiceChoice.BRITISH_MALE.speaks(SpokenLanguage.HINDI)).isFalse()
        assertThat(VoiceChoice.NONE.speaks(SpokenLanguage.HINDI)).isTrue()
    }

    @Test
    fun `a voice keeps the recording untouched and plays the new file, and No change plays the recording again with its words kept`() {
        val start = withRecording()
        val voiced =
            start
                .setVoiceOver(
                    ClipId("rec"),
                    VoiceOver(VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH, WordsSource.SPOKEN, words, made),
                ).assertValid()
        assertThat(voiced.rec().media).isEqualTo(recording)
        assertThat(voiced.rec().playedMedia).isEqualTo(made)
        assertThat(voiced.recordingsInUse()).containsExactly(recording.relPath, made.relPath)
        val own = voiced.useOwnVoice(ClipId("rec")).assertValid()
        assertThat(own.rec().playedMedia).isEqualTo(recording)
        assertThat(own.rec().voiceOver?.voice).isEqualTo(VoiceChoice.NONE)
        assertThat(own.rec().voiceOver?.phrases).isEqualTo(words)
        assertThat(own.recordingsInUse()).containsExactly(recording.relPath)
        // Plain sounds and missing items are left alone.
        assertThat(start.useOwnVoice(ClipId("rec"))).isEqualTo(start)
        assertThat(start.setVoiceOver(ClipId("nope"), null)).isEqualTo(start)
        assertThat(start.setVoiceOver(ClipId("a"), null)).isEqualTo(start)
    }

    @Test
    fun `undo and redo step through voice changes`() {
        val editor = ProjectEditor(withRecording(), ids)
        val over = VoiceOver(VoiceChoice.INDIAN_FEMALE, SpokenLanguage.ENGLISH, WordsSource.SPOKEN, words, made)
        assertThat(editor.edit { setVoiceOver(ClipId("rec"), over) }).isEqualTo(EditResult.Applied)
        assertThat(editor.edit { useOwnVoice(ClipId("rec")) }).isEqualTo(EditResult.Applied)
        assertThat(editor.project.rec().playedMedia).isEqualTo(recording)
        assertThat(editor.undo()).isTrue()
        assertThat(editor.project.rec().playedMedia).isEqualTo(made)
        assertThat(editor.undo()).isTrue()
        assertThat(editor.project.rec().voiceOver).isNull()
        assertThat(editor.redo()).isTrue()
        assertThat(editor.project.rec().voiceOver).isEqualTo(over)
    }

    @Test
    fun `a typed item has no No change, and speaking it again replaces its file and length`() {
        val clip =
            requireNotNull(recordingClip(ClipId("rec"), made, 2_000, 3_000, 20_000))
                .copy(voiceOver = VoiceOver(VoiceChoice.AMERICAN_MALE, SpokenLanguage.ENGLISH, WordsSource.TYPED, words.take(1)))
        val typed =
            Fixtures
                .blankWith(Fixtures.photo("a", 20_000), ids = ids)
                .addRow(TrackType.AUDIO, AudioRole.VOICE, id = TrackId("voice1"))
                .addRecording(clip, TrackId("voice1"))
                .assertValid()
        assertThat(typed.useOwnVoice(ClipId("rec"))).isEqualTo(typed)
        assertThat(typed.setVoiceOver(ClipId("rec"), null)).isEqualTo(typed)
        assertThat(typed.setVoiceOver(ClipId("rec"), clip.voiceOver!!.copy(voice = VoiceChoice.NONE))).isEqualTo(typed)
        val again =
            typed
                .setTypedVoice(
                    ClipId("rec"),
                    remade,
                    4_500,
                    VoiceOver(VoiceChoice.BRITISH_FEMALE, SpokenLanguage.ENGLISH, WordsSource.TYPED, words.take(1)),
                ).assertValid()
        assertThat(again.rec().media).isEqualTo(remade)
        assertThat(again.rec().durationMs).isEqualTo(4_500)
        assertThat(again.rec().startMs).isEqualTo(2_000)
        assertThat(again.rec().voiceOver?.voice).isEqualTo(VoiceChoice.BRITISH_FEMALE)
        // Cut where the video ends.
        val long = typed.setTypedVoice(ClipId("rec"), remade, 30_000, again.rec().voiceOver!!).assertValid()
        assertThat(long.rec().durationMs).isEqualTo(18_000)
        // Only typed items, and never with No change.
        val spoken = withRecording()
        assertThat(spoken.setTypedVoice(ClipId("rec"), remade, 4_000, again.rec().voiceOver!!)).isEqualTo(spoken)
        assertThat(
            typed.setTypedVoice(ClipId("rec"), remade, 4_000, again.rec().voiceOver!!.copy(voice = VoiceChoice.NONE)),
        ).isEqualTo(typed)
        assertThat(typed.setTypedVoice(ClipId("rec"), remade, 50, again.rec().voiceOver!!)).isEqualTo(typed)
    }

    @Test
    fun `a stored voice is checked like everything else`() {
        val start = withRecording()

        fun bad(over: VoiceOver) =
            start
                .rec()
                .copy(voiceOver = over)
                .let { c ->
                    start.copy(
                        tracks =
                            start.tracks.map { t ->
                                t.copy(
                                    clips =
                                        t.clips.map {
                                            if (it.id ==
                                                c.id
                                            ) {
                                                c
                                            } else {
                                                it
                                            }
                                        },
                                )
                            },
                    )
                }.validate()
        val ok = VoiceOver(VoiceChoice.BRITISH_MALE, SpokenLanguage.ENGLISH, WordsSource.SPOKEN, words, made)
        assertThat(bad(ok)).isEmpty()
        assertThat(bad(ok.copy(phrases = words.reversed()))).isNotEmpty()
        assertThat(bad(ok.copy(phrases = listOf(VoicePhrase(9_000, "after the end"))))).isNotEmpty()
        assertThat(bad(ok.copy(phrases = listOf(VoicePhrase(0, "x".repeat(Limits.MAX_TEXT_LENGTH + 1)))))).isNotEmpty()
        assertThat(bad(ok.copy(phrases = List(VoiceOver.MAX_PHRASES + 1) { VoicePhrase(0, "a") }))).isNotEmpty()
        assertThat(bad(ok.copy(voice = VoiceChoice.NONE))).isNotEmpty()
        assertThat(bad(ok.copy(language = SpokenLanguage.HINDI))).isNotEmpty()
        assertThat(bad(ok.copy(source = WordsSource.TYPED, voice = VoiceChoice.NONE, generated = null))).isNotEmpty()
        assertThat(bad(ok.copy(generated = MediaRef.Copied("song.m4a")))).isNotEmpty()
        assertThat(ok.text).isEqualTo("Welcome to the annual day Please take your seats")
    }

    @Test
    fun `a voice is saved at schema 8, read back the same, and a schema 7 voice item reads as a plain recording`() {
        val codec = ProjectCodec()
        val voiced =
            withRecording().setVoiceOver(
                ClipId("rec"),
                VoiceOver(VoiceChoice.INDIAN_MALE, SpokenLanguage.HINDI, WordsSource.SPOKEN, words, made),
            )
        val text = codec.encode(voiced)
        assertThat(text).contains("\"schemaVersion\": 8")
        assertThat(text).contains("\"voice\": \"indian_male\"")
        assertThat(text).contains("\"language\": \"hi\"")
        val back = codec.decode(text) as ProjectDecodeResult.Ok
        assertThat(back.project).isEqualTo(voiced)
        val older = codec.encode(withRecording()).replace("\"schemaVersion\": 8", "\"schemaVersion\": 7")
        val read = codec.decode(older) as ProjectDecodeResult.Ok
        assertThat(read.fromVersion).isEqualTo(7)
        assertThat(read.project.rec().voiceOver).isNull()
        assertThat(read.project.rec().playedMedia).isEqualTo(recording)
    }
}
