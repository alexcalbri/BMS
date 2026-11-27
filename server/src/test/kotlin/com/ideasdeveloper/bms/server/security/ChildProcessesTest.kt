package com.ideasdeveloper.bms.server.security

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The compiler child process never gets the service's secrets, and still compiles (deployment review). */
class ChildProcessesTest {
    private val parent = System.getenv() + mapOf(
        "DB_PASSWORD" to "db-secret", "DB_PROVISIONING_PASSWORD" to "prov-secret", "BMS_MASTER_KEY" to "master-secret",
        "SMTP_PASSWORD" to "smtp-secret", "MODULE_GITHUB_TOKEN" to "gh-secret",
    )

    @Test
    fun theChildSeesOnlyTheAllowlistedVariables() {
        val work = Files.createTempDirectory("child-env")
        val output = work.resolve("env.txt").toFile()
        val process = ChildProcesses.builder(listOf("/usr/bin/env"), work, parent).redirectErrorStream(true).redirectOutput(output).start()
        assertEquals(0, process.waitFor())
        val names = output.readLines().map { it.substringBefore('=') }.toSet()
        assertTrue(names.isNotEmpty())
        assertTrue(names.all { it in ChildProcesses.AllowedVariables || it == "HOME" || it == "TMPDIR" }, names.toString())
        val text = output.readText()
        listOf("db-secret", "prov-secret", "master-secret", "smtp-secret", "gh-secret").forEach { assertFalse(text.contains(it), it) }
        assertTrue(text.contains("HOME=$work"))
    }

    @Test
    fun theKotlinCompilerStillCompilesInTheRestrictedChild() {
        val classpath = System.getProperty("bms.test.moduleCompiler").orEmpty().split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Path.of(it) }
        if (classpath.none { it.fileName.toString().startsWith("kotlin-compiler-embeddable") }) return
        val work = Files.createTempDirectory("child-kotlinc")
        val source = work.resolve("Hello.kt").also { Files.writeString(it, "package demo\nfun hello(): String = \"hola\"\n") }
        val classes = Files.createDirectories(work.resolve("classes"))
        val stdlib = classpath.first { it.fileName.toString().startsWith("kotlin-stdlib") && !it.fileName.toString().contains("common") }
        val arguments = work.resolve("kotlinc.args")
        Files.write(arguments, listOf("-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", "\"$stdlib\"", "-d", "\"$classes\"", "\"$source\""))
        val log = work.resolve("kotlinc.log").toFile()
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ChildProcesses.startKotlinCompiler(java, classpath, arguments, work, log, parent)
        assertEquals(0, process.waitFor(), log.readText())
        assertTrue(Files.isRegularFile(classes.resolve("demo/HelloKt.class")), log.readText())
    }
}
