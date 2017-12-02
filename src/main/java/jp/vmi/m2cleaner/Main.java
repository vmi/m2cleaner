package jp.vmi.m2cleaner;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Main
 */
public class Main {

    private static class TestResult {
        public boolean hasError;
        public String reason;

        public TestResult(boolean hasError, String reason) {
            this.hasError = hasError;
            this.reason = reason;
        }
    }

    private static final String HELP = "Usage: java -jar m2cleaner.jar [--test|--clean] [DIRECTORIES ...]";

    private static int errCount = 0;
    private static final Map<Path, TestResult> tested = new HashMap<>();

    private static int count = 0;
    private static boolean nl = false;

    private static void ind(char c) {
        System.err.print(c);
        if (++count % 80 == 0) {
            System.err.println();
            nl = true;
        } else {
            nl = false;
        }
    }

    private static void ok(Path path) {
        tested.put(path, new TestResult(false, null));
        ind('o');
    }

    private static void ng(Path path, String reason) {
        tested.put(path, new TestResult(true, reason));
        errCount++;
        ind('x');
    }

    private static void skip() {
        ind('.');
    }

    private static Path trimExt(Path path) {
        String s = path.toString();
        return Paths.get(s.substring(0, s.lastIndexOf('.')));
    }

    private static byte[] readSha1(Path path) throws IOException {
        long size = Files.size(path);
        if (size >= 256) {
            ng(path, String.format("Too large (%d bytes)", size));
            return null;
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != 1) {
            ng(path, String.format("Multiple lines (%d lines)", lines.size()));
            return null;
        }
        String line = lines.get(0);
        byte[] sha1 = new byte[20];
        try {
            for (int offset = 0; offset < 20; offset++) {
                sha1[offset] = (byte) Integer.parseUnsignedInt(line.substring(offset * 2, offset * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            ng(path, "Illegal SHA-1 format: " + line.trim());
            return null;
        }
        ok(path);
        return sha1;
    }

    private static byte[] calcSha1(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        byte[] buf = new byte[64 * 1024];
        try (InputStream is = new FileInputStream(path.toFile())) {
            int bytes;
            while ((bytes = is.read(buf)) >= 0) {
                digest.update(buf, 0, bytes);
            }
            return digest.digest();
        }
    }

    private static void testSha1(Path path, byte[] sha1) throws IOException {
        byte[] fileSha1 = calcSha1(path);
        if (Arrays.equals(fileSha1, sha1)) {
            ok(path);
        } else {
            ng(path, String.format("SHA-1 is not match: file=%s, sha1=%s", fileSha1, sha1));
        }
    }

    private static void testJar(Path path) {
        byte[] buf = new byte[64 * 1024];
        try (JarFile jarFile = new JarFile(path.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                if (jarEntry.isDirectory())
                    continue;
                try (InputStream is = jarFile.getInputStream(jarEntry)) {
                    while (is.read(buf) > 0) {
                        // no operation.
                    }
                }
            }
        } catch (IOException e) {
            ng(path, e.getMessage());
            return;
        }
        ok(path);
    }

    public static void testAndClean(List<Path> directories, boolean isClean) {
        for (Path directory : directories) {
            try {
                Files.walk(directory, FileVisitOption.FOLLOW_LINKS).forEach(path -> {
                    try {
                        String rPath = directory.relativize(path).toString();
                        if (rPath.startsWith(".") || Files.isDirectory(path) || tested.containsKey(path)) {
                            return;
                        } else if (rPath.endsWith(".sha1")) {
                            Path filePath = trimExt(path);
                            byte[] sha1 = readSha1(path);
                            if (Files.exists(filePath)) {
                                testSha1(filePath, sha1);
                            }
                        } else if (rPath.endsWith(".jar")) {
                            Path sha1Path = Paths.get(path.toString() + ".sha1");
                            byte[] sha1 = null;
                            if (Files.exists(sha1Path)) {
                                sha1 = readSha1(sha1Path);
                            }
                            if (sha1 != null) {
                                testSha1(path, sha1);
                            } else {
                                testJar(path);
                            }
                        } else {
                            skip(); // ignore otehr files.
                        }
                    } catch (IOException e) {
                        System.out.printf("[ERROR] %s%n", e.getMessage());
                    }
                });
            } catch (IOException e) {
                System.out.printf("[ERROR] %s%n", e.getMessage());
            }
        }
        if (!nl)
            System.err.println();
        System.out.println("Errors: " + errCount);
        tested.entrySet().stream().sorted((a, b) -> a.getKey().compareTo(b.getKey())).forEach(entry -> {
            if (entry.getValue().hasError) {
                System.out.printf("%s: %s%n", entry.getKey(), entry.getValue().reason);
            }
        });
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println(HELP);
            System.exit(1);
        }
        boolean isClean = false;
        switch (args[0]) {
        case "--test":
            // no operation.
            break;
        case "--clean":
            isClean = true;
            break;
        default:
            System.out.println("[ERROR] Illegal arguments: " + String.join(" ", args));
            System.out.println(HELP);
            System.exit(1);
            break;
        }
        List<Path> directories = new ArrayList<>();
        if (args.length == 1) {
            directories.add(Paths.get(System.getProperty("user.home"), ".m2/repository"));
        } else {
            for (int i = 1; i < args.length; i++) {
                directories.add(Paths.get(args[i]));
            }
        }
        testAndClean(directories, isClean);
    }
}
