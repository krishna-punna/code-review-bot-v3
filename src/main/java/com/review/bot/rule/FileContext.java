package com.review.bot.rule;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a rule may look at for one changed file.
 * The AST is parsed lazily and cached, so text-only rules pay nothing for it.
 */
public final class FileContext {

    // Default level rejects newer syntax (records, pattern switch); parse anything modern.
    // The configuration is shareable, but JavaParser itself is not thread-safe,
    // so a new parser is created per file (reviews run on several threads).
    private static final ParserConfiguration PARSER_CONFIG =
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);

    private final String path;
    private final String content;
    private final Set<Integer> changedLines;
    private final List<String> lines;
    private Optional<CompilationUnit> ast;

    public FileContext(String path, String content, Set<Integer> changedLines) {
        this.path = path;
        this.content = content;
        this.changedLines = Set.copyOf(changedLines);
        this.lines = content.lines().toList();
    }

    public String path() { return path; }
    public String content() { return content; }
    public Set<Integer> changedLines() { return changedLines; }
    public boolean isJava() { return path.endsWith(".java"); }
    public boolean isTest() { return path.contains("/src/test/") || path.startsWith("src/test/"); }

    public List<String> lines() {
        return lines;
    }

    /** Text of a 1-based line number, or "" if out of range. */
    public String lineText(int line) {
        return line >= 1 && line <= lines.size() ? lines.get(line - 1) : "";
    }

    /** Parsed AST, or empty if the file is not Java or does not parse. */
    public synchronized Optional<CompilationUnit> ast() {
        if (ast == null) {
            if (!isJava()) {
                ast = Optional.empty();
            } else {
                ParseResult<CompilationUnit> result = new JavaParser(PARSER_CONFIG).parse(content);
                ast = result.isSuccessful() ? result.getResult() : Optional.empty();
            }
        }
        return ast;
    }
}
