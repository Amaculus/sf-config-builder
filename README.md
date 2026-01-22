# sf-config-builder

Manage Screaming Frog `.seospiderconfig` files programmatically.

## Installation

```bash
pip install sf-config-builder
```

### Requirements

- **Screaming Frog SEO Spider** must be installed (provides JARs for deserialization)
- Python 3.8+

## Quick Start

```python
from sfconfig import SFConfig

# Load existing config (auto-detects SF installation)
config = SFConfig.load("base.seospiderconfig")

# Or specify custom SF path
config = SFConfig.load("base.seospiderconfig", sf_path="D:/Apps/Screaming Frog SEO Spider")

# Configure for e-commerce audit
config.max_urls = 100000
config.rendering_mode = "JAVASCRIPT"

# Add custom extractions
config.add_extraction("Price", "//span[@class='price']")
config.add_extraction("SKU", "//span[@itemprop='sku']")
config.add_extraction("Stock", ".availability", selector_type="CSS")

# Add exclude patterns
config.add_exclude(r".*\.pdf$")
config.add_exclude(r".*/admin/.*")

# Save and run
config.save("client-audit.seospiderconfig")
config.run_crawl("https://example.com", output_folder="./results")
```

## Features

### Inspect Configs

```python
config = SFConfig.load("my.seospiderconfig")

# Get specific field
max_urls = config.get("mCrawlConfig.mMaxUrls")

# List all fields
for field in config.fields():
    print(f"{field['path']}: {field['value']}")

# Filter by prefix
crawl_fields = config.fields(prefix="mCrawlConfig")
```

### Modify Configs

```python
# Direct field access
config.set("mCrawlConfig.mMaxUrls", 100000)

# Convenience properties
config.max_urls = 100000
config.max_depth = 10
config.rendering_mode = "JAVASCRIPT"  # STATIC | JAVASCRIPT
config.robots_mode = "IGNORE"         # RESPECT | IGNORE
config.crawl_delay = 0.5
config.user_agent = "MyBot/1.0"
```

### Custom Extractions

```python
# Add extraction rules
config.add_extraction(
    name="Price",
    selector="//span[@class='price']",
    selector_type="XPATH",      # XPATH | CSS | REGEX
    extract_mode="TEXT"         # TEXT | HTML_ELEMENT | INNER_HTML
)

# List extractions
for ext in config.extractions:
    print(f"{ext['name']}: {ext['selector']}")

# Remove by name
config.remove_extraction("Price")

# Clear all
config.clear_extractions()
```

### Exclude/Include Patterns

```python
# Excludes (URLs matching these patterns are skipped)
config.add_exclude(r".*\.pdf$")
config.add_exclude(r".*/admin/.*")

# Includes (only URLs matching these are crawled)
config.add_include(r".*/products/.*")

# List patterns
print(config.excludes)
print(config.includes)
```

### Compare Configs

```python
from sfconfig import SFConfig

diff = SFConfig.diff("old.seospiderconfig", "new.seospiderconfig")

if diff.has_changes:
    print(f"Found {diff.change_count} differences:")
    print(diff)

# Filter by prefix
crawl_changes = diff.changes_for("mCrawlConfig")
```

### Test Extractions

```python
# Test selector against live URL before full crawl
result = config.test_extraction(
    url="https://example.com/product",
    selector="//span[@class='price']",
    selector_type="XPATH"
)

if result["match_count"] > 0:
    print(f"Found: {result['matches']}")
else:
    print("Selector didn't match - fix before crawling")
```

### Run Crawls

```python
# Blocking crawl
config.run_crawl(
    url="https://example.com",
    output_folder="./results",
    export_tabs=["Internal:All", "Response Codes:All"],
    export_format="csv",
    timeout=3600
)

# Async crawl
process = config.run_crawl_async(
    url="https://example.com",
    output_folder="./results"
)
# Do other work...
process.wait()  # Block until complete
```

## Multi-Client Workflow

```python
from sfconfig import SFConfig

clients = [
    {"domain": "client1.com", "max_urls": 50000},
    {"domain": "client2.com", "max_urls": 100000},
]

for client in clients:
    config = SFConfig.load("agency-base.seospiderconfig")
    config.max_urls = client["max_urls"]
    config.add_extraction("Price", "//span[@class='price']")

    config.save(f"/tmp/{client['domain']}.seospiderconfig")
    config.run_crawl(
        url=f"https://{client['domain']}",
        output_folder=f"./results/{client['domain']}"
    )
```

## Error Handling

```python
from sfconfig import (
    SFConfig,
    SFNotFoundError,
    SFValidationError,
    SFParseError,
    SFCrawlError
)

try:
    config = SFConfig.load("my.seospiderconfig")
    config.set("mInvalidField", 123)
    config.save()
except SFNotFoundError:
    print("Install Screaming Frog first")
except SFValidationError as e:
    print(f"Invalid field: {e}")
except SFParseError as e:
    print(f"Could not parse config: {e}")
except SFCrawlError as e:
    print(f"Crawl failed: {e}")
```

## Environment Variables

| Variable | Description |
|----------|-------------|
| `SF_PATH` | Custom path to SF's JAR directory |
| `SF_CLI_PATH` | Custom path to SF CLI executable |
| `JAVA_HOME` | Custom Java installation path |

## Architecture

```
User Python code
       |
       v
+------------------+
|  sfconfig        |  (Python wrapper)
|  - SFConfig      |
|  - SFDiff        |
+--------+---------+
         | subprocess.run()
         v
+------------------+
|  ConfigBuilder   |  (Java CLI, bundled ~50KB)
|  .jar            |
+--------+---------+
         | classpath includes
         v
+------------------+
|  SF's JARs       |  (from user's local SF install, NOT bundled)
+------------------+
```

At runtime, the library builds a classpath combining:
- `ConfigBuilder.jar` (bundled with this package)
- `{SF_INSTALL_PATH}/*` (user's local Screaming Frog JARs)

This means:
- Only our small JAR is distributed (no licensing issues)
- SF's proprietary JARs are used from the user's existing installation
- Compatibility is maintained across SF versions

## Development

### Building the Java CLI

The Java CLI lives in a separate repo (`sf-config-builder`). To build:

```bash
cd /path/to/sf-config-builder

# Compile against SF's JARs (as compile-time dependency)
javac -cp "C:/Program Files/Screaming Frog SEO Spider/*" \
      -d bin src/ConfigBuilder.java

# Package into JAR
cd bin
jar cfe ConfigBuilder.jar ConfigBuilder *.class

# Copy to Python package
cp ConfigBuilder.jar /path/to/sf-config-tool/sfconfig/java/
```

**Important**: Only bundle `ConfigBuilder.jar`. Do NOT bundle any JARs from SF's install directory - those are proprietary and already on the user's machine.

### Installing for Development

```bash
cd sf-config-tool
pip install -e ".[dev]"
pytest tests/
```

## License

MIT
