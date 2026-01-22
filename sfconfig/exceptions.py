"""Custom exceptions for sf-config-tool."""


class SFConfigError(Exception):
    """Base exception for sf-config-tool."""
    pass


class SFNotFoundError(SFConfigError):
    """Screaming Frog is not installed or not found."""
    pass


class SFValidationError(SFConfigError):
    """Invalid field path or value."""
    pass


class SFParseError(SFConfigError):
    """Could not parse config file."""
    pass


class SFCrawlError(SFConfigError):
    """Crawl execution failed."""
    pass
