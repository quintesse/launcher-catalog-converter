import java.util.Optional.ofNullable

import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture

import org.arquillian.smart.testing.rules.git.server.EmbeddedHttpGitServer
import org.arquillian.smart.testing.rules.git.server.EmbeddedHttpGitServerBuilder
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

import io.openshift.booster.catalog.Booster
import io.openshift.booster.catalog.BoosterCatalogService
import io.openshift.booster.catalog.LauncherConfiguration

object Main {

    val DEFAULT_GITHUB_URL = "https://github.com/fabric8-launcher/launcher-booster-catalog.git"

    private val ENV_PRODUCTION = "production"
    private val ENV_STAGING = "staging"
    private val ENV_DEVELOPMENT = "development"

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 0 || args.size == 1 && args[0] == "--help") {
            println("Usage: convert <dest> [<catalog>] [<dev_gitref>] [<staging_gitref>] [<prod_gitref>]")
            println("       convert --help")
            println()
            println("  dest           - Destination folder (will be created if necessary)")
            println("  catalog        - Either a GitHub url to a booster catalog or a file path to a directory of Git bundles.")
            println("  dev_gitref     - The gitref to use for the base booster .yaml files, the ones used in development (default 'master')")
            println("  staging_gitref - The gitref for the boosters used in staging (default = don't use)")
            println("  prod_gitref    - The gitref for the boosters used in production (default = don't use)")
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

        val stagingRef: String?
        if (args.size > 3) {
            stagingRef = args[3]
        } else {
            stagingRef = null
        }

        val productionRef: String?
        if (args.size > 4) {
            productionRef = args[4]
        } else {
            productionRef = null
        }

        val developmentBoosters = fetchBoosters(repoUrl, defaultRef)
        val stagingBoosters = if (stagingRef != null) fetchBoosters(repoUrl, stagingRef) else emptySet<Booster>()
        val productionBoosters = if (productionRef != null) fetchBoosters(repoUrl, productionRef) else emptySet<Booster>()

        if (developmentBoosters == null || stagingBoosters == null || productionBoosters == null) {
            return
        }

        val combined = combineBoosters(developmentBoosters, stagingBoosters, productionBoosters)

        setDescriptionsFromPath(developmentBoosters)

        val opts = DumperOptions()
        opts.indent = 2
        opts.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        opts.isPrettyFlow = true
        opts.width = 1000

        val yaml = Yaml(opts)

        for (env in combined.values) {
            val devBooster = env[ENV_DEVELOPMENT]
            if (devBooster != null) {
                val data = envToMap(env)
                val yamlFile = getYamlFileName(destFolder, devBooster)
                Files.createDirectories(yamlFile.parentFile.toPath())
                val writer = FileWriter(yamlFile)
                println("Writing booster yaml file: " + yamlFile)
                yaml.dump(data, writer)
            }
        }
    }

    @Throws(Exception::class)
    fun fetchBoosters(repoUrl: String, catalogRef: String): Set<Booster>? {
        var repoUrl = repoUrl
        val local = !repoUrl.contains(":")
        var server: EmbeddedHttpGitServer? = null
        try {
            if (local) {
                val bundleDir = File(repoUrl)
                if (!bundleDir.isDirectory) {
                    System.err.println("Bundles folder does not exist or isn't a directory: " + repoUrl)
                    return null
                }
                server = bundlesFromDirectory(bundleDir)
                        .usingPort(8765)
                        .create()
                server!!.start()
                repoUrl = "http://localhost:8765/booster-catalog/"
                System.setProperty(LauncherConfiguration.PropertyName.LAUNCHER_GIT_HOST, "http://localhost:8765/")
                System.setProperty(LauncherConfiguration.PropertyName.LAUNCHER_BOOSTER_CATALOG_REPOSITORY, "http://localhost:8765/booster-catalog/")
                System.setProperty(LauncherConfiguration.PropertyName.LAUNCHER_BOOSTER_CATALOG_REF, catalogRef)
            }

            val service = BoosterCatalogService.Builder()
                    .catalogRepository(repoUrl)
                    .catalogRef(catalogRef)
                    .build()
            val result = service.index()
            val boosters = result.get()

            println("Read " + boosters.size + " boosters.")

            return boosters
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

    private fun combineBoosters(
            developmentBoosters: Set<Booster>,
            stagingBoosters: Set<Booster>,
            productionBoosters: Set<Booster>): Map<String, Map<String, Booster>> {

        val result = LinkedHashMap<String, MutableMap<String, Booster>>()

        // First put in all the development boosters, each in their own map
        for (b in developmentBoosters) {
            val env = LinkedHashMap<String, Booster>()
            env.put(ENV_DEVELOPMENT, b)
            result.put(b.id, env)
        }

        // Now put their staging counterparts in their with them
        for (b in stagingBoosters) {
            val env = result[b.id]
            if (env != null) {
                dedup(b, env[ENV_DEVELOPMENT])
                env.put(ENV_STAGING, b)
            } else {
                System.err.println("ERROR: Found a booster in staging that does not exist in development(master): " + b.id)
            }
        }

        // And finally their production counterparts too
        for (b in productionBoosters) {
            val env = result[b.id]
            if (env != null) {
                dedup(b, env[ENV_DEVELOPMENT])
                env.put(ENV_PRODUCTION, b)
            } else {
                System.err.println("ERROR: Found a booster in production that does not exist in development(master): " + b.id)
            }
        }

        return result
    }

    private fun <T> dedup(env: T?, def: T): T? {
        return if (env != null && env != def) {
            env
        } else {
            null
        }
    }

    private fun dedupMap(env: MutableMap<String, Any>, def: Map<String, Any>) {
        for (key in def.keys) {
            if (env.containsKey(key)) {
                val envValue = env[key]
                val defValue = def[key]
                if (envValue is Map<*, *>) {
                    if (defValue is MutableMap<*, *>) {
                        dedupMap(envValue as MutableMap<String, Any>, defValue as MutableMap<String, Any>)
                    }
                } else if (envValue == defValue) {
                    env.remove(key)
                }
            }
        }
    }

    private fun dedup(env: Booster, def: Booster) {
        env.githubRepo = dedup(env.githubRepo, def.githubRepo)
        env.gitRef = dedup(env.gitRef, def.gitRef)
        env.supportedDeploymentTypes = dedup(env.supportedDeploymentTypes, def.supportedDeploymentTypes)
        env.buildProfile = dedup(env.buildProfile, def.buildProfile)
        env.boosterDescriptorPath = dedup(env.boosterDescriptionPath, def.boosterDescriptionPath)
        dedupMap(env.metadata, def.metadata)
        if (env.version != null && env.version.name == def.version.name) {
            env.version = null
        }
    }

    private fun getYamlFileName(destFolder: String, b: Booster): File {
        var f = File(destFolder)
        if (b.mission != null) {
            f = File(f, b.mission.id)
        }
        if (b.runtime != null) {
            f = File(f, b.runtime.id)
        }
        if (b.version != null) {
            f = File(f, b.version.id)
        }
        return File(f, "booster.yaml")
    }

    private fun envToMap(env: Map<String, Booster>): HashMap<String, Any> {
        val devBooster = env[ENV_DEVELOPMENT]
        val boosterData = if (devBooster != null) boosterToMap(devBooster) else LinkedHashMap<String, Any>()

        if (env.containsKey(ENV_STAGING) || env.containsKey(ENV_PRODUCTION)) {
            val environment = LinkedHashMap<String, Any>()
            boosterData.put("environment", environment)
            val envBooster = env[ENV_STAGING]
            if (envBooster != null) {
                val stagingData = boosterToMap(envBooster)
                environment.put(ENV_STAGING, stagingData)
            }
            val prodBooster = env[ENV_PRODUCTION]
            if (prodBooster != null) {
                val productionData = boosterToMap(prodBooster)
                environment.put(ENV_PRODUCTION, productionData)
            }
        }

        return boosterData
    }

    private fun boosterToMap(b: Booster): HashMap<String, Any> {
        val result = LinkedHashMap<String, Any>()

        if (b.githubRepo != null || b.gitRef != null) {
            val source = LinkedHashMap<String, Any>()
            result.put("source", source)
            val git = LinkedHashMap<String, Any>()
            source.put("git", git)
            if (b.githubRepo != null) {
                git.put("url", "https://github.com/" + b.githubRepo)
            }
            if (b.gitRef != null) {
                git.put("ref", b.gitRef)
            }
        }

        val name = b.metadata["name"]
        if (name != null) {
            result.put("name", name)
        }
        val description = b.metadata["description"]
        if (description != null) {
            result.put("description", description)
        }

        if (b.buildProfile != null
                || b.supportedDeploymentTypes != null && !b.supportedDeploymentTypes.isEmpty()
                || b.version != null) {
            val metadata = LinkedHashMap<String, Any>()
            result.put("metadata", metadata)

            if (b.version != null) {
                val version = LinkedHashMap<String, Any>()
                metadata.put("version", version)
                var name = b.version.name
                val versions = b.metadata["versions"]
                if (versions is Map<*, *>) {
                    val v = (versions as Map<String, Any>)[b.version.id]
                    if (v is Map<*, *>) {
                        name = (v as Map<String, Any>)["name"].toString()
                    }
                }
                version.put("name", name)
            }
            if (b.buildProfile != null) {
                metadata.put("buildProfile", b.buildProfile)
            }
            if ("zip".equals(b.supportedDeploymentTypes, ignoreCase = true)) {
                metadata.put("runsOn", "none")
            }

        }

        return result
    }

    private fun setDescriptionsFromPath(boosters: Set<Booster>) {
        for (b in boosters) {
            if (b.boosterDescriptionPath != null) {
                val descriptionPath = b.contentPath.resolve(b.boosterDescriptionPath)
                if (Files.exists(descriptionPath)) {
                    try {
                        val descriptionContent = Files.readAllBytes(descriptionPath)
                        b.metadata.put("description", String(descriptionContent))
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                }
            }
        }
    }
}
