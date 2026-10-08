import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Before/after check for the mixin fixes in BUGFIX-REPORT.md, without a Minecraft server.
 *
 * <p>Builds the mixins twice - once from a baseline commit, once from the working tree - and applies
 * each build with the real Mixin 0.8.7 transformer and MixinExtras to small stand-in classes that have
 * the same method shapes the mixins target ({@code stubs/}). Each test drives those classes and prints
 * PASS/FAIL; the table at the end compares that with what each build is expected to do.
 *
 * <p>Plain Java on purpose, so it runs the same from PowerShell, cmd or bash. Needs a JDK 11 or newer
 * (single-file source launch, and javac for {@code --release 8}), git on the PATH unless
 * {@code --baseline-dir} is given, and Maven Central for the first run (the jars are pinned by SHA-1 and
 * cached in {@code .work/deps}, so later runs work offline).
 *
 * <pre>
 *   java tools/mixin-harness/Run.java                       baseline 96cc13c vs working tree
 *   java tools/mixin-harness/Run.java --baseline &lt;rev&gt;
 *   java tools/mixin-harness/Run.java --baseline-dir &lt;dir&gt;  dir holds com/novotimo/mtmixins/...
 * </pre>
 */
public class Run {

    static final String DEFAULT_BASELINE = "96cc13c";
    static final String PKG = "src/main/java/com/novotimo/mtmixins";
    static final String MAVEN = "https://repo1.maven.org/maven2/";

    /** Maven coordinate path and its SHA-1, pinned so a rerun needs no network and a bad download is caught. */
    static final String[][] JARS = {
            {"net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar", "5f66cc9f59b8efaa942155a3d5a30599bf6640dd"},
            {"io/github/llamalad7/mixinextras-common/0.5.5/mixinextras-common-0.5.5.jar", "50d37e763fe461b5e9df961436b399e9b681ac35"},
            {"org/ow2/asm/asm/9.7/asm-9.7.jar", "073d7b3086e14beb604ced229c302feff6449723"},
            {"org/ow2/asm/asm-tree/9.7/asm-tree-9.7.jar", "e446a17b175bfb733b87c5c2560ccb4e57d69f1a"},
            {"org/ow2/asm/asm-commons/9.7/asm-commons-9.7.jar", "e86dda4696d3c185fcc95d8d311904e7ce38a53f"},
            {"org/ow2/asm/asm-util/9.7/asm-util-9.7.jar", "c0655519f24d92af2202cb681cd7c1569df6ead6"},
            {"org/ow2/asm/asm-analysis/9.7/asm-analysis-9.7.jar", "e4a258b7eb96107106c0599f0061cfc1832fe07a"},
            {"org/apache/logging/log4j/log4j-api/2.8.1/log4j-api-2.8.1.jar", "e801d13612e22cad62a3f4f3fe7fdbe6334a8e72"},
            {"com/google/guava/guava/21.0/guava-21.0.jar", "3a3d111be1be1b745edfa7d91678a12d7ed38709"},
            {"com/google/code/gson/gson/2.8.0/gson-2.8.0.jar", "c4ba5371a29ac9b2ad6129b1d39ea38750043eff"},
    };

    /** Mod sources the tested mixins need, relative to PKG. A file missing from a build is skipped. */
    static final String[] SOURCES = {
            "MedievalTimesMixins.java", "INamedLootTable.java",
            "util/ChunkBiomeCache.java", "util/EntityCollisions.java",
            "mixin/oreveins/MixinWorldGenVeins.java",
            "mixin/vanilla/MixinEntityCollisionCount.java", "mixin/vanilla/MixinEntityLivingBaseCollisions.java",
            "mixin/vanilla/AccessorWorldUnloadQueue.java", "mixin/vanilla/MixinWorldServerEntityDupe.java",
            "mixin/lycanites/MixinBlockSpawnLocation.java", "mixin/lycanites/MixinMaterialSpawnLocation.java",
            "mixin/vanilla/MixinLootTableAttribution.java", "mixin/vanilla/MixinLootTableManagerName.java",
            "mixin/vanilla/MixinLootTableShuffle.java",
    };

    /** config file -> mixins it lists (only those present in the build are written). */
    static final Map<String, String[]> CONFIGS = new LinkedHashMap<>();
    /** test class -> config it runs under. */
    static final Map<String, String> TESTS = new LinkedHashMap<>();
    /** Checks that show the bug: these must FAIL on the baseline. Everything else must PASS on both. */
    static final List<String> BUG_CHECKS = Arrays.asList(
            "[B18] minecart", "[B18] pen",
            "[B15] unload race + 2 copies in slice",
            "[B2b] cross-world",
            "[B13] BlockSpawnLocation sweep", "[B13] MaterialSpawnLocation sweep",
            "[lootattrib] shared name bounded",
            "[B20] occupancy line matches ground truth");

    static {
        CONFIGS.put("collisions.json", new String[] {"vanilla.MixinEntityCollisionCount", "vanilla.MixinEntityLivingBaseCollisions"});
        CONFIGS.put("dupe.json", new String[] {"vanilla.AccessorWorldUnloadQueue", "vanilla.MixinWorldServerEntityDupe"});
        CONFIGS.put("oreveins.json", new String[] {"oreveins.MixinWorldGenVeins"});
        CONFIGS.put("lycanites.json", new String[] {"lycanites.MixinBlockSpawnLocation", "lycanites.MixinMaterialSpawnLocation"});
        CONFIGS.put("lootname.json", new String[] {"vanilla.MixinLootTableAttribution", "vanilla.MixinLootTableManagerName"});
        CONFIGS.put("lootstats.json", new String[] {"vanilla.MixinLootTableShuffle"});
        TESTS.put("tests.CollisionTest", "collisions.json");
        TESTS.put("tests.DupeTest", "dupe.json");
        TESTS.put("tests.OreVeinsTest", "oreveins.json");
        TESTS.put("tests.LycanitesTest", "lycanites.json");
        TESTS.put("tests.LootNameTest", "lootname.json");
        TESTS.put("tests.LootStatsTest", "lootstats.json");
    }

    static Path harness, repo, work, deps;
    static String depsClasspath;

    public static void main(String[] args) throws Exception {
        String baselineRev = DEFAULT_BASELINE;
        Path baselineDir = null;
        for (int i = 0; i < args.length; i++) {
            if ("--baseline".equals(args[i]) && i + 1 < args.length) {
                baselineRev = args[++i];
            } else if ("--baseline-dir".equals(args[i]) && i + 1 < args.length) {
                baselineDir = Paths.get(args[++i]).toAbsolutePath().normalize();
            } else if ("--harness".equals(args[i]) && i + 1 < args.length) {
                harness = Paths.get(args[++i]).toAbsolutePath().normalize();
            } else {
                die("Unknown argument " + args[i] + ". Usage: java Run.java [--baseline <rev> | --baseline-dir <dir>]");
            }
        }
        if (harness == null) {
            harness = findHarness();
        }
        repo = harness.getParent().getParent();
        if (!Files.isDirectory(repo.resolve(PKG))) {
            die("Could not find " + PKG + " under " + repo);
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            die("No Java compiler available. Run this with a JDK (11 or newer), not a JRE.");
        }

        work = harness.resolve(".work");
        deps = work.resolve("deps");
        Files.createDirectories(deps);
        List<String> depPaths = new ArrayList<>();
        for (String[] jar : JARS) {
            depPaths.add(fetch(jar[0], jar[1]).toString());
        }
        depsClasspath = String.join(File.pathSeparator, depPaths);

        Path build = work.resolve("build");
        deleteTree(build);
        Path harnessOut = build.resolve("harness");
        Path stubsOut = build.resolve("stubs");
        compile(javac, javaFiles(harness.resolve("src").resolve("harness")), depsClasspath, harnessOut);
        copyTree(harness.resolve("src").resolve("META-INF"), harnessOut.resolve("META-INF"));
        compile(javac, javaFiles(harness.resolve("stubs")), depsClasspath, stubsOut);

        Path oldSrc;
        String oldLabel;
        if (baselineDir != null) {
            oldSrc = locatePackageRoot(baselineDir);
            oldLabel = baselineDir.toString();
        } else {
            oldSrc = extractBaseline(baselineRev, build.resolve("src-old"));
            oldLabel = baselineRev;
        }
        buildVariant(javac, "old", oldSrc, build, stubsOut);
        buildVariant(javac, "new", repo.resolve(PKG), build, stubsOut);
        Path testsOut = build.resolve("tests");
        compile(javac, javaFiles(harness.resolve("tests")),
                cp(depsClasspath, stubsOut.toString(), build.resolve("new").toString()), testsOut);

        Map<String, String> oldResults = new LinkedHashMap<>();
        Map<String, String> newResults = new LinkedHashMap<>();
        for (Map.Entry<String, String> test : TESTS.entrySet()) {
            String name = test.getKey().substring("tests.".length());
            System.out.println("=== " + name + " - before (" + oldLabel + ")");
            runTest("old", test.getValue(), test.getKey(), build, oldResults);
            System.out.println("=== " + name + " - after (working tree)");
            runTest("new", test.getValue(), test.getKey(), build, newResults);
            System.out.println();
        }

        System.out.println("=== Summary (bug checks must FAIL before and PASS after; regression checks must PASS on both)");
        List<String> names = new ArrayList<>(newResults.keySet());
        for (String n : oldResults.keySet()) {
            if (!names.contains(n)) {
                names.add(n);
            }
        }
        int width = names.stream().mapToInt(String::length).max().orElse(10);
        boolean allOk = true;
        for (String n : names) {
            boolean bug = BUG_CHECKS.contains(n);
            String before = oldResults.getOrDefault(n, "MISSING");
            String after = newResults.getOrDefault(n, "MISSING");
            boolean ok = (bug ? "FAIL".equals(before) : "PASS".equals(before)) && "PASS".equals(after);
            allOk &= ok;
            System.out.println(String.format(Locale.ROOT, "  %-" + width + "s  %-10s  before %-7s after %-7s  %s",
                    n, bug ? "bug" : "regression", before, after, ok ? "OK" : "UNEXPECTED"));
        }
        for (String n : BUG_CHECKS) {
            if (!names.contains(n)) {
                allOk = false;
                System.out.println("  " + n + "  never reported  UNEXPECTED");
            }
        }
        System.out.println(allOk
                ? "RESULT: every bug reproduces on the baseline, is fixed on the working tree, and nothing regressed."
                : "RESULT: at least one check did not behave as expected - see UNEXPECTED above.");
        System.exit(allOk ? 0 : 1);
    }

    // ----------------------------------------------------------------------------------------------

    static void buildVariant(JavaCompiler javac, String variant, Path pkgRoot, Path build, Path stubsOut) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String s : SOURCES) {
            Path p = pkgRoot.resolve(s);
            if (Files.isRegularFile(p)) {
                files.add(p);
            }
        }
        Path out = build.resolve(variant);
        compile(javac, files, cp(depsClasspath, stubsOut.toString()), out);
        Path res = build.resolve(variant + "-res");
        Files.createDirectories(res);
        for (Map.Entry<String, String[]> config : CONFIGS.entrySet()) {
            List<String> present = new ArrayList<>();
            for (String mixin : config.getValue()) {
                if (Files.isRegularFile(out.resolve("com/novotimo/mtmixins/mixin/" + mixin.replace('.', '/') + ".class"))) {
                    present.add("\"" + mixin + "\"");
                }
            }
            String json = "{\"required\":true,\"minVersion\":\"0.8\",\"package\":\"com.novotimo.mtmixins.mixin\","
                    + "\"target\":\"@env(DEFAULT)\",\"compatibilityLevel\":\"JAVA_8\","
                    + "\"injectors\":{\"defaultRequire\":1},\"mixins\":[" + String.join(",", present) + "]}\n";
            Files.write(res.resolve(config.getKey()), json.getBytes(StandardCharsets.UTF_8));
        }
    }

    static final Pattern CHECK = Pattern.compile("^(\\[[^\\]]+\\][^:]*): (PASS|FAIL)\\b");
    static final Pattern TRUTH = Pattern.compile("slots = (\\d+)%");
    /** The mixin formats with the default locale; the child JVM is pinned to en-US, but accept a comma anyway. */
    static final Pattern LOGGED = Pattern.compile("over 100 fills: [0-9.,]+ slots, (\\d+)% of capacity");

    static void runTest(String variant, String config, String testClass, Path build, Map<String, String> results)
            throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory(work, "run");
        Path log = dir.resolve("log.txt");
        String java = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>(Arrays.asList(java,
                "-Duser.language=en", "-Duser.country=US",
                "-Dorg.apache.logging.log4j.simplelog.level=INFO",
                "-Dorg.apache.logging.log4j.simplelog.logFile=" + log,
                "-cp", cp(build.resolve("harness").toString(), depsClasspath),
                "harness.Main", config, testClass,
                build.resolve(variant + "-res").toString(), build.resolve(variant).toString(),
                build.resolve("stubs").toString(), build.resolve("tests").toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String truth = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Picked up JAVA_TOOL_OPTIONS") || line.contains("StatusLogger")) {
                    continue;
                }
                System.out.println(line);
                Matcher m = CHECK.matcher(line);
                if (m.find()) {
                    results.put(m.group(1).trim(), m.group(2));
                }
                Matcher t = TRUTH.matcher(line);
                if (line.startsWith("[B20]") && t.find()) {
                    truth = t.group(1);
                }
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            System.out.println("    (test JVM exited with " + exit + ")");
        }
        if (Files.isRegularFile(log)) {
            String logged = null;
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                int at = line.indexOf("Loot fill");
                if (at >= 0) {
                    System.out.println("    log: " + line.substring(at));
                    Matcher m = LOGGED.matcher(line);
                    if (m.find()) {
                        logged = m.group(1);
                    }
                }
            }
            if (truth != null) {
                boolean match = logged != null && Math.abs(Integer.parseInt(logged) - Integer.parseInt(truth)) <= 1;
                String verdict = match ? "PASS" : "FAIL";
                System.out.println("[B20] occupancy line matches ground truth: " + verdict
                        + " - logged " + (logged == null ? "nothing" : logged + "%") + ", ground truth " + truth + "%");
                results.put("[B20] occupancy line matches ground truth", verdict);
            }
        }
        deleteTree(dir);
    }

    // ----------------------------------------------------------------------------------------------

    static Path extractBaseline(String rev, Path dest) throws IOException, InterruptedException {
        deleteTree(dest);
        Files.createDirectories(dest);
        Path zip = dest.resolve("baseline.zip");
        ProcessBuilder pb = new ProcessBuilder("git", "-C", repo.toString(), "archive", "--format=zip",
                "--output=" + zip, rev, "--", PKG);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            die("Could not run git (" + e.getMessage() + "). Install git, or point the harness at a copy of the "
                    + "baseline's " + PKG + ": run.ps1 -BaselineDir <dir>, or Run.java --baseline-dir <dir>.");
            return null;
        }
        String out = readAll(p.getInputStream());
        if (p.waitFor() != 0 || !Files.isRegularFile(zip)) {
            die("git archive " + rev + " failed: " + out.trim());
        }
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path target = dest.resolve(e.getName()).normalize();
                if (!target.startsWith(dest)) {
                    die("Refusing zip entry outside the destination: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return dest.resolve(PKG);
    }

    /** Accepts a repo root, a src/main/java dir, or the com/novotimo/mtmixins dir itself. */
    static Path locatePackageRoot(Path dir) {
        Path[] candidates = {dir.resolve(PKG), dir.resolve("com/novotimo/mtmixins"), dir};
        for (Path c : candidates) {
            if (Files.isRegularFile(c.resolve("MedievalTimesMixins.java"))) {
                return c;
            }
        }
        die("No MedievalTimesMixins.java found under " + dir);
        return null;
    }

    static Path findHarness() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("Run.java")) && Files.isDirectory(p.resolve("stubs"))) {
                return p;
            }
            Path h = p.resolve("tools").resolve("mixin-harness");
            if (Files.isRegularFile(h.resolve("Run.java"))) {
                return h;
            }
        }
        die("Run this from inside the repository (or pass --harness <path to tools/mixin-harness>).");
        return null;
    }

    /** Returns the cached jar when its hash matches; only a missing or damaged jar touches the network. */
    static Path fetch(String coordinate, String expected) throws Exception {
        Path target = deps.resolve(coordinate.substring(coordinate.lastIndexOf('/') + 1));
        if (Files.isRegularFile(target) && sha1(Files.readAllBytes(target)).equalsIgnoreCase(expected)) {
            return target;
        }
        byte[] jar;
        try {
            jar = download(MAVEN + coordinate);
        } catch (IOException e) {
            die("Could not download " + MAVEN + coordinate + " (" + e + "). The first run needs internet access "
                    + "to Maven Central; later runs use the copies in " + deps + ".");
            return null;
        }
        String actual = sha1(jar);
        if (!actual.equalsIgnoreCase(expected)) {
            die("Checksum mismatch for " + coordinate + ": expected " + expected + ", got " + actual);
        }
        Files.write(target, jar);
        return target;
    }

    /** Maven Central rate-limits bursts with HTTP 429, so back off and retry rather than fail. */
    static byte[] download(String url) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            if (attempt > 0) {
                Thread.sleep(2000L << (attempt - 1));
            }
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "mtmixins-harness");
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                last = e;
                continue;
            }
            if (code == 200) {
                try (InputStream in = c.getInputStream()) {
                    return readAllBytes(in);
                }
            }
            last = new IOException("HTTP " + code + " for " + url);
            if (code != 429 && code < 500) {
                break;
            }
        }
        throw last;
    }

    static void compile(JavaCompiler javac, List<Path> files, String classpath, Path out) throws IOException {
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(Arrays.asList("--release", "8", "-proc:none", "-g", "-nowarn",
                "-Xlint:-options", "-encoding", "UTF-8", "-d", out.toString(), "-cp", classpath));
        for (Path f : files) {
            args.add(f.toString());
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(null, null, err, args.toArray(new String[0]));
        String msg = new String(err.toByteArray(), StandardCharsets.UTF_8);
        String filtered = Arrays.stream(msg.split("\\R"))
                .filter(l -> !l.startsWith("Note:") && !l.startsWith("Picked up JAVA_TOOL_OPTIONS") && !l.trim().isEmpty())
                .collect(Collectors.joining(System.lineSeparator()));
        if (rc != 0) {
            die("Compilation failed:" + System.lineSeparator() + filtered);
        }
    }

    static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    static String cp(String... parts) {
        return String.join(File.pathSeparator, parts);
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static String sha1(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(data)) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static String readAll(InputStream in) throws IOException {
        return new String(readAllBytes(in), StandardCharsets.UTF_8);
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
    }

    static void die(String message) {
        System.err.println("ERROR: " + message);
        System.exit(2);
    }
}
