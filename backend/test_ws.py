"""Quick WebSocket smoke-test for the Llama Agent backend."""
import asyncio
import json


async def test():
    try:
        import websockets
    except ImportError:
        print("Installing websockets...")
        import subprocess, sys
        subprocess.run([sys.executable, "-m", "pip", "install", "websockets", "-q"])
        import websockets

    url = "ws://localhost:8000/ws/chat"
    print(f"Connecting to {url} ...")

    try:
        async with websockets.connect(url) as ws:
            print("WebSocket connected!\n")
            await ws.send(json.dumps({"message": "What is 2 + 2? Answer briefly."}))
            print("Message sent. Waiting for response...\n")

            full_response = ""
            while True:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=60.0)
                except asyncio.TimeoutError:
                    print("\n[timeout waiting for response]")
                    break

                data = json.loads(raw)
                kind = data.get("type")

                if kind == "token":
                    content = data.get("content", "")
                    print(content, end="", flush=True)
                    full_response += content
                elif kind == "tool_start":
                    print(f"\n[TOOL START] {data['tool']} | input: {data.get('input','')[:120]}")
                elif kind == "tool_end":
                    print(f"[TOOL END]   {data['tool']} | output: {data.get('output','')[:120]}\n")
                elif kind == "done":
                    print("\n\n--- Response complete ---")
                    break
                elif kind == "error":
                    print(f"\n[ERROR] {data.get('message','')}")
                    break

            print(f"\nFull response length: {len(full_response)} chars")
    except Exception as e:
        print(f"Connection error: {e}")


if __name__ == "__main__":
    asyncio.run(test())
