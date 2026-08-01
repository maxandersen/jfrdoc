# jfrdoc

Generate documentation for JFR (Java Flight Recorder) events available in your JDK and classpath.

Produces HTML, JSON, or plain text output with:
- Event names, labels, descriptions, and field details
- Source tracking (JDK built-in vs classpath JARs with Maven/Gradle GAV resolution)
- JDK version history — which version each event was introduced and optionally removed
- HTML output includes searchable event cards, category/package/type indexes, and a version timeline

## Usage

Requires [JBang](https://jbang.dev):

```bash
# Generate HTML documentation (default)
jbang jfrdoc.java

# All JDK events to stdout as text
jbang jfrdoc.java -f text -o -

# JSON output
jbang jfrdoc.java -o events.json

# Filter by event name (regex)
jbang jfrdoc.java --name "GarbageCollection"

# Only non-JDK events (from classpath)
jbang jfrdoc.java --no-jdk

# Include classpath JARs that contain JFR events
jbang -cp mylib.jar jfrdoc.java
```

Output format is inferred from the `--output` file extension, or set explicitly with `--format`.

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
jbang jfrdoc.java --generate-since 11,17,21,24,25

# Ranges are supported
jbang jfrdoc.java --generate-since 11-25

# Custom output path
jbang jfrdoc.java --generate-since 11,17,21,24,25 -o jfr-since.properties
```

Use `--since <file>` to load a custom version history file.

## Output Formats

| Format | Default file | Description |
|--------|-------------|-------------|
| `html` | `jfrdoc.html` | Searchable page with event cards, indexes, dark/light theme, version timeline |
| `json` | `jfrdoc.json` | Machine-readable with all event metadata |
| `text` | `jfrdoc.txt` | Plain text summary |

Use `-o -` to write to stdout instead of a file.

## Examples

```bash
# What GC events are available?
jbang jfrdoc.java -f text -o - --category GC

# Find events with a StackTrace field
jbang jfrdoc.java -f text -o - --attribute-type StackTrace

# Events added in JDK 21+ (filter on since data)
jbang jfrdoc.java --name "Virtual|Continuation|Finalizer"

# Full HTML docs for everything
jbang jfrdoc.java
```
