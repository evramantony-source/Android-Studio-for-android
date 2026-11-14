package com.example.compiler

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.dx.command.dexer.Main as DxMain
import com.example.model.ApkEntryInfo
import com.example.model.BuildDiagnostic
import com.example.model.BuildResult
import com.example.model.DiagnosticSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jdt.internal.compiler.batch.Main as EcjMain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OfflineApkBuilder(
    private val context: Context? = null,
) {
    suspend fun buildProject(
        projectDir: File,
        packageName: String,
        onProgress: (String) -> Unit = {},
    ): BuildResult = withContext(Dispatchers.IO) {
        val ctx = requireNotNull(context) { "Android Context is required for asset extraction" }
        val toolchainDir = File(ctx.filesDir, "toolchain").apply { mkdirs() }
        val stubsJar = ensureAssetExtracted(ctx, "android-stubs.jar", File(toolchainDir, "android-stubs.jar"))
        val keystoreFile = ensureAssetExtracted(ctx, "debug_signing.p12", File(toolchainDir, "debug_signing.p12"))
        buildWithToolchain(
            projectDir = projectDir,
            packageName = packageName,
            stubsJar = stubsJar,
            keystoreFile = keystoreFile,
            onProgress = onProgress,
        )
    }

    fun buildWithToolchain(
        projectDir: File,
        packageName: String,
        stubsJar: File,
        keystoreFile: File,
        onProgress: (String) -> Unit = {},
    ): BuildResult {
        val startMs = System.currentTimeMillis()
        val logs = mutableListOf<String>()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        fun log(msg: String) {
            logs.add(msg)
            onProgress(msg)
        }

        return try {
            val buildDir = File(projectDir, "build")
            if (buildDir.exists()) buildDir.deleteRecursively()
            val genDir = File(buildDir, "gen").apply { mkdirs() }
            val classesDir = File(buildDir, "classes").apply { mkdirs() }
            val dexDir = File(buildDir, "dex").apply { mkdirs() }
            val apkOutDir = File(buildDir, "outputs/apk").apply { mkdirs() }

            // 1. Compile XML resources -> R.java + resources.arsc
            log("[1/5] Compiling resources (strings.xml, colors.xml)...")
            val stringsFile = File(projectDir, "res/values/strings.xml")
            val colorsFile = File(projectDir, "res/values/colors.xml")
            val compiledRes = ResourceTableCompiler.compile(
                packageName = packageName,
                stringsXml = stringsFile.takeIf { it.exists() }?.readText(),
                colorsXml = colorsFile.takeIf { it.exists() }?.readText(),
            )
            val rFileDir = File(genDir, packageName.replace('.', '/')).apply { mkdirs() }
            File(rFileDir, "R.java").writeText(compiledRes.rJavaSource)
            log("      Generated R.java (${compiledRes.stringCount} strings, ${compiledRes.colorCount} colors) & resources.arsc (${compiledRes.resourcesArsc.size} bytes)")

            // 2. Encode AndroidManifest.xml -> binary AXML
            log("[2/5] Encoding binary AndroidManifest.xml (AXML)...")
            val manifestFile = File(projectDir, "AndroidManifest.xml")
            if (!manifestFile.exists()) {
                diagnostics.add(
                    BuildDiagnostic("AndroidManifest.xml", 1, 1, DiagnosticSeverity.ERROR, "Missing AndroidManifest.xml in project root."),
                )
                return BuildResult(
                    success = false,
                    durationMs = System.currentTimeMillis() - startMs,
                    logs = logs,
                    diagnostics = diagnostics,
                )
            }
            val manifestBytes = BinaryXmlEncoder.encodeManifest(
                xmlText = manifestFile.readText(),
                resourceIds = compiledRes.resourceIds,
            )
            log("      Binary AXML encoded (${manifestBytes.size} bytes)")

            // 3. Compile Java sources via Eclipse JDT (ecj)
            val javaFiles = collectFilesWithExt(File(projectDir, "src"), ".java") +
                collectFilesWithExt(genDir, ".java")
            if (javaFiles.isEmpty()) {
                diagnostics.add(
                    BuildDiagnostic("src", 1, 1, DiagnosticSeverity.ERROR, "No .java source files found in src/."),
                )
                return BuildResult(
                    success = false,
                    durationMs = System.currentTimeMillis() - startMs,
                    logs = logs,
                    diagnostics = diagnostics,
                )
            }
            log("[3/5] Compiling ${javaFiles.size} Java file(s) with Eclipse JDT (Java 8)...")

            val ecjOut = StringWriter()
            val ecjErr = StringWriter()
            val ecjArgs = buildList {
                add("-1.8")
                add("-proc:none")
                add("-encoding")
                add("UTF-8")
                add("-classpath")
                add(stubsJar.absolutePath)
                add("-d")
                add(classesDir.absolutePath)
                javaFiles.forEach { add(it.absolutePath) }
            }.toTypedArray()

            val ecjCompiler = EcjMain(PrintWriter(ecjOut), PrintWriter(ecjErr), false, null, null)
            val ecjOk = ecjCompiler.compile(ecjArgs)
            val combinedEcjOutput = buildString {
                if (ecjOut.toString().isNotBlank()) appendLine(ecjOut.toString().trim())
                if (ecjErr.toString().isNotBlank()) appendLine(ecjErr.toString().trim())
            }.trim()

            if (combinedEcjOutput.isNotEmpty()) {
                diagnostics.addAll(parseEcjDiagnostics(combinedEcjOutput, projectDir))
                combinedEcjOutput.lines().take(30).forEach { line ->
                    if (line.isNotBlank()) log("      $line")
                }
            }

            if (!ecjOk) {
                log("✖ Java compilation failed with ${diagnostics.count { it.severity == DiagnosticSeverity.ERROR }} error(s).")
                return BuildResult(
                    success = false,
                    durationMs = System.currentTimeMillis() - startMs,
                    logs = logs,
                    diagnostics = diagnostics,
                )
            }

            val classFiles = collectFilesWithExt(classesDir, ".class")
            log("      Compiled ${classFiles.size} .class file(s)")

            // 4. Convert .class -> classes.dex via dalvik-dx
            log("[4/5] Converting JVM bytecode to Dalvik DEX (classes.dex)...")
            val dexFile = File(dexDir, "classes.dex")
            val dxArgs = DxMain.Arguments().apply {
                outName = dexFile.absolutePath
                fileNames = arrayOf(classesDir.absolutePath)
                minSdkVersion = 24
                makeOptionsObjects()
            }
            val dxResult = DxMain.run(dxArgs)
            if (dxResult != 0 || !dexFile.exists()) {
                log("✖ DEX conversion failed (exit code $dxResult).")
                return BuildResult(
                    success = false,
                    compiledClassCount = classFiles.size,
                    durationMs = System.currentTimeMillis() - startMs,
                    logs = logs,
                    diagnostics = diagnostics,
                )
            }
            log("      Generated classes.dex (${dexFile.length()} bytes)")

            // 5. Package & Sign APK (v1 + v2 + v3)
            log("[5/5] Packaging & signing APK (Scheme v1/v2/v3)...")
            val unsignedApk = File(buildDir, "app-unsigned.apk")
            val signedApk = File(apkOutDir, "app-debug.apk")
            packageUnsignedApk(
                outputApk = unsignedApk,
                resourcesArsc = compiledRes.resourcesArsc,
                manifestAxml = manifestBytes,
                classesDex = dexFile.readBytes(),
                assetsDir = File(projectDir, "assets"),
            )

            val signerConfig = loadSignerConfig(keystoreFile)
            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(24)
                .build()
                .sign()

            val entries = inspectApkEntries(signedApk)
            val elapsed = System.currentTimeMillis() - startMs
            log("✔ Build succeeded in ${elapsed}ms -> ${signedApk.name} (${signedApk.length()} bytes)")

            BuildResult(
                success = true,
                apkPath = signedApk.absolutePath,
                apkSizeBytes = signedApk.length(),
                compiledClassCount = classFiles.size,
                durationMs = elapsed,
                logs = logs,
                diagnostics = diagnostics,
                apkEntries = entries,
                signerSubject = signerConfig.certificates.firstOrNull()?.subjectX500Principal?.name.orEmpty(),
            )
        } catch (e: Exception) {
            logs.add("✖ Build crashed: ${e.javaClass.simpleName}: ${e.localizedMessage}")
            diagnostics.add(
                BuildDiagnostic("build", 1, 1, DiagnosticSeverity.ERROR, e.localizedMessage ?: "Unexpected build error"),
            )
            BuildResult(
                success = false,
                durationMs = System.currentTimeMillis() - startMs,
                logs = logs,
                diagnostics = diagnostics,
            )
        }
    }

    private fun packageUnsignedApk(
        outputApk: File,
        resourcesArsc: ByteArray,
        manifestAxml: ByteArray,
        classesDex: ByteArray,
        assetsDir: File,
    ) {
        ZipOutputStream(outputApk.outputStream().buffered()).use { zos ->
            // Write resources.arsc FIRST as STORED; local header is 30 + 14 = 44 bytes (4-byte aligned).
            val arscCrc = CRC32().apply { update(resourcesArsc) }.value
            val arscEntry = ZipEntry("resources.arsc").apply {
                method = ZipEntry.STORED
                size = resourcesArsc.size.toLong()
                compressedSize = resourcesArsc.size.toLong()
                crc = arscCrc
            }
            zos.putNextEntry(arscEntry)
            zos.write(resourcesArsc)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(manifestAxml)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(classesDex)
            zos.closeEntry()

            if (assetsDir.exists() && assetsDir.isDirectory) {
                assetsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(assetsDir).invariantSeparatorsPath
                    zos.putNextEntry(ZipEntry("assets/$rel"))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun loadSignerConfig(keystoreFile: File): ApkSigner.SignerConfig {
        val ks = KeyStore.getInstance("PKCS12")
        keystoreFile.inputStream().use { ks.load(it, "droidforge".toCharArray()) }
        val alias = ks.aliases().nextElement()
        val key = ks.getKey(alias, "droidforge".toCharArray()) as PrivateKey
        val chain = ks.getCertificateChain(alias).map { it as X509Certificate }
        return ApkSigner.SignerConfig.Builder("DROIDFORGE", key, chain).build()
    }

    private fun inspectApkEntries(apkFile: File): List<ApkEntryInfo> {
        return ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().map { entry ->
                ApkEntryInfo(
                    name = entry.name,
                    compressedSize = entry.compressedSize,
                    uncompressedSize = entry.size,
                    isStored = entry.method == ZipEntry.STORED,
                )
            }.toList()
        }
    }

    private fun ensureAssetExtracted(ctx: Context, assetName: String, target: File): File {
        if (!target.exists() || target.length() == 0L) {
            ctx.assets.open(assetName).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return target
    }

    private fun collectFilesWithExt(dir: File, ext: String): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(ext) }.toList()
    }

    private fun parseEcjDiagnostics(output: String, projectDir: File): List<BuildDiagnostic> {
        val diagnostics = mutableListOf<BuildDiagnostic>()
        val headerRegex = Regex("""^\d+\.\s+(ERROR|WARNING)\s+in\s+(.+?)\s+\(at line (\d+)\)""")
        val lines = output.lines()
        var idx = 0
        while (idx < lines.size) {
            val match = headerRegex.find(lines[idx].trim())
            if (match != null) {
                val severity = if (match.groupValues[1] == "ERROR") {
                    DiagnosticSeverity.ERROR
                } else {
                    DiagnosticSeverity.WARNING
                }
                val rawPath = match.groupValues[2].trim()
                val relPath = runCatching {
                    File(rawPath).relativeTo(projectDir).invariantSeparatorsPath
                }.getOrDefault(File(rawPath).name)
                val lineNum = match.groupValues[3].toIntOrNull() ?: 1

                var message = "Compilation issue"
                var lookAhead = idx + 1
                while (lookAhead < lines.size && lookAhead <= idx + 5) {
                    val candidate = lines[lookAhead].trim()
                    if (candidate.isNotEmpty() &&
                        !candidate.startsWith("^") &&
                        !candidate.startsWith("----------") &&
                        !headerRegex.containsMatchIn(candidate)
                    ) {
                        // Prefer the final descriptive line after the caret marker
                        message = candidate
                    }
                    if (candidate.startsWith("----------")) break
                    lookAhead++
                }
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = relPath,
                        line = lineNum,
                        column = 1,
                        severity = severity,
                        message = message,
                    ),
                )
            }
            idx++
        }
        return diagnostics
    }
}
