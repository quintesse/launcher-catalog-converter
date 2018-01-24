import static java.util.Optional.ofNullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.arquillian.smart.testing.rules.git.server.EmbeddedHttpGitServer;
import org.arquillian.smart.testing.rules.git.server.EmbeddedHttpGitServerBuilder;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import io.openshift.booster.catalog.Booster;
import io.openshift.booster.catalog.BoosterCatalogService;
import io.openshift.booster.catalog.LauncherConfiguration;

public class Main {

    public static final String DEFAULT_GITHUB_URL = "https://github.com/fabric8-launcher/launcher-booster-catalog.git";

    private static final String ENV_PRODUCTION = "production";
    private static final String ENV_STAGING = "staging";
    private static final String ENV_DEVELOPMENT = "development";
    
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            System.out.println("Usage: convert <dest> [<catalog>] [<dev_gitref>] [<staging_gitref>] [<prod_gitref>]");
            System.out.println("       convert --help");
            System.out.println();
            System.out.println("  dest           - Destination folder (will be created if necessary)");
            System.out.println("  catalog        - Either a GitHub url to a booster catalog or a file path to a directory of Git bundles.");
            System.out.println("  dev_gitref     - The gitref to use for the base booster .yaml files, the ones used in development (default 'master')");
            System.out.println("  staging_gitref - The gitref for the boosters used in staging (default = don't use)");
            System.out.println("  prod_gitref    - The gitref for the boosters used in production (default = don't use)");
            return;
        }
        
        String destFolder = args[0];
        
        String repoUrl;
        if (args.length == 1) {
            repoUrl = DEFAULT_GITHUB_URL;
        } else {
            repoUrl = args[1];
            if (repoUrl.isEmpty()) {
                repoUrl = DEFAULT_GITHUB_URL;
            }
        }
        
        String defaultRef;
        if (args.length > 2) {
            defaultRef = args[2];
        } else {
            defaultRef = "master";
        }
        
        String stagingRef;
        if (args.length > 3) {
            stagingRef = args[3];
        } else {
            stagingRef = null;
        }
        
        String productionRef;
        if (args.length > 4) {
            productionRef = args[4];
        } else {
            productionRef = null;
        }
        
        Set<Booster> developmentBoosters = fetchBoosters(repoUrl, defaultRef);
        Set<Booster> stagingBoosters = stagingRef != null ? fetchBoosters(repoUrl, stagingRef) : Collections.emptySet();
        Set<Booster> productionBoosters = productionRef != null ? fetchBoosters(repoUrl, productionRef) : Collections.emptySet();
        
        if (developmentBoosters == null || stagingBoosters == null || productionBoosters == null) {
            return;
        }
        
        Map<String, Map<String, Booster>> combined = combineBoosters(developmentBoosters, stagingBoosters, productionBoosters);
        
        setDescriptionsFromPath(developmentBoosters);
        
        DumperOptions opts = new DumperOptions();
        opts.setIndent(2);
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setWidth(1000);
        
        Yaml yaml = new Yaml(opts);
        
        for (Map<String, Booster> env : combined.values()) {
            HashMap<String, Object> data = envToMap(env);
            File yamlFile = getYamlFileName(destFolder, env.get(ENV_DEVELOPMENT));
            Files.createDirectories(yamlFile.getParentFile().toPath());
            FileWriter writer = new FileWriter(yamlFile);
            System.out.println("Writing booster yaml file: " + yamlFile);
            yaml.dump(data, writer);
        }
    }
    
    public static Set<Booster> fetchBoosters(String repoUrl, String catalogRef) throws Exception {
        boolean local = !repoUrl.contains(":");
        EmbeddedHttpGitServer server = null;
        try {
            if (local) {
                File bundleDir = new File(repoUrl);
                if (!bundleDir.isDirectory()) {
                    System.err.println("Bundles folder does not exist or isn't a directory: " + repoUrl);
                    return null;
                }
                server = bundlesFromDirectory(bundleDir)
                        .usingPort(8765)
                        .create();
                server.start();
                repoUrl = "http://localhost:8765/booster-catalog/";
                System.setProperty(LauncherConfiguration.PropertyName.LAUNCHER_GIT_HOST, "http://localhost:8765/");
                System.setProperty(LauncherConfiguration.PropertyName.LAUNCHER_BOOSTER_CATALOG_REPOSITORY, "http://localhost:8765/booster-catalog/");
                System.setProperty(LauncherConfiguration.PropertyName.LAUNCHER_BOOSTER_CATALOG_REF, catalogRef);
            }
            
            BoosterCatalogService service = new BoosterCatalogService.Builder()
                    .catalogRepository(repoUrl)
                    .catalogRef(catalogRef)
                    .build();
            CompletableFuture<Set<Booster>> result = service.index();
            Set<Booster> boosters = result.get();
            
            System.out.println("Read " + boosters.size() + " boosters.");
            
            return boosters;
        } finally {
            if (server != null) {
                server.stop();
            }
        }
    }

    private static EmbeddedHttpGitServerBuilder bundlesFromDirectory(File bundleDir) {
        EmbeddedHttpGitServerBuilder builder = new EmbeddedHttpGitServerBuilder("", "");
        Arrays.stream(ofNullable(bundleDir.listFiles()).orElse(new File[0]))
            .filter(file -> file.getName().endsWith(".bundle"))
            .forEach(file -> builder.fromFile(file.getName().substring(0, file.getName().lastIndexOf(".bundle")), file));
        return builder;
    }
    
    private static Map<String, Map<String, Booster>> combineBoosters(
            Set<Booster> developmentBoosters,
            Set<Booster> stagingBoosters,
            Set<Booster> productionBoosters) {

        Map<String, Map<String, Booster>> result = new LinkedHashMap<>();
        
        // First put in all the development boosters, each in their own map
        for (Booster b : developmentBoosters) {
            Map<String, Booster> env = new LinkedHashMap<>();
            env.put(ENV_DEVELOPMENT, b);
            result.put(b.getId(), env);
        }
        
        // Now put their staging counterparts in their with them
        for (Booster b : stagingBoosters) {
            Map<String, Booster> env = result.get(b.getId());
            if (env != null) {
                dedup(b, env.get(ENV_DEVELOPMENT));
                env.put(ENV_STAGING, b);
            } else {
                System.err.println("ERROR: Found a booster in staging that does not exist in development(master): " + b.getId());
            }
        }
        
        // And finally their production counterparts too
        for (Booster b : productionBoosters) {
            Map<String, Booster> env = result.get(b.getId());
            if (env != null) {
                dedup(b, env.get(ENV_DEVELOPMENT));
                env.put(ENV_PRODUCTION, b);
            } else {
                System.err.println("ERROR: Found a booster in production that does not exist in development(master): " + b.getId());
            }
        }
        
        return result;
    }

    private static <T> T dedup(T env, T def) {
        if (env != null && !env.equals(def)) {
            return env;
        } else {
            return null;
        }
    }
    
    private static void dedupMap(Map<String, Object> env, Map<String, Object> def) {
        for (String key : def.keySet()) {
            if (env.containsKey(key)) {
                Object envValue = env.get(key);
                Object defValue = def.get(key);
                if (envValue instanceof Map) {
                    if (defValue instanceof Map) {
                        dedupMap((Map<String, Object>)envValue, (Map<String, Object>)defValue);
                    }
                } else if (envValue.equals(defValue)) {
                    env.remove(key);
                }
            }
        }
    }
    
    private static void dedup(Booster env, Booster def) {
        env.setGithubRepo(dedup(env.getGithubRepo(), def.getGithubRepo()));
        env.setGitRef(dedup(env.getGitRef(), def.getGitRef()));
        env.setSupportedDeploymentTypes(dedup(env.getSupportedDeploymentTypes(), def.getSupportedDeploymentTypes()));
        env.setBuildProfile(dedup(env.getBuildProfile(), def.getBuildProfile()));
        env.setBoosterDescriptorPath(dedup(env.getBoosterDescriptionPath(), def.getBoosterDescriptionPath()));
        dedupMap(env.getMetadata(), def.getMetadata());
        if (env.getVersion() != null && env.getVersion().getName().equals(def.getVersion().getName())) {
            env.setVersion(null);
        }
    }

    private static File getYamlFileName(String destFolder, Booster b) {
        File f = new File(destFolder);
        if (b.getMission() != null) {
            f = new File(f, b.getMission().getId());
        }
        if (b.getRuntime() != null) {
            f = new File(f, b.getRuntime().getId());
        }
        if (b.getVersion() != null) {
            f = new File(f, b.getVersion().getId());
        }
        return new File(f, "booster.yaml");
    }

    private static HashMap<String, Object> envToMap(Map<String, Booster> env) {
        HashMap<String, Object> boosterData = boosterToMap(env.get(ENV_DEVELOPMENT));
        
        if (env.containsKey(ENV_STAGING) || env.containsKey(ENV_PRODUCTION)) {
            HashMap<String, Object> environment = new LinkedHashMap<>();
            boosterData.put("environment", environment);
            if (env.containsKey(ENV_STAGING)) {
                HashMap<String, Object> stagingData = boosterToMap(env.get(ENV_STAGING));
                environment.put(ENV_STAGING, stagingData);
            }
            if (env.containsKey(ENV_PRODUCTION)) {
                HashMap<String, Object> productionData = boosterToMap(env.get(ENV_PRODUCTION));
                environment.put(ENV_PRODUCTION, productionData);
            }
        }
        
        return boosterData;
    }

    private static HashMap<String, Object> boosterToMap(Booster b) {
        HashMap<String, Object> result = new LinkedHashMap<>();

        if (b.getGithubRepo() != null || b.getGitRef() != null) {
            HashMap<String, Object> source = new LinkedHashMap<>();
            result.put("source", source);
            HashMap<String, Object> git = new LinkedHashMap<>();
            source.put("git", git);
            if (b.getGithubRepo() != null) {
                git.put("url", "https://github.com/" + b.getGithubRepo());
            }
            if (b.getGitRef() != null) {
                git.put("ref", b.getGitRef());
            }
        }

        if (b.getMetadata().get("name") != null) {
            result.put("name", b.getMetadata().get("name"));
        }
        if (b.getMetadata().get("description") != null) {
            result.put("description", b.getMetadata().get("description"));
        }
        
        if (b.getBuildProfile() != null
                || (b.getSupportedDeploymentTypes() != null && !b.getSupportedDeploymentTypes().isEmpty())
                || b.getVersion() != null) {
            HashMap<String, Object> metadata = new LinkedHashMap<>();
            result.put("metadata", metadata);
            
            if (b.getVersion() != null) {
                HashMap<String, Object> version = new LinkedHashMap<>();
                metadata.put("version", version);
                String name = b.getVersion().getName();
                Object versions = b.getMetadata().get("versions");
                if (versions instanceof Map) {
                    Object v = ((Map<String, Object>)versions).get(b.getVersion().getId());
                    if (v instanceof Map) {
                        name = ((Map<String, Object>)v).get("name").toString();
                    }
                }
                version.put("name", name);
            }
            if (b.getBuildProfile() != null) {
                metadata.put("buildProfile", b.getBuildProfile());
            }
            if ("zip".equalsIgnoreCase(b.getSupportedDeploymentTypes())) {
                metadata.put("runsOn", "none");
            }
            
        }
        
        return result;
    }
    
    private static void setDescriptionsFromPath(Set<Booster> boosters) {
        for (Booster b : boosters) {
            if (b.getBoosterDescriptionPath() != null) {
                Path descriptionPath = b.getContentPath().resolve(b.getBoosterDescriptionPath());
                if (Files.exists(descriptionPath)) {
                    try {
                        byte[] descriptionContent = Files.readAllBytes(descriptionPath);
                        b.getMetadata().put("description", new String(descriptionContent));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
