"""Tests for Screaming Frog installation path detection."""

import pytest

from sfconfig.exceptions import SFNotFoundError
from sfconfig.paths import SF_PATHS, get_sf_jar_path

MAC_CURRENT = "/Applications/Screaming Frog SEO Spider.app/Contents/Java"
MAC_LEGACY = "/Applications/Screaming Frog SEO Spider.app/Contents/Resources/Java"


@pytest.fixture(autouse=True)
def _no_sf_path_env(monkeypatch):
    """SF_PATH would short-circuit detection; clear it for these tests."""
    monkeypatch.delenv("SF_PATH", raising=False)


class TestMacOSJarPaths:
    """macOS bundles moved the jars from Contents/Resources/Java to Contents/Java."""

    def test_both_macos_layouts_are_candidates(self):
        """Both the current and the legacy bundle layout must be probed."""
        assert MAC_CURRENT in SF_PATHS["Darwin"]
        assert MAC_LEGACY in SF_PATHS["Darwin"]

    def test_current_layout_is_probed_before_legacy(self):
        """A machine with both present should resolve to the current layout."""
        darwin = SF_PATHS["Darwin"]
        assert darwin.index(MAC_CURRENT) < darwin.index(MAC_LEGACY)

    def test_resolves_current_layout(self, monkeypatch):
        """Detection succeeds when only Contents/Java exists (current SF builds)."""
        monkeypatch.setattr("sfconfig.paths.get_platform", lambda: "Darwin")
        monkeypatch.setattr("os.path.exists", lambda path: path == MAC_CURRENT)

        assert get_sf_jar_path() == MAC_CURRENT

    def test_resolves_legacy_layout(self, monkeypatch):
        """Detection still succeeds on older bundles with Contents/Resources/Java."""
        monkeypatch.setattr("sfconfig.paths.get_platform", lambda: "Darwin")
        monkeypatch.setattr("os.path.exists", lambda path: path == MAC_LEGACY)

        assert get_sf_jar_path() == MAC_LEGACY

    def test_raises_when_no_layout_exists(self, monkeypatch):
        """Neither layout present is still a clear SFNotFoundError."""
        monkeypatch.setattr("sfconfig.paths.get_platform", lambda: "Darwin")
        monkeypatch.setattr("os.path.exists", lambda path: False)

        with pytest.raises(SFNotFoundError):
            get_sf_jar_path()


class TestExplicitPathWins:
    """An explicit path or SF_PATH must take precedence over auto-detection."""

    def test_explicit_argument_wins(self, monkeypatch):
        monkeypatch.setattr("os.path.exists", lambda path: True)

        assert get_sf_jar_path("/custom/sf/jars") == "/custom/sf/jars"

    def test_env_var_wins_over_defaults(self, monkeypatch):
        monkeypatch.setenv("SF_PATH", "/env/sf/jars")
        monkeypatch.setattr("os.path.exists", lambda path: True)

        assert get_sf_jar_path() == "/env/sf/jars"
