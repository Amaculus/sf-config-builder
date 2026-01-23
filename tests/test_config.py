"""Tests for SFConfig class."""

import json
import pytest
from unittest.mock import patch, MagicMock

from sfconfig import SFConfig, SFValidationError, SFParseError


class TestSFConfigInit:
    """Tests for SFConfig initialization."""

    def test_init_with_data(self):
        """SFConfig should store provided data."""
        data = {"fields": [], "sfVersion": "22.0", "configVersion": "22.0"}
        config = SFConfig(data, path="/test/path.seospiderconfig")

        assert config._data == data
        assert config._path == "/test/path.seospiderconfig"
        assert config._patches == {}

    def test_init_without_path(self):
        """SFConfig should work without a path."""
        data = {"fields": []}
        config = SFConfig(data)

        assert config.path is None


class TestSFConfigGet:
    """Tests for SFConfig.get method."""

    def test_get_existing_field(self):
        """get() should return field value if it exists."""
        data = {
            "fields": [
                {"path": "mCrawlConfig.mMaxUrls", "value": 5000000}
            ]
        }
        config = SFConfig(data)

        assert config.get("mCrawlConfig.mMaxUrls") == 5000000

    def test_get_missing_field_returns_default(self):
        """get() should return default if field doesn't exist."""
        config = SFConfig({"fields": []})

        assert config.get("mCrawlConfig.mMissing") is None
        assert config.get("mCrawlConfig.mMissing", default=42) == 42

    def test_get_returns_patched_value(self):
        """get() should return patched value over original."""
        data = {
            "fields": [
                {"path": "mCrawlConfig.mMaxUrls", "value": 5000000}
            ]
        }
        config = SFConfig(data)
        config.set("mCrawlConfig.mMaxUrls", 100000)

        assert config.get("mCrawlConfig.mMaxUrls") == 100000


class TestSFConfigSet:
    """Tests for SFConfig.set method."""

    def test_set_stores_patch(self):
        """set() should store value in patches."""
        config = SFConfig({"fields": []})
        config.set("mCrawlConfig.mMaxUrls", 100000)

        assert config._patches["mCrawlConfig.mMaxUrls"] == 100000

    def test_set_returns_self(self):
        """set() should return self for chaining."""
        config = SFConfig({"fields": []})
        result = config.set("mCrawlConfig.mMaxUrls", 100000)

        assert result is config

    def test_set_chaining(self):
        """set() should support method chaining."""
        config = SFConfig({"fields": []})
        config.set("mCrawlConfig.mMaxUrls", 100000).set("mCrawlConfig.mMaxDepth", 10)

        assert config._patches["mCrawlConfig.mMaxUrls"] == 100000
        assert config._patches["mCrawlConfig.mMaxDepth"] == 10


class TestSFConfigProperties:
    """Tests for SFConfig convenience properties."""

    def test_max_urls_getter(self):
        """max_urls property should get mMaxUrls field."""
        data = {
            "fields": [
                {"path": "mCrawlConfig.mMaxUrls", "value": 5000000}
            ]
        }
        config = SFConfig(data)

        assert config.max_urls == 5000000

    def test_max_urls_setter(self):
        """max_urls setter should set mMaxUrls field."""
        config = SFConfig({"fields": []})
        config.max_urls = 100000

        assert config._patches["mCrawlConfig.mMaxUrls"] == 100000

    def test_rendering_mode_getter(self):
        """rendering_mode property should get mRenderingMode field."""
        data = {
            "fields": [
                {"path": "mCrawlConfig.mRenderingMode", "value": "JAVASCRIPT"}
            ]
        }
        config = SFConfig(data)

        assert config.rendering_mode == "JAVASCRIPT"

    def test_rendering_mode_setter(self):
        """rendering_mode setter should set mRenderingMode field."""
        config = SFConfig({"fields": []})
        config.rendering_mode = "JAVASCRIPT"

        assert config._patches["mCrawlConfig.mRenderingMode"] == "JAVASCRIPT"


class TestSFConfigExtractions:
    """Tests for SFConfig extraction methods."""

    def test_add_extraction_basic(self):
        """add_extraction should add operation with defaults."""
        config = SFConfig({"fields": []})
        config.add_extraction("Price", "//span[@class='price']")

        assert len(config._extraction_ops) == 1
        op = config._extraction_ops[0]
        assert op["op"] == "add"
        assert op["name"] == "Price"
        assert op["selector"] == "//span[@class='price']"
        assert op["selectorType"] == "XPATH"
        assert op["extractMode"] == "TEXT"

    def test_add_extraction_with_options(self):
        """add_extraction should accept custom options."""
        config = SFConfig({"fields": []})
        config.add_extraction(
            "Title",
            "h1.title",
            selector_type="CSS",
            extract_mode="HTML_ELEMENT"
        )

        op = config._extraction_ops[0]
        assert op["selectorType"] == "CSS"
        assert op["extractMode"] == "HTML_ELEMENT"

    def test_add_extraction_chaining(self):
        """add_extraction should support chaining."""
        config = SFConfig({"fields": []})
        result = config.add_extraction("A", "//a").add_extraction("B", "//b")

        assert result is config
        assert len(config._extraction_ops) == 2

    def test_remove_extraction(self):
        """remove_extraction should add remove operation."""
        config = SFConfig({"fields": []})
        config.remove_extraction("Price")

        assert len(config._extraction_ops) == 1
        assert config._extraction_ops[0] == {"op": "remove", "name": "Price"}

    def test_clear_extractions(self):
        """clear_extractions should add clear operation."""
        config = SFConfig({"fields": []})
        config.clear_extractions()

        assert len(config._extraction_ops) == 1
        assert config._extraction_ops[0] == {"op": "clear"}


class TestSFConfigCustomSearches:
    """Tests for SFConfig custom search methods."""

    def test_add_custom_search_basic(self):
        """add_custom_search should add operation with defaults."""
        config = SFConfig({"fields": []})
        config.add_custom_search("Filter 1", ".*", data_type="REGEX")

        assert len(config._custom_search_ops) == 1
        op = config._custom_search_ops[0]
        assert op["op"] == "add"
        assert op["name"] == "Filter 1"
        assert op["query"] == ".*"
        assert op["mode"] == "CONTAINS"
        assert op["dataType"] == "REGEX"
        assert op["scope"] == "HTML"

    def test_add_custom_search_with_xpath(self):
        """add_custom_search should include xpath when provided."""
        config = SFConfig({"fields": []})
        config.add_custom_search(
            "Filter 2",
            "//title",
            mode="XPATH",
            data_type="TEXT",
            scope="HTML",
            xpath="//title",
        )

        op = config._custom_search_ops[0]
        assert op["xpath"] == "//title"

    def test_remove_custom_search(self):
        """remove_custom_search should add remove operation."""
        config = SFConfig({"fields": []})
        config.remove_custom_search("Filter 1")

        assert config._custom_search_ops == [{"op": "remove", "name": "Filter 1"}]

    def test_clear_custom_searches(self):
        """clear_custom_searches should add clear operation."""
        config = SFConfig({"fields": []})
        config.clear_custom_searches()

        assert config._custom_search_ops == [{"op": "clear"}]


class TestSFConfigCustomJavaScript:
    """Tests for SFConfig custom JavaScript methods."""

    def test_add_custom_javascript_basic(self):
        """add_custom_javascript should add operation with defaults."""
        config = SFConfig({"fields": []})
        config.add_custom_javascript("Extractor 1", "return document.title;")

        assert len(config._custom_js_ops) == 1
        op = config._custom_js_ops[0]
        assert op["op"] == "add"
        assert op["name"] == "Extractor 1"
        assert op["javascript"] == "return document.title;"
        assert op["type"] == "EXTRACTION"
        assert op["timeout_secs"] == 10
        assert op["content_types"] == "text/html"

    def test_remove_custom_javascript(self):
        """remove_custom_javascript should add remove operation."""
        config = SFConfig({"fields": []})
        config.remove_custom_javascript("Extractor 1")

        assert config._custom_js_ops == [{"op": "remove", "name": "Extractor 1"}]

    def test_clear_custom_javascript(self):
        """clear_custom_javascript should add clear operation."""
        config = SFConfig({"fields": []})
        config.clear_custom_javascript()

        assert config._custom_js_ops == [{"op": "clear"}]

class TestSFConfigExcludes:
    """Tests for SFConfig exclude methods."""

    def test_add_exclude(self):
        """add_exclude should add append operation."""
        config = SFConfig({"fields": []})
        config.add_exclude(r".*\.pdf$")

        assert len(config._exclude_ops) == 1
        assert config._exclude_ops[0] == {"op": "append", "values": [r".*\.pdf$"]}

    def test_remove_exclude(self):
        """remove_exclude should add remove operation."""
        config = SFConfig({"fields": []})
        config.remove_exclude(r".*\.pdf$")

        assert len(config._exclude_ops) == 1
        assert config._exclude_ops[0] == {"op": "remove", "values": [r".*\.pdf$"]}

    def test_clear_excludes(self):
        """clear_excludes should add clear operation."""
        config = SFConfig({"fields": []})
        config.clear_excludes()

        assert len(config._exclude_ops) == 1
        assert config._exclude_ops[0] == {"op": "clear"}

    def test_excludes_property(self):
        """excludes property should return patterns from data."""
        data = {
            "fields": [
                {"path": "mExcludeManager.mExcludePatterns", "value": ["a", "b"]}
            ]
        }
        config = SFConfig(data)

        assert config.excludes == ["a", "b"]


class TestSFConfigFields:
    """Tests for SFConfig.fields method."""

    def test_fields_returns_all(self):
        """fields() without prefix should return all fields."""
        data = {
            "fields": [
                {"path": "mCrawlConfig.mMaxUrls", "value": 5000000},
                {"path": "mUserAgent.mAgent", "value": "Bot/1.0"},
            ]
        }
        config = SFConfig(data)

        assert len(config.fields()) == 2

    def test_fields_with_prefix(self):
        """fields() with prefix should filter results."""
        data = {
            "fields": [
                {"path": "mCrawlConfig.mMaxUrls", "value": 5000000},
                {"path": "mCrawlConfig.mMaxDepth", "value": 10},
                {"path": "mUserAgent.mAgent", "value": "Bot/1.0"},
            ]
        }
        config = SFConfig(data)

        crawl_fields = config.fields(prefix="mCrawlConfig")
        assert len(crawl_fields) == 2


class TestSFConfigRepr:
    """Tests for SFConfig string representations."""

    def test_repr(self):
        """__repr__ should show path and version."""
        data = {"fields": [], "configVersion": "22.0"}
        config = SFConfig(data, path="/test/config.seospiderconfig")

        repr_str = repr(config)
        assert "SFConfig" in repr_str
        assert "/test/config.seospiderconfig" in repr_str
        assert "22.0" in repr_str

    def test_str(self):
        """__str__ should show path."""
        config = SFConfig({"fields": []}, path="/test/config.seospiderconfig")

        assert "SFConfig" in str(config)
        assert "/test/config.seospiderconfig" in str(config)

    def test_str_unsaved(self):
        """__str__ should show 'unsaved' when no path."""
        config = SFConfig({"fields": []})

        assert "unsaved" in str(config)
