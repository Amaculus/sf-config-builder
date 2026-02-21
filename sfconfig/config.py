"""SFConfig class for managing Screaming Frog configuration files."""

import json
import subprocess
from pathlib import Path
from typing import Any, Dict, List, Optional, Union

from .diff import SFDiff
from .exceptions import SFConfigError, SFCrawlError, SFParseError, SFValidationError
from .paths import get_classpath_separator, get_java_path, get_sf_cli_path, get_sf_jar_path


class SFConfig:
    """Manage Screaming Frog configuration files.

    This class wraps the Java ConfigBuilder CLI to provide a Pythonic interface
    for inspecting, modifying, and using .seospiderconfig files.

    Example:
        >>> config = SFConfig.load("base.seospiderconfig")
        >>> config.max_urls = 100000
        >>> config.add_extraction("Price", "//span[@class='price']")
        >>> config.save("client.seospiderconfig")
        >>> config.run_crawl("https://example.com", output_folder="./results")
    """

    JAR_PATH = Path(__file__).parent / "java" / "ConfigBuilder.jar"

    def __init__(
        self,
        data: Dict[str, Any],
        path: Optional[str] = None,
        sf_path: Optional[str] = None,
    ):
        """Initialize SFConfig with inspection data.

        Args:
            data: Parsed JSON response from Java CLI --inspect command.
            path: Path to the source config file.
            sf_path: Optional custom path to SF installation directory.
        """
        self._data = data
        self._path = path
        self._sf_path = sf_path
        self._patches: Dict[str, Any] = {}
        self._extraction_ops: List[Dict[str, Any]] = []
        self._custom_search_ops: List[Dict[str, Any]] = []
        self._custom_js_ops: List[Dict[str, Any]] = []
        self._exclude_ops: List[Dict[str, Any]] = []
        self._include_ops: List[Dict[str, Any]] = []

    # ==================== Loading ====================

    @classmethod
    def load(cls, path: str, sf_path: Optional[str] = None) -> "SFConfig":
        """Load a config file.

        Args:
            path: Path to the .seospiderconfig file.
            sf_path: Optional custom path to SF installation directory.
                     Auto-detects if not provided.

        Returns:
            SFConfig instance with loaded configuration.

        Raises:
            SFParseError: If the config file cannot be parsed.
            SFNotFoundError: If Screaming Frog is not installed.
        """
        result = cls._run_java("--inspect", "--config", str(path), sf_path=sf_path)
        return cls(result, str(path), sf_path=sf_path)

    @classmethod
    def default(cls) -> "SFConfig":
        """Create config from SF's default settings.

        Returns:
            SFConfig instance with default SF configuration.

        Raises:
            NotImplementedError: This feature is not yet implemented.
        """
        from .paths import get_default_config_path

        default_path = get_default_config_path()
        if default_path:
            return cls.load(str(default_path))

        raise NotImplementedError(
            "Default config not found. "
            "Please load an existing config file instead."
        )

    # ==================== Inspection ====================

    def to_dict(self) -> Dict[str, Any]:
        """Return full config as dictionary.

        Returns:
            Dictionary containing all config data from the Java CLI.
        """
        return self._data

    def get(self, path: str, default: Any = None) -> Any:
        """Get a specific field value.

        Args:
            path: Dot-separated field path (e.g., "mCrawlConfig.mMaxUrls").
            default: Value to return if field is not found.

        Returns:
            The field value, or default if not found.
        """
        # Check patches first (pending changes take precedence)
        if path in self._patches:
            return self._patches[path]

        # Search in loaded data
        for field in self._data.get("fields", []):
            if field.get("path") == path:
                return field.get("value")

        return default

    def fields(self, prefix: Optional[str] = None) -> List[Dict[str, Any]]:
        """List all fields, optionally filtered by prefix.

        Args:
            prefix: Optional path prefix to filter fields.

        Returns:
            List of field dictionaries containing path, type, value, etc.
        """
        fields = self._data.get("fields", [])
        if prefix:
            fields = [f for f in fields if f.get("path", "").startswith(prefix)]
        return fields

    @property
    def sf_version(self) -> str:
        """Get the Screaming Frog version that created this config."""
        return self._data.get("sfVersion", "unknown")

    @property
    def config_version(self) -> str:
        """Get the config file version."""
        return self._data.get("configVersion", "unknown")

    @property
    def path(self) -> Optional[str]:
        """Get the path to the loaded config file."""
        return self._path

    # ==================== Modification ====================

    def set(self, path: str, value: Any) -> "SFConfig":
        """Set a field value.

        Args:
            path: Dot-separated field path (e.g., "mCrawlConfig.mMaxUrls").
            value: The value to set.

        Returns:
            Self for method chaining.
        """
        self._patches[path] = value
        return self

    # Convenience properties
    @property
    def max_urls(self) -> int:
        """Get the maximum URLs to crawl."""
        return self.get("mCrawlConfig.mMaxUrls", 0)

    @max_urls.setter
    def max_urls(self, value: int):
        """Set the maximum URLs to crawl."""
        self.set("mCrawlConfig.mMaxUrls", value)

    @property
    def rendering_mode(self) -> str:
        """Get the rendering mode (STATIC or JAVASCRIPT)."""
        return self.get("mCrawlConfig.mRenderingMode", "STATIC")

    @rendering_mode.setter
    def rendering_mode(self, value: str):
        """Set the rendering mode (STATIC or JAVASCRIPT)."""
        self.set("mCrawlConfig.mRenderingMode", value)

    @property
    def crawl_css(self) -> bool:
        """Get whether CSS assets are crawled."""
        return bool(self.get("mCrawlConfig.mCrawlCSS", False))

    @crawl_css.setter
    def crawl_css(self, value: bool):
        """Set whether CSS assets are crawled."""
        self.set("mCrawlConfig.mCrawlCSS", bool(value))

    @property
    def store_css(self) -> bool:
        """Get whether CSS assets are stored."""
        return bool(self.get("mCrawlConfig.mStoreCSS", False))

    @store_css.setter
    def store_css(self, value: bool):
        """Set whether CSS assets are stored."""
        self.set("mCrawlConfig.mStoreCSS", bool(value))

    @property
    def crawl_javascript(self) -> bool:
        """Get whether JavaScript assets are crawled."""
        return bool(self.get("mCrawlConfig.mCrawlJavaScript", False))

    @crawl_javascript.setter
    def crawl_javascript(self, value: bool):
        """Set whether JavaScript assets are crawled."""
        self.set("mCrawlConfig.mCrawlJavaScript", bool(value))

    @property
    def store_javascript(self) -> bool:
        """Get whether JavaScript assets are stored."""
        return bool(self.get("mCrawlConfig.mStoreJavaScript", False))

    @store_javascript.setter
    def store_javascript(self, value: bool):
        """Set whether JavaScript assets are stored."""
        self.set("mCrawlConfig.mStoreJavaScript", bool(value))

    @property
    def robots_mode(self) -> str:
        """Get the robots.txt handling mode (RESPECT or IGNORE)."""
        return self.get("mCrawlConfig.mRobotsTxtMode", "RESPECT")

    @robots_mode.setter
    def robots_mode(self, value: str):
        """Set the robots.txt handling mode (RESPECT or IGNORE)."""
        self.set("mCrawlConfig.mRobotsTxtMode", value)

    @property
    def max_depth(self) -> int:
        """Get the maximum crawl depth."""
        return self.get("mCrawlConfig.mMaxDepth", 0)

    @max_depth.setter
    def max_depth(self, value: int):
        """Set the maximum crawl depth."""
        self.set("mCrawlConfig.mMaxDepth", value)

    @property
    def crawl_delay(self) -> float:
        """Get the crawl delay in seconds."""
        return self.get("mCrawlConfig.mCrawlDelay", 0.0)

    @crawl_delay.setter
    def crawl_delay(self, value: float):
        """Set the crawl delay in seconds."""
        self.set("mCrawlConfig.mCrawlDelay", value)

    @property
    def user_agent(self) -> str:
        """Get the user agent string."""
        return self.get("mUserAgentConfig.mUserAgent", "")

    @user_agent.setter
    def user_agent(self, value: str):
        """Set the user agent string."""
        self.set("mUserAgentConfig.mUserAgent", value)

    @property
    def limit_performance(self) -> bool:
        """Get whether crawl performance throttling is enabled."""
        return bool(self.get("mPerformanceConfig.mLimitPerformance", False))

    @limit_performance.setter
    def limit_performance(self, value: bool):
        """Set whether crawl performance throttling is enabled."""
        self.set("mPerformanceConfig.mLimitPerformance", bool(value))

    @property
    def url_requests_per_second(self) -> float:
        """Get the URL requests-per-second target."""
        return float(self.get("mPerformanceConfig.mUrlRequestsPerSecond", 0.0))

    @url_requests_per_second.setter
    def url_requests_per_second(self, value: Union[int, float]):
        """Set the URL requests-per-second target."""
        self.set("mPerformanceConfig.mUrlRequestsPerSecond", float(value))

    # ==================== Extractions ====================

    def add_extraction(
        self,
        name: str,
        selector: str,
        selector_type: str = "XPATH",
        extract_mode: str = "TEXT",
        attribute: Optional[str] = None,
    ) -> "SFConfig":
        """Add a custom extraction rule.

        Args:
            name: Name for the extraction (appears as column header in exports).
            selector: The selector pattern (XPath, CSS, or Regex).
            selector_type: Type of selector - "XPATH", "CSS", or "REGEX".
            extract_mode: What to extract - "TEXT", "HTML_ELEMENT", "INNER_HTML",
                         or "FUNCTION_VALUE".
            attribute: Optional attribute to extract (for ATTRIBUTE mode).

        Returns:
            Self for method chaining.

        Example:
            >>> config.add_extraction("Price", "//span[@class='price']")
            >>> config.add_extraction("SKU", ".sku-code", selector_type="CSS")
        """
        op = {
            "op": "add",
            "name": name,
            "selector": selector,
            "selectorType": selector_type.upper(),
            "extractMode": extract_mode.upper(),
        }
        if attribute:
            op["attribute"] = attribute
        self._extraction_ops.append(op)
        return self

    def remove_extraction(self, name: str) -> "SFConfig":
        """Remove an extraction rule by name.

        Args:
            name: Name of the extraction rule to remove.

        Returns:
            Self for method chaining.
        """
        self._extraction_ops.append({"op": "remove", "name": name})
        return self

    def clear_extractions(self) -> "SFConfig":
        """Remove all extraction rules.

        Returns:
            Self for method chaining.
        """
        self._extraction_ops.append({"op": "clear"})
        return self

    @property
    def extractions(self) -> List[Dict[str, Any]]:
        """List current extraction rules.

        Returns:
            List of extraction rule dictionaries.
        """
        # Try to get from virtual field
        extractions = self.get("mCustomExtractionConfig.extractions", [])
        if extractions:
            return extractions

        # Fall back to parsing mFilters if available
        return []

    # ==================== Custom Searches ====================

    def add_custom_search(
        self,
        name: str,
        query: str,
        mode: str = "CONTAINS",
        data_type: str = "TEXT",
        scope: str = "HTML",
        case_sensitive: bool = False,
        xpath: Optional[str] = None,
    ) -> "SFConfig":
        """Add a custom search rule.

        Args:
            name: Name for the search filter (appears in UI/exports).
            query: Query string or regex.
            mode: Search mode (e.g., CONTAINS, REGEX).
            data_type: TEXT, HTML, or REGEX.
            scope: HTML, TEXT, or similar scope depending on version.
            case_sensitive: Whether the search is case sensitive.
            xpath: Optional XPath (for XPath searches).

        Returns:
            Self for method chaining.
        """
        op: Dict[str, Any] = {
            "op": "add",
            "name": name,
            "query": query,
            "mode": mode.upper(),
            "dataType": data_type.upper(),
            "scope": scope.upper(),
            "caseSensitive": case_sensitive,
        }
        if xpath:
            op["xpath"] = xpath
        self._custom_search_ops.append(op)
        return self

    def remove_custom_search(self, name: str) -> "SFConfig":
        """Remove a custom search rule by name."""
        self._custom_search_ops.append({"op": "remove", "name": name})
        return self

    def clear_custom_searches(self) -> "SFConfig":
        """Remove all custom search rules."""
        self._custom_search_ops.append({"op": "clear"})
        return self

    @property
    def custom_searches(self) -> List[Dict[str, Any]]:
        """List current custom search rules."""
        return self.get("custom_searches", [])

    # ==================== Custom JavaScript ====================

    def add_custom_javascript(
        self,
        name: str,
        javascript: str,
        script_type: str = "EXTRACTION",
        timeout_secs: int = 10,
        content_types: str = "text/html",
    ) -> "SFConfig":
        """Add a custom JavaScript rule."""
        op = {
            "op": "add",
            "name": name,
            "javascript": javascript,
            "type": script_type.upper(),
            "timeout_secs": timeout_secs,
            "content_types": content_types,
        }
        self._custom_js_ops.append(op)
        return self

    def remove_custom_javascript(self, name: str) -> "SFConfig":
        """Remove a custom JavaScript rule by name."""
        self._custom_js_ops.append({"op": "remove", "name": name})
        return self

    def clear_custom_javascript(self) -> "SFConfig":
        """Remove all custom JavaScript rules."""
        self._custom_js_ops.append({"op": "clear"})
        return self

    @property
    def custom_javascript(self) -> List[Dict[str, Any]]:
        """List current custom JavaScript rules."""
        return self.get("custom_javascript", [])

    # ==================== Excludes ====================

    def add_exclude(self, pattern: str) -> "SFConfig":
        """Add an exclude pattern (regex).

        URLs matching this pattern will be excluded from crawling.

        Args:
            pattern: Regex pattern to exclude.

        Returns:
            Self for method chaining.

        Example:
            >>> config.add_exclude(r".*\\.pdf$")  # Exclude PDFs
            >>> config.add_exclude(r".*/admin/.*")  # Exclude admin paths
        """
        self._exclude_ops.append({"op": "append", "values": [pattern]})
        return self

    def remove_exclude(self, pattern: str) -> "SFConfig":
        """Remove an exclude pattern.

        Args:
            pattern: The exact pattern to remove.

        Returns:
            Self for method chaining.
        """
        self._exclude_ops.append({"op": "remove", "values": [pattern]})
        return self

    def clear_excludes(self) -> "SFConfig":
        """Remove all exclude patterns.

        Returns:
            Self for method chaining.
        """
        self._exclude_ops.append({"op": "clear"})
        return self

    @property
    def excludes(self) -> List[str]:
        """List current exclude patterns.

        Returns:
            List of regex patterns.
        """
        return self.get("mExcludeManager.mExcludePatterns", [])

    # ==================== Includes ====================

    def add_include(self, pattern: str) -> "SFConfig":
        """Add an include pattern (regex).

        Only URLs matching include patterns will be crawled.

        Args:
            pattern: Regex pattern to include.

        Returns:
            Self for method chaining.
        """
        self._include_ops.append({"op": "append", "values": [pattern]})
        return self

    def remove_include(self, pattern: str) -> "SFConfig":
        """Remove an include pattern.

        Args:
            pattern: The exact pattern to remove.

        Returns:
            Self for method chaining.
        """
        self._include_ops.append({"op": "remove", "values": [pattern]})
        return self

    def clear_includes(self) -> "SFConfig":
        """Remove all include patterns.

        Returns:
            Self for method chaining.
        """
        self._include_ops.append({"op": "clear"})
        return self

    @property
    def includes(self) -> List[str]:
        """List current include patterns.

        Returns:
            List of regex patterns.
        """
        return self.get("mCrawlConfig.mIncludePatterns", [])

    # ==================== Allowed Domains ====================

    def add_allowed_domain(self, domain: str) -> "SFConfig":
        """Add an allowed domain for crawling.

        Args:
            domain: Domain to allow (e.g., "example.com").

        Returns:
            Self for method chaining.
        """
        # This typically maps to a specific SF config field
        # Implementation depends on SF version
        self.set("mCrawlConfig.mAllowedDomains",
                 self.get("mCrawlConfig.mAllowedDomains", []) + [domain])
        return self

    # ==================== Saving ====================

    def save(self, output_path: Optional[str] = None) -> "SFConfig":
        """Save config to file.

        Args:
            output_path: Path to save to. If None, overwrites the original file.

        Returns:
            Self for method chaining.

        Raises:
            SFConfigError: If no output path is specified and no original path exists.
            SFValidationError: If patches contain invalid fields or values.
        """
        output = output_path or self._path
        if not output:
            raise SFConfigError("No output path specified and no original path to overwrite")

        # Build patches dict
        patches = dict(self._patches)

        if self._extraction_ops:
            patches["extractions"] = self._extraction_ops

        if self._custom_search_ops:
            patches["custom_searches"] = self._custom_search_ops

        if self._custom_js_ops:
            patches["custom_javascript"] = self._custom_js_ops

        if self._exclude_ops:
            # Convert ops to the format expected by Java CLI
            if len(self._exclude_ops) == 1 and self._exclude_ops[0].get("op") == "clear":
                patches["mExcludeManager.mExcludePatterns"] = {"op": "clear"}
            else:
                # Combine all ops
                combined_op = {"op": "append", "values": []}
                for op in self._exclude_ops:
                    if op.get("op") == "append":
                        combined_op["values"].extend(op.get("values", []))
                    elif op.get("op") == "remove":
                        combined_op = {"op": "remove", "values": op.get("values", [])}
                    elif op.get("op") == "clear":
                        combined_op = {"op": "clear"}
                patches["mExcludeManager.mExcludePatterns"] = combined_op

        if self._include_ops:
            if len(self._include_ops) == 1 and self._include_ops[0].get("op") == "clear":
                patches["mCrawlConfig.mIncludePatterns"] = {"op": "clear"}
            else:
                combined_op = {"op": "append", "values": []}
                for op in self._include_ops:
                    if op.get("op") == "append":
                        combined_op["values"].extend(op.get("values", []))
                    elif op.get("op") == "remove":
                        combined_op = {"op": "remove", "values": op.get("values", [])}
                    elif op.get("op") == "clear":
                        combined_op = {"op": "clear"}
                patches["mCrawlConfig.mIncludePatterns"] = combined_op

        patches_json = json.dumps(patches)

        self._run_java(
            "--build",
            "--template", self._path,
            "--output", str(output),
            "--patches", patches_json,
            sf_path=self._sf_path,
        )

        # Update state
        self._path = str(output)
        self._patches = {}
        self._extraction_ops = []
        self._custom_search_ops = []
        self._custom_js_ops = []
        self._exclude_ops = []
        self._include_ops = []

        # Reload to get fresh data
        result = self._run_java("--inspect", "--config", str(output), sf_path=self._sf_path)
        self._data = result

        return self

    def preview_save(self) -> List[Dict[str, Any]]:
        """Preview changes without saving.

        Returns:
            List of change dictionaries showing what would be modified.
        """
        patches = dict(self._patches)
        if self._extraction_ops:
            patches["extractions"] = self._extraction_ops
        if self._custom_search_ops:
            patches["custom_searches"] = self._custom_search_ops
        if self._custom_js_ops:
            patches["custom_javascript"] = self._custom_js_ops
        if self._exclude_ops:
            patches["mExcludeManager.mExcludePatterns"] = self._exclude_ops
        if self._include_ops:
            patches["mCrawlConfig.mIncludePatterns"] = self._include_ops

        patches_json = json.dumps(patches)

        # Use NUL on Windows, /dev/null on Unix
        import platform
        null_path = "NUL" if platform.system() == "Windows" else "/dev/null"

        result = self._run_java(
            "--build",
            "--template", self._path,
            "--output", null_path,
            "--patches", patches_json,
            "--dry-run",
            sf_path=self._sf_path,
        )

        return result.get("changes", [])

    # ==================== Crawling ====================

    def run_crawl(
        self,
        url: str,
        output_folder: str,
        export_tabs: Optional[List[str]] = None,
        export_format: str = "csv",
        timeout: Optional[int] = None,
    ) -> None:
        """Run a crawl (blocking).

        Args:
            url: The URL to start crawling from.
            output_folder: Directory to save crawl results.
            export_tabs: List of tabs to export (e.g., ["Internal:All", "Response Codes:All"]).
            export_format: Export format - "csv" or "xlsx".
            timeout: Maximum time in seconds to wait for crawl completion.

        Raises:
            SFCrawlError: If the crawl fails or times out.
            SFConfigError: If the config hasn't been saved yet.
        """
        process = self.run_crawl_async(url, output_folder, export_tabs, export_format)

        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            raise SFCrawlError(f"Crawl timed out after {timeout} seconds")

        if process.returncode != 0:
            raise SFCrawlError(f"Crawl failed with exit code {process.returncode}")

    def run_crawl_async(
        self,
        url: str,
        output_folder: str,
        export_tabs: Optional[List[str]] = None,
        export_format: str = "csv",
    ) -> subprocess.Popen:
        """Run a crawl (non-blocking).

        Args:
            url: The URL to start crawling from.
            output_folder: Directory to save crawl results.
            export_tabs: List of tabs to export.
            export_format: Export format - "csv" or "xlsx".

        Returns:
            subprocess.Popen handle for the crawl process.

        Raises:
            SFConfigError: If the config hasn't been saved yet.
        """
        if not self._path:
            raise SFConfigError("Save config before running crawl")

        cli = get_sf_cli_path()

        cmd = [
            cli,
            "--crawl", url,
            "--config", self._path,
            "--headless",
            "--output-folder", str(output_folder),
            "--export-format", export_format,
        ]

        if export_tabs:
            cmd.extend(["--export-tabs", ",".join(export_tabs)])

        return subprocess.Popen(cmd)

    # ==================== Test Extraction ====================

    def test_extraction(
        self,
        url: str,
        extraction_name: Optional[str] = None,
        selector: Optional[str] = None,
        selector_type: str = "XPATH",
        extract_mode: str = "TEXT",
        render_js: bool = False,
    ) -> Dict[str, Any]:
        """Test an extraction against a live URL.

        Args:
            url: URL to fetch and test against.
            extraction_name: Name of an existing extraction rule to test.
            selector: Inline selector to test (alternative to extraction_name).
            selector_type: Type of selector - "XPATH", "CSS", or "REGEX".
            extract_mode: What to extract - "TEXT", "HTML_ELEMENT", etc.
            render_js: Whether to render JavaScript before extraction.

        Returns:
            Dictionary containing:
            - success: Whether the test succeeded
            - matches: List of matched values
            - match_count: Number of matches
            - warnings: Any warnings

        Raises:
            SFValidationError: If neither extraction_name nor selector is provided.
        """
        if extraction_name:
            # Find extraction in config
            for ext in self.extractions:
                if ext.get("name") == extraction_name:
                    selector = ext.get("selector")
                    selector_type = ext.get("selectorType", "XPATH")
                    extract_mode = ext.get("extractMode", "TEXT")
                    break
            else:
                raise SFValidationError(f"Extraction '{extraction_name}' not found")

        if not selector:
            raise SFValidationError("Provide extraction_name or selector")

        args = [
            "--test-extraction",
            "--url", url,
            "--selector", selector,
            "--selector-type", selector_type.upper(),
            "--extract-mode", extract_mode.upper(),
        ]

        if render_js:
            args.append("--render-js")

        result = self._run_java(*args, sf_path=self._sf_path)
        return result

    # ==================== Diff ====================

    @classmethod
    def diff(
        cls,
        config_a: Union[str, "SFConfig"],
        config_b: Union[str, "SFConfig"],
        prefix: Optional[str] = None,
        sf_path: Optional[str] = None,
    ) -> SFDiff:
        """Compare two configs.

        Args:
            config_a: First config (path or SFConfig instance).
            config_b: Second config (path or SFConfig instance).
            prefix: Optional path prefix to filter differences.
            sf_path: Optional custom path to SF installation directory.

        Returns:
            SFDiff object representing the differences.

        Example:
            >>> diff = SFConfig.diff("old.seospiderconfig", "new.seospiderconfig")
            >>> if diff.has_changes:
            ...     print(diff)
        """
        path_a = config_a._path if isinstance(config_a, SFConfig) else str(config_a)
        path_b = config_b._path if isinstance(config_b, SFConfig) else str(config_b)

        # Get sf_path from config if not provided
        if sf_path is None and isinstance(config_a, SFConfig):
            sf_path = config_a._sf_path

        args = ["--diff", "--config-a", path_a, "--config-b", path_b]
        if prefix:
            args.extend(["--prefix", prefix])

        result = cls._run_java(*args, sf_path=sf_path)
        return SFDiff(result)

    # ==================== Internal ====================

    @classmethod
    def _run_java(cls, *args: str, sf_path: Optional[str] = None) -> Dict[str, Any]:
        """Execute Java CLI and return parsed JSON result.

        Args:
            *args: Command line arguments to pass to the Java CLI.
            sf_path: Optional custom path to SF installation directory.

        Returns:
            Parsed JSON response from the CLI.

        Raises:
            SFParseError: If the CLI output is not valid JSON.
            SFValidationError: If the CLI returns a validation error.
            SFConfigError: If the CLI returns any other error.
        """
        java = get_java_path(sf_path)
        sf_jar_path = get_sf_jar_path(sf_path)
        cp_sep = get_classpath_separator()

        # Build classpath
        classpath = f"{cls.JAR_PATH}{cp_sep}{sf_jar_path}/*"

        cmd = [java, "-cp", classpath, "ConfigBuilder", *args]

        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
        )

        # Handle empty output
        if not result.stdout.strip():
            if result.stderr:
                raise SFConfigError(f"Java CLI error: {result.stderr}")
            raise SFParseError("No output from Java CLI")

        try:
            data = json.loads(result.stdout)
        except json.JSONDecodeError as e:
            raise SFParseError(
                f"Invalid JSON from CLI: {result.stdout[:200]}...\n"
                f"Parse error: {e}"
            )

        if not data.get("success", True):
            error_type = data.get("errorType", "UNKNOWN")
            error_msg = data.get("error", "Unknown error")
            details = data.get("details", {})

            if error_type == "VALIDATION_ERROR":
                raise SFValidationError(f"{error_msg}: {details}" if details else error_msg)
            elif error_type == "PARSE_ERROR":
                raise SFParseError(error_msg)
            elif error_type == "IO_ERROR":
                raise SFConfigError(f"I/O error: {error_msg}")
            else:
                raise SFConfigError(error_msg)

        return data

    def __repr__(self) -> str:
        """Return developer-friendly representation."""
        return f"<SFConfig path={self._path!r} version={self.config_version}>"

    def __str__(self) -> str:
        """Return human-readable string representation."""
        return f"SFConfig({self._path or 'unsaved'})"
