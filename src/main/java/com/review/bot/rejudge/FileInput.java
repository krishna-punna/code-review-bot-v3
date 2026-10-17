package com.review.bot.rejudge;

import com.review.bot.review.ChangeSet.ChangedFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One file of a change being re-judged. The key identifies exactly what the rules would see;
 * the file is only loaded (on GitHub: fetched) if its key differs from the previous review's.
 */
public record FileInput(String path, String key, Loader loader) {

    @FunctionalInterface
    public interface Loader {
        ChangedFile load() throws IOException;
    }

    /** A file whose content and changed lines are already at hand (CLI, REST API). */
    public static FileInput of(ChangedFile file) {
        String lines = file.changedLines().stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        return new FileInput(file.path(), sha256(file.path() + "\n" + lines + "\n" + file.content()), () -> file);
    }

    /**
     * Key of a GitHub PR file: its patch against the base. Together with the base commit (the
     * state's context) the patch fixes the file's content and changed lines, so the file need not
     * be fetched to know it is unchanged.
     */
    public static String patchKey(String path, String patch) {
        return sha256(path + "\n" + patch);
    }

    static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
