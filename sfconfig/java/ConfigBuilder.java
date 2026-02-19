import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class ConfigBuilder {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private static final String ERROR_VALIDATION = "VALIDATION_ERROR";
    private static final String ERROR_IO = "IO_ERROR";
    private static final String ERROR_PARSE = "PARSE_ERROR";
    private static final String ERROR_UNKNOWN = "UNKNOWN";

    private static final Set<String> ALLOWLIST = new LinkedHashSet<>(Arrays.asList(
            "mCrawlConfig.mMaxUrls",
            "mCrawlConfig.mMaxDepth",
            "mCrawlConfig.mMaxThreads",
            "mCrawlConfig.mRobotsTxtMode",
            "mCrawlConfig.mRespectCanonical",
            "mCrawlConfig.mRenderingMode",
            "mCrawlConfig.mCrawlDelay",
            "mCrawlConfig.mCrawlCSS",
            "mCrawlConfig.mStoreCSS",
            "mCrawlConfig.mCrawlJavaScript",
            "mCrawlConfig.mStoreJavaScript",
            "mCrawlConfig.mStoreOriginalHtml",
            "mCrawlConfig.mStoreRenderedHtml",
            "mCrawlConfig.mExtractHttpHeader",
            "mCrawlConfig.mExtractCookies",
            "mCrawlConfig.mInspectAccessibility",
            "mUserAgentConfig.mUserAgent",
            "mUserAgentConfig.mPreset",
            "mContentConfig.mMinContentLength",
            "mLanguageToolConfig.mSpellCheckEnabled",
            "mLanguageToolConfig.mGrammarCheckEnabled",
            "mDuplicateConfig.mNearDuplicateChecking",
            "mDuplicateConfig.mNearDuplicateThreshold",
            "mSpiderStructuredDataConfig.mExtractJsonLd",
            "mSpiderStructuredDataConfig.mExtractMicrodata",
            "mSpiderStructuredDataConfig.mExtractRdfa",
            "mSpiderStructuredDataConfig.mGoogleValidation",
            "mSpiderStructuredDataConfig.mSchemaDotOrgValidation",
            "mSpiderStructuredDataConfig.mCaseSensitiveValidation",
            "mPerformanceConfig.mLimitPerformance",
            "mPerformanceConfig.mUrlRequestsPerSecond"
    ));

    private static final Set<String> LIST_ALLOWLIST = new LinkedHashSet<>(Arrays.asList(
            "mExcludeManager.mExcludePatterns",
            "mExcludeManager.mExcludeUrls",
            "mCrawlConfig.mIncludePatterns",
            "mCrawlConfig.mAllowedDomains"
    ));

    private static final Set<String> SENSITIVE_KEYS = new HashSet<>(Arrays.asList(
            "password",
            "token",
            "secret",
            "apikey",
            "api_key",
            "auth"
    ));

    private static final String VIRTUAL_EXCLUDE_PATTERNS = "mExcludeManager.mExcludePatterns";
    private static final String VIRTUAL_EXCLUDE_URLS = "mExcludeManager.mExcludeUrls";
    private static final String VIRTUAL_EXTRACTIONS = "mCustomExtractionConfig.extractions";
    private static final String VIRTUAL_CUSTOM_SEARCHES = "mCustomSearchConfig.searches";
    private static final String VIRTUAL_CUSTOM_JAVASCRIPT = "mCustomJavaScriptConfig.javascript";

    private static final int MAX_DEPTH = 10;

    public static void main(String[] args) {
        try {
            Args parsed = parseArgs(args);
            if (parsed.command == null) {
                throw new CliException(ERROR_VALIDATION, 1, "No command provided", null);
            }

            if ("inspect".equals(parsed.command)) {
                handleInspect(parsed);
            } else if ("build".equals(parsed.command)) {
                handleBuild(parsed);
            } else if ("diff".equals(parsed.command)) {
                handleDiff(parsed);
            } else if ("test-extraction".equals(parsed.command)) {
                handleTestExtraction(parsed);
            } else {
                throw new CliException(ERROR_VALIDATION, 1, "Unknown command: " + parsed.command, null);
            }
        } catch (CliException ex) {
            writeError(ex);
            System.exit(ex.exitCode);
        } catch (Exception ex) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("exception", ex.getClass().getName());
            details.put("message", ex.getMessage());
            writeError(new CliException(ERROR_UNKNOWN, 99, "Unexpected error", details));
            System.exit(99);
        }
    }

    private static void handleInspect(Args args) throws Exception {
        Path configPath = validatePath(requireArg(args, "config"), true);
        String prefix = args.options.get("prefix");

        Object root = readConfig(configPath);
        String configVersion = readConfigVersion(root);
        String sfVersion = readSfVersion();

        Map<String, FieldInfo> fieldMap = new LinkedHashMap<>();
        collectFields(root, "", fieldMap, new HashSet<>(), 0);
        addVirtualFields(root, fieldMap);

        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map.Entry<String, FieldInfo> entry : fieldMap.entrySet()) {
            String path = entry.getKey();
            if (prefix != null && !path.startsWith(prefix)) {
                continue;
            }

            FieldInfo info = entry.getValue();
            Map<String, Object> fieldJson = new LinkedHashMap<>();
            fieldJson.put("path", path);
            fieldJson.put("type", info.type);
            fieldJson.put("value", maskValueIfNeeded(path, info.value));
            if (info.enumOptions != null) {
                fieldJson.put("enumOptions", info.enumOptions);
            }
            fieldJson.put("editable", isEditable(path));
            if (info.length != null) {
                fieldJson.put("length", info.length);
            }
            fields.add(fieldJson);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configVersion", configVersion);
        result.put("sfVersion", sfVersion);
        String warning = buildVersionWarning(configVersion, sfVersion);
        if (warning != null) {
            result.put("warning", warning);
        }
        result.put("fields", fields);
        writeJson(result);
    }

    private static void handleBuild(Args args) throws Exception {
        Path templatePath = validatePath(requireArg(args, "template"), true);
        Path outputPath = validatePath(requireArg(args, "output"), false);
        String patchJson = resolvePatchesArg(args);
        boolean dryRun = args.flags.contains("dry-run");

        Object root = readConfig(templatePath);
        String configVersion = readConfigVersion(root);
        String sfVersion = readSfVersion();

        JsonObject patches;
        try {
            patches = JsonParser.parseString(patchJson).getAsJsonObject();
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid JSON for --patches", null);
        }

        List<Map<String, Object>> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (patches.has("extractions")) {
            JsonElement extractionEl = patches.get("extractions");
            if (!extractionEl.isJsonArray()) {
                throw new CliException(ERROR_VALIDATION, 1, "extractions must be an array", null);
            }
            List<Map<String, Object>> before = extractRules(root);
            applyExtractionPatches(root, extractionEl.getAsJsonArray(), warnings);
            List<Map<String, Object>> after = extractRules(root);
            if (!Objects.equals(before, after)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("path", VIRTUAL_EXTRACTIONS);
                change.put("before", before);
                change.put("after", after);
                changes.add(change);
            }
        }

        if (patches.has("custom_searches")) {
            JsonElement searchEl = patches.get("custom_searches");
            if (!searchEl.isJsonArray()) {
                throw new CliException(ERROR_VALIDATION, 1, "custom_searches must be an array", null);
            }
            List<Map<String, Object>> before = extractCustomSearches(root);
            applyCustomSearchPatches(root, searchEl.getAsJsonArray(), warnings);
            List<Map<String, Object>> after = extractCustomSearches(root);
            if (!Objects.equals(before, after)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("path", VIRTUAL_CUSTOM_SEARCHES);
                change.put("before", before);
                change.put("after", after);
                changes.add(change);
            }
        }

        if (patches.has("custom_javascript")) {
            JsonElement jsEl = patches.get("custom_javascript");
            if (!jsEl.isJsonArray()) {
                throw new CliException(ERROR_VALIDATION, 1, "custom_javascript must be an array", null);
            }
            List<Map<String, Object>> before = extractCustomJavaScript(root);
            applyCustomJavaScriptPatches(root, jsEl.getAsJsonArray(), warnings);
            List<Map<String, Object>> after = extractCustomJavaScript(root);
            if (!Objects.equals(before, after)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("path", VIRTUAL_CUSTOM_JAVASCRIPT);
                change.put("before", before);
                change.put("after", after);
                changes.add(change);
            }
        }

        for (Map.Entry<String, JsonElement> entry : patches.entrySet()) {
            String path = entry.getKey();
            if ("extractions".equals(path) || "custom_searches".equals(path) || "custom_javascript".equals(path)) {
                continue;
            }

            if (!isEditable(path)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("allowedFields", allowedFieldsList());
                throw new CliException(ERROR_VALIDATION, 1, "Field not allowed: " + path, details);
            }

            if ("mCrawlConfig.mMaxUrls".equals(path)) {
                Integer before = getMaxUrls(root);
                if (before != null) {
                    int after = (Integer) coerceValue(Integer.class, entry.getValue(), path);
                    setMaxUrls(root, after);
                    if (!Objects.equals(before, after)) {
                        Map<String, Object> change = new LinkedHashMap<>();
                        change.put("path", path);
                        change.put("before", before);
                        change.put("after", after);
                        changes.add(change);
                    }
                    continue;
                }
            }

            if ("mCrawlConfig.mMaxDepth".equals(path)) {
                Integer before = getMaxDepth(root);
                if (before != null) {
                    int after = (Integer) coerceValue(Integer.class, entry.getValue(), path);
                    setMaxDepth(root, after);
                    if (!Objects.equals(before, after)) {
                        Map<String, Object> change = new LinkedHashMap<>();
                        change.put("path", path);
                        change.put("before", before);
                        change.put("after", after);
                        changes.add(change);
                    }
                    continue;
                }
            }

            if ("mCrawlConfig.mRenderingMode".equals(path)) {
                Object crawlConfig = getNestedField(root, "mCrawlConfig");
                if (crawlConfig != null
                        && !fieldExists(crawlConfig, "mRenderingMode")
                        && fieldExists(crawlConfig, "mCrawlerMode")) {
                    String before = getRenderingMode(root);
                    String input = (String) coerceValue(String.class, entry.getValue(), path);
                    setRenderingMode(root, input);
                    String after = getRenderingMode(root);
                    if (!Objects.equals(before, after)) {
                        Map<String, Object> change = new LinkedHashMap<>();
                        change.put("path", path);
                        change.put("before", before);
                        change.put("after", after);
                        changes.add(change);
                    }
                    continue;
                }
            }

            if (isVirtualExcludeField(path)) {
                List<String> before = getExcludeList(root);
                List<String> after = applyListPatch(before, entry.getValue(), warnings);
                setExcludeList(root, after);

                if (!Objects.equals(before, after)) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("path", path);
                    change.put("before", before);
                    change.put("after", after);
                    changes.add(change);
                }
                continue;
            }

            Object target = resolvePath(root, path);
            Field targetField = findField(target.getClass(), leafName(path));
            targetField.setAccessible(true);
            Object before = targetField.get(target);

            if (LIST_ALLOWLIST.contains(path)) {
                List<String> beforeList = toStringList(before);
                List<String> afterList = applyListPatch(beforeList, entry.getValue(), warnings);
                List<String> safeList = afterList == null ? new ArrayList<>() : afterList;
                targetField.set(target, safeList);
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("path", path);
                change.put("before", beforeList);
                change.put("after", afterList);
                changes.add(change);
            } else {
                Object coerced = coerceValue(targetField.getType(), entry.getValue(), path);
                targetField.set(target, coerced);
                Object after = targetField.get(target);
                if (!Objects.equals(before, after)) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("path", path);
                    change.put("before", toJsonValue(before, path));
                    change.put("after", toJsonValue(after, path));
                    changes.add(change);
                }
            }
        }

        if (!dryRun) {
            writeConfig(outputPath, root);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("outputPath", dryRun ? null : outputPath.toString());
        result.put("configVersion", configVersion);
        result.put("sfVersion", sfVersion);
        String warning = buildVersionWarning(configVersion, sfVersion);
        if (warning != null) {
            warnings.add(warning);
        }
        result.put("changes", changes);
        result.put("warnings", warnings);
        writeJson(result);
    }

    private static void handleDiff(Args args) throws Exception {
        Path configA = validatePath(requireArg(args, "config-a"), true);
        Path configB = validatePath(requireArg(args, "config-b"), true);
        String prefix = args.options.get("prefix");

        Object rootA = readConfig(configA);
        Object rootB = readConfig(configB);

        String versionA = readConfigVersion(rootA);
        String versionB = readConfigVersion(rootB);
        String sfVersion = readSfVersion();

        Map<String, FieldInfo> fieldsA = new LinkedHashMap<>();
        Map<String, FieldInfo> fieldsB = new LinkedHashMap<>();
        collectFields(rootA, "", fieldsA, new HashSet<>(), 0);
        collectFields(rootB, "", fieldsB, new HashSet<>(), 0);
        addVirtualFields(rootA, fieldsA);
        addVirtualFields(rootB, fieldsB);

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(fieldsA.keySet());
        allKeys.addAll(fieldsB.keySet());

        List<Map<String, Object>> diffs = new ArrayList<>();

        for (String path : allKeys) {
            if (prefix != null && !path.startsWith(prefix)) {
                continue;
            }

            FieldInfo a = fieldsA.get(path);
            FieldInfo b = fieldsB.get(path);
            Object valA = a == null ? null : a.value;
            Object valB = b == null ? null : b.value;

            if (a != null && b != null && a.isList && b.isList) {
                List<String> listA = toStringList(valA);
                List<String> listB = toStringList(valB);
                if (!Objects.equals(listA, listB)) {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("path", path);
                    diff.put("type", "list");
                    diff.put("added", listDiffAdded(listA, listB));
                    diff.put("removed", listDiffRemoved(listA, listB));
                    diff.put("unchanged", listDiffUnchanged(listA, listB));
                    diffs.add(diff);
                }
            } else {
                if (!Objects.equals(valA, valB)) {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("path", path);
                    diff.put("valueA", toJsonValue(valA, path));
                    diff.put("valueB", toJsonValue(valB, path));
                    diffs.add(diff);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configVersionA", versionA);
        result.put("configVersionB", versionB);
        result.put("sfVersion", sfVersion);
        String warning = buildVersionWarning(versionA, sfVersion);
        if (warning != null) {
            result.put("warning", warning);
        }
        result.put("differences", diffs);
        result.put("totalDifferences", diffs.size());
        writeJson(result);
    }

    private static void handleTestExtraction(Args args) throws Exception {
        String url = requireArg(args, "url");
        String selector = requireArg(args, "selector");
        String selectorType = requireArg(args, "selector-type");
        String extractMode = requireArg(args, "extract-mode");
        boolean renderJs = args.flags.contains("render-js");

        List<String> warnings = new ArrayList<>();
        if (renderJs) {
            warnings.add("render-js not supported; using static HTML");
        }

        String html = fetchHtml(url);
        List<String> matches = extractMatches(html, url, selector, selectorType, extractMode);

        List<Map<String, Object>> matchItems = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            item.put("value", matches.get(i));
            matchItems.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("url", url);
        result.put("selector", selector);
        result.put("matches", matchItems);
        result.put("matchCount", matches.size());
        result.put("warnings", warnings);
        writeJson(result);
    }

    private static Args parseArgs(String[] args) throws CliException {
        Args parsed = new Args();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--inspect".equals(arg)) {
                parsed.command = "inspect";
            } else if ("--build".equals(arg)) {
                parsed.command = "build";
            } else if ("--diff".equals(arg)) {
                parsed.command = "diff";
            } else if ("--test-extraction".equals(arg)) {
                parsed.command = "test-extraction";
            } else if ("--config".equals(arg) && i + 1 < args.length) {
                parsed.options.put("config", args[++i]);
            } else if ("--template".equals(arg) && i + 1 < args.length) {
                parsed.options.put("template", args[++i]);
            } else if ("--output".equals(arg) && i + 1 < args.length) {
                parsed.options.put("output", args[++i]);
            } else if ("--patches".equals(arg) && i + 1 < args.length) {
                parsed.options.put("patches", args[++i]);
            } else if ("--patches-file".equals(arg) && i + 1 < args.length) {
                parsed.options.put("patches-file", args[++i]);
            } else if ("--prefix".equals(arg) && i + 1 < args.length) {
                parsed.options.put("prefix", args[++i]);
            } else if ("--config-a".equals(arg) && i + 1 < args.length) {
                parsed.options.put("config-a", args[++i]);
            } else if ("--config-b".equals(arg) && i + 1 < args.length) {
                parsed.options.put("config-b", args[++i]);
            } else if ("--dry-run".equals(arg)) {
                parsed.flags.add("dry-run");
            } else if ("--url".equals(arg) && i + 1 < args.length) {
                parsed.options.put("url", args[++i]);
            } else if ("--selector".equals(arg) && i + 1 < args.length) {
                parsed.options.put("selector", args[++i]);
            } else if ("--selector-type".equals(arg) && i + 1 < args.length) {
                parsed.options.put("selector-type", args[++i]);
            } else if ("--extract-mode".equals(arg) && i + 1 < args.length) {
                parsed.options.put("extract-mode", args[++i]);
            } else if ("--render-js".equals(arg)) {
                parsed.flags.add("render-js");
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                parsed.command = "help";
            } else {
                throw new CliException(ERROR_VALIDATION, 1, "Unknown argument: " + arg, null);
            }
        }

        if ("help".equals(parsed.command)) {
            throw new CliException(ERROR_VALIDATION, 1, "Help not implemented", null);
        }

        return parsed;
    }

    private static String requireArg(Args args, String key) throws CliException {
        String value = args.options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new CliException(ERROR_VALIDATION, 1, "Missing required argument: --" + key, null);
        }
        return value;
    }

    private static String resolvePatchesArg(Args args) throws CliException {
        String patchFile = args.options.get("patches-file");
        if (patchFile != null && !patchFile.trim().isEmpty()) {
            try {
                return Files.readString(Paths.get(patchFile), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new CliException(ERROR_IO, 2, "Unable to read patches file", null);
            }
        }

        String patchJson = args.options.get("patches");
        if (patchJson == null || patchJson.trim().isEmpty()) {
            throw new CliException(ERROR_VALIDATION, 1, "Missing required argument: --patches or --patches-file", null);
        }
        return patchJson;
    }

    private static Path validatePath(String raw, boolean mustExist) throws CliException {
        if (raw == null || raw.trim().isEmpty()) {
            throw new CliException(ERROR_VALIDATION, 1, "Empty path", null);
        }
        if (raw.matches(".*(^|[\\\\/])\\.\\.([\\\\/]|$).*")) {
            throw new CliException(ERROR_VALIDATION, 1, "Path traversal is not allowed: " + raw, null);
        }

        Path path = Paths.get(raw).toAbsolutePath().normalize();
        if (mustExist && !Files.exists(path)) {
            throw new CliException(ERROR_IO, 2, "File not found: " + path, null);
        }
        return path;
    }

    private static Object readConfig(Path path) throws CliException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(path.toFile()))) {
            return ois.readObject();
        } catch (IOException ex) {
            throw new CliException(ERROR_IO, 2, "Unable to read config: " + path, null);
        } catch (ClassNotFoundException ex) {
            throw new CliException(ERROR_PARSE, 3, "Unknown class in config: " + ex.getMessage(), null);
        } catch (Exception ex) {
            throw new CliException(ERROR_PARSE, 3, "Invalid config file: " + path, null);
        }
    }

    private static void writeConfig(Path path, Object root) throws CliException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path.toFile()))) {
            oos.writeObject(root);
        } catch (IOException ex) {
            throw new CliException(ERROR_IO, 2, "Unable to write config: " + path, null);
        }
    }

    private static void collectFields(Object obj, String prefix, Map<String, FieldInfo> out, Set<Integer> seen, int depth) {
        if (obj == null || depth > MAX_DEPTH) {
            return;
        }
        int identity = System.identityHashCode(obj);
        if (seen.contains(identity)) {
            return;
        }
        seen.add(identity);

        Class<?> cls = obj.getClass();

        if (isLeafValue(obj)) {
            String path = prefix;
            FieldInfo info = buildFieldInfo(obj, path);
            out.put(path, info);
            return;
        }

        if (obj instanceof List) {
            FieldInfo info = buildListFieldInfo((List<?>) obj, prefix);
            out.put(prefix, info);
            return;
        }

        for (Field field : getAllFields(cls)) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            String name = field.getName();
            String path = prefix.isEmpty() ? name : prefix + "." + name;

            if (name.equals("mFilters") && prefix.endsWith("mCustomExtractionConfig")) {
                continue;
            }

            if (name.equals("mRawExcludeListString") && prefix.endsWith("mExcludeManger")) {
                continue;
            }

            field.setAccessible(true);
            Object value;
            try {
                value = field.get(obj);
            } catch (IllegalAccessException ex) {
                continue;
            }

            if (value == null) {
                if (isLeafType(field.getType())) {
                    FieldInfo info = new FieldInfo(typeNameFor(field.getType()), null);
                    info.isList = false;
                    out.put(path, info);
                }
                continue;
            }

            if (isLeafValue(value)) {
                out.put(path, buildFieldInfo(value, path));
            } else if (value instanceof List) {
                out.put(path, buildListFieldInfo((List<?>) value, path));
            } else if (shouldRecurse(value)) {
                collectFields(value, path, out, seen, depth + 1);
            }
        }
    }

    private static void addVirtualFields(Object root, Map<String, FieldInfo> out) {
        List<String> excludeList = getExcludeList(root);
        if (excludeList != null) {
            FieldInfo info = new FieldInfo("list<string>", excludeList);
            info.isList = true;
            info.length = excludeList.size();
            out.put(VIRTUAL_EXCLUDE_PATTERNS, info);

            FieldInfo urlInfo = new FieldInfo("list<string>", excludeList);
            urlInfo.isList = true;
            urlInfo.length = excludeList.size();
            out.put(VIRTUAL_EXCLUDE_URLS, urlInfo);
        }

        if (!out.containsKey("mCrawlConfig.mMaxUrls")) {
            Integer maxUrls = getMaxUrls(root);
            if (maxUrls != null) {
                FieldInfo info = new FieldInfo("int", maxUrls);
                out.put("mCrawlConfig.mMaxUrls", info);
            }
        }

        if (!out.containsKey("mCrawlConfig.mMaxDepth")) {
            Integer maxDepth = getMaxDepth(root);
            if (maxDepth != null) {
                FieldInfo info = new FieldInfo("int", maxDepth);
                out.put("mCrawlConfig.mMaxDepth", info);
            }
        }

        if (!out.containsKey("mCrawlConfig.mRenderingMode")) {
            String renderingMode = getRenderingMode(root);
            if (renderingMode != null) {
                FieldInfo info = new FieldInfo("enum", renderingMode);
                info.enumOptions = Arrays.asList("HTML", "JAVASCRIPT");
                out.put("mCrawlConfig.mRenderingMode", info);
            }
        }

        List<Map<String, Object>> rules = extractRules(root);
        if (rules != null) {
            FieldInfo info = new FieldInfo("extraction_rules", rules);
            info.isList = true;
            info.length = rules.size();
            out.put(VIRTUAL_EXTRACTIONS, info);
        }

        List<Map<String, Object>> searches = extractCustomSearches(root);
        if (searches != null) {
            FieldInfo info = new FieldInfo("custom_searches", searches);
            info.isList = true;
            info.length = searches.size();
            out.put(VIRTUAL_CUSTOM_SEARCHES, info);
        }

        List<Map<String, Object>> scripts = extractCustomJavaScript(root);
        if (scripts != null) {
            FieldInfo info = new FieldInfo("custom_javascript", scripts);
            info.isList = true;
            info.length = scripts.size();
            out.put(VIRTUAL_CUSTOM_JAVASCRIPT, info);
        }
    }

    private static List<Map<String, Object>> extractRules(Object root) {
        Object custom = getNestedField(root, "mCustomExtractionConfig");
        if (custom == null) {
            return null;
        }

        Object listObj = getNestedField(custom, "mFilters");
        if (!(listObj instanceof List)) {
            return new ArrayList<>();
        }

        List<?> rawList = (List<?>) listObj;
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Object rule : rawList) {
            Map<String, Object> ruleMap = extractionRuleToMap(rule);
            if (ruleMap != null) {
                rules.add(ruleMap);
            }
        }
        return rules;
    }

    private static Map<String, Object> extractionRuleToMap(Object rule) {
        if (rule == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        Object name = getNestedField(rule, "mName");
        Object type = getNestedField(rule, "mExtractionType");
        Object dataType = getNestedField(rule, "mDataType");
        Object expression = getNestedField(rule, "mExpression");
        Object attribute = getNestedField(rule, "mAttribute");

        map.put("name", name == null ? null : name.toString());
        map.put("selector", expression == null ? null : expression.toString());
        map.put("selectorType", mapExtractionTypeName(type));
        map.put("extractMode", mapDataTypeName(dataType));
        if (attribute != null && !attribute.toString().isEmpty()) {
            map.put("attribute", attribute.toString());
        }
        return map;
    }

    private static List<Map<String, Object>> extractCustomSearches(Object root) {
        Object custom = getNestedField(root, "mCustomSearchConfig");
        if (custom == null) {
            return null;
        }

        Object listObj = getNestedField(custom, "mSearches");
        if (!(listObj instanceof List)) {
            return new ArrayList<>();
        }

        List<?> rawList = (List<?>) listObj;
        List<Map<String, Object>> searches = new ArrayList<>();
        for (Object rule : rawList) {
            Map<String, Object> ruleMap = customSearchToMap(rule);
            if (ruleMap != null) {
                searches.add(ruleMap);
            }
        }
        return searches;
    }

    private static Map<String, Object> customSearchToMap(Object rule) {
        if (rule == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        Object name = getNestedField(rule, "mName");
        Object mode = getNestedField(rule, "mMode");
        Object dataType = getNestedField(rule, "mDataType");
        Object query = getNestedField(rule, "mQuery");
        Object caseSensitive = getNestedField(rule, "mCaseSensitiveTextSearch");
        Object scope = getNestedField(rule, "mScope");
        Object xpath = getNestedField(rule, "mXPath");

        map.put("name", name == null ? null : name.toString());
        map.put("mode", mapEnumName(mode));
        map.put("dataType", mapEnumName(dataType));
        map.put("query", query == null ? null : query.toString());
        if (caseSensitive != null) {
            map.put("caseSensitive", caseSensitive);
        }
        map.put("scope", mapEnumName(scope));
        if (xpath != null && !xpath.toString().isEmpty()) {
            map.put("xpath", xpath.toString());
        }
        return map;
    }

    private static void applyCustomSearchPatches(Object root, JsonArray ops, List<String> warnings) throws CliException {
        Object custom = getNestedField(root, "mCustomSearchConfig");
        if (custom == null) {
            throw new CliException(ERROR_VALIDATION, 1, "Custom search config not found", null);
        }

        Object listObj = getNestedField(custom, "mSearches");
        List<Object> list;
        if (listObj instanceof List) {
            list = (List<Object>) listObj;
        } else {
            list = new ArrayList<>();
            setNestedField(custom, "mSearches", list);
        }

        for (JsonElement opEl : ops) {
            if (!opEl.isJsonObject()) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid custom search op entry", null);
            }
            JsonObject opObj = opEl.getAsJsonObject();
            String op = getJsonString(opObj, "op");
            if (op == null) {
                throw new CliException(ERROR_VALIDATION, 1, "Custom search op missing 'op'", null);
            }
            String opLower = op.toLowerCase(Locale.ROOT);

            if ("clear".equals(opLower)) {
                list.clear();
                continue;
            }

            if ("remove".equals(opLower)) {
                String name = getJsonString(opObj, "name");
                if (name == null) {
                    throw new CliException(ERROR_VALIDATION, 1, "Custom search remove requires name", null);
                }
                boolean removed = removeCustomSearchByName(list, name);
                if (!removed) {
                    warnings.add("Custom search not found for removal: " + name);
                }
                continue;
            }

            if ("add".equals(opLower)) {
                String name = getJsonString(opObj, "name");
                String query = getJsonString(opObj, "query");
                String mode = getJsonString(opObj, "mode");
                String dataType = getJsonString(opObj, "dataType");
                String scope = getJsonString(opObj, "scope");
                Boolean caseSensitive = getJsonBoolean(opObj, "caseSensitive");
                String xpath = getJsonString(opObj, "xpath");
                if (name == null || query == null) {
                    throw new CliException(ERROR_VALIDATION, 1, "Custom search add requires name and query", null);
                }

                Object searchMode = parseSearchMode(mode == null ? "CONTAINS" : mode);
                Object searchDataType = parseSearchDataType(dataType == null ? "TEXT" : dataType);
                Object searchScope = parseSearchScope(scope == null ? "HTML" : scope);
                boolean isCaseSensitive = caseSensitive != null && caseSensitive;
                Object rule = newCustomSearchInfo(name, searchMode, searchDataType, query, isCaseSensitive, searchScope, xpath);
                removeCustomSearchByName(list, name);
                list.add(rule);
                continue;
            }

            throw new CliException(ERROR_VALIDATION, 1, "Unsupported custom search op: " + op, null);
        }
    }

    private static boolean removeCustomSearchByName(List<Object> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            Object rule = list.get(i);
            Object ruleName = getNestedField(rule, "mName");
            if (ruleName != null && name.equals(ruleName.toString())) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    private static Object newCustomSearchInfo(
            String name,
            Object mode,
            Object dataType,
            String query,
            boolean caseSensitive,
            Object scope,
            String xpath
    ) throws CliException {
        try {
            Class<?> infoClass = Class.forName("seo.spider.config.custom.search.CustomSearchInfo");
            Constructor<?> ctor = infoClass.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            Object instance = ctor.newInstance(0);
            setNestedField(instance, "mName", name);
            setNestedField(instance, "mMode", mode);
            setNestedField(instance, "mDataType", dataType);
            setNestedField(instance, "mQuery", query);
            setNestedField(instance, "mCaseSensitiveTextSearch", caseSensitive);
            setNestedField(instance, "mScope", scope);
            if (xpath != null) {
                setNestedField(instance, "mXPath", xpath);
            }
            return instance;
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Unable to create custom search rule", null);
        }
    }

    private static Object parseSearchMode(String mode) throws CliException {
        String normalized = mode.toUpperCase(Locale.ROOT);
        try {
            Class<?> enumClass = Class.forName("seo.spider.config.custom.search.SearchMode");
            return Enum.valueOf((Class<Enum>) enumClass, normalized);
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid search mode: " + mode, null);
        }
    }

    private static Object parseSearchDataType(String dataType) throws CliException {
        String normalized = dataType.toUpperCase(Locale.ROOT);
        try {
            Class<?> enumClass = Class.forName("seo.spider.config.custom.search.SearchDataType");
            return Enum.valueOf((Class<Enum>) enumClass, normalized);
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid search data type: " + dataType, null);
        }
    }

    private static Object parseSearchScope(String scope) throws CliException {
        String normalized = scope.toUpperCase(Locale.ROOT);
        try {
            Class<?> enumClass = Class.forName("seo.spider.config.custom.search.SearchScope");
            return Enum.valueOf((Class<Enum>) enumClass, normalized);
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid search scope: " + scope, null);
        }
    }

    private static List<Map<String, Object>> extractCustomJavaScript(Object root) {
        Object custom = getNestedField(root, "mCustomJavaScriptConfig");
        if (custom == null) {
            return null;
        }

        Object listObj = getNestedField(custom, "mJsSnippets");
        if (!(listObj instanceof List)) {
            return new ArrayList<>();
        }

        List<?> rawList = (List<?>) listObj;
        List<Map<String, Object>> scripts = new ArrayList<>();
        for (Object rule : rawList) {
            Map<String, Object> ruleMap = customJavaScriptToMap(rule);
            if (ruleMap != null) {
                scripts.add(ruleMap);
            }
        }
        return scripts;
    }

    private static Map<String, Object> customJavaScriptToMap(Object rule) {
        if (rule == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        Object name = getNestedField(rule, "mName");
        Object type = getNestedField(rule, "mType");
        Object javaScript = getNestedField(rule, "mJavaScript");
        Object timeout = getNestedField(rule, "mActionTimeoutSecs");
        Object contentTypes = getNestedField(rule, "mContentTypes");

        map.put("name", name == null ? null : name.toString());
        map.put("type", mapEnumName(type));
        map.put("javascript", javaScript == null ? null : javaScript.toString());
        if (timeout != null) {
            map.put("timeout_secs", timeout);
        }
        if (contentTypes != null && !contentTypes.toString().isEmpty()) {
            map.put("content_types", contentTypes.toString());
        }
        return map;
    }

    private static void applyCustomJavaScriptPatches(Object root, JsonArray ops, List<String> warnings) throws CliException {
        Object custom = getNestedField(root, "mCustomJavaScriptConfig");
        if (custom == null) {
            throw new CliException(ERROR_VALIDATION, 1, "Custom JavaScript config not found", null);
        }

        Object listObj = getNestedField(custom, "mJsSnippets");
        List<Object> list;
        if (listObj instanceof List) {
            list = (List<Object>) listObj;
        } else {
            list = new ArrayList<>();
            setNestedField(custom, "mJsSnippets", list);
        }

        for (JsonElement opEl : ops) {
            if (!opEl.isJsonObject()) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid custom JavaScript op entry", null);
            }
            JsonObject opObj = opEl.getAsJsonObject();
            String op = getJsonString(opObj, "op");
            if (op == null) {
                throw new CliException(ERROR_VALIDATION, 1, "Custom JavaScript op missing 'op'", null);
            }
            String opLower = op.toLowerCase(Locale.ROOT);

            if ("clear".equals(opLower)) {
                list.clear();
                continue;
            }

            if ("remove".equals(opLower)) {
                String name = getJsonString(opObj, "name");
                if (name == null) {
                    throw new CliException(ERROR_VALIDATION, 1, "Custom JavaScript remove requires name", null);
                }
                boolean removed = removeCustomJavaScriptByName(list, name);
                if (!removed) {
                    warnings.add("Custom JavaScript not found for removal: " + name);
                }
                continue;
            }

            if ("add".equals(opLower)) {
                String name = getJsonString(opObj, "name");
                String javaScript = getJsonString(opObj, "javascript");
                String type = getJsonString(opObj, "type");
                Integer timeout = getJsonInt(opObj, "timeout_secs");
                String contentTypes = getJsonString(opObj, "content_types");
                if (name == null || javaScript == null) {
                    throw new CliException(ERROR_VALIDATION, 1, "Custom JavaScript add requires name and javascript", null);
                }

                Object scriptType = parseCustomJavaScriptType(type == null ? "EXTRACTION" : type);
                int timeoutSecs = timeout == null ? 10 : timeout;
                String contentTypeValue = contentTypes == null ? "text/html" : contentTypes;
                Object rule = newCustomJavaScriptInfo(name, scriptType, javaScript, timeoutSecs, contentTypeValue);
                removeCustomJavaScriptByName(list, name);
                list.add(rule);
                continue;
            }

            throw new CliException(ERROR_VALIDATION, 1, "Unsupported custom JavaScript op: " + op, null);
        }
    }

    private static boolean removeCustomJavaScriptByName(List<Object> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            Object rule = list.get(i);
            Object ruleName = getNestedField(rule, "mName");
            if (ruleName != null && name.equals(ruleName.toString())) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    private static Object newCustomJavaScriptInfo(
            String name,
            Object type,
            String javaScript,
            int timeoutSecs,
            String contentTypes
    ) throws CliException {
        try {
            Class<?> infoClass = Class.forName("seo.spider.config.custom.javascript.CustomJavaScriptInfo");
            Class<?> typeClass = Class.forName("seo.spider.config.custom.javascript.id142006137");
            try {
                Constructor<?> ctor = infoClass.getDeclaredConstructor(
                        String.class, typeClass, String.class, int.class, String.class);
                ctor.setAccessible(true);
                return ctor.newInstance(name, type, javaScript, timeoutSecs, contentTypes);
            } catch (NoSuchMethodException ex) {
                Constructor<?> ctor = infoClass.getDeclaredConstructor(int.class);
                ctor.setAccessible(true);
                Object instance = ctor.newInstance(0);
                setNestedField(instance, "mName", name);
                setNestedField(instance, "mType", type);
                setNestedField(instance, "mJavaScript", javaScript);
                setNestedField(instance, "mActionTimeoutSecs", timeoutSecs);
                setNestedField(instance, "mContentTypes", contentTypes);
                return instance;
            }
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Unable to create custom JavaScript rule", null);
        }
    }

    private static Object parseCustomJavaScriptType(String type) throws CliException {
        String normalized = type.toUpperCase(Locale.ROOT);
        try {
            Class<?> enumClass = Class.forName("seo.spider.config.custom.javascript.id142006137");
            return Enum.valueOf((Class<Enum>) enumClass, normalized);
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid custom JavaScript type: " + type, null);
        }
    }

    private static void applyExtractionPatches(Object root, JsonArray ops, List<String> warnings) throws CliException {
        Object custom = getNestedField(root, "mCustomExtractionConfig");
        if (custom == null) {
            throw new CliException(ERROR_VALIDATION, 1, "Custom extraction config not found", null);
        }

        Object listObj = getNestedField(custom, "mFilters");
        List<Object> list;
        if (listObj instanceof List) {
            list = (List<Object>) listObj;
        } else {
            list = new ArrayList<>();
            setNestedField(custom, "mFilters", list);
        }

        for (JsonElement opEl : ops) {
            if (!opEl.isJsonObject()) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid extraction op entry", null);
            }
            JsonObject opObj = opEl.getAsJsonObject();
            String op = getJsonString(opObj, "op");
            if (op == null) {
                throw new CliException(ERROR_VALIDATION, 1, "Extraction op missing 'op'", null);
            }
            String opLower = op.toLowerCase(Locale.ROOT);

            if ("clear".equals(opLower)) {
                list.clear();
                continue;
            }

            if ("remove".equals(opLower)) {
                String name = getJsonString(opObj, "name");
                if (name == null) {
                    throw new CliException(ERROR_VALIDATION, 1, "Extraction remove requires name", null);
                }
                boolean removed = removeExtractionByName(list, name);
                if (!removed) {
                    warnings.add("Extraction not found for removal: " + name);
                }
                continue;
            }

            if ("add".equals(opLower)) {
                String name = getJsonString(opObj, "name");
                String selector = getJsonString(opObj, "selector");
                String selectorType = getJsonString(opObj, "selectorType");
                String extractMode = getJsonString(opObj, "extractMode");
                String attribute = getJsonString(opObj, "attribute");
                if (name == null || selector == null || selectorType == null || extractMode == null) {
                    throw new CliException(ERROR_VALIDATION, 1, "Extraction add requires name, selector, selectorType, extractMode", null);
                }

                Object extractionType = parseExtractionType(selectorType);
                Object dataType = parseDataType(extractMode);
                Object rule = newCustomExtractionInfo(name, extractionType, dataType, selector, attribute);
                removeExtractionByName(list, name);
                list.add(rule);
                continue;
            }

            throw new CliException(ERROR_VALIDATION, 1, "Unsupported extraction op: " + op, null);
        }
    }

    private static boolean removeExtractionByName(List<Object> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            Object rule = list.get(i);
            Object ruleName = getNestedField(rule, "mName");
            if (ruleName != null && name.equals(ruleName.toString())) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    private static Object newCustomExtractionInfo(String name, Object extractionType, Object dataType, String expression, String attribute) throws CliException {
        try {
            String safeAttribute = attribute == null ? "" : attribute;
            Class<?> infoClass = Class.forName("seo.spider.extraction.CustomExtractionInfo");
            Class<?> extractionTypeClass = Class.forName("seo.spider.extraction.CustomExtractionInfo$ExtractionType");
            Class<?> dataTypeClass = Class.forName("seo.spider.extraction.CustomExtractionInfo$DataType");

            try {
                Constructor<?> ctor = infoClass.getDeclaredConstructor(
                        String.class, extractionTypeClass, dataTypeClass, String.class, String.class);
                ctor.setAccessible(true);
                return ctor.newInstance(name, extractionType, dataType, expression, safeAttribute);
            } catch (NoSuchMethodException ex) {
                Constructor<?> ctor = infoClass.getDeclaredConstructor(int.class);
                ctor.setAccessible(true);
                Object instance = ctor.newInstance(0);
                setNestedField(instance, "mName", name);
                setNestedField(instance, "mExtractionType", extractionType);
                setNestedField(instance, "mDataType", dataType);
                setNestedField(instance, "mExpression", expression);
                setNestedField(instance, "mAttribute", safeAttribute);
                return instance;
            }
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Unable to create extraction rule", null);
        }
    }

    private static Object parseExtractionType(String selectorType) throws CliException {
        String normalized = selectorType.toUpperCase(Locale.ROOT);
        if ("CSS".equals(normalized)) {
            normalized = "CSSPATH";
        }
        try {
            Class<?> enumClass = Class.forName("seo.spider.extraction.CustomExtractionInfo$ExtractionType");
            return Enum.valueOf((Class<Enum>) enumClass, normalized);
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid selectorType: " + selectorType, null);
        }
    }

    private static Object parseDataType(String extractMode) throws CliException {
        String normalized = extractMode.toUpperCase(Locale.ROOT);
        if ("TEXT".equals(normalized)) {
            normalized = "INNER_TEXT";
        } else if ("HTML_ELEMENT".equals(normalized)) {
            normalized = "OUTER_HTML";
        }
        try {
            Class<?> enumClass = Class.forName("seo.spider.extraction.CustomExtractionInfo$DataType");
            return Enum.valueOf((Class<Enum>) enumClass, normalized);
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid extractMode: " + extractMode, null);
        }
    }

    private static List<String> getExcludeList(Object root) {
        Object manager = getExcludeManager(root);
        if (manager == null) {
            return null;
        }
        Object raw = getNestedField(manager, "mRawExcludeListString");
        if (raw == null) {
            return new ArrayList<>();
        }
        String rawString = raw.toString();
        if (rawString.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String[] lines = rawString.split("\\r?\\n");
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static void setExcludeList(Object root, List<String> list) throws CliException {
        Object manager = getExcludeManager(root);
        if (manager == null) {
            throw new CliException(ERROR_VALIDATION, 1, "Exclude manager not found", null);
        }
        StringBuilder builder = new StringBuilder();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    builder.append(System.lineSeparator());
                }
                builder.append(list.get(i));
            }
        }
        setNestedField(manager, "mRawExcludeListString", builder.toString());
    }

    private static Object getExcludeManager(Object root) {
        Object manager = getNestedField(root, "mExcludeManger");
        if (manager == null) {
            manager = getNestedField(root, "mExcludeManager");
        }
        return manager;
    }

    private static boolean isVirtualExcludeField(String path) {
        return VIRTUAL_EXCLUDE_PATTERNS.equals(path) || VIRTUAL_EXCLUDE_URLS.equals(path);
    }

    private static Integer getMaxUrls(Object root) {
        Object crawlConfig = getNestedField(root, "mCrawlConfig");
        if (crawlConfig == null) {
            return null;
        }
        Object limitFlag = getNestedField(crawlConfig, "mLimitSearchTotal");
        Object limitValue = getNestedField(crawlConfig, "mSearchTotalLimit");
        if (!(limitFlag instanceof Boolean) || !(limitValue instanceof Number)) {
            return null;
        }
        if (!((Boolean) limitFlag)) {
            return 0;
        }
        return ((Number) limitValue).intValue();
    }

    private static void setMaxUrls(Object root, int value) throws CliException {
        Object crawlConfig = getNestedField(root, "mCrawlConfig");
        if (crawlConfig == null) {
            throw new CliException(ERROR_VALIDATION, 1, "mCrawlConfig not found", null);
        }
        Object limitFlag = getNestedField(crawlConfig, "mLimitSearchTotal");
        Object limitValue = getNestedField(crawlConfig, "mSearchTotalLimit");
        if (!(limitFlag instanceof Boolean) || !(limitValue instanceof Number)) {
            throw new CliException(ERROR_VALIDATION, 1, "mMaxUrls is not supported in this config version", null);
        }
        setNestedField(crawlConfig, "mLimitSearchTotal", value != 0);
        setNestedField(crawlConfig, "mSearchTotalLimit", value);
    }

    private static Integer getMaxDepth(Object root) {
        Object crawlConfig = getNestedField(root, "mCrawlConfig");
        if (crawlConfig == null) {
            return null;
        }
        Object limitFlag = getNestedField(crawlConfig, "mLimitSearchDepth");
        Object limitValue = getNestedField(crawlConfig, "mSearchDepthLimit");
        if (!(limitFlag instanceof Boolean) || !(limitValue instanceof Number)) {
            return null;
        }
        if (!((Boolean) limitFlag)) {
            return 0;
        }
        return ((Number) limitValue).intValue();
    }

    private static void setMaxDepth(Object root, int value) throws CliException {
        Object crawlConfig = getNestedField(root, "mCrawlConfig");
        if (crawlConfig == null) {
            throw new CliException(ERROR_VALIDATION, 1, "mCrawlConfig not found", null);
        }
        Object limitFlag = getNestedField(crawlConfig, "mLimitSearchDepth");
        Object limitValue = getNestedField(crawlConfig, "mSearchDepthLimit");
        if (!(limitFlag instanceof Boolean) || !(limitValue instanceof Number)) {
            throw new CliException(ERROR_VALIDATION, 1, "mMaxDepth is not supported in this config version", null);
        }
        setNestedField(crawlConfig, "mLimitSearchDepth", value != 0);
        setNestedField(crawlConfig, "mSearchDepthLimit", value);
    }

    private static String getRenderingMode(Object root) {
        Object crawlConfig = getNestedField(root, "mCrawlConfig");
        if (crawlConfig == null) {
            return null;
        }
        if (fieldExists(crawlConfig, "mRenderingMode")) {
            Object rendering = getNestedField(crawlConfig, "mRenderingMode");
            return rendering == null ? null : mapEnumName(rendering);
        }
        if (fieldExists(crawlConfig, "mCrawlerMode")) {
            Object crawlerMode = getNestedField(crawlConfig, "mCrawlerMode");
            return crawlerMode == null ? null : mapCrawlerModeToRendering(crawlerMode);
        }
        return null;
    }

    private static void setRenderingMode(Object root, String mode) throws CliException {
        Object crawlConfig = getNestedField(root, "mCrawlConfig");
        if (crawlConfig == null) {
            throw new CliException(ERROR_VALIDATION, 1, "mCrawlConfig not found", null);
        }

        if (fieldExists(crawlConfig, "mRenderingMode")) {
            Field field = findField(crawlConfig.getClass(), "mRenderingMode");
            String normalized = mode.toUpperCase(Locale.ROOT);
            try {
                Object enumValue = Enum.valueOf((Class<Enum>) field.getType(), normalized);
                field.setAccessible(true);
                field.set(crawlConfig, enumValue);
                return;
            } catch (Exception ex) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid rendering mode: " + mode, null);
            }
        }

        if (fieldExists(crawlConfig, "mCrawlerMode")) {
            Field field = findField(crawlConfig.getClass(), "mCrawlerMode");
            String mapped = mapRenderingToCrawlerMode(mode);
            try {
                Object enumValue = Enum.valueOf((Class<Enum>) field.getType(), mapped);
                field.setAccessible(true);
                field.set(crawlConfig, enumValue);
                return;
            } catch (Exception ex) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid rendering mode: " + mode, null);
            }
        }

        throw new CliException(ERROR_VALIDATION, 1, "mRenderingMode is not supported in this config version", null);
    }

    private static String mapCrawlerModeToRendering(Object crawlerMode) {
        String name = mapEnumName(crawlerMode);
        if ("STANDARD".equals(name)) {
            return "HTML";
        }
        if ("RENDER".equals(name) || "AJAX".equals(name)) {
            return "JAVASCRIPT";
        }
        return name;
    }

    private static String mapRenderingToCrawlerMode(String mode) throws CliException {
        if (mode == null) {
            throw new CliException(ERROR_VALIDATION, 1, "Rendering mode is required", null);
        }
        String normalized = mode.toUpperCase(Locale.ROOT);
        if ("HTML".equals(normalized)) {
            return "STANDARD";
        }
        if ("JAVASCRIPT".equals(normalized)) {
            return "RENDER";
        }
        if ("STANDARD".equals(normalized) || "RENDER".equals(normalized) || "AJAX".equals(normalized)) {
            return normalized;
        }
        throw new CliException(ERROR_VALIDATION, 1, "Invalid rendering mode: " + mode, null);
    }

    private static List<String> applyListPatch(List<String> before, JsonElement patch, List<String> warnings) throws CliException {
        List<String> base = before == null ? new ArrayList<>() : new ArrayList<>(before);
        if (patch == null || patch.isJsonNull()) {
            throw new CliException(ERROR_VALIDATION, 1, "List patch cannot be null", null);
        }

        if (patch.isJsonArray()) {
            return jsonArrayToList(patch.getAsJsonArray());
        }

        if (!patch.isJsonObject()) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid list patch format", null);
        }

        JsonObject opObj = patch.getAsJsonObject();
        String op = getJsonString(opObj, "op");
        if (op == null) {
            throw new CliException(ERROR_VALIDATION, 1, "List patch missing op", null);
        }
        String opLower = op.toLowerCase(Locale.ROOT);

        if ("clear".equals(opLower)) {
            return new ArrayList<>();
        }

        if ("set".equals(opLower)) {
            JsonElement values = opObj.get("values");
            if (values == null || !values.isJsonArray()) {
                throw new CliException(ERROR_VALIDATION, 1, "List patch set requires values array", null);
            }
            return jsonArrayToList(values.getAsJsonArray());
        }

        JsonElement valuesEl = opObj.get("values");
        if (valuesEl == null || !valuesEl.isJsonArray()) {
            throw new CliException(ERROR_VALIDATION, 1, "List patch requires values array", null);
        }
        List<String> values = jsonArrayToList(valuesEl.getAsJsonArray());

        if ("append".equals(opLower)) {
            base.addAll(values);
            return base;
        }

        if ("prepend".equals(opLower)) {
            List<String> out = new ArrayList<>(values);
            out.addAll(base);
            return out;
        }

        if ("remove".equals(opLower)) {
            for (String value : values) {
                base.removeIf(item -> Objects.equals(item, value));
            }
            return base;
        }

        throw new CliException(ERROR_VALIDATION, 1, "Unsupported list patch op: " + op, null);
    }

    private static List<String> jsonArrayToList(JsonArray array) throws CliException {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                values.add(null);
                continue;
            }
            if (!element.isJsonPrimitive()) {
                throw new CliException(ERROR_VALIDATION, 1, "List values must be primitives", null);
            }
            values.add(element.getAsString());
        }
        return values;
    }

    private static Object resolvePath(Object root, String path) throws CliException {
        if (root == null) {
            throw new CliException(ERROR_VALIDATION, 1, "Config root is null", null);
        }
        String[] parts = path.split("\\.");
        Object current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Field field = findField(current.getClass(), part);
            field.setAccessible(true);
            Object next;
            try {
                next = field.get(current);
            } catch (IllegalAccessException ex) {
                throw new CliException(ERROR_VALIDATION, 1, "Unable to read field: " + part, null);
            }
            if (next == null) {
                throw new CliException(ERROR_VALIDATION, 1, "Null field encountered at: " + part, null);
            }
            current = next;
        }
        return current;
    }

    private static String leafName(String path) {
        int idx = path.lastIndexOf('.');
        if (idx == -1) {
            return path;
        }
        return path.substring(idx + 1);
    }

    private static Field findField(Class<?> cls, String name) throws CliException {
        for (Field field : getAllFields(cls)) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        if ("mExcludeManager".equals(name)) {
            for (Field field : getAllFields(cls)) {
                if (field.getName().equals("mExcludeManger")) {
                    return field;
                }
            }
        }
        throw new CliException(ERROR_VALIDATION, 1, "Field not found: " + name, null);
    }

    private static List<Field> getAllFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    private static Object coerceValue(Class<?> type, JsonElement value, String path) throws CliException {
        if (value == null || value.isJsonNull()) {
            if (type.isPrimitive()) {
                throw new CliException(ERROR_VALIDATION, 1, "Null not allowed for primitive: " + path, null);
            }
            return null;
        }

        if (type == String.class) {
            if (!value.isJsonPrimitive()) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid string for: " + path, null);
            }
            return value.getAsString();
        }

        if (type == int.class || type == Integer.class || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            if (!value.isJsonPrimitive()) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid int for: " + path, null);
            }
            return value.getAsInt();
        }

        if (type == long.class || type == Long.class) {
            if (!value.isJsonPrimitive()) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid long for: " + path, null);
            }
            return value.getAsLong();
        }

        if (type == double.class || type == Double.class) {
            if (!value.isJsonPrimitive()) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid double for: " + path, null);
            }
            return value.getAsDouble();
        }

        if (type == float.class || type == Float.class) {
            if (!value.isJsonPrimitive()) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid float for: " + path, null);
            }
            return value.getAsFloat();
        }

        if (type == boolean.class || type == Boolean.class) {
            if (!value.isJsonPrimitive()) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid boolean for: " + path, null);
            }
            return value.getAsBoolean();
        }

        if (type.isEnum()) {
            if (!value.isJsonPrimitive()) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid enum for: " + path, null);
            }
            String raw = value.getAsString();
            String normalized = raw.toUpperCase(Locale.ROOT);
            try {
                return Enum.valueOf((Class<Enum>) type, normalized);
            } catch (Exception ex) {
                throw new CliException(ERROR_VALIDATION, 1, "Invalid enum value for " + path + ": " + raw, null);
            }
        }

        throw new CliException(ERROR_VALIDATION, 1, "Unsupported field type: " + type.getName(), null);
    }

    private static boolean isEditable(String path) {
        if (path == null) {
            return false;
        }
        return ALLOWLIST.contains(path)
                || LIST_ALLOWLIST.contains(path)
                || VIRTUAL_EXTRACTIONS.equals(path)
                || VIRTUAL_CUSTOM_SEARCHES.equals(path)
                || VIRTUAL_CUSTOM_JAVASCRIPT.equals(path)
                || isVirtualExcludeField(path);
    }

    private static List<String> allowedFieldsList() {
        List<String> fields = new ArrayList<>();
        fields.addAll(ALLOWLIST);
        fields.addAll(LIST_ALLOWLIST);
        fields.add("extractions");
        fields.add(VIRTUAL_EXTRACTIONS);
        fields.add("custom_searches");
        fields.add(VIRTUAL_CUSTOM_SEARCHES);
        fields.add("custom_javascript");
        fields.add(VIRTUAL_CUSTOM_JAVASCRIPT);
        return fields;
    }

    private static FieldInfo buildFieldInfo(Object value, String path) {
        String type = typeNameFor(value.getClass());
        Object output = value instanceof Enum ? mapEnumName(value) : value;
        FieldInfo info = new FieldInfo(type, output);
        if (value instanceof Enum) {
            info.enumOptions = enumOptions(value.getClass());
        }
        return info;
    }

    private static FieldInfo buildListFieldInfo(List<?> list, String path) {
        List<String> values = toStringList(list);
        FieldInfo info = new FieldInfo("list<string>", values);
        info.isList = true;
        info.length = values.size();
        return info;
    }

    private static Object toJsonValue(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum) {
            return mapEnumName(value);
        }
        if (value instanceof List) {
            return toStringList(value);
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < length; i++) {
                Object item = java.lang.reflect.Array.get(value, i);
                values.add(item == null ? null : item.toString());
            }
            return values;
        }
        return value;
    }

    private static Object maskValueIfNeeded(String path, Object value) {
        if (value == null || path == null) {
            return value;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String key : SENSITIVE_KEYS) {
            if (lower.contains(key)) {
                return "***REDACTED***";
            }
        }
        return value;
    }

    private static boolean isLeafValue(Object value) {
        if (value == null) {
            return true;
        }
        Class<?> cls = value.getClass();
        if (isLeafType(cls)) {
            return true;
        }
        return value instanceof Map;
    }

    private static boolean isLeafType(Class<?> type) {
        if (type.isPrimitive() || type.isEnum()) {
            return true;
        }
        if (type == String.class || type == Boolean.class || type == Character.class) {
            return true;
        }
        return Number.class.isAssignableFrom(type);
    }

    private static boolean shouldRecurse(Object value) {
        if (value == null || isLeafValue(value)) {
            return false;
        }
        if (value instanceof List || value instanceof Map) {
            return false;
        }
        String name = value.getClass().getName();
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("org."));
    }

    private static String typeNameFor(Class<?> type) {
        if (type.isEnum()) {
            return "enum";
        }
        if (type == String.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            return "int";
        }
        if (type == long.class || type == Long.class) {
            return "long";
        }
        if (type == double.class || type == Double.class) {
            return "double";
        }
        if (type == float.class || type == Float.class) {
            return "float";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (Number.class.isAssignableFrom(type)) {
            return "number";
        }
        return type.getSimpleName();
    }

    private static List<String> enumOptions(Class<?> enumClass) {
        if (enumClass == null || !enumClass.isEnum()) {
            return null;
        }
        List<String> options = new ArrayList<>();
        Object[] values = enumClass.getEnumConstants();
        if (values == null) {
            return null;
        }
        for (Object item : values) {
            options.add(((Enum<?>) item).name());
        }
        return options;
    }

    private static String mapEnumName(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        return value.toString();
    }

    private static String mapExtractionTypeName(Object type) {
        if (type == null) {
            return null;
        }
        String name = type.toString();
        if ("CSSPATH".equals(name)) {
            return "CSS";
        }
        return name;
    }

    private static String mapDataTypeName(Object type) {
        if (type == null) {
            return null;
        }
        String name = type.toString();
        if ("INNER_TEXT".equals(name)) {
            return "TEXT";
        }
        if ("OUTER_HTML".equals(name)) {
            return "HTML_ELEMENT";
        }
        return name;
    }

    private static boolean fieldExists(Object obj, String fieldName) {
        if (obj == null || fieldName == null) {
            return false;
        }
        for (Field candidate : getAllFields(obj.getClass())) {
            if (candidate.getName().equals(fieldName)) {
                return true;
            }
        }
        if ("mExcludeManager".equals(fieldName)) {
            for (Field candidate : getAllFields(obj.getClass())) {
                if (candidate.getName().equals("mExcludeManger")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object getNestedField(Object obj, String fieldName) {
        if (obj == null || fieldName == null) {
            return null;
        }
        Field field = null;
        for (Field candidate : getAllFields(obj.getClass())) {
            if (candidate.getName().equals(fieldName)) {
                field = candidate;
                break;
            }
        }
        if (field == null && "mExcludeManager".equals(fieldName)) {
            for (Field candidate : getAllFields(obj.getClass())) {
                if (candidate.getName().equals("mExcludeManger")) {
                    field = candidate;
                    break;
                }
            }
        }
        if (field == null) {
            return null;
        }
        field.setAccessible(true);
        try {
            return field.get(obj);
        } catch (IllegalAccessException ex) {
            return null;
        }
    }

    private static void setNestedField(Object obj, String fieldName, Object value) {
        if (obj == null || fieldName == null) {
            throw new IllegalStateException("Cannot set field on null object");
        }
        Field field = null;
        for (Field candidate : getAllFields(obj.getClass())) {
            if (candidate.getName().equals(fieldName)) {
                field = candidate;
                break;
            }
        }
        if (field == null && "mExcludeManager".equals(fieldName)) {
            for (Field candidate : getAllFields(obj.getClass())) {
                if (candidate.getName().equals("mExcludeManger")) {
                    field = candidate;
                    break;
                }
            }
        }
        if (field == null) {
            throw new IllegalStateException("Field not found: " + fieldName);
        }
        field.setAccessible(true);
        try {
            field.set(obj, value);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Unable to set field: " + fieldName);
        }
    }

    private static List<String> toStringList(Object listObj) {
        List<String> values = new ArrayList<>();
        if (listObj == null) {
            return values;
        }
        if (listObj instanceof List) {
            for (Object item : (List<?>) listObj) {
                values.add(item == null ? null : item.toString());
            }
            return values;
        }
        if (listObj.getClass().isArray()) {
            Object[] array = (Object[]) listObj;
            for (Object item : array) {
                values.add(item == null ? null : item.toString());
            }
            return values;
        }
        values.add(listObj.toString());
        return values;
    }

    private static List<String> listDiffAdded(List<String> listA, List<String> listB) {
        List<String> added = new ArrayList<>();
        Set<String> setA = new LinkedHashSet<>(listA);
        for (String item : listB) {
            if (!setA.contains(item)) {
                added.add(item);
            }
        }
        return added;
    }

    private static List<String> listDiffRemoved(List<String> listA, List<String> listB) {
        List<String> removed = new ArrayList<>();
        Set<String> setB = new LinkedHashSet<>(listB);
        for (String item : listA) {
            if (!setB.contains(item)) {
                removed.add(item);
            }
        }
        return removed;
    }

    private static List<String> listDiffUnchanged(List<String> listA, List<String> listB) {
        List<String> unchanged = new ArrayList<>();
        Set<String> setB = new LinkedHashSet<>(listB);
        for (String item : listA) {
            if (setB.contains(item)) {
                unchanged.add(item);
            }
        }
        return unchanged;
    }

    private static String readConfigVersion(Object root) {
        Object version = getNestedField(root, "mConfigVersion");
        if (version == null) {
            return null;
        }
        return version.toString();
    }

    private static String readSfVersion() {
        String classpath = System.getProperty("java.class.path");
        if (classpath == null) {
            return null;
        }
        String separator = System.getProperty("path.separator");
        if (separator == null) {
            separator = ";";
        }
        String[] entries = classpath.split(Pattern.quote(separator));
        for (String entry : entries) {
            if (!entry.endsWith("ScreamingFrogSEOSpider.jar")) {
                continue;
            }
            try (JarFile jar = new JarFile(entry)) {
                Manifest manifest = jar.getManifest();
                if (manifest == null) {
                    return null;
                }
                Attributes attributes = manifest.getMainAttributes();
                List<String> keys = Arrays.asList(
                        "Implementation-Version",
                        "Specification-Version",
                        "Bundle-Version",
                        "Version",
                        "ScreamingFrog-Version"
                );
                for (String key : keys) {
                    String value = attributes.getValue(key);
                    if (value != null && !value.trim().isEmpty()) {
                        return value.trim();
                    }
                }
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    private static String buildVersionWarning(String configVersion, String sfVersion) {
        if (configVersion == null || sfVersion == null) {
            return null;
        }
        if (!configVersion.equals(sfVersion)) {
            return "Config version " + configVersion + " may not be compatible with SF " + sfVersion;
        }
        return null;
    }

    private static String fetchHtml(String url) throws CliException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid URL: " + url, null);
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "ConfigBuilder/1.0")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 400) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("status", status);
                throw new CliException(ERROR_IO, 2, "HTTP error fetching URL: " + url, details);
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CliException(ERROR_IO, 2, "Interrupted while fetching URL: " + url, null);
        } catch (IOException ex) {
            throw new CliException(ERROR_IO, 2, "Unable to fetch URL: " + url, null);
        }
    }

    private static List<String> extractMatches(String html, String url, String selector, String selectorType, String extractMode) throws CliException {
        String type = selectorType.toUpperCase(Locale.ROOT);
        String mode = normalizeExtractMode(extractMode);

        if ("CSS".equals(type) || "CSSPATH".equals(type)) {
            return extractCss(html, url, selector, mode);
        }
        if ("XPATH".equals(type)) {
            return extractXpath(html, url, selector, mode);
        }
        if ("REGEX".equals(type)) {
            return extractRegex(html, selector);
        }
        throw new CliException(ERROR_VALIDATION, 1, "Unsupported selector type: " + selectorType, null);
    }

    private static List<String> extractCss(String html, String url, String selector, String mode) throws CliException {
        try {
            Document doc = Jsoup.parse(html, url);
            Elements elements = doc.select(selector);
            List<String> matches = new ArrayList<>();
            for (Element element : elements) {
                matches.add(extractElementValue(element, mode));
            }
            return matches;
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid CSS selector: " + selector, null);
        }
    }

    private static List<String> extractXpath(String html, String url, String selector, String mode) throws CliException {
        try {
            Document doc = Jsoup.parse(html, url);
            org.w3c.dom.Document w3cDoc = new W3CDom().fromJsoup(doc);
            XPathExpression expr = XPathFactory.newInstance().newXPath().compile(selector);
            NodeList nodes = (NodeList) expr.evaluate(w3cDoc, XPathConstants.NODESET);
            List<String> matches = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                matches.add(extractNodeValue(node, mode));
            }
            return matches;
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid XPath selector: " + selector, null);
        }
    }

    private static List<String> extractRegex(String html, String selector) throws CliException {
        try {
            Pattern pattern = Pattern.compile(selector, Pattern.DOTALL | Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(html);
            List<String> matches = new ArrayList<>();
            while (matcher.find()) {
                matches.add(matcher.group());
            }
            return matches;
        } catch (Exception ex) {
            throw new CliException(ERROR_VALIDATION, 1, "Invalid regex selector: " + selector, null);
        }
    }

    private static String extractElementValue(Element element, String mode) {
        if ("TEXT".equals(mode)) {
            return element.text();
        }
        if ("INNER_HTML".equals(mode)) {
            return element.html();
        }
        if ("HTML_ELEMENT".equals(mode)) {
            return element.outerHtml();
        }
        if ("FUNCTION_VALUE".equals(mode)) {
            return element.text();
        }
        return element.text();
    }

    private static String extractNodeValue(Node node, String mode) {
        if (node == null) {
            return "";
        }
        if ("TEXT".equals(mode) || "FUNCTION_VALUE".equals(mode)) {
            return node.getTextContent();
        }
        if ("HTML_ELEMENT".equals(mode)) {
            return nodeToString(node);
        }
        if ("INNER_HTML".equals(mode)) {
            NodeList children = node.getChildNodes();
            if (children == null || children.getLength() == 0) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < children.getLength(); i++) {
                builder.append(nodeToString(children.item(i)));
            }
            return builder.toString();
        }
        return node.getTextContent();
    }

    private static String normalizeExtractMode(String extractMode) throws CliException {
        if (extractMode == null) {
            throw new CliException(ERROR_VALIDATION, 1, "Missing extract mode", null);
        }
        String mode = extractMode.toUpperCase(Locale.ROOT);
        switch (mode) {
            case "TEXT":
            case "INNER_HTML":
            case "HTML_ELEMENT":
            case "FUNCTION_VALUE":
                return mode;
            default:
                throw new CliException(ERROR_VALIDATION, 1, "Unsupported extract mode: " + extractMode, null);
        }
    }

    private static String nodeToString(Node node) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.METHOD, "html");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(writer));
            return writer.toString();
        } catch (Exception ex) {
            return node.getTextContent();
        }
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj == null || key == null) {
            return null;
        }
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive()) {
            return element.toString();
        }
        return element.getAsString();
    }

    private static Boolean getJsonBoolean(JsonObject obj, String key) {
        if (obj == null || key == null) {
            return null;
        }
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            try {
                return element.getAsBoolean();
            } catch (Exception ex) {
                return Boolean.parseBoolean(element.getAsString());
            }
        }
        return null;
    }

    private static Integer getJsonInt(JsonObject obj, String key) {
        if (obj == null || key == null) {
            return null;
        }
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            try {
                return element.getAsInt();
            } catch (Exception ex) {
                try {
                    return Integer.parseInt(element.getAsString());
                } catch (Exception ignore) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void writeJson(Object payload) {
        System.out.println(GSON.toJson(payload));
    }

    private static void writeError(CliException ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("error", ex.getMessage());
        error.put("errorType", ex.errorType);
        error.put("details", ex.details == null ? new LinkedHashMap<>() : ex.details);
        writeJson(error);
    }

    private static class Args {
        String command;
        Map<String, String> options = new HashMap<>();
        Set<String> flags = new HashSet<>();
    }

    private static class FieldInfo {
        String type;
        Object value;
        List<String> enumOptions;
        Integer length;
        boolean isList;

        FieldInfo(String type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    private static class CliException extends Exception {
        final String errorType;
        final int exitCode;
        final Map<String, Object> details;

        CliException(String errorType, int exitCode, String message, Map<String, Object> details) {
            super(message);
            this.errorType = errorType;
            this.exitCode = exitCode;
            this.details = details;
        }
    }
}
