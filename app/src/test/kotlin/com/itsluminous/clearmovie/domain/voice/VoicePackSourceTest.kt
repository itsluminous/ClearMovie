package com.itsluminous.clearmovie.domain.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where the app downloads the voice pack from (cm-voice-pack-download). */
class VoicePackSourceTest {
    private val commit = VoicePackSource.COMMITS.getValue("v1")

    @Test
    fun `pack files come over HTTPS from the repository's raw host at a fixed commit`() {
        assertThat(commit).matches("[0-9a-f]{40}")
        assertThat(VoicePackSource.url("v1", "voices.zip"))
            .isEqualTo("https://raw.githubusercontent.com/itsluminous/ClearMovie/$commit/voice-packs/v1/voices.zip")
        assertThat(VoicePackSource.url("v1", "../voices.zip")).isNull()
        assertThat(VoicePackSource.url("v1", "manifest.json")).isNull()
        assertThat(VoicePackSource.url("v2", "voices.zip")).isNull()
    }

    @Test
    fun `only pack links at the pinned commit are allowed`() {
        assertThat(VoicePackSource.allowed(VoicePackSource.url("v1", "engine-arm64-v8a.zip")!!)).isTrue()
        assertThat(
            VoicePackSource.allowed("https://raw.githubusercontent.com/itsluminous/ClearMovie/main/voice-packs/v1/voices.zip"),
        ).isFalse()
        assertThat(
            VoicePackSource.allowed("https://raw.githubusercontent.com/itsluminous/ClearMovie/${"0".repeat(40)}/voice-packs/v1/voices.zip"),
        ).isFalse()
        assertThat(
            VoicePackSource.allowed("http://raw.githubusercontent.com/itsluminous/ClearMovie/$commit/voice-packs/v1/voices.zip"),
        ).isFalse()
        assertThat(
            VoicePackSource.allowed(
                "https://raw.githubusercontent.com.evil.example/itsluminous/ClearMovie/$commit/voice-packs/v1/voices.zip",
            ),
        ).isFalse()
        assertThat(
            VoicePackSource.allowed("https://raw.githubusercontent.com/itsluminous/ClearMovie/$commit/voice-packs/v1/voices.zip#x"),
        ).isFalse()
    }

    @Test
    fun `the room a download needs covers everything unpacked and the largest zip`() {
        val a = VoicePackFile("a.zip", VoicePackFile.VOICES, 100, 300, 1, "a".repeat(64))
        val b = VoicePackFile("b.zip", VoicePackFile.SPEECH_EN, 40, 70, 1, "b".repeat(64))
        assertThat(roomToDownload(listOf(a, b), 5)).isEqualTo(300 + 70 + 100 + 5)
        assertThat(roomToDownload(emptyList(), 5)).isEqualTo(5)
    }
}
