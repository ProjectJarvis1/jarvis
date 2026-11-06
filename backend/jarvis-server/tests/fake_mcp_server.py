"""시험용 MCP 서버(stdio). 노션처럼 검색 결과 JSON 을 돌려주고, 느린 도구와 실패하는 도구도 있다."""
import json
import time

from mcp.server import MCPServer

server = MCPServer("fake")


@server.tool(name="API-post-search")
def search(query: str, page_size: int = 10) -> str:
    return json.dumps({"results": [{"id": "p1", "object": "page", "url": "https://n/p1",
                                    "properties": {"Name": {"type": "title", "title": [{"plain_text": f"{query} 페이지"}]}},
                                    "page_size_seen": page_size}]})


@server.tool(name="API-patch-block-children")
def append(block_id: str, children: list) -> str:
    return json.dumps({"ok": True, "block_id": block_id, "text": children[0]["paragraph"]["rich_text"][0]["text"]["content"]})


@server.tool(name="slow")
def slow() -> str:
    time.sleep(5)
    return "늦음"


@server.tool(name="boom")
def boom() -> str:
    raise ValueError("터짐")


if __name__ == "__main__":
    server.run()
