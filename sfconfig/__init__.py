"""sf-config-tool: Manage Screaming Frog configs programmatically.

This library provides a Python interface for managing Screaming Frog
.seospiderconfig files, enabling inspection, diffing, modification,
and crawl execution without using the SF GUI.

Example:
    >>> from sfconfig import SFConfig
    >>> config = SFConfig.load("base.seospiderconfig")
    >>> config.add_extraction("Price", "//span[@class='price']")
    >>> config.save("client.seospiderconfig")
    >>> config.run_crawl("https://example.com", output_folder="./results")
"""

from .config import SFConfig
from .diff import SFDiff
from .exceptions import (
    SFConfigError,
    SFCrawlError,
    SFNotFoundError,
    SFParseError,
    SFValidationError,
)

__version__ = "0.1.3"
__all__ = [
    "SFConfig",
    "SFDiff",
    "SFConfigError",
    "SFNotFoundError",
    "SFValidationError",
    "SFParseError",
    "SFCrawlError",
]
