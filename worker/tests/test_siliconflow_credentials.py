import unittest
from unittest.mock import patch

from config import resolve_siliconflow_api_key, settings


class ResolveSiliconflowApiKeyTest(unittest.TestCase):
    def test_prefers_model_config_api_key(self) -> None:
        with patch.object(settings, "siliconflow_api_key", "env-key"):
            resolved = resolve_siliconflow_api_key({"apiKey": "db-key"})
        self.assertEqual(resolved, "db-key")

    def test_falls_back_to_env_when_model_config_missing(self) -> None:
        with patch.object(settings, "siliconflow_api_key", "env-key"):
            resolved = resolve_siliconflow_api_key({"apiKey": None})
        self.assertEqual(resolved, "env-key")

    def test_ignores_placeholder_model_config_key(self) -> None:
        with patch.object(settings, "siliconflow_api_key", "env-key"):
            resolved = resolve_siliconflow_api_key({"apiKey": "replace-with-real-siliconflow-key"})
        self.assertEqual(resolved, "env-key")


if __name__ == "__main__":
    unittest.main()
