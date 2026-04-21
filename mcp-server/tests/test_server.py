from __future__ import annotations

import asyncio
import unittest

import httpx

from s32ds_mcp_server.bridge_client import BridgeClient, BridgeHttpError, BridgeSettings
from s32ds_mcp_server import server


def _mock_transport(handler):
    return httpx.MockTransport(handler)


class BridgeClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_bridge_client_attaches_bearer_token(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.headers["Authorization"], "Bearer secret-token")
            return httpx.Response(200, json={"ok": True, "data": {"bridgeVersion": "0.1.0"}})

        client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="secret-token"),
            transport=_mock_transport(handler),
        )

        payload = await client.get_json("/health")
        self.assertTrue(payload["ok"])

    async def test_bridge_client_raises_on_http_error(self) -> None:
        def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(401, json={"ok": False, "error": {"code": "UNAUTHORIZED"}})

        client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token=""),
            transport=_mock_transport(handler),
        )

        with self.assertRaises(BridgeHttpError) as exc_info:
            await client.get_json("/health")

        self.assertEqual(exc_info.exception.status_code, 401)


class ServerFetchTests(unittest.IsolatedAsyncioTestCase):
    async def test_fetch_health_uses_bridge_mock(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "GET")
            self.assertEqual(request.url.path, "/health")
            return httpx.Response(200, json={"ok": True, "data": {"bridgeVersion": "0.1.0"}})

        server.bridge_client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        payload = await server.fetch_health()
        self.assertEqual(payload["data"]["bridgeVersion"], "0.1.0")

    async def test_fetch_command_search_passes_query(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "GET")
            self.assertEqual(request.url.path, "/commands/search")
            self.assertEqual(request.url.params["q"], "Build Project")
            return httpx.Response(200, json={"ok": True, "data": {"count": 1}})

        server.bridge_client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        payload = await server.fetch_command_search("Build Project")
        self.assertEqual(payload["data"]["count"], 1)

    async def test_fetch_visible_menu_posts_body(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.url.path, "/visible-menu")
            self.assertEqual(request.content, b'{"locationUri":"menu:org.eclipse.ui.main.menu"}')
            return httpx.Response(200, json={"ok": True, "data": {"items": []}})

        server.bridge_client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        payload = await server.fetch_visible_menu("menu:org.eclipse.ui.main.menu")
        self.assertTrue(payload["ok"])


class ServerRegistrationTests(unittest.TestCase):
    def test_create_server_registers_phase1_tools(self) -> None:
        mcp_server = server.create_server()
        tool_names = sorted(tool.name for tool in asyncio.run(mcp_server.list_tools()))
        self.assertEqual(
            tool_names,
            sorted(
                [
                    "find_command",
                    "get_state",
                    "health",
                    "list_commands",
                    "list_legacy_actions",
                    "list_perspectives",
                    "list_registry_menus",
                    "list_views",
                    "list_visible_menu",
                    "list_wizards",
                ]
            ),
        )
