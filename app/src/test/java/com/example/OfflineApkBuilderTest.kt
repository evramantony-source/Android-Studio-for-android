package com.example

import com.android.apksig.ApkVerifier
import com.example.compiler.OfflineApkBuilder
import com.example.data.TemplateProvider
import com.example.model.DiagnosticSeverity
import com.example.model.ProjectTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

class OfflineApkBuilderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun resolveAsset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate asset $name from ${File(".").absolutePath}")
    }

    @Test
    fun buildsAndSignsValidApkFromTemplate() {
        val projectDir = tempFolder.newFolder("counter_project")
        val template = ProjectTemplate.COUNTER_APP
        val files = TemplateProvider.generateFiles(
            template = template,
            appName = template.defaultAppName,
            packageName = template.defaultPackage,
        )
        files.forEach { (relPath, content) ->
            val target = File(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        val builder = OfflineApkBuilder()
        val result = builder.buildWithToolchain(
            projectDir = projectDir,
            packageName = template.defaultPackage,
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
        )

        assertTrue("Expected APK build to succeed, logs=${result.logs}", result.success)
        assertTrue(result.apkSizeBytes > 0)

        val apkFile = File(result.apkPath!!)
        assertTrue(apkFile.exists())

        // Verify APK v1/v2/v3 cryptographic signature with apksig ApkVerifier
        val verification = ApkVerifier.Builder(apkFile)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue("APK signature verification failed: ${verification.errors}", verification.isVerified)
        assertTrue(verification.isVerifiedUsingV2Scheme)

        // Verify internal binary entries (AXML, ARSC, DEX)
        ZipFile(apkFile).use { zip ->
            val arscEntry = zip.getEntry("resources.arsc")!!
            val arscBytes = zip.getInputStream(arscEntry).readBytes()
            val arscType = ByteBuffer.wrap(arscBytes).order(ByteOrder.LITTLE_ENDIAN).short
            assertEquals(0x0002.toShort(), arscType)

            val manifestEntry = zip.getEntry("AndroidManifest.xml")!!
            val manifestBytes = zip.getInputStream(manifestEntry).readBytes()
            val manifestType = ByteBuffer.wrap(manifestBytes).order(ByteOrder.LITTLE_ENDIAN).short
            assertEquals(0x0003.toShort(), manifestType)

            val dexEntry = zip.getEntry("classes.dex")!!
            val dexBytes = zip.getInputStream(dexEntry).readBytes()
            assertEquals('d'.code.toByte(), dexBytes[0])
            assertEquals('e'.code.toByte(), dexBytes[1])
            assertEquals('x'.code.toByte(), dexBytes[2])
        }
    }

    @Test
    fun reportsLineDiagnosticOnJavaSyntaxError() {
        val projectDir = tempFolder.newFolder("broken_project")
        val files = TemplateProvider.generateFiles(
            template = ProjectTemplate.BLANK_ACTIVITY,
            appName = "Broken",
            packageName = "com.example.broken",
        ).toMutableMap()
        files["src/com/example/broken/MainActivity.java"] = """
            package com.example.broken;
            import android.app.Activity;
            import android.os.Bundle;
            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    missingMethodCall();
                }
            }
        """.trimIndent()

        files.forEach { (relPath, content) ->
            val target = File(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        val result = OfflineApkBuilder().buildWithToolchain(
            projectDir = projectDir,
            packageName = "com.example.broken",
            stubsJar = resolveAsset("android-stubs.jar"),
            keystoreFile = resolveAsset("debug_signing.p12"),
        )

        assertFalse(result.success)
        assertTrue(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR && it.line == 8 })
    }
}
