package tasks

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLinkRegistrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base = "dev.example.mobile"
    private val fingerprint = List(32) { "AB" }.joinToString(":")

    private fun config(
        environment: String = "dev",
        host: String = "links.example.invalid",
        apple: Boolean = true,
    ): String {
        val id = base + if (environment == "prod") "" else ".$environment"
        val values =
            linkedMapOf<String, Any>(
                "host" to host,
                "android" to
                    mapOf("applicationId" to id, "sha256CertFingerprints" to listOf(fingerprint)),
            )
        if (apple) values["ios"] = mapOf("applicationId" to "ABCDE12345.$id")
        return JsonOutput.toJson(values)
    }

    @Test
    fun validatesExactPublicRegistrationsAndOptionalAppleSetup() {
        val dev = parseAppLinkRegistration("dev", config(), base)
        assertEquals("/app/dev/", dev.pathPrefix)
        assertEquals("dev.example.mobile.dev", dev.applicationId)
        assertEquals(listOf(fingerprint), dev.fingerprints)
        assertEquals("ABCDE12345.dev.example.mobile.dev", dev.appleAppId)
        assertEquals(base, parseAppLinkRegistration("prod", config("prod"), base).applicationId)
        assertNull(parseAppLinkRegistration("dev", config(apple = false), base).appleAppId)
    }

    @Test
    fun rejectsHostInjectionAndCrossEnvironmentRegistrations() {
        listOf(
                "",
                "localhost",
                "127.0.0.1",
                "*.example.com",
                "https://example.com",
                "example.com:443",
                "example.com/",
                "EXAMPLE.com",
                "example..com",
                "a".repeat(64) + ".com",
                "-bad.example.com",
                "user@example.com",
                "example.com\n",
            )
            .forEach { host ->
                assertThrows(IllegalArgumentException::class.java) {
                    parseAppLinkRegistration("dev", config(host = host), base)
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            parseAppLinkRegistration("staging", config(), base)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseAppLinkRegistration("qa", config(), base)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseAppLinkRegistration(
                "dev",
                config().replace("ABCDE12345.$base.dev", "ABCDE12345.$base.staging"),
                base,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseAppLinkRegistration("dev", config().replace("ABCDE12345", "team"), base)
        }
    }

    @Test
    fun rejectsCredentialsMalformedFingerprintsAndOversizedInputWithoutEchoingValues() {
        val raw = JsonSlurper().parseText(config()) as Map<*, *>
        val unknown = raw + ("clientSecret" to "private-test-value")
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                parseAppLinkRegistration("dev", JsonOutput.toJson(unknown), base)
            }
        assertFalse(error.toString().contains("private-test-value"))
        val malformed =
            assertThrows(IllegalArgumentException::class.java) {
                parseAppLinkRegistration("dev", "private-test-value {", base)
            }
        assertFalse(malformed.toString().contains("private-test-value"))
        listOf(
                emptyList(),
                listOf("AB"),
                listOf(fingerprint.lowercase()),
                listOf(fingerprint, fingerprint),
                List(9) { fingerprint },
            )
            .forEach { fingerprints ->
                val values =
                    raw +
                        ("android" to
                            mapOf(
                                "applicationId" to "$base.dev",
                                "sha256CertFingerprints" to fingerprints,
                            ))
                assertThrows(IllegalArgumentException::class.java) {
                    parseAppLinkRegistration("dev", JsonOutput.toJson(values), base)
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            parseAppLinkRegistration("dev", " ".repeat(16_385), base)
        }
    }

    @Test
    fun generatedManifestKeepsOauthSeparateAndLeavesUnconfiguredBuildsUnchanged() {
        val source =
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>
          <activity android:name="Example"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
          <intent-filter><action android:name="android.intent.action.VIEW"/><data android:scheme="example-dev" android:host="app"/></intent-filter></activity>
          <activity android:name="OAuth"><intent-filter><data android:scheme="example-dev" android:host="oauth" android:path="/callback"/></intent-filter></activity>
        </application></manifest>"""
        assertEquals(source, appLinkManifest(source, null))
        val output = appLinkManifest(source, parseAppLinkRegistration("dev", config(), base))
        val xml =
            javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(output.byteInputStream())
        val data = xml.getElementsByTagName("data")
        assertEquals(3, data.length)
        val https =
            (0 until data.length)
                .map { data.item(it) as org.w3c.dom.Element }
                .single {
                    it.getAttributeNS("http://schemas.android.com/apk/res/android", "scheme") ==
                        "https"
                }
        assertEquals(
            "links.example.invalid",
            https.getAttributeNS("http://schemas.android.com/apk/res/android", "host"),
        )
        assertEquals(
            "/app/dev/",
            https.getAttributeNS("http://schemas.android.com/apk/res/android", "pathPrefix"),
        )
        assertEquals(
            "true",
            (https.parentNode as org.w3c.dom.Element).getAttributeNS(
                "http://schemas.android.com/apk/res/android",
                "autoVerify",
            ),
        )
        assertEquals(
            "Example",
            (https.parentNode.parentNode as org.w3c.dom.Element).getAttributeNS(
                "http://schemas.android.com/apk/res/android",
                "name",
            ),
        )
        assertTrue(output.contains("/callback"))
        assertThrows(IllegalArgumentException::class.java) {
            appLinkManifest(
                "<manifest><application/></manifest>",
                parseAppLinkRegistration("dev", config(), base),
            )
        }
    }

    @Test
    fun appleEntitlementsRetainPushAndDoNotEnableUnconfiguredAssociatedDomains() {
        val source =
            """<?xml version="1.0"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>aps-environment</key><string>${'$'}(APS_ENVIRONMENT)</string></dict></plist>"""
        val enabled = appLinkEntitlements(source, parseAppLinkRegistration("dev", config(), base))
        assertTrue(enabled.contains("applinks:links.example.invalid"))
        assertTrue(enabled.contains("${'$'}(APS_ENVIRONMENT)"))
        assertFalse(appLinkEntitlements(source, null).contains("associated-domains"))
        assertFalse(
            appLinkEntitlements(
                    source,
                    parseAppLinkRegistration("dev", config(apple = false), base),
                )
                .contains("associated-domains")
        )
        val secret = temporary.newFile("private.xml").apply { writeText("PRIVATE-XML-VALUE") }
        val external =
            """<!DOCTYPE plist [<!ENTITY local SYSTEM "${secret.toURI()}">]><plist><dict><key>x</key><string>&local;</string></dict></plist>"""
        assertFalse(appLinkEntitlements(external, null).contains("PRIVATE-XML-VALUE"))
    }

    @Test
    fun generatedAssociationsShareDomainsAndRetireRemovedConfiguration() {
        val project =
            ProjectBuilder.builder().withProjectDir(temporary.newFolder("project")).build()
        val configDirectory = temporary.newFolder("config")
        val dev = File(configDirectory, "dev.json").apply { writeText(config()) }
        val staging = File(configDirectory, "staging.json").apply { writeText(config("staging")) }
        val baseFile =
            temporary.newFile("base.entitlements").apply {
                writeText(
                    "<plist version=\"1.0\"><dict><key>aps-environment</key><string>development</string></dict></plist>"
                )
            }
        val output = File(project.projectDir, "build/generated/app-links")
        val task =
            project.tasks.register("generateAppLinks", GenerateAppLinks::class.java).get().apply {
                configurationFiles.from(project.fileTree(configDirectory) { include("*.json") })
                baseApplicationId.set(base)
                baseEntitlements.set(baseFile)
                outputDirectory.set(output)
            }
        task.generate()
        val website = File(output, "sites/links.example.invalid/.well-known")
        val android = JsonSlurper().parse(File(website, "assetlinks.json")) as List<*>
        assertEquals(2, android.size)
        val target = (android[0] as Map<*, *>)["target"] as Map<*, *>
        assertEquals("$base.dev", target["package_name"])
        assertEquals(listOf(fingerprint), target["sha256_cert_fingerprints"])
        val apple =
            (JsonSlurper().parse(File(website, "apple-app-site-association")) as Map<*, *>)[
                "applinks"]
                as Map<*, *>
        val details = apple["details"] as List<*>
        assertEquals(2, details.size)
        val paths =
            details.map {
                (((it as Map<*, *>)["components"] as List<*>).single() as Map<*, *>)["/"]
            }
        assertEquals(listOf("/app/dev/*", "/app/staging/*"), paths)
        assertFalse(File(output, "ios/prod.entitlements").readText().contains("associated-domains"))
        assertEquals("$base.dev\n", File(output, "ios/dev.bundle-id").readText())
        dev.delete()
        staging.delete()
        task.generate()
        assertFalse(website.exists())
        assertFalse(File(output, "ios/dev.entitlements").readText().contains("associated-domains"))
        assertFalse(
            File(output, "kotlin/dev/fajar/starter/common/config/BuildAppLinks.kt")
                .readText()
                .contains("links.example.invalid")
        )
    }
}
