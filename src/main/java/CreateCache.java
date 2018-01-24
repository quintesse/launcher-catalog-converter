import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

import io.openshift.booster.catalog.Booster;

public class CreateCache {

    public static void main(String[] args) throws Exception {
        Set<Booster> boosters = Main.fetchBoosters(Main.DEFAULT_GITHUB_URL, "master");
        
        Files.createDirectories(Main.CACHE_DIR.toPath());
        
        for (Booster b : boosters) {
            File dest = new File(Main.CACHE_DIR, b.getGithubRepo());
            createBundle(b.getContentPath().toFile(), dest);
        }
    }
    
    private static void createBundle(File source, File dest) throws InterruptedException, IOException {
        ProcessBuilder builder = new ProcessBuilder()
                .directory(source)
                .command("git", "bundle", "create", "--all", dest.toString())
                .inheritIO();
        int exitCode = builder.start().waitFor();
        assert exitCode == 0 : "Process returned exit code: " + exitCode;
    }
}
