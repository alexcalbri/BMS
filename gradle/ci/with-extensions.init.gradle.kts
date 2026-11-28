allprojects {
    if (path == ":server") {
        afterEvaluate {
            val ext = rootProject.layout.projectDirectory.dir("extensions").asFile
            val sets = extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            fun dirs(name: String) = sets.getByName(name).extensions.getByName("kotlin") as org.gradle.api.file.SourceDirectorySet
            ext.listFiles().orEmpty().filter { it.isDirectory }.forEach { e ->
                dirs("test").srcDir(java.io.File(e, "server/src/main/kotlin"))
                dirs("test").srcDir(java.io.File(e, "server/src/test/kotlin"))
                sets.getByName("test").resources.srcDir(java.io.File(e, "server/src/main/resources"))
            }
        }
    }
}
