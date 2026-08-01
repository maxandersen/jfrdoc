# jfrdoc

Generate documentation for JFR (Java Flight Recorder) events available in your JDK and classpath.

Produces HTML, JSON, or plain text output with:
- Event names, labels, descriptions, and field details
- Source tracking (JDK built-in vs classpath JARs with Maven/Gradle GAV resolution)
- JDK version history — which version each event was introduced and optionally removed
- HTML output includes searchable event cards, category/package/type indexes, and a version timeline

## Install

Requires [JBang](https://jbang.dev):

```bash
# Run directly
jbang jfrdoc@maxandersen

# Or install as a command
jbang app install jfrdoc@maxandersen
jfrdoc --help
```

## Usage

```bash
# Generate HTML documentation for all JDK events (default)
jfrdoc

# Plain text to stdout
jfrdoc -f text -o -

# JSON output
jfrdoc -o events.json

# Filter by event name (regex)
jfrdoc --name "GarbageCollection"

# Only non-JDK events (from classpath)
jfrdoc --no-jdk
```

Output format is inferred from the `--output` file extension, or set explicitly with `--format`.

## Documenting Library JFR Events

Libraries that define custom JFR events can be documented by adding them to the classpath:

```bash
# Document JFR events from a Maven dependency
jbang --deps dev.tamboui:tamboui-core:0.3.0 jfrdoc@maxandersen --no-jdk

# Include a local JAR
jbang -cp mylib.jar jfrdoc@maxandersen

# Mix JDK and library events
jbang --deps dev.tamboui:tamboui-core:0.3.0 jfrdoc@maxandersen

# Multiple libraries
jbang --deps dev.tamboui:tamboui-core:0.3.0,io.quarkus:quarkus-core:3.21.0 jfrdoc@maxandersen --no-jdk
```

## Filters

All filters are regex, case-insensitive, repeatable (OR'd):

| Flag | Filters on |
|------|-----------|
| `-n, --name` | Event name (e.g. `jdk.GarbageCollection`) |
| `-l, --label` | Event label (e.g. `Garbage Collection`) |
| `-d, --description` | Event description |
| `-c, --category` | Category (e.g. `GC`, `Runtime`) |
| `--attribute` | Field name |
| `--attribute-type` | Field type (e.g. `long`, `java.lang.Thread`) |
| `--attribute-content-type` | Field content type (e.g. `jdk.jfr.Timestamp`) |
| `--attribute-description` | Field description |
| `--jdk / --no-jdk` | Only/exclude `jdk.*` events |

## Version History

The bundled `jfr-since.properties` tracks when each `jdk.*` event was introduced (and removed) across JDK 11, 17, 21, 24, and 25. This data is shown in all output formats.

To regenerate or extend the version data:

```bash
# Regenerate for specific versions (downloads JDKs via jbang)
jfrdoc --generate-since 11,17,21,24,25

# Ranges are supported
jfrdoc --generate-since 11-25

# Custom output path
jfrdoc --generate-since 11,17,21,24,25 -o jfr-since.properties
```

Use `--since <file>` to load a custom version history file.

## Output Formats

| Format | Default file | Description |
|--------|-------------|-------------|
| `html` | `jfrdoc.html` | Searchable page with event cards, indexes, dark/light theme, version timeline |
| `json` | `jfrdoc.json` | Machine-readable with all event metadata |
| `text` | `jfrdoc.txt` | Plain text summary |

Use `-o -` to write to stdout instead of a file.
