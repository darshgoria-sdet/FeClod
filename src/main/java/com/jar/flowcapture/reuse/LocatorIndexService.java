package com.jar.flowcapture.reuse;

import com.jar.flowcapture.capture.CandidateLocator;
import com.jar.flowcapture.capture.ExistingLocatorRef;
import com.jar.flowcapture.config.FlowCaptureProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Indexes every @AndroidFindBy locator already declared in the target repo's locaters/ tree,
 * so a newly captured element can be matched against one that already exists instead of
 * being declared for the third or fourth time.
 *
 * A regex scan is genuinely sufficient here rather than a Java parser: these annotations have
 * a fixed, regular shape across all 18 locator classes, and a mis-parse costs at most a
 * missed reuse suggestion — never incorrect generated code, because QA confirms every match.
 */
@Slf4j
@Service
public class LocatorIndexService {

    /** Captures @AndroidFindBy(<attr> = "<value>") including inside @AndroidFindAll chains. */
    private static final Pattern ANNOTATION = Pattern.compile(
            "@Android(?:FindBy|By)\\s*\\(\\s*(xpath|uiAutomator|id|accessibility)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The WebElement field an annotation decorates — always the next field declared after it. */
    private static final Pattern FIELD = Pattern.compile(
            "(?:WebElement|List<WebElement>)\\s+(\\w+)\\s*;");

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private final FlowCaptureProperties properties;

    /** semantic key -> every place it is declared. */
    private volatile Map<String, List<ExistingLocatorRef>> byKey = Map.of();

    /** "ClassName#fieldName" -> that field's declaration, with every alternative locator on it. */
    private volatile Map<String, DeclaredLocator> byClassField = Map.of();
    private volatile int indexedFiles;
    private volatile int indexedLocators;

    public LocatorIndexService(FlowCaptureProperties properties) {
        this.properties = properties;
    }

    public record IndexStats(int files, int locators, int distinctKeys) {}

    /**
     * One WebElement field already declared in the repo, together with every locator on it.
     * A field annotated with @AndroidFindAll has several alternatives that are OR-ed at
     * runtime, so anything executing this field must try them all rather than only the first.
     */
    public record DeclaredLocator(ExistingLocatorRef ref, List<CandidateLocator> candidates) {}

    /**
     * Looks up a specific field by class and name, for actions that are pinned to a known
     * repo locator rather than to something QA just captured (e.g. the home-screen anchor
     * HomeScreenLocators.profileIcon).
     */
    public Optional<DeclaredLocator> findDeclared(String className, String fieldName) {
        if (className == null || fieldName == null) return Optional.empty();
        return Optional.ofNullable(byClassField.get(className + "#" + fieldName));
    }

    public IndexStats stats() {
        return new IndexStats(indexedFiles, indexedLocators, byKey.size());
    }

    /**
     * Rebuilds the index from disk. Called when a flow starts rather than once at boot,
     * because the target repo is a live working copy the QA engineer may pull or edit
     * between recordings — a stale index would suggest reusing a locator that no longer exists.
     */
    public synchronized void refresh() {
        Path root = locatorsRoot();
        if (root == null || !Files.isDirectory(root)) {
            log.warn("Locator root not found at {} — reuse suggestions disabled", root);
            byKey = Map.of();
            byClassField = Map.of();
            indexedFiles = 0;
            indexedLocators = 0;
            return;
        }

        Map<String, List<ExistingLocatorRef>> index = new HashMap<>();
        Map<String, DeclaredLocator> declared = new HashMap<>();
        int files = 0;
        int locators = 0;

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                files++;
                locators += indexFile(file, index, declared);
            }
        } catch (IOException e) {
            log.warn("Could not scan locator classes under {}: {}", root, e.getMessage());
        }

        // Record every co-declaring class on each ref, so the prompt can tell QA whether a
        // locator is genuinely shared across features or just duplicated once.
        for (var entry : index.entrySet()) {
            List<String> owners = entry.getValue().stream()
                    .map(ExistingLocatorRef::getLocatorsClassName).distinct().sorted().toList();
            entry.getValue().forEach(ref -> ref.setAlsoDeclaredIn(
                    owners.stream().filter(o -> !o.equals(ref.getLocatorsClassName())).toList()));
        }

        byKey = index;
        byClassField = declared;
        indexedFiles = files;
        indexedLocators = locators;
        log.info("Indexed {} locators across {} files ({} distinct semantic keys)", locators, files, index.size());
    }

    private int indexFile(Path file, Map<String, List<ExistingLocatorRef>> index,
                          Map<String, DeclaredLocator> declared) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            log.debug("Skipping unreadable locator file {}: {}", file, e.getMessage());
            return 0;
        }

        String className = file.getFileName().toString().replace(".java", "");
        Matcher pkg = PACKAGE.matcher(source);
        String packageName = pkg.find() ? pkg.group(1) : "";

        int count = 0;
        Matcher m = ANNOTATION.matcher(source);
        while (m.find()) {
            count++;
            CandidateLocator.Strategy strategy = strategyFor(m.group(1), m.group(2));

            Matcher fieldMatcher = FIELD.matcher(source);
            String fieldName = fieldMatcher.find(m.end()) ? fieldMatcher.group(1) : null;
            if (fieldName == null) continue;

            ExistingLocatorRef ref = new ExistingLocatorRef();
            ref.setLocatorsClassName(className);
            ref.setLocatorsPackage(packageName);
            ref.setFieldName(fieldName);
            ref.setLocatorValue(m.group(2));
            ref.setStrategy(strategy);
            ref.setAlsoDeclaredIn(List.of());

            // By class+field BEFORE the semantic-key filter: a field is still addressable by
            // name even when its locator has no stable identity to match a captured element
            // against, and the two lookups answer different questions.
            declared.computeIfAbsent(className + "#" + fieldName,
                            k -> new DeclaredLocator(ref, new ArrayList<>()))
                    .candidates().add(new CandidateLocator(strategy, m.group(2), null));

            String key = LocatorKey.of(strategy, m.group(2));
            if (key == null) continue; // no stable identity — never suggest reusing it
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
        }
        return count;
    }

    /**
     * Finds an existing declaration for a freshly captured element. Walks the ranked
     * candidates best-first, so a resource-id match is preferred over a text match even
     * when both exist.
     *
     * @return empty when nothing matches, or when the only matches are in the class this
     *         flow is generating (intra-flow duplicates are deduped silently by the
     *         generator; prompting for them would be noise).
     */
    public Optional<ExistingLocatorRef> findExisting(List<CandidateLocator> candidates, String generatingClassName) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();

        for (CandidateLocator candidate : candidates) {
            String key = LocatorKey.of(candidate);
            if (key == null) continue;

            List<ExistingLocatorRef> matches = byKey.getOrDefault(key, List.of()).stream()
                    .filter(ref -> !ref.getLocatorsClassName().equals(generatingClassName))
                    .toList();
            if (matches.isEmpty()) continue;

            ExistingLocatorRef best = matches.get(0);
            ExistingLocatorRef copy = new ExistingLocatorRef(
                    best.getLocatorsClassName(), best.getLocatorsPackage(), best.getFieldName(),
                    best.getLocatorValue(), best.getStrategy(),
                    best.getStrategy() != candidate.getStrategy(),
                    matches.stream().map(ExistingLocatorRef::getLocatorsClassName)
                            .distinct().filter(c -> !c.equals(best.getLocatorsClassName())).sorted().toList());
            return Optional.of(copy);
        }
        return Optional.empty();
    }

    /**
     * The id attribute is ambiguous in source: @AndroidFindBy(id = ...) is a resource-id,
     * but the value shape decides how it normalises, so classify from both.
     */
    private CandidateLocator.Strategy strategyFor(String attribute, String value) {
        return switch (attribute) {
            case "id" -> CandidateLocator.Strategy.RESOURCE_ID;
            case "accessibility" -> CandidateLocator.Strategy.CONTENT_DESC;
            case "uiAutomator" -> value.contains(".instance(")
                    ? CandidateLocator.Strategy.INDEX_BASED
                    : CandidateLocator.Strategy.TEXT_CONTAINS_UIAUTOMATOR;
            default -> CandidateLocator.Strategy.EXACT_TEXT_XPATH;
        };
    }

    private Path locatorsRoot() {
        String repo = properties.getTargetRepoPath();
        if (repo == null || repo.isBlank()) return null;
        return Path.of(repo, "src", "main", "Java", "locaters");
    }
}
