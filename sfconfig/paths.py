"""Screaming Frog installation path detection."""

import os
import platform
import shutil
from pathlib import Path
from typing import Optional

from .exceptions import SFNotFoundError


# Default installation paths by platform (list for multiple possible locations)
SF_PATHS = {
    "Darwin": ["/Applications/Screaming Frog SEO Spider.app/Contents/Resources/Java"],
    "Windows": [
        "C:/Program Files/Screaming Frog SEO Spider",
        "C:/Program Files (x86)/Screaming Frog SEO Spider",
    ],
    "Linux": ["/usr/share/screamingfrogseospider"],
}

SF_CLI_NAMES = {
    "Darwin": "ScreamingFrogSEOSpider",
    "Windows": "ScreamingFrogSEOSpiderCli.exe",
    "Linux": "screamingfrogseospider",
}


def get_platform() -> str:
    """Get the current platform name."""
    return platform.system()


def get_sf_jar_path(sf_path: Optional[str] = None) -> str:
    """Get path to SF's JAR files directory.

    Args:
        sf_path: Optional custom path to SF installation.
                 If not provided, checks SF_PATH env var then common locations.

    Returns:
        Path to the directory containing SF's JAR files.

    Raises:
        SFNotFoundError: If Screaming Frog installation is not found.
    """
    # Try explicit argument first
    if sf_path and os.path.exists(sf_path):
        return sf_path

    # Try custom path from env var
    env_path = os.environ.get("SF_PATH")
    if env_path and os.path.exists(env_path):
        return env_path

    # Try default paths for current platform
    plat = get_platform()
    paths = SF_PATHS.get(plat, [])

    for path in paths:
        if os.path.exists(path):
            return path

    raise SFNotFoundError(
        "Screaming Frog not found.\n"
        f"Checked: {paths}\n"
        "Install from: https://www.screamingfrog.co.uk/seo-spider/\n"
        "Or pass sf_path argument, or set SF_PATH environment variable."
    )


def get_sf_cli_path(sf_path: Optional[str] = None) -> str:
    """Get path to SF CLI executable.

    Args:
        sf_path: Optional custom path to SF installation directory.

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
    cli_name = SF_CLI_NAMES.get(plat, "screamingfrogseospider")

    # If sf_path provided, derive CLI path from it
    if sf_path:
        if plat == "Darwin":
            # macOS: /Applications/Screaming Frog SEO Spider.app/Contents/MacOS/ScreamingFrogSEOSpider
            cli_path = sf_path.replace("/Contents/Resources/Java", f"/Contents/MacOS/{cli_name}")
        else:
            cli_path = os.path.join(sf_path, cli_name)
        if os.path.exists(cli_path):
            return cli_path

    # Try default paths
    paths = SF_PATHS.get(plat, [])
    for base_path in paths:
        if plat == "Darwin":
            cli_path = base_path.replace("/Contents/Resources/Java", f"/Contents/MacOS/{cli_name}")
        else:
            cli_path = os.path.join(base_path, cli_name)
        if os.path.exists(cli_path):
            return cli_path

    # On Linux, check if it's in PATH
    if plat == "Linux":
        which_result = shutil.which("screamingfrogseospider")
        if which_result:
            return which_result

    raise SFNotFoundError(
        "Screaming Frog CLI not found.\n"
        "Or set SF_CLI_PATH environment variable."
    )


def get_java_path(sf_path: Optional[str] = None) -> str:
    """Get path to Java executable.

    Prefers SF's bundled JRE, falls back to system Java.

    Args:
        sf_path: Optional custom path to SF installation directory.

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

    plat = get_platform()
    java_name = "java.exe" if plat == "Windows" else "java"

    # If sf_path provided, try its bundled JRE
    if sf_path:
        if plat == "Darwin":
            jre_path = sf_path.replace(
                "/Contents/Resources/Java",
                f"/Contents/PlugIns/jre.bundle/Contents/Home/bin/{java_name}"
            )
        else:
            jre_path = os.path.join(sf_path, "jre", "bin", java_name)
        if os.path.exists(jre_path):
            return jre_path

    # Try default SF paths for bundled JRE
    paths = SF_PATHS.get(plat, [])
    for base_path in paths:
        if plat == "Darwin":
            jre_path = base_path.replace(
                "/Contents/Resources/Java",
                f"/Contents/PlugIns/jre.bundle/Contents/Home/bin/{java_name}"
            )
        else:
            jre_path = os.path.join(base_path, "jre", "bin", java_name)
        if os.path.exists(jre_path):
            return jre_path

    # Fall back to system Java
    which_result = shutil.which(java_name)
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
