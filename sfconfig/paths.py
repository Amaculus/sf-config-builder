"""Screaming Frog installation path detection."""

import os
import platform
import shutil
from pathlib import Path
from typing import Optional

from .exceptions import SFNotFoundError


# Default installation paths by platform
SF_PATHS = {
    "Darwin": "/Applications/Screaming Frog SEO Spider.app/Contents/Resources/Java",
    "Windows": "C:/Program Files/Screaming Frog SEO Spider",
    "Linux": "/usr/share/screamingfrogseospider",
}

SF_CLI_PATHS = {
    "Darwin": "/Applications/Screaming Frog SEO Spider.app/Contents/MacOS/ScreamingFrogSEOSpider",
    "Windows": "C:/Program Files/Screaming Frog SEO Spider/ScreamingFrogSEOSpiderCli.exe",
    "Linux": "screamingfrogseospider",
}

SF_JRE_PATHS = {
    "Darwin": "/Applications/Screaming Frog SEO Spider.app/Contents/PlugIns/jre.bundle/Contents/Home/bin/java",
    "Windows": "C:/Program Files/Screaming Frog SEO Spider/jre/bin/java.exe",
    "Linux": "/usr/share/screamingfrogseospider/jre/bin/java",
}


def get_platform() -> str:
    """Get the current platform name."""
    return platform.system()


def get_sf_jar_path() -> str:
    """Get path to SF's JAR files directory.

    Returns:
        Path to the directory containing SF's JAR files.

    Raises:
        SFNotFoundError: If Screaming Frog installation is not found.
    """
    # Try custom path from env var first
    custom = os.environ.get("SF_PATH")
    if custom and os.path.exists(custom):
        return custom

    # Try default path for current platform
    plat = get_platform()
    path = SF_PATHS.get(plat)

    if path and os.path.exists(path):
        return path

    raise SFNotFoundError(
        "Screaming Frog not found.\n"
        f"Expected at: {path}\n"
        "Install from: https://www.screamingfrog.co.uk/seo-spider/\n"
        "Or set SF_PATH environment variable."
    )


def get_sf_cli_path() -> str:
    """Get path to SF CLI executable.

    Returns:
        Path to the Screaming Frog CLI executable.

    Raises:
        SFNotFoundError: If CLI executable is not found.
    """
    # Try custom path from env var
    custom = os.environ.get("SF_CLI_PATH")
    if custom and os.path.exists(custom):
        return custom

    plat = get_platform()
    path = SF_CLI_PATHS.get(plat)

    if path and os.path.exists(path):
        return path

    # On Linux, check if it's in PATH
    if plat == "Linux":
        which_result = shutil.which("screamingfrogseospider")
        if which_result:
            return which_result

    raise SFNotFoundError(
        "Screaming Frog CLI not found.\n"
        f"Expected at: {path}\n"
        "Or set SF_CLI_PATH environment variable."
    )


def get_java_path() -> str:
    """Get path to Java executable.

    Prefers SF's bundled JRE, falls back to system Java.

    Returns:
        Path to Java executable.

    Raises:
        SFNotFoundError: If no Java installation is found.
    """
    # Try custom path from env var
    custom = os.environ.get("JAVA_HOME")
    if custom:
        java_path = os.path.join(custom, "bin", "java")
        if get_platform() == "Windows":
            java_path += ".exe"
        if os.path.exists(java_path):
            return java_path

    # Try SF's bundled JRE first
    plat = get_platform()
    jre_path = SF_JRE_PATHS.get(plat)

    if jre_path and os.path.exists(jre_path):
        return jre_path

    # Fall back to system Java
    java_cmd = "java.exe" if plat == "Windows" else "java"
    which_result = shutil.which(java_cmd)
    if which_result:
        return which_result

    raise SFNotFoundError(
        "Java not found.\n"
        "Screaming Frog installation may be corrupted or Java is not installed.\n"
        "Set JAVA_HOME environment variable if Java is installed elsewhere."
    )


def get_classpath_separator() -> str:
    """Get the classpath separator for the current platform."""
    return ";" if get_platform() == "Windows" else ":"


def get_default_config_path() -> Optional[Path]:
    """Get path to SF's default config file location.

    Returns:
        Path to default config, or None if not found.
    """
    plat = get_platform()

    if plat == "Windows":
        appdata = os.environ.get("APPDATA")
        if appdata:
            path = Path(appdata) / "Screaming Frog SEO Spider" / "spider.config"
            if path.exists():
                return path
    elif plat == "Darwin":
        home = Path.home()
        path = home / "Library" / "Application Support" / "Screaming Frog SEO Spider" / "spider.config"
        if path.exists():
            return path
    elif plat == "Linux":
        home = Path.home()
        path = home / ".ScreamingFrogSEOSpider" / "spider.config"
        if path.exists():
            return path

    return None
