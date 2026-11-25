package com.itsluminous.clearmovie

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.clearmovie.domain.voice.VoicePackSource
import com.itsluminous.clearmovie.testing.SourceTree
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ClearMovie uses the internet only to download the voice pack
 * (cm-voice-pack-download). This keeps that promise enforced:
 * - Only `data/voice/PackDownloader.kt` may use networking APIs in the app's
 *   own sources (and the vendored ones).
 * - The manifest asks for INTERNET with the network security config, which
 *   allows no plain HTTP and trusts only the pack's host.
 * - No networking library is a dependency.
 * `scripts/verify-release-apks.sh` checks the release APK itself the same way
 * (its permissions, its network security config, and the code that calls the
 * network).
 */
class NetworkUseConventionTest {
    private val moduleDir = SourceTree.resDir.parentFile.parentFile.parentFile
    private val allowed = setOf("com/itsluminous/clearmovie/data/voice/PackDownloader.kt")

    /** What counts as using the network. */
    private val networking =
        listOf(
            Regex("""\bjava\.net\.(?!URI\b)"""),
            Regex("""\bjavax\.net\."""),
            Regex("""\b(Http|Https)?URLConnection\b"""),
            Regex("""\b(Datagram|Server|SSL)?Socket(Factory)?\b"""),
            Regex("""\bopen(Connection|Stream)\s*\("""),
            Regex("""\btoURL\s*\(|\bInet(Socket)?Address\b|\bgetSocketFactory\b|\bbindSocket\b|\bMediaPlayer\b"""),
            // Media readers that also take a web address (the app gives them content:// links only).
            Regex("""\b(MediaExtractor|MediaMetadataRetriever)\b[\s\S]{0,200}?\.setDataSource\(\s*"|\bsetVideo(URI|Path)\b"""),
            Regex("""\b(okhttp3|retrofit2|io\.ktor|org\.chromium\.net|com\.android\.volley)\b"""),
            Regex("""\bandroid\.net\.http\b"""),
            Regex("""\b(WebView|DownloadManager)\b"""),
            Regex("""\b(Default)?HttpDataSource\b|\bUdpDataSource\b|\bRtmpDataSource\b"""),
        )

    fun violations(
        path: String,
        source: String,
    ): List<String> {
        if (allowed.any { path.replace('\\', '/').endsWith(it) }) return emptyList()
        val code = withoutComments(source)
        return networking.mapNotNull { re -> re.find(code)?.let { "$path uses ${it.value.trim()}" } }
    }

    /** One level of the scanner: code (with its open braces), a "string", or a """raw string""". */
    private class Frame(
        val mode: Char,
        var braces: Int = 0,
    )

    /**
     * [source] with its `//` and `/* */` comments left out. Strings, raw strings and char literals are
     * kept as they are, and `${...}` inside a string is read as code again (where a comment can start),
     * so neither a "//" in a string nor a quote in a template hides the code after it.
     */
    private fun withoutComments(source: String): String {
        val out = StringBuilder(source.length)
        val frames = ArrayDeque(listOf(Frame('c')))
        var i = 0

        fun at(s: String) = source.startsWith(s, i)

        fun copy(n: Int) {
            val end = minOf(source.length, i + n)
            out.append(source, i, end)
            i = end
        }
        while (i < source.length) {
            val frame = frames.last()
            val c = source[i]
            when (frame.mode) {
                'c' -> {
                    when {
                        at("//") -> {
                            while (i < source.length && source[i] != '\n') i++
                        }

                        at("/*") -> {
                            val end = source.indexOf("*/", i + 2)
                            i = if (end < 0) source.length else end + 2
                            out.append(' ')
                        }

                        at("\"\"\"") -> {
                            frames.addLast(Frame('r'))
                            copy(3)
                        }

                        c == '"' -> {
                            frames.addLast(Frame('"'))
                            copy(1)
                        }

                        c == '\'' -> {
                            // A char literal: 'a', '\n', '\'' or '\u0041'.
                            var j = i + 1
                            if (source.getOrNull(j) == '\\') j += 2 else j += 1
                            val close = source.indexOf('\'', j)
                            copy(if (close < 0) 1 else close + 1 - i)
                        }

                        c == '{' -> {
                            frame.braces++
                            copy(1)
                        }

                        c == '}' && frame.braces == 0 && frames.size > 1 -> {
                            frames.removeLast()
                            copy(1)
                        }

                        c == '}' -> {
                            frame.braces--
                            copy(1)
                        }

                        else -> {
                            copy(1)
                        }
                    }
                }

                '"' -> {
                    when {
                        c == '\\' -> {
                            copy(2)
                        }

                        c == '"' -> {
                            frames.removeLast()
                            copy(1)
                        }

                        at("\${") -> {
                            frames.addLast(Frame('c'))
                            copy(2)
                        }

                        else -> {
                            copy(1)
                        }
                    }
                }

                else -> {
                    when {
                        at("\"\"\"") -> {
                            // A raw string ends at its last quote in a row.
                            var n = 3
                            while (source.getOrNull(i + n) == '"') n++
                            frames.removeLast()
                            copy(n)
                        }

                        at("\${") -> {
                            frames.addLast(Frame('c'))
                            copy(2)
                        }

                        else -> {
                            copy(1)
                        }
                    }
                }
            }
        }
        return out.toString()
    }

    private fun sources(): Sequence<File> =
        listOf(File(moduleDir, "src/main"), File(moduleDir, "src/vendor"))
            .asSequence()
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") } }

    @Test
    fun `only the voice pack downloader uses the network`() {
        val files = sources().toList()
        assertThat(files.map { it.invariantSeparatorsPath }.any { it.endsWith(allowed.single()) }).isTrue()
        val found = files.flatMap { violations(it.invariantSeparatorsPath, it.readText()) }
        assertWithMessage("Networking outside the voice pack downloader:\n" + found.joinToString("\n")).that(found).isEmpty()
    }

    @Test
    fun `the checker catches planted network use`() {
        assertThat(violations("ui/X.kt", "import java.net.URL\nval c = URL(u).openConnection()")).hasSize(2)
        assertThat(violations("ui/X.kt", "import okhttp3.OkHttpClient")).hasSize(1)
        assertThat(violations("ui/X.kt", "val s = java.net.Socket(\"h\", 1)")).isNotEmpty()
        assertThat(violations("ui/X.kt", "val d = DefaultHttpDataSource.Factory()")).hasSize(1)
        assertThat(violations("ui/X.kt", "val w = WebView(context)")).hasSize(1)
        assertThat(violations("ui/X.kt", "import java.net.URI\n// a URL is not opened here")).isEmpty()
        assertThat(violations("ui/X.kt", "val t = URI.create(u).toURL().readText()")).hasSize(1)
        assertThat(violations("ui/X.kt", "val a = InetAddress.getByName(h)")).isNotEmpty()
        assertThat(violations("ui/X.kt", "val x = 2 *\n    URL(u).openStream()")).hasSize(1)
        assertThat(violations("ui/X.kt", "/* URL(u).openStream() */ val s = \"// not a comment\"")).isEmpty()
        assertThat(violations("ui/X.kt", "val s = \"a\" // x\nval c = u.openConnection()")).hasSize(1)
        // A quote inside a template, or a raw string ending in a backslash, does not hide the code after it.
        assertThat(violations("ui/X.kt", "val c = URI(\"\${base ?: \"https://h\"}/f\").toURL().openStream()")).hasSize(2)
        assertThat(violations("ui/X.kt", "val p = \"\"\"C:\\\"\"\"\nval q = \"a/*\"\nval c = u.openConnection()")).hasSize(1)
        assertThat(violations("ui/X.kt", "val q = '\"'\nval c = u.openConnection() // '\"'")).hasSize(1)
        assertThat(violations("ui/X.kt", "val a = InetSocketAddress(host, 443)")).hasSize(1)
        assertThat(violations("com/itsluminous/clearmovie/data/voice/PackDownloader.kt", "import java.net.URL")).isEmpty()
    }

    private fun xml(file: File): Element =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }

    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun `the manifest asks for the internet only with the network security config`() {
        val manifest = xml(File(moduleDir, "src/main/AndroidManifest.xml"))
        val permissions = manifest.children("uses-permission").map { it.getAttributeNS(androidNs, "name") }
        assertThat(permissions).contains("android.permission.INTERNET")
        val app = manifest.children("application").single()
        assertThat(app.getAttributeNS(androidNs, "networkSecurityConfig")).isEqualTo("@xml/network_security_config")
        assertThat(app.getAttributeNS(androidNs, "usesCleartextTraffic")).isEqualTo("false")
    }

    @Test
    fun `the network security config allows no plain HTTP and trusts only the pack's host`() {
        val config = xml(File(SourceTree.resDir, "xml/network_security_config.xml"))
        assertThat(config.children("debug-overrides")).isEmpty()
        val base = config.children("base-config").single()
        assertThat(base.getAttribute("cleartextTrafficPermitted")).isEqualTo("false")
        // No trust anchors: a secure connection to any other host fails.
        assertThat(
            base
                .children("trust-anchors")
                .single()
                .getElementsByTagName("*")
                .length,
        ).isEqualTo(0)
        val domain = config.children("domain-config").single()
        assertThat(domain.getAttribute("cleartextTrafficPermitted")).isEqualTo("false")
        assertThat(domain.getElementsByTagName("domain-config").length).isEqualTo(0)
        val hosts = domain.children("domain")
        assertThat(hosts.map { it.textContent.trim() }).containsExactly(VoicePackSource.HOST)
        assertThat(hosts.single().getAttribute("includeSubdomains")).isEqualTo("false")
    }

    @Test
    fun `no networking library is a dependency`() {
        val build = File(moduleDir, "build.gradle.kts").readText() + File(moduleDir, "../gradle/libs.versions.toml").readText()
        val libraries =
            listOf(
                "okhttp",
                "retrofit",
                "ktor",
                "cronet",
                "volley",
                "firebase",
                "play-services",
                "com.google.android.gms",
                "media3-datasource-okhttp",
                "media3-datasource-cronet",
                "media3-datasource-rtmp",
                "media3-exoplayer-hls",
                "media3-exoplayer-dash",
                "media3-exoplayer-smoothstreaming",
                "media3-exoplayer-rtsp",
                "media3-exoplayer-ima",
            ).joinToString("|") { Regex.escape(it) }.toRegex()
        assertThat(libraries.findAll(build).map { it.value }.toList()).isEmpty()
    }
}
