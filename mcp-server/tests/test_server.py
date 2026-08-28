from __future__ import annotations

import asyncio
import json
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

    async def test_bridge_client_delete_json(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "DELETE")
            self.assertEqual(request.url.path, "/debug/watch")
            return httpx.Response(200, json={"ok": True, "data": {"removed": 1}})

        client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        payload = await client.delete_json("/debug/watch")
        self.assertEqual(payload["data"]["removed"], 1)


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

    async def test_hide_view_posts_optional_secondary_id(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.url.path, "/hide-view")
            self.assertEqual(
                json.loads(request.content),
                {"viewId": "example.view", "secondaryId": "2"},
            )
            return httpx.Response(200, json={"ok": True, "data": {"hidden": True}})

        server.bridge_client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        payload = await server.post_hide_view("example.view", "2")
        self.assertTrue(payload["data"]["hidden"])

    async def test_debug_snapshot_posts_selector_and_paths(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.url.path, "/debug/snapshot")
            body = json.loads(request.content)
            self.assertEqual(body["expressions"], ["counter", "ports[0].quality"])
            self.assertEqual(body["configName"], "generation")
            self.assertEqual(body["frame"], 0)
            self.assertEqual(body["format"], "natural")
            return httpx.Response(200, json={"ok": True, "data": {"successCount": 2}})

        server.bridge_client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        payload = await server.fetch_debug_snapshot(
            ["counter", "ports[0].quality"], config_name="generation"
        )
        self.assertEqual(payload["data"]["successCount"], 2)

    async def test_debug_suspend_posts_target_without_ui_fallback(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.url.path, "/debug/suspend")
            self.assertEqual(
                json.loads(request.content),
                {"sessionId": "5", "allowUiFallback": False},
            )
            return httpx.Response(200, json={"ok": True, "data": {"sessionId": "5"}})

        server.bridge_client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        payload = await server.call_debug_suspend(session_id="5")
        self.assertEqual(payload["data"]["sessionId"], "5")

    async def test_debug_resume_ui_fallback_is_explicit(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.url.path, "/debug/resume")
            self.assertEqual(
                json.loads(request.content),
                {"launchId": "abc", "allowUiFallback": True},
            )
            return httpx.Response(200, json={"ok": True, "data": {"launchId": "abc"}})

        server.bridge_client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        payload = await server.call_debug_resume(
            launch_id="abc", allow_ui_fallback=True
        )
        self.assertEqual(payload["data"]["launchId"], "abc")

    async def test_debug_terminate_can_target_one_launch(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.url.path, "/debug/terminate")
            self.assertEqual(
                json.loads(request.content),
                {"sessionId": "5", "all": False},
            )
            return httpx.Response(
                200,
                json={"ok": True, "data": {"terminatedLaunches": 1}},
            )

        server.bridge_client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        payload = await server.call_debug_terminate(session_id="5")
        self.assertEqual(payload["data"]["terminatedLaunches"], 1)

    async def test_danger_off_error_surfaces(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.url.path, "/debug/step")
            return httpx.Response(
                403,
                json={"ok": False, "error": {"code": "DANGER_OFF"}},
            )

        server.bridge_client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        with self.assertRaises(BridgeHttpError) as exc_info:
            await server.call_debug_step("over")

        self.assertEqual(exc_info.exception.status_code, 403)
        self.assertEqual(exc_info.exception.payload["error"]["code"], "DANGER_OFF")

    async def test_call_launch_run_with_overrides_posts_body(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.url.path, "/launch/run-with-overrides")
            body = json.loads(request.content)
            self.assertEqual(body["configName"], "cfg")
            self.assertEqual(body["mode"], "debug")
            self.assertEqual(body["copyName"], "cfg_copy")
            self.assertEqual(body["overrides"], {"a.bool": False, "b.text": "x"})
            return httpx.Response(200, json={"ok": True, "data": {"configName": "cfg_copy"}})

        server.bridge_client = BridgeClient(
            BridgeSettings(base_url="http://127.0.0.1:39231", token="abc"),
            transport=_mock_transport(handler),
        )

        payload = await server.call_launch_run_with_overrides(
            "cfg", "debug", "cfg_copy", {"a.bool": False, "b.text": "x"}
        )
        self.assertEqual(payload["data"]["configName"], "cfg_copy")



class ServerRegistrationTests(unittest.TestCase):
    def test_create_server_registers_all_phase_tools(self) -> None:
        """Sanity: registered tools must include the core set from every phase."""
        mcp_server = server.create_server()
        tool_names = set(tool.name for tool in asyncio.run(mcp_server.list_tools()))
        required = {
            # Phase 1 ??read-only
            "health",
            "get_state",
            "list_commands",
            "find_command",
            "list_registry_menus",
            "list_legacy_actions",
            "list_views",
            "list_perspectives",
            "list_wizards",
            "list_visible_menu",
            "list_problems",
            # Phase 2 ??safe writes
            "show_view",
            "hide_view",
            "switch_perspective",
            "open_file",
            "save_all",
            "build_project",
            "list_editors",
            # Phase 3 ??S32DS discovery
            "s32ds_inventory",
            "s32ds_config_tools",
            "s32ds_debuggers",
            "s32ds_toolchains",
            # Phase 3.5 ??guardrails
            "list_launch_configs",
            "analyze_launch_config",
            "debug_sessions",
            "debug_stackframes",
            "debug_variables",
            "debug_snapshot",
            "debug_breakpoints",
            "dialogs_open",
            "dialog_widgets",
            "console_list",
            "console_tail",
            # Phase 5 ??danger gate + mutating debug/launch operations
            "danger_state",
            "danger_enable",
            "danger_disable",
            "debug_step",
            "debug_resume",
            "debug_suspend",
            "debug_terminate",
            "debug_restart",
            "debug_breakpoint_set",
            "debug_breakpoint_clear",
            "debug_memory_write",
            "debug_register_write",
            "launch_run",
            "launch_run_with_overrides",
            # Phase 6 ??expression/watch/variable/run-to-line
            "debug_evaluate",
            "debug_watch_list",
            "debug_watch_add",
            "debug_watch_remove",
            "debug_variable_write",
            "debug_run_to_line",
            "debug_jump_to_line",
        }
        missing = required - tool_names
        self.assertEqual(missing, set(), f"missing tools: {missing}")
