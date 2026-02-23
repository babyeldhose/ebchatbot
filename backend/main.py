import json
import traceback

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from langchain_core.messages import HumanMessage

from agent import create_agent

app = FastAPI(title="Llama Agent API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# Build the agent once at startup (loads model, tools, etc.)
print("Loading agent …")
agent = create_agent()
print("Agent ready.")


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.websocket("/ws/chat")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()

    # Per-connection conversation history
    history: list = []

    try:
        while True:
            raw = await websocket.receive_text()
            data = json.loads(raw)
            user_text = data.get("message", "").strip()
            if not user_text:
                continue

            history.append(HumanMessage(content=user_text))

            try:
                async for event in agent.astream_events(
                    {"messages": history},
                    version="v2",
                ):
                    kind = event["event"]

                    # ── stream LLM tokens ──────────────────────────────
                    if kind == "on_chat_model_stream":
                        chunk = event["data"]["chunk"]
                        content = chunk.content
                        # Skip list content (tool-call deltas)
                        if isinstance(content, str) and content:
                            await websocket.send_text(
                                json.dumps({"type": "token", "content": content})
                            )

                    # ── tool invocation start ──────────────────────────
                    elif kind == "on_tool_start":
                        tool_input = event["data"].get("input", {})
                        await websocket.send_text(
                            json.dumps(
                                {
                                    "type": "tool_start",
                                    "tool": event["name"],
                                    "input": str(tool_input)[:400],
                                }
                            )
                        )

                    # ── tool result ────────────────────────────────────
                    elif kind == "on_tool_end":
                        tool_output = event["data"].get("output", "")
                        await websocket.send_text(
                            json.dumps(
                                {
                                    "type": "tool_end",
                                    "tool": event["name"],
                                    "output": str(tool_output)[:600],
                                }
                            )
                        )

                    # ── capture final history from LangGraph state ─────
                    elif (
                        kind == "on_chain_end"
                        and event.get("name") == "LangGraph"
                    ):
                        output = event["data"].get("output", {})
                        if output and "messages" in output:
                            history = list(output["messages"])

                await websocket.send_text(json.dumps({"type": "done"}))

            except Exception:
                err = traceback.format_exc()
                await websocket.send_text(
                    json.dumps({"type": "error", "message": err[:600]})
                )
                await websocket.send_text(json.dumps({"type": "done"}))

    except WebSocketDisconnect:
        pass


if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
