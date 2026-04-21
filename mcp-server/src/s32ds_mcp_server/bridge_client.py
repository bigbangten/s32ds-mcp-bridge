from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Optional

import httpx


class BridgeHttpError(RuntimeError):
    def __init__(self, status_code: int, payload: Any):
        self.status_code = status_code
        self.payload = payload
        super().__init__(f"Bridge request failed with HTTP {status_code}")


@dataclass(frozen=True)
class BridgeSettings:
    base_url: str
    token: str

    @classmethod
    def from_env(cls) -> "BridgeSettings":
        return cls(
            base_url=os.environ.get("S32DS_BRIDGE_URL", "http://127.0.0.1:39231").rstrip("/"),
            token=os.environ.get("S32DS_BRIDGE_TOKEN", "").strip(),
        )


class BridgeClient:
    def __init__(
        self,
        settings: Optional[BridgeSettings] = None,
        *,
        transport: Optional[httpx.AsyncBaseTransport] = None,
    ) -> None:
        self.settings = settings or BridgeSettings.from_env()
        self.transport = transport

    def headers(self) -> dict[str, str]:
        if not self.settings.token:
            return {}
        return {"Authorization": f"Bearer {self.settings.token}"}

    async def get_json(self, path: str, *, timeout: float = 30.0) -> Any:
        return await self.request_json("GET", path, timeout=timeout)

    async def post_json(self, path: str, payload: dict[str, Any], *, timeout: float = 30.0) -> Any:
        return await self.request_json("POST", path, json=payload, timeout=timeout)

    async def request_json(
        self,
        method: str,
        path: str,
        *,
        json: Optional[dict[str, Any]] = None,
        timeout: float = 30.0,
    ) -> Any:
        async with httpx.AsyncClient(
            base_url=self.settings.base_url,
            timeout=timeout,
            transport=self.transport,
        ) as client:
            response = await client.request(method, path, json=json, headers=self.headers())

        try:
            payload = response.json()
        except ValueError:
            payload = response.text

        if response.is_error:
            raise BridgeHttpError(response.status_code, payload)
        return payload
