///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 15+
//DEPS org.aesh:aesh:3.16
//FILES jfr-since.properties

import jdk.jfr.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.HelpEntry;
import org.aesh.command.HelpSectionProvider;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;
import org.aesh.AeshRuntimeRunner;

@CommandDefinition(name = "jfrdoc", description = "Generate JFR event documentation",
        generateHelp = true, helpSectionProvider = jfrdoc.Examples.class)
public class jfrdoc implements Command<CommandInvocation> {

    public static class Examples implements HelpSectionProvider {
        @Override
        public Map<String, List<HelpEntry>> getAdditionalSections() {
            List<HelpEntry> examples = List.of(
                new HelpEntry("jbang app install jfrdoc@maxandersen",
                    "Install as a command (then use jfrdoc directly)"),
                new HelpEntry("jfrdoc -f html",
                    "Generate HTML docs for all JDK events"),
                new HelpEntry("jfrdoc -f text -o -",
                    "Print all events as plain text to stdout"),
                new HelpEntry("jfrdoc --name GarbageCollection",
                    "Filter events by name (regex)"),
                new HelpEntry("jfrdoc --category GC -o gc-events.txt",
                    "GC events as text (format inferred from extension)"),
                new HelpEntry("jbang -cp lib.jar jfrdoc --no-jdk",
                    "Document custom JFR events from a local JAR"),
                new HelpEntry("jbang --deps dev.tamboui:tamboui-core:0.3.0 jfrdoc --no-jdk",
                    "Document JFR events from a Maven dependency"),
                new HelpEntry("jfrdoc --generate-since 11,17,21,24,25",
                    "Regenerate version history by scanning JDKs via jbang"),
                new HelpEntry("jfrdoc --generate-since 11-25",
                    "Same but with version range expansion")
            );
            return Map.of("Examples", examples);
        }
    }

    @Option(name = "format", shortName = 'f', description = "Output format: json, text, or html (inferred from --output extension, default: html)")
    String format;

    @Option(name = "jdk", description = "Only jdk.* events (--no-jdk to exclude them)",
            hasValue = false, negatable = true)
    Boolean jdk;

    @OptionList(name = "name", shortName = 'n', description = "Regex filter on event name (repeatable, OR'd)")
    List<String> names;

    @OptionList(name = "label", shortName = 'l', description = "Regex filter on event label (repeatable, OR'd)")
    List<String> labels;

    @OptionList(name = "description", shortName = 'd', description = "Regex filter on event description (repeatable, OR'd)")
    List<String> descriptions;

    @OptionList(name = "category", shortName = 'c', description = "Regex filter on category (repeatable, OR'd)")
    List<String> categories;

    @OptionList(name = "attribute", description = "Regex filter on attribute name (repeatable, OR'd)")
    List<String> attributes;

    @OptionList(name = "attribute-type", description = "Regex filter on attribute type (repeatable, OR'd)")
    List<String> attributeTypes;

    @OptionList(name = "attribute-content-type", description = "Regex filter on attribute contentType (repeatable, OR'd)")
    List<String> attributeContentTypes;

    @OptionList(name = "attribute-description", description = "Regex filter on attribute description (repeatable, OR'd)")
    List<String> attributeDescriptions;

    @Option(name = "since", shortName = 's', description = "Path to since properties file (default: bundled jfr-since.properties)")
    String sinceFile;

    @Option(name = "output", shortName = 'o', description = "Output file (default: jfrdoc.<format>, use - for stdout)")
    String output;

    @Option(name = "generate-since", description = "Generate since properties by scanning JDK versions via jbang (e.g. 11,17,21,24,25)")
    String generateSince;

    /** event name -> [sinceJDK, removedJDK (or 0)] */
    Map<String, int[]> sinceData = new LinkedHashMap<>();
    /** JDK versions scanned (parsed from properties header) */
    List<Integer> scannedVersions = new ArrayList<>();

    @Override
    public CommandResult execute(CommandInvocation inv) {
        // Handle --generate-since first (standalone mode)
        if (generateSince != null && !generateSince.isEmpty()) {
            return generateSinceFile(generateSince);
        }

        String version = System.getProperty("java.version").split("[._+]")[0];
        String dist = System.getProperty("java.vendor", "openjdk").toLowerCase().contains("oracle") ? "oracle" : "openjdk";

        loadSinceData();
        registerClasspathEvents();

        // Pre-compile regex filters once
        List<Pattern> compiledNames = compilePatterns(names);
        List<Pattern> compiledLabels = compilePatterns(labels);
        List<Pattern> compiledDescriptions = compilePatterns(descriptions);
        List<Pattern> compiledCategories = compilePatterns(categories);
        List<Pattern> compiledAttributes = compilePatterns(attributes);
        List<Pattern> compiledAttributeTypes = compilePatterns(attributeTypes);
        List<Pattern> compiledAttributeContentTypes = compilePatterns(attributeContentTypes);
        List<Pattern> compiledAttributeDescriptions = compilePatterns(attributeDescriptions);

        List<EventType> types = FlightRecorder.getFlightRecorder().getEventTypes()
                .stream()
                .filter(e -> jdk == null || (jdk ? e.getName().startsWith("jdk.") : !e.getName().startsWith("jdk.")))
                .filter(e -> matchesAny(compiledNames, e.getName()))
                .filter(e -> matchesAny(compiledLabels, e.getLabel()))
                .filter(e -> matchesAny(compiledDescriptions, e.getDescription()))
                .filter(e -> compiledCategories == null ||
                        e.getCategoryNames().stream().anyMatch(cat -> matchesAny(compiledCategories, cat)))
                .filter(e -> compiledAttributes == null ||
                        e.getFields().stream().anyMatch(f -> matchesAny(compiledAttributes, f.getName())))
                .filter(e -> compiledAttributeTypes == null ||
                        e.getFields().stream().anyMatch(f -> matchesAny(compiledAttributeTypes, f.getTypeName())))
                .filter(e -> compiledAttributeContentTypes == null ||
                        e.getFields().stream().anyMatch(f -> matchesAny(compiledAttributeContentTypes, f.getContentType())))
                .filter(e -> compiledAttributeDescriptions == null ||
                        e.getFields().stream().anyMatch(f -> matchesAny(compiledAttributeDescriptions, f.getDescription())))
                .sorted(Comparator.comparing(EventType::getName))
                .collect(Collectors.toList());

        // Infer format from output extension if not explicitly set
        if (format == null && output != null && !output.equals("-")) {
            if (output.endsWith(".html") || output.endsWith(".htm")) format = "html";
            else if (output.endsWith(".txt") || output.endsWith(".text")) format = "text";
            else if (output.endsWith(".json")) format = "json";
        }
        if (format == null) format = "html";
        String ext = format.equals("text") ? "txt" : format.equals("html") ? "html" : "json";
        try (PrintStream out = openOutput(output, "jfrdoc." + ext)) {
            switch (format) {
                case "text": printText(out, types, version, dist); break;
                case "html": printHtml(out, types, version, dist); break;
                default:     printJson(out, types, version, dist); break;
            }
        } catch (IOException e) {
            System.err.println("Error writing output: " + e.getMessage());
            return CommandResult.FAILURE;
        }
        System.err.println(dist + " " + version + " — " + types.size() + " events"
                + (sinceData.isEmpty() ? "" : ", " + sinceData.size() + " with version history")
                + (!eventSources.isEmpty() ? ", " + eventSources.size() + " from classpath" : ""));
        return CommandResult.SUCCESS;
    }

    static PrintStream openOutput(String output, String defaultFile) throws IOException {
        if ("-".equals(output)) return System.out;
        String file = output != null ? output : defaultFile;
        System.err.println("Writing " + file);
        return new PrintStream(Files.newOutputStream(Path.of(file)), true, "UTF-8");
    }

    String sinceLabel(String eventName) {
        int[] v = sinceData.get(eventName);
        if (v == null) return null;
        return v[1] > 0 ? "since " + v[0] + ", removed " + v[1] : "since " + v[0];
    }

    int sinceVersion(String eventName) {
        int[] v = sinceData.get(eventName);
        return v != null ? v[0] : 0;
    }

    int removedVersion(String eventName) {
        int[] v = sinceData.get(eventName);
        return v != null ? v[1] : 0;
    }

    void printText(PrintStream out, List<EventType> types, String version, String dist) {
        out.println(dist + " " + version + " — " + types.size() + " events\n");
        for (EventType et : types) {
            String src = eventSources.get(et.getName());
            String srcInfo = src != null ? " [" + sourceLabel(src) + "]" : " [jdk]";
            String sl = sinceLabel(et.getName());
            String sinceInfo = sl != null ? " (" + sl + ")" : "";
            out.println(et.getName() + (et.getLabel() != null ? " (" + et.getLabel() + ")" : "") + srcInfo + sinceInfo);
            if (et.getDescription() != null && !et.getDescription().isEmpty()) {
                out.println("  " + et.getDescription());
            }
            for (ValueDescriptor vd : et.getFields()) {
                out.println("  " + vd.getTypeName() + " " + vd.getName()
                        + (vd.getDescription() != null && !vd.getDescription().isEmpty() ? " — " + vd.getDescription() : ""));
            }
            out.println();
        }
    }

    void printHtml(PrintStream out, List<EventType> types, String version, String dist) {
        // Build indexes: category -> events, package -> events, type -> events, contentType -> events
        Map<String, List<EventType>> byCategory = new TreeMap<>();
        Map<String, List<EventType>> byPackage = new TreeMap<>();
        Map<String, List<EventType>> bySource = new TreeMap<>();
        Map<String, Set<String>> typeToEvents = new TreeMap<>();  // attribute type -> event names
        Map<String, Set<String>> contentTypeToEvents = new TreeMap<>(); // contentType -> event names

        for (EventType et : types) {
            // package = everything before last dot in name
            String name = et.getName();
            String pkg = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "(default)";
            byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(et);

            for (String cat : et.getCategoryNames()) {
                byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(et);
            }
            String src = sourceLabel(eventSources.get(name));
            bySource.computeIfAbsent(src, k -> new ArrayList<>()).add(et);
            for (ValueDescriptor vd : et.getFields()) {
                typeToEvents.computeIfAbsent(vd.getTypeName(), k -> new TreeSet<>()).add(name);
                String ct = vd.getContentType();
                if (ct != null && !ct.isEmpty()) {
                    contentTypeToEvents.computeIfAbsent(ct, k -> new TreeSet<>()).add(name);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>JFR Events — ").append(h(dist)).append(" ").append(h(version)).append("</title>\n");
        sb.append("<style>\n");
        sb.append(CSS);
        sb.append("</style>\n</head>\n<body>\n");

        // Header
        sb.append("<header><h1>JFR Events</h1>");
        sb.append("<p class=\"subtitle\">").append(h(dist)).append(" ").append(h(version));
        sb.append(" &mdash; ").append(types.size()).append(" events</p></header>\n");
        sb.append("<button class=\"theme-toggle\" id=\"theme-toggle\" title=\"Toggle dark/light/system\">\u263e</button>\n");

        // Active filters banner
        List<String> activeFilters = new ArrayList<>();
        if (jdk != null) activeFilters.add(jdk ? "--jdk" : "--no-jdk");
        if (names != null && !names.isEmpty()) names.forEach(v -> activeFilters.add("--name " + v));
        if (labels != null && !labels.isEmpty()) labels.forEach(v -> activeFilters.add("--label " + v));
        if (descriptions != null && !descriptions.isEmpty()) descriptions.forEach(v -> activeFilters.add("--description " + v));
        if (categories != null && !categories.isEmpty()) categories.forEach(v -> activeFilters.add("--category " + v));
        if (attributes != null && !attributes.isEmpty()) attributes.forEach(v -> activeFilters.add("--attribute " + v));
        if (attributeTypes != null && !attributeTypes.isEmpty()) attributeTypes.forEach(v -> activeFilters.add("--attribute-type " + v));
        if (attributeContentTypes != null && !attributeContentTypes.isEmpty()) attributeContentTypes.forEach(v -> activeFilters.add("--attribute-content-type " + v));
        if (attributeDescriptions != null && !attributeDescriptions.isEmpty()) attributeDescriptions.forEach(v -> activeFilters.add("--attribute-description " + v));
        if (!activeFilters.isEmpty()) {
            sb.append("<div class=\"filters-banner\"><strong>Active filters:</strong> ");
            for (String f : activeFilters) {
                sb.append("<code>").append(h(f)).append("</code> ");
            }
            sb.append("</div>\n");
        }

        // Nav
        sb.append("<nav id=\"toc\">\n");
        sb.append("<a href=\"#idx-events\">Events</a>\n");
        sb.append("<a href=\"#idx-categories\">Categories</a>\n");
        sb.append("<a href=\"#idx-packages\">Packages</a>\n");
        sb.append("<a href=\"#idx-types\">Attribute Types</a>\n");
        sb.append("<a href=\"#idx-content-types\">Content Types</a>\n");
        sb.append("<a href=\"#idx-sources\">Sources</a>\n");
        if (!scannedVersions.isEmpty() && !Boolean.FALSE.equals(jdk)) sb.append("<a href=\"#idx-versions\">Version Timeline</a>\n");
        sb.append("</nav>\n");

        // === Events (first — most used section) ===
        sb.append("<div class=\"search-box\"><input type=\"text\" id=\"search\" placeholder=\"Filter events...\" autofocus></div>\n");
        sb.append("<section id=\"idx-events\"><h2>Events").append(anchor("idx-events")).append("</h2>\n");
        for (EventType et : types) {
            String eName = et.getName();
            String ePkg = eName.contains(".") ? eName.substring(0, eName.lastIndexOf('.')) : "(default)";
            String evtId = "evt-" + slug(eName);
            sb.append("<div class=\"event\" id=\"").append(evtId).append("\">\n");
            sb.append("<h3>").append(h(eName)).append(anchor(evtId)).append("</h3>\n");
            if (et.getLabel() != null && !et.getLabel().isEmpty()) {
                sb.append("<div class=\"event-label\">").append(h(et.getLabel())).append("</div>\n");
            }
            if (et.getDescription() != null && !et.getDescription().isEmpty()) {
                sb.append("<p class=\"event-desc\">").append(h(et.getDescription())).append("</p>\n");
            }
            sb.append("<div class=\"tags\">\n");
            String eSrc = sourceLabel(eventSources.get(eName));
            sb.append("<a class=\"tag tag-src\" href=\"#src-").append(slug(eSrc)).append("\">").append(h(eSrc)).append("</a>\n");
            sb.append("<a class=\"tag tag-pkg\" href=\"#pkg-").append(slug(ePkg)).append("\">").append(h(ePkg)).append("</a>\n");
            String eSince = sinceLabel(eName);
            if (eSince != null) {
                boolean removed = removedVersion(eName) > 0;
                sb.append("<span class=\"tag tag-since").append(removed ? " tag-removed" : "").append("\">").append(h(eSince)).append("</span>\n");
            }
            for (String cat : et.getCategoryNames()) {
                sb.append("<a class=\"tag tag-cat\" href=\"#cat-").append(slug(cat)).append("\">").append(h(cat)).append("</a>\n");
            }
            sb.append("</div>\n");
            List<ValueDescriptor> eFields = et.getFields();
            if (!eFields.isEmpty()) {
                sb.append("<table><thead><tr><th>Name</th><th>Type</th><th>Content Type</th><th>Description</th></tr></thead>\n<tbody>\n");
                for (ValueDescriptor vd : eFields) {
                    sb.append("<tr>");
                    sb.append("<td><code>").append(h(vd.getName())).append("</code></td>");
                    sb.append("<td><a href=\"#type-").append(slug(vd.getTypeName())).append("\"><code>").append(h(vd.getTypeName())).append("</code></a></td>");
                    String eCt = vd.getContentType();
                    if (eCt != null && !eCt.isEmpty()) {
                        sb.append("<td><a href=\"#ct-").append(slug(eCt)).append("\"><code>").append(h(eCt)).append("</code></a></td>");
                    } else {
                        sb.append("<td></td>");
                    }
                    sb.append("<td>").append(h(vd.getDescription())).append("</td>");
                    sb.append("</tr>\n");
                }
                sb.append("</tbody></table>\n");
            }
            sb.append("</div>\n");
        }
        sb.append("</section>\n");

        // === Index sections ===
        indexSection(sb, "idx-categories", "Categories", "cat",
                "JFR events are organized into categories that group related functionality "
                + "(e.g. GC, Compiler, Runtime). An event can belong to multiple categories.",
                byCategory, EventType::getName, false, null);
        indexSection(sb, "idx-packages", "Packages", "pkg",
                "Event names are qualified by package. JDK built-in events use <code>jdk.*</code>. "
                + "Third-party libraries and application events use their own namespace.",
                byPackage, EventType::getName, false, null);
        indexSection(sb, "idx-types", "Attribute Types", "type",
                "The Java/JFR types used in event fields. Primitives like <code>long</code> and <code>boolean</code> "
                + "appear alongside structured types like <code>jdk.types.StackTrace</code> and <code>java.lang.Thread</code>. "
                + "Click an event name to jump to its definition.",
                typeToEvents, Function.identity(), true, null);
        indexSection(sb, "idx-content-types", "Content Types", "ct",
                "Content types describe the semantic meaning of a field value. "
                + "For example, a <code>long</code> field with content type <code>jdk.jfr.Timestamp</code> holds an epoch timestamp, "
                + "while <code>jdk.jfr.DataAmount</code> means the value is a byte count. "
                + "JFR tooling uses content types for formatting and unit display.",
                contentTypeToEvents, Function.identity(), true, (cardSb, key) -> {
                    String[] meta = contentTypeMeta(key);
                    if (meta[0] != null) cardSb.append("<div class=\"ct-label\">").append(h(meta[0])).append("</div>\n");
                    if (meta[1] != null) cardSb.append("<p class=\"ct-desc\">").append(h(meta[1])).append("</p>\n");
                });
        indexSection(sb, "idx-sources", "Sources", "src",
                "Which JAR or module each event originates from. "
                + "Built-in JDK events show as <code>jdk</code>; library events show their JAR filename.",
                bySource, EventType::getName, true, (cardSb, key) -> {
                    String fullPath = bySource.get(key).stream()
                        .map(et -> eventSources.get(et.getName()))
                        .filter(s -> s != null).findFirst().orElse("jdk runtime");
                    if (!key.contains(":"))
                        cardSb.append("<p class=\"source-path\">").append(h(fullPath)).append("</p>\n");
                });

        // === Version Timeline (only for jdk events) ===
        if (!scannedVersions.isEmpty() && !Boolean.FALSE.equals(jdk)) {
            int minVer = scannedVersions.get(0);
            int maxVer = scannedVersions.get(scannedVersions.size() - 1);
            int span = maxVer - minVer;
            sb.append("<section id=\"idx-versions\"><h2>Version Timeline").append(anchor("idx-versions")).append("</h2>\n");
            sb.append("<p class=\"info\">Shows the JDK version range each event is available in. ");
            sb.append("Green bars indicate availability; red bars indicate the event was removed.</p>\n");
            // Version tick marks
            sb.append("<div class=\"timeline-header\"><div class=\"timeline-name\"></div><div class=\"timeline-track\">\n");
            for (int ver : scannedVersions) {
                double pct = span > 0 ? (ver - minVer) * 100.0 / span : 0;
                sb.append("<span class=\"timeline-tick\" style=\"left:").append(String.format("%.1f", pct)).append("%\">").append(ver).append("</span>\n");
            }
            sb.append("</div></div>\n");
            // Event rows
            Set<String> allNames = new TreeSet<>();
            allNames.addAll(sinceData.keySet());
            for (EventType et : types) allNames.add(et.getName());
            sb.append("<div class=\"timeline-rows\">\n");
            for (String name : allNames) {
                int[] v = sinceData.get(name);
                if (v == null) continue;
                int since = v[0];
                // Find last present version
                int lastPresent = since;
                for (int sv : scannedVersions) {
                    if (presentIn(name, sv)) lastPresent = sv;
                }
                boolean removed = v[1] > 0;
                double left = span > 0 ? (since - minVer) * 100.0 / span : 0;
                double right = span > 0 ? (lastPresent - minVer) * 100.0 / span : 0;
                double width = Math.max(right - left, 1.5); // min width for single-version events
                sb.append("<div class=\"timeline-row\"><div class=\"timeline-name\">")
                  .append("<a href=\"#evt-").append(slug(name)).append("\">").append(h(name)).append("</a></div>");
                sb.append("<div class=\"timeline-track\">")
                  .append("<div class=\"timeline-bar").append(removed ? " timeline-removed" : "").append("\" ")
                  .append("style=\"left:").append(String.format("%.1f", left)).append("%;width:").append(String.format("%.1f", width)).append("%\">");
                sb.append("<span class=\"timeline-label-start\">").append(since).append("</span>");
                if (lastPresent != since) {
                    sb.append("<span class=\"timeline-label-end\">").append(lastPresent).append("</span>");
                }
                sb.append("</div></div></div>\n");
            }
            sb.append("</div></section>\n");
        }
        // Back to top + script
        sb.append("<script>\n").append(JS).append("\n</script>\n");
        sb.append("</body>\n</html>");
        out.println(sb);
    }

    static final String CSS = """
        :root {
            --bg: #0d1117; --bg2: #161b22; --bg3: #21262d; --border: #30363d;
            --fg: #e6edf3; --fg2: #8b949e; --accent: #58a6ff; --accent2: #3fb950;
            --tag-pkg: #1f6feb; --tag-cat: #238636;
        }
        @media (prefers-color-scheme: light) {
            :root {
                --bg: #ffffff; --bg2: #f6f8fa; --bg3: #eaeef2; --border: #d0d7de;
                --fg: #1f2328; --fg2: #656d76; --accent: #0969da; --accent2: #1a7f37;
                --tag-pkg: #ddf4ff; --tag-cat: #dafbe1;
            }
        }
        [data-theme="light"] {
            --bg: #ffffff; --bg2: #f6f8fa; --bg3: #eaeef2; --border: #d0d7de;
            --fg: #1f2328; --fg2: #656d76; --accent: #0969da; --accent2: #1a7f37;
            --tag-pkg: #ddf4ff; --tag-cat: #dafbe1;
        }
        [data-theme="dark"] {
            --bg: #0d1117; --bg2: #161b22; --bg3: #21262d; --border: #30363d;
            --fg: #e6edf3; --fg2: #8b949e; --accent: #58a6ff; --accent2: #3fb950;
            --tag-pkg: #1f6feb; --tag-cat: #238636;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif;
               background: var(--bg); color: var(--fg); line-height: 1.6; padding: 0; }
        header { background: var(--bg2); border-bottom: 1px solid var(--border); padding: 2rem 2rem 1.5rem;
                 text-align: center; }
        header h1 { font-size: 2rem; font-weight: 600; }
        .subtitle { color: var(--fg2); font-size: 1.1rem; margin-top: .3rem; }
        nav#toc { position: sticky; top: 0; z-index: 100; background: var(--bg2);
                  border-bottom: 1px solid var(--border); padding: .75rem 2rem;
                  display: flex; gap: 1.5rem; flex-wrap: wrap; }
        nav#toc a { color: var(--accent); text-decoration: none; font-weight: 500; font-size: .95rem; }
        nav#toc a:hover { text-decoration: underline; }
        section { max-width: 1200px; margin: 2rem auto; padding: 0 2rem; }
        section > h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: .5rem;
                       padding-bottom: .5rem; border-bottom: 2px solid var(--accent); }
        [id] { scroll-margin-top: 4rem; }
        .info { color: var(--fg2); margin-bottom: 1.5rem; font-size: .95rem; }
        .info code { background: var(--bg3); padding: .15em .4em; border-radius: 4px; font-size: .9em; }
        .index-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 1rem; }
        .index-card { background: var(--bg2); border: 1px solid var(--border); border-radius: 8px;
                      padding: 1rem; }
        .index-card h3 { font-size: 1rem; margin-bottom: .5rem; display: flex; align-items: center; gap: .5rem; }
        .index-card h3 code { font-size: .95rem; }
        .ct-label { font-weight: 600; color: var(--fg); font-size: .9rem; margin: .25rem 0 .1rem; }
        .ct-desc { color: var(--fg2); font-size: .85rem; margin-bottom: .5rem; }
        .badge { background: var(--accent); color: #fff; font-size: .75rem; font-weight: 600;
                 padding: .1em .5em; border-radius: 10px; }
        .index-card ul { list-style: none; max-height: 200px; overflow-y: auto; }
        .index-card li { font-size: .85rem; padding: .1rem 0; }
        .index-card a { color: var(--accent); text-decoration: none; }
        .index-card a:hover { text-decoration: underline; }
        .event { background: var(--bg2); border: 1px solid var(--border); border-radius: 8px;
                 padding: 1.25rem; margin-bottom: 1rem; }
        .event h3 { font-size: 1.15rem; font-weight: 600; }
        .anchor { text-decoration: none; color: var(--fg2); opacity: 0; margin-left: .4rem;
                  font-weight: 400; transition: opacity .15s; }
        h2:hover .anchor, h3:hover .anchor, .anchor:focus { opacity: 1; }
        .index-card h3 .anchor { font-size: .85rem; }
        .event-label { color: var(--fg2); font-size: .95rem; margin: .15rem 0; }
        .event-desc { margin: .5rem 0; }
        .tags { display: flex; flex-wrap: wrap; gap: .4rem; margin: .75rem 0; }
        .tag { font-size: .8rem; padding: .2em .6em; border-radius: 12px; text-decoration: none; font-weight: 500; }
        .tag-pkg { background: var(--tag-pkg); color: #fff; }
        .tag-cat { background: var(--tag-cat); color: #fff; }
        .tag-src { background: #8957e5; color: #fff; }
        .tag-since { background: #1a7f37; color: #fff; }
        .tag-removed { background: #da3633; color: #fff; }
        .timeline-header { display: flex; align-items: flex-end; padding-bottom: .25rem;
                           border-bottom: 1px solid var(--border); margin-bottom: .5rem; }
        .timeline-rows { max-height: 70vh; overflow-y: auto; }
        .timeline-row { display: flex; align-items: center; padding: .15rem 0;
                        border-bottom: 1px solid var(--border); }
        .timeline-row:hover { background: var(--bg2); }
        .timeline-name { width: 300px; min-width: 200px; flex-shrink: 0; font-size: .85rem;
                         white-space: nowrap; overflow: hidden; text-overflow: ellipsis; padding-right: .5rem; }
        .timeline-name a { color: var(--accent); text-decoration: none; }
        .timeline-name a:hover { text-decoration: underline; }
        .timeline-track { flex: 1; position: relative; height: 1.4rem; }
        .timeline-tick { position: absolute; transform: translateX(-50%); font-size: .7rem;
                         color: var(--fg2); }
        .timeline-bar { position: absolute; top: .2rem; height: 1rem; border-radius: 4px;
                        background: var(--accent2); display: flex; align-items: center;
                        justify-content: space-between; padding: 0 .3rem; min-width: 1.5rem; }
        .timeline-removed { background: var(--accent2); background: linear-gradient(
                            to right, var(--accent2) 80%, #da3633 100%); }
        .timeline-label-start, .timeline-label-end { font-size: .65rem; font-weight: 600;
                        color: #fff; white-space: nowrap; }
        @media (max-width: 768px) { .timeline-name { width: 150px; min-width: 100px; } }
        .source-path { color: var(--fg2); font-size: .8rem; word-break: break-all; margin-bottom: .5rem; }
        @media (prefers-color-scheme: light) {
            .tag-pkg { color: #0969da; } .tag-cat { color: #1a7f37; }
            .tag-src { background: #f3e8ff; color: #8957e5; }
        }
        [data-theme="light"] .tag-pkg { color: #0969da; }
        [data-theme="light"] .tag-cat { color: #1a7f37; }
        [data-theme="light"] .tag-src { background: #f3e8ff; color: #8957e5; }
        [data-theme="dark"] .tag-pkg { color: #fff; }
        [data-theme="dark"] .tag-cat { color: #fff; }
        [data-theme="dark"] .tag-src { background: #8957e5; color: #fff; }
        .theme-toggle { background: none; border: 1px solid var(--border); color: var(--fg);
                         cursor: pointer; font-size: 1.2rem; padding: .3rem .6rem; border-radius: 6px;
                         line-height: 1; position: fixed; top: .75rem; right: 1rem; z-index: 200; }
        table { width: 100%; border-collapse: collapse; margin-top: .75rem; font-size: .9rem; }
        th { text-align: left; padding: .5rem .75rem; background: var(--bg3); border-bottom: 2px solid var(--border);
             font-weight: 600; font-size: .85rem; color: var(--fg2); text-transform: uppercase; letter-spacing: .03em; }
        td { padding: .4rem .75rem; border-bottom: 1px solid var(--border); }
        td code { background: var(--bg3); padding: .1em .35em; border-radius: 3px; font-size: .9em; }
        td a { color: var(--accent); text-decoration: none; }
        td a:hover { text-decoration: underline; }
        tr:hover { background: var(--bg3); }
        @media (max-width: 768px) {
            section { padding: 0 1rem; }
            .index-grid { grid-template-columns: 1fr; }
            table { font-size: .8rem; }
            th, td { padding: .3rem .5rem; }
        }
        .filters-banner { background: var(--bg3); border-bottom: 1px solid var(--border);
                          padding: .6rem 2rem; font-size: .9rem; color: var(--fg2); display: flex;
                          flex-wrap: wrap; gap: .4rem; align-items: center; }
        .filters-banner code { background: var(--bg); padding: .15em .5em; border-radius: 4px;
                               font-size: .85em; color: var(--accent); }
        .search-box { display: flex; justify-content: center; padding: 1rem 2rem 0; }
        .search-box input { width: 100%; max-width: 600px; padding: .6rem 1rem; font-size: 1rem;
                            background: var(--bg2); color: var(--fg); border: 1px solid var(--border);
                            border-radius: 8px; outline: none; }
        .search-box input:focus { border-color: var(--accent); }
        .hidden { display: none !important; }
        """;

    static final String JS = """
        document.addEventListener('DOMContentLoaded', () => {
            const input = document.getElementById('search');
            const events = document.querySelectorAll('.event');
            const timelineRows = document.querySelectorAll('.timeline-row');
            input.addEventListener('input', () => {
                const q = input.value.toLowerCase();
                events.forEach(el => {
                    el.classList.toggle('hidden', q && !el.textContent.toLowerCase().includes(q));
                });
                timelineRows.forEach(el => {
                    el.classList.toggle('hidden', q && !el.textContent.toLowerCase().includes(q));
                });
            });

            const btn = document.getElementById('theme-toggle');
            const icons = { dark: '\u2600', light: '\u263e', system: '\u2699' };
            const cycle = { dark: 'light', light: 'system', system: 'dark' };
            const stored = localStorage.getItem('jfr-doc-theme') || 'system';
            function apply(mode) {
                if (mode === 'system') {
                    document.documentElement.removeAttribute('data-theme');
                } else {
                    document.documentElement.setAttribute('data-theme', mode);
                }
                btn.textContent = icons[mode];
                btn.title = 'Theme: ' + mode + ' (click to change)';
            }
            apply(stored);
            btn.addEventListener('click', () => {
                const current = localStorage.getItem('jfr-doc-theme') || 'system';
                const next = cycle[current];
                localStorage.setItem('jfr-doc-theme', next);
                apply(next);
            });
        });
        """;

    /** Resolve @Label and @Description from a content type annotation class. Returns [label, description]. */
    static String[] contentTypeMeta(String contentType) {
        try {
            Class<?> cls = Class.forName(contentType);
            Label label = cls.getAnnotation(Label.class);
            Description desc = cls.getAnnotation(Description.class);
            return new String[]{
                label != null ? label.value() : null,
                desc != null ? desc.value() : null
            };
        } catch (Throwable t) {
            return new String[]{null, null};
        }
    }

    static String anchor(String id) {
        return " <a class=\"anchor\" href=\"#" + id + "\">&#x1f517;</a>";
    }

    static String slug(String s) {
        return s.replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("^-|-$", "").toLowerCase();
    }

    static String h(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }


    /** Render an index section with cards. Handles both Map<String,List<EventType>> and Map<String,Set<String>>. */
    @SuppressWarnings("unchecked")
    static <V> void indexSection(StringBuilder sb, String sectionId, String title, String idPrefix,
            String description, Map<String, ? extends Collection<V>> data,
            Function<V, String> nameExtractor, boolean codeHeading,
            BiConsumer<StringBuilder, String> cardExtra) {
        sb.append("<section id=\"").append(sectionId).append("\"><h2>").append(title).append(anchor(sectionId)).append("</h2>\n");
        sb.append("<p class=\"info\">").append(description).append("</p>\n");
        sb.append("<div class=\"index-grid\">\n");
        for (var entry : data.entrySet()) {
            String key = entry.getKey();
            String cardId = idPrefix + "-" + slug(key);
            sb.append("<div class=\"index-card\" id=\"").append(cardId).append("\">\n");
            sb.append("<h3>");
            if (codeHeading) sb.append("<code>").append(h(key)).append("</code>");
            else sb.append(h(key));
            sb.append(" <span class=\"badge\">").append(entry.getValue().size()).append("</span>").append(anchor(cardId)).append("</h3>\n");
            if (cardExtra != null) cardExtra.accept(sb, key);
            sb.append("<ul>\n");
            for (V item : entry.getValue()) {
                String name = nameExtractor.apply(item);
                sb.append("<li><a href=\"#evt-").append(slug(name)).append("\">").append(h(name)).append("</a></li>\n");
            }
            sb.append("</ul></div>\n");
        }
        sb.append("</div></section>\n");
    }
    void printJson(PrintStream out, List<EventType> types, String version, String dist) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n\t\"version\": \"").append(version).append("\",\n");
        sb.append("\t\"distribution\": \"").append(dist).append("\",\n");
        sb.append("\t\"events\": [\n");

        for (int i = 0; i < types.size(); i++) {
            EventType et = types.get(i);
            sb.append("\t\t{\n");
            sb.append("\t\t\t\"name\": \"").append(esc(et.getName())).append("\",\n");
            sb.append("\t\t\t\"description\": \"").append(esc(et.getDescription())).append("\",\n");
            sb.append("\t\t\t\"label\": \"").append(esc(et.getLabel())).append("\",\n");
            String jsonSrc = eventSources.get(et.getName());
            sb.append("\t\t\t\"source\": \"").append(esc(jsonSrc != null ? jsonSrc : "jdk")).append("\",\n");
            String jsonGav = sourceLabel(jsonSrc);
            if (jsonGav.contains(":")) {
                sb.append("\t\t\t\"source-gav\": \"").append(esc(jsonGav)).append("\",\n");
            }
            int sv = sinceVersion(et.getName());
            if (sv > 0) sb.append("\t\t\t\"since\": ").append(sv).append(",\n");
            int rv = removedVersion(et.getName());
            if (rv > 0) sb.append("\t\t\t\"removed\": ").append(rv).append(",\n");
            sb.append("\t\t\t\"categories\": [");
            List<String> cats = et.getCategoryNames();
            for (int c = 0; c < cats.size(); c++) {
                if (c > 0) sb.append(", ");
                sb.append("\"").append(esc(cats.get(c))).append("\"");
            }
            sb.append("],\n\t\t\t\"attributes\": [\n");
            List<ValueDescriptor> fields = et.getFields();
            for (int f = 0; f < fields.size(); f++) {
                ValueDescriptor vd = fields.get(f);
                sb.append("\t\t\t\t{\"name\": \"").append(esc(vd.getName()));
                sb.append("\", \"type\": \"").append(esc(vd.getTypeName()));
                sb.append("\", \"contentType\": \"").append(esc(vd.getContentType()));
                sb.append("\", \"description\": \"").append(esc(vd.getDescription())).append("\"}");
                if (f < fields.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("\t\t\t]\n\t\t}");
            if (i < types.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("\t]\n}");
        out.println(sb);
    }

    void loadSinceData() {
        try {
            List<String> lines;
            if (sinceFile != null) {
                lines = Files.readAllLines(Path.of(sinceFile));
            } else {
                try (var is = getClass().getResourceAsStream("/jfr-since.properties")) {
                    if (is == null) return;
                    lines = new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.toList());
                }
            }
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String name = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (name.equals("_versions")) {
                    for (String v : val.split(",")) {
                        try { scannedVersions.add(Integer.parseInt(v.trim())); } catch (NumberFormatException ignored) {}
                    }
                    continue;
                }
                String[] parts = val.split(":");
                int since = Integer.parseInt(parts[0].trim());
                int removed = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
                sinceData.put(name, new int[]{since, removed});
            }
        } catch (IOException e) {
            // ponytail: no since data is fine, just means no version annotations
        }
    }

    /** Check if an event was present in a specific JDK version. */
    boolean presentIn(String eventName, int version) {
        int[] v = sinceData.get(eventName);
        if (v == null) return false;
        return version >= v[0] && (v[1] == 0 || version < v[1]);
    }

    /** Expand "11-17,21,25" into ["11","12",...,"17","21","25"] */
    static List<String> expandVersions(String spec) {
        List<String> result = new ArrayList<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] range = part.split("-", 2);
                int from = Integer.parseInt(range[0].trim());
                int to = Integer.parseInt(range[1].trim());
                for (int v = from; v <= to; v++) result.add(String.valueOf(v));
            } else {
                result.add(part);
            }
        }
        return result;
    }

    CommandResult generateSinceFile(String versions) {
        List<String> jdkVersions = expandVersions(versions);
        Map<String, Set<String>> eventsByVersion = new LinkedHashMap<>();
        for (String v : jdkVersions) {
            v = v.trim();
            try {
                ProcessBuilder pb = new ProcessBuilder("jbang", "--java", v, "-c",
                        "jdk.jfr.FlightRecorder.getFlightRecorder().getEventTypes().stream()"
                        + ".map(e -> e.getName()).sorted().forEach(System.out::println);");
                pb.redirectErrorStream(false);
                Process proc = pb.start();
                Set<String> events = new TreeSet<>();
                try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("jdk.") || line.contains(".")) events.add(line.trim());
                    }
                }
                proc.waitFor();
                if (events.isEmpty()) {
                    System.err.println("JDK " + v + ": skipped (no events found, JDK may not be available)");
                } else {
                    eventsByVersion.put(v, events);
                    System.err.println("JDK " + v + ": " + events.size() + " events");
                }
            } catch (Exception e) {
                System.err.println("Failed to scan JDK " + v + ": " + e.getMessage());
            }
        }
        // Build since/removed map
        Set<String> allEvents = new TreeSet<>();
        eventsByVersion.values().forEach(allEvents::addAll);
        try (PrintStream out = openOutput(output, "jfr-since.properties")) {
        out.println("# JFR event version history");
        out.println("# Format: eventName=sinceJDK[:removedJDK]");
        out.println("# Generated: " + java.time.LocalDate.now());
        List<String> scanned = new ArrayList<>(eventsByVersion.keySet());
        out.println("_versions=" + String.join(",", scanned));
        out.println();
        for (String event : allEvents) {
            String since = null;
            String removed = null;
            for (String v : scanned) {
                boolean present = eventsByVersion.getOrDefault(v, Set.of()).contains(event);
                if (present) {
                    if (since == null) since = v;
                    removed = null; // present again
                } else if (since != null && removed == null) {
                    removed = v;
                }
            }
            if (since != null) {
                out.println(event + "=" + since + (removed != null ? ":" + removed : ""));
            }
        }
        } catch (IOException e) {
            System.err.println("Error writing output: " + e.getMessage());
            return CommandResult.FAILURE;
        }
        return CommandResult.SUCCESS;
    }

    /** event name -> source jar/dir path */
    static Map<String, String> eventSources = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    static void registerClasspathEvents() {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(System.getProperty("path.separator"))) {
            Path p = Path.of(entry);
            if (!Files.exists(p)) continue;
            try {
                if (entry.endsWith(".jar")) {
                    try (FileSystem fs = FileSystems.newFileSystem(p)) {
                        scanPath(fs.getPath("/"), entry);
                    }
                } else if (Files.isDirectory(p)) {
                    scanPath(p, entry);
                }
            } catch (Exception e) { /* skip */ }
        }
    }

    @SuppressWarnings("unchecked")
    static void scanPath(Path root, String source) throws IOException {
        Files.walk(root)
            .filter(f -> f.toString().endsWith(".class") && !f.toString().contains("module-info"))
            .forEach(f -> {
                try {
                    String rel = root.relativize(f).toString();
                    String className = rel.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "");
                    Class<?> cls = Class.forName(className, false, jfrdoc.class.getClassLoader());
                    if (Event.class.isAssignableFrom(cls) && cls != Event.class) {
                        FlightRecorder.register((Class<? extends Event>) cls);
                        Name nameAnn = cls.getAnnotation(Name.class);
                        String eventName = nameAnn != null ? nameAnn.value() : className;
                        eventSources.put(eventName, source);
                    }
                } catch (Throwable t) { /* skip — linkage errors, missing deps, etc */ }
            });
    }

    /** Try to extract Maven coordinates from path, fall back to jar filename */
    static String sourceLabel(String source) {
        if (source == null) return "jdk";
        // Maven: .m2/repository/{group}/{artifact}/{version}/{file}.jar
        var m = Pattern.compile(".*/\\.m2/repository/(.*)/([^/]*)/([^/]*)/[^/]*\\.jar$").matcher(source);
        if (m.matches()) return m.group(1).replace('/', '.') + ":" + m.group(2) + ":" + m.group(3);
        // Gradle: .gradle/caches/modules-2/files-2.1/{group}/{artifact}/{version}/{hash}/{file}.jar
        var g = Pattern.compile(".*/\\.gradle/caches/modules-2/files-2.1/([^/]*)/([^/]*)/([^/]*)/.*").matcher(source);
        if (g.matches()) return g.group(1) + ":" + g.group(2) + ":" + g.group(3);
        return Path.of(source).getFileName().toString();
    }

    /** Compile regex list once; returns null for empty/null input (meaning no filter). */
    static List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return null;
        return patterns.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .collect(Collectors.toList());
    }

    /** null = no filter (pass). Otherwise OR across pre-compiled patterns. */
    static boolean matchesAny(List<Pattern> patterns, String value) {
        if (patterns == null) return true;
        if (value == null) value = "";
        for (Pattern p : patterns) {
            if (p.matcher(value).find()) return true;
        }
        return false;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\b", "\\b").replace("\f", "\\f");
    }

    public static void main(String[] args) {
        if (args.length == 0) args = new String[]{"--help"};
        AeshRuntimeRunner.builder().command(jfrdoc.class).args(args).execute();
    }
}
