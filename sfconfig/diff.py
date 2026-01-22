"""SFDiff class for comparing Screaming Frog configurations."""

from typing import Any, Dict, List, Optional


class SFDiff:
    """Represents a diff between two SF configs.

    Example:
        >>> diff = SFConfig.diff("old.seospiderconfig", "new.seospiderconfig")
        >>> if diff.has_changes:
        ...     print(f"Found {diff.change_count} differences:")
        ...     print(diff)
    """

    def __init__(self, data: Dict[str, Any]):
        """Initialize SFDiff with diff data from Java CLI.

        Args:
            data: Parsed JSON response from the Java CLI --diff command.
        """
        self._data = data

    @property
    def has_changes(self) -> bool:
        """Check if there are any differences between the configs."""
        return len(self.changes) > 0

    @property
    def change_count(self) -> int:
        """Get the total number of differences."""
        return self._data.get("totalDifferences", len(self.changes))

    @property
    def changes(self) -> List[Dict[str, Any]]:
        """Get list of all changes.

        Returns:
            List of change dictionaries, each containing:
            - path: The field path that changed
            - valueA / old: The value in the first config
            - valueB / new: The value in the second config
        """
        return self._data.get("differences", [])

    @property
    def config_version_a(self) -> str:
        """Get the version of the first config."""
        return self._data.get("configVersionA", "unknown")

    @property
    def config_version_b(self) -> str:
        """Get the version of the second config."""
        return self._data.get("configVersionB", "unknown")

    def changes_for(self, prefix: str) -> List[Dict[str, Any]]:
        """Get changes filtered by path prefix.

        Args:
            prefix: The path prefix to filter by (e.g., "mCrawlConfig").

        Returns:
            List of changes where the path starts with the given prefix.
        """
        return [c for c in self.changes if c.get("path", "").startswith(prefix)]

    def to_dict(self) -> Dict[str, Any]:
        """Return the full diff as a dictionary.

        Returns:
            Dictionary containing:
            - config_version_a: Version of first config
            - config_version_b: Version of second config
            - total_differences: Number of differences
            - differences: List of change objects
        """
        return {
            "config_version_a": self.config_version_a,
            "config_version_b": self.config_version_b,
            "total_differences": self.change_count,
            "differences": self.changes,
        }

    def __str__(self) -> str:
        """Return human-readable string representation of the diff."""
        if not self.has_changes:
            return "No changes"

        lines = []
        for change in self.changes:
            path = change.get("path", "unknown")
            # Handle both naming conventions from Java CLI
            old = change.get("valueA", change.get("old", "?"))
            new = change.get("valueB", change.get("new", "?"))

            # Format values for display
            old_str = self._format_value(old)
            new_str = self._format_value(new)

            lines.append(f"{path}: {old_str} -> {new_str}")

        return "\n".join(lines)

    def __repr__(self) -> str:
        """Return developer-friendly representation."""
        return f"<SFDiff changes={self.change_count}>"

    def __len__(self) -> int:
        """Return the number of changes."""
        return self.change_count

    def __bool__(self) -> bool:
        """Return True if there are changes."""
        return self.has_changes

    def __iter__(self):
        """Iterate over changes."""
        return iter(self.changes)

    @staticmethod
    def _format_value(value: Any) -> str:
        """Format a value for display.

        Args:
            value: The value to format.

        Returns:
            String representation of the value.
        """
        if value is None:
            return "null"
        if isinstance(value, str):
            # Truncate long strings
            if len(value) > 50:
                return f'"{value[:47]}..."'
            return f'"{value}"'
        if isinstance(value, list):
            if len(value) == 0:
                return "[]"
            if len(value) <= 3:
                return str(value)
            return f"[{len(value)} items]"
        if isinstance(value, dict):
            return f"{{{len(value)} keys}}"
        return str(value)
