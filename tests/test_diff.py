"""Tests for SFDiff class."""

import pytest

from sfconfig import SFDiff


class TestSFDiffInit:
    """Tests for SFDiff initialization."""

    def test_init_with_data(self):
        """SFDiff should store provided data."""
        data = {
            "totalDifferences": 2,
            "differences": [
                {"path": "a", "valueA": 1, "valueB": 2},
                {"path": "b", "valueA": "x", "valueB": "y"},
            ]
        }
        diff = SFDiff(data)

        assert diff._data == data


class TestSFDiffProperties:
    """Tests for SFDiff properties."""

    def test_has_changes_true(self):
        """has_changes should be True when differences exist."""
        data = {"differences": [{"path": "a", "valueA": 1, "valueB": 2}]}
        diff = SFDiff(data)

        assert diff.has_changes is True

    def test_has_changes_false(self):
        """has_changes should be False when no differences."""
        data = {"differences": []}
        diff = SFDiff(data)

        assert diff.has_changes is False

    def test_change_count_from_total(self):
        """change_count should use totalDifferences if available."""
        data = {"totalDifferences": 5, "differences": []}
        diff = SFDiff(data)

        assert diff.change_count == 5

    def test_change_count_from_list(self):
        """change_count should count differences if totalDifferences missing."""
        data = {"differences": [{"path": "a"}, {"path": "b"}]}
        diff = SFDiff(data)

        assert diff.change_count == 2

    def test_changes(self):
        """changes should return differences list."""
        data = {
            "differences": [
                {"path": "a", "valueA": 1, "valueB": 2}
            ]
        }
        diff = SFDiff(data)

        assert diff.changes == data["differences"]

    def test_config_versions(self):
        """config_version_a/b should return version strings."""
        data = {
            "configVersionA": "22.0",
            "configVersionB": "22.1",
            "differences": []
        }
        diff = SFDiff(data)

        assert diff.config_version_a == "22.0"
        assert diff.config_version_b == "22.1"


class TestSFDiffChangesFor:
    """Tests for SFDiff.changes_for method."""

    def test_changes_for_filters_by_prefix(self):
        """changes_for should filter by path prefix."""
        data = {
            "differences": [
                {"path": "mCrawlConfig.mMaxUrls", "valueA": 100, "valueB": 200},
                {"path": "mCrawlConfig.mMaxDepth", "valueA": 5, "valueB": 10},
                {"path": "mUserAgent.mAgent", "valueA": "a", "valueB": "b"},
            ]
        }
        diff = SFDiff(data)

        crawl_changes = diff.changes_for("mCrawlConfig")
        assert len(crawl_changes) == 2

    def test_changes_for_empty_result(self):
        """changes_for should return empty list if no matches."""
        data = {
            "differences": [
                {"path": "mCrawlConfig.mMaxUrls", "valueA": 100, "valueB": 200}
            ]
        }
        diff = SFDiff(data)

        agent_changes = diff.changes_for("mUserAgent")
        assert len(agent_changes) == 0


class TestSFDiffToDict:
    """Tests for SFDiff.to_dict method."""

    def test_to_dict_structure(self):
        """to_dict should return properly structured dict."""
        data = {
            "configVersionA": "22.0",
            "configVersionB": "22.1",
            "totalDifferences": 1,
            "differences": [{"path": "a"}]
        }
        diff = SFDiff(data)

        result = diff.to_dict()
        assert result["config_version_a"] == "22.0"
        assert result["config_version_b"] == "22.1"
        assert result["total_differences"] == 1
        assert len(result["differences"]) == 1


class TestSFDiffStr:
    """Tests for SFDiff string representation."""

    def test_str_no_changes(self):
        """__str__ should show 'No changes' when empty."""
        diff = SFDiff({"differences": []})

        assert str(diff) == "No changes"

    def test_str_with_changes(self):
        """__str__ should format changes nicely."""
        data = {
            "differences": [
                {"path": "mCrawlConfig.mMaxUrls", "valueA": 100, "valueB": 200}
            ]
        }
        diff = SFDiff(data)

        result = str(diff)
        assert "mCrawlConfig.mMaxUrls" in result
        assert "100" in result
        assert "200" in result
        assert "->" in result

    def test_str_handles_old_new_keys(self):
        """__str__ should handle 'old'/'new' key names."""
        data = {
            "differences": [
                {"path": "field", "old": "before", "new": "after"}
            ]
        }
        diff = SFDiff(data)

        result = str(diff)
        assert "before" in result
        assert "after" in result


class TestSFDiffRepr:
    """Tests for SFDiff repr."""

    def test_repr(self):
        """__repr__ should show change count."""
        data = {"totalDifferences": 5, "differences": []}
        diff = SFDiff(data)

        assert repr(diff) == "<SFDiff changes=5>"


class TestSFDiffDunderMethods:
    """Tests for SFDiff dunder methods."""

    def test_len(self):
        """__len__ should return change count."""
        data = {"totalDifferences": 3, "differences": []}
        diff = SFDiff(data)

        assert len(diff) == 3

    def test_bool_true(self):
        """__bool__ should be True when changes exist."""
        data = {"differences": [{"path": "a"}]}
        diff = SFDiff(data)

        assert bool(diff) is True

    def test_bool_false(self):
        """__bool__ should be False when no changes."""
        data = {"differences": []}
        diff = SFDiff(data)

        assert bool(diff) is False

    def test_iter(self):
        """__iter__ should iterate over changes."""
        data = {"differences": [{"path": "a"}, {"path": "b"}]}
        diff = SFDiff(data)

        paths = [c["path"] for c in diff]
        assert paths == ["a", "b"]


class TestSFDiffFormatValue:
    """Tests for SFDiff._format_value method."""

    def test_format_none(self):
        """_format_value should format None as 'null'."""
        assert SFDiff._format_value(None) == "null"

    def test_format_string(self):
        """_format_value should quote strings."""
        assert SFDiff._format_value("hello") == '"hello"'

    def test_format_long_string(self):
        """_format_value should truncate long strings."""
        long_str = "a" * 100
        result = SFDiff._format_value(long_str)
        assert len(result) < 60
        assert "..." in result

    def test_format_empty_list(self):
        """_format_value should format empty list."""
        assert SFDiff._format_value([]) == "[]"

    def test_format_short_list(self):
        """_format_value should format short lists."""
        assert SFDiff._format_value([1, 2]) == "[1, 2]"

    def test_format_long_list(self):
        """_format_value should summarize long lists."""
        result = SFDiff._format_value([1, 2, 3, 4, 5])
        assert "5 items" in result

    def test_format_dict(self):
        """_format_value should summarize dicts."""
        result = SFDiff._format_value({"a": 1, "b": 2})
        assert "2 keys" in result

    def test_format_number(self):
        """_format_value should format numbers as-is."""
        assert SFDiff._format_value(42) == "42"
        assert SFDiff._format_value(3.14) == "3.14"
