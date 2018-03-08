import io.fabric8.launcher.booster.catalog.Booster
import io.fabric8.launcher.booster.catalog.LauncherConfiguration
import io.fabric8.launcher.booster.catalog.rhoar.*
import org.arquillian.smart.testing.rules.git.server.EmbeddedHttpGitServer
import org.arquillian.smart.testing.rules.git.server.EmbeddedHttpGitServerBuilder
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.Optional.ofNullable
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap

object Main {

    val DEFAULT_GITHUB_URL = "https://github.com/fabric8-launcher/launcher-booster-catalog.git"

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 0 || args.size == 1 && args[0] == "--help") {
            println("Usage: convert <dest> [<catalog>] [<gitref>]")
            println("       convert --help")
            println()
            println("  dest           - Destination folder (will be created if necessary)")
            println("  catalog        - Either a GitHub url to a booster catalog or a file path to a directory of Git bundles.")
            println("  gitref         - The gitref to use for the base booster .yaml files, the ones used in development (default 'master')")
            return
        }

        val destFolder = args[0]

        var repoUrl: String
        if (args.size == 1) {
            repoUrl = DEFAULT_GITHUB_URL
        } else {
            repoUrl = args[1]
            if (repoUrl.isEmpty()) {
                repoUrl = DEFAULT_GITHUB_URL
            }
        }

        val defaultRef: String
        if (args.size > 2) {
            defaultRef = args[2]
        } else {
            defaultRef = "master"
        }

        val yaml = Yaml(DumperOptions().apply {
            indent = 2
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            width = 1000
        })

        val service = fetchBoosters(repoUrl, defaultRef)

        val dest = Paths.get(destFolder);
        service.boosters.forEach {
            var targetFolder = dest.resolve(it.runtime!!.id)
            it.version?.let {
                targetFolder = targetFolder.resolve(it.id)
            }
            targetFolder = targetFolder.resolve(it.mission!!.id)
            Files.createDirectories(targetFolder)
            val yamlFile = File(targetFolder.toFile(), "booster.yaml")
            val writer = FileWriter(yamlFile)
            println("Writing booster yaml file: " + yamlFile)
            yaml.dump(it.data, writer)
        }

        val metadata =  LinkedHashMap<String, Any>()
        metadata.put("missions", categoryList(service, service.missions))
        metadata.put("runtimes", categoryList(service, service.runtimes))
        val yamlFile = File(dest.toFile(), "metadata.yaml")
        val writer = FileWriter(yamlFile)
        println("Writing metadata yaml file: " + yamlFile)
        yaml.dump(metadata, writer)
    }

    private fun categoryList(service: RhoarBoosterCatalogService, categories: Set<CategoryBase>): Any {
        return ArrayList<Map<String, Any>>().apply {
            for (cat in categories) {
                add(categoryMap(service, cat))
            }
        }
    }

    private fun categoryMap(service: RhoarBoosterCatalogService, cat: CategoryBase): Map<String, Any> {
        return LinkedHashMap<String, Any>().apply {
            put("id", cat.id)
            put("name", cat.name)
            cat.description?.let { put("description", it) }
            when (cat) {
                is Mission -> {
                    if (cat.isSuggested) {
                        Booster.setDataValue(this, "metadata/suggested", true)
                    }
                }
                is Runtime -> {
                    cat.icon?.let { put("icon", it) }
                    Booster.setDataValue(this, "metadata/pipelinePlatform", cat.pipelinePlatform)
                    val versions = service.getVersions(BoosterPredicates.withRuntime(cat))
                    put("versions", categoryList(service, versions))
                }
            }
        }
    }

    fun fetchBoosters(repoUrl: String, catalogRef: String): RhoarBoosterCatalogService {
        var repoUrl = repoUrl
        val local = !repoUrl.contains(":")
        var server: EmbeddedHttpGitServer? = null
        try {
            if (local) {
                val bundleDir = File(repoUrl)
                if (!bundleDir.isDirectory) {
                    throw RuntimeException("Bundles folder does not exist or isn't a directory: " + repoUrl)
                }
                server = bundlesFromDirectory(bundleDir)
                        .usingPort(8765)
                        .create()
                server!!.start()
                repoUrl = "http://localhost:8765/booster-catalog/"
                System.setProperty(LauncherConfiguration.PropertyName.LAUNCHER_BOOSTER_CATALOG_REPOSITORY, "http://localhost:8765/booster-catalog/")
                System.setProperty(LauncherConfiguration.PropertyName.LAUNCHER_BOOSTER_CATALOG_REF, catalogRef)
            }

            val service = RhoarBoosterCatalogService.Builder()
                    .catalogRepository(repoUrl)
                    .catalogRef(catalogRef)
                    .build()
            val boosters = service.index().get()

            println("Read " + boosters.size + " boosters.")

            return service
        } finally {
            if (server != null) {
                server.stop()
            }
        }
    }

    private fun bundlesFromDirectory(bundleDir: File): EmbeddedHttpGitServerBuilder {
        val builder = EmbeddedHttpGitServerBuilder("", "")
        Arrays.stream(ofNullable(bundleDir.listFiles()).orElse(arrayOfNulls(0)))
                .filter { file -> file.name.endsWith(".bundle") }
                .forEach { file -> builder.fromFile(file.name.substring(0, file.name.lastIndexOf(".bundle")), file) }
        return builder
    }
}
