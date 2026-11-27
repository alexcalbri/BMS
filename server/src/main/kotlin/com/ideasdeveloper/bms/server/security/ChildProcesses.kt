package com.ideasdeveloper.bms.server.security

import java.io.File
import java.nio.file.Path

/**
 * Child processes of the server (the Kotlin compiler that builds module and extension packages from code
 * downloaded from the catalog) get a minimal allowlisted environment, never the service's: no DB_PASSWORD,
 * DB_PROVISIONING_PASSWORD, BMS_MASTER_KEY, SMTP or GitHub credentials. HOME and TMPDIR point to [work].
 * No proxy variables: the compiler makes no network access.
 */
object ChildProcesses {
    val AllowedVariables = setOf("PATH", "JAVA_HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ")

    fun builder(command: List<String>, work: Path, parent: Map<String, String> = System.getenv()): ProcessBuilder {
        val builder = ProcessBuilder(command)
        val environment = builder.environment()
        environment.clear()
        parent.filterKeys { it in AllowedVariables }.forEach { (key, value) -> environment[key] = value }
        environment["HOME"] = work.toString()
        environment["TMPDIR"] = work.toString()
        return builder
    }

    /** Starts K2JVMCompiler with [arguments] (an @argfile) in a restricted child, its output in [output]. */
    fun startKotlinCompiler(javaExecutable: String, compilerJars: List<Path>, arguments: Path, work: Path, output: File, parent: Map<String, String> = System.getenv()): Process =
        builder(
            listOf(
                javaExecutable, "-Xmx1g", "-Duser.home=$work", "-Djava.io.tmpdir=$work",
                "-cp", compilerJars.joinToString(File.pathSeparator),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "@$arguments",
            ),
            work,
            parent,
        ).redirectErrorStream(true).redirectOutput(output).start()
}
