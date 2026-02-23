import os
import subprocess
import tempfile

from langchain_community.tools import DuckDuckGoSearchRun
from langchain_core.tools import tool
from langchain_ollama import ChatOllama
from langgraph.prebuilt import create_react_agent

# ── model ──────────────────────────────────────────────────────────────────
MODEL_NAME = os.getenv("OLLAMA_MODEL", "llama3.2")


# ── tools ──────────────────────────────────────────────────────────────────

def build_tools():
    search = DuckDuckGoSearchRun()
    search.name = "web_search"
    search.description = (
        "Search the web for up-to-date information. "
        "Input: a plain-text search query."
    )

    @tool
    def execute_python(code: str) -> str:
        """Execute Python code and return its stdout/stderr output.

        Use this for calculations, data processing, and logic tasks.

        Args:
            code: Valid Python source code to execute.
        """
        tmp_path = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", suffix=".py", delete=False, encoding="utf-8"
            ) as f:
                f.write(code)
                tmp_path = f.name

            result = subprocess.run(
                ["python", tmp_path],
                capture_output=True,
                text=True,
                timeout=30,
            )
            if result.returncode == 0:
                return result.stdout.strip() or "(no output)"
            return f"Exit {result.returncode}:\n{result.stderr.strip()}"
        except subprocess.TimeoutExpired:
            return "Error: execution timed out (30 s limit)"
        except Exception as exc:
            return f"Error: {exc}"
        finally:
            if tmp_path and os.path.exists(tmp_path):
                os.unlink(tmp_path)

    @tool
    def read_file(file_path: str) -> str:
        """Read and return the text contents of a file on disk.

        Args:
            file_path: Absolute or relative path to the file.
        """
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                data = f.read(12_000)
            return data if data else "(empty file)"
        except FileNotFoundError:
            return f"Error: file not found – {file_path}"
        except Exception as exc:
            return f"Error: {exc}"

    @tool
    def write_file(file_path: str, content: str) -> str:
        """Write text content to a file, creating parent directories as needed.

        Args:
            file_path: Absolute or relative path to the file to create/overwrite.
            content: Text content to write.
        """
        try:
            parent = os.path.dirname(file_path)
            if parent:
                os.makedirs(parent, exist_ok=True)
            with open(file_path, "w", encoding="utf-8") as f:
                f.write(content)
            return f"Wrote {len(content)} chars to {file_path}"
        except Exception as exc:
            return f"Error: {exc}"

    @tool
    def list_files(directory: str) -> str:
        """List files and subdirectories inside a directory.

        Args:
            directory: Path to the directory to list (use '.' for current directory).
        """
        try:
            entries = sorted(
                os.scandir(directory), key=lambda e: (not e.is_dir(), e.name)
            )
            lines = []
            for e in entries:
                tag = "DIR " if e.is_dir() else "FILE"
                size = f" ({e.stat().st_size} B)" if e.is_file() else ""
                lines.append(f"[{tag}] {e.name}{size}")
            return "\n".join(lines) if lines else "(empty directory)"
        except Exception as exc:
            return f"Error: {exc}"

    return [search, execute_python, read_file, write_file, list_files]


# ── system prompt ──────────────────────────────────────────────────────────

SYSTEM_PROMPT = """You are a helpful AI assistant powered by Llama with access to tools:

• web_search       – look up current information on the internet
• execute_python   – run Python code for calculations or data processing
• read_file        – read a file from disk
• write_file       – write / create a file on disk
• list_files       – list the contents of a directory

Think step-by-step. Use tools whenever they help you give a more accurate answer.
Briefly explain your reasoning before and after using a tool.
"""


# ── factory ────────────────────────────────────────────────────────────────

def create_agent():
    llm = ChatOllama(model=MODEL_NAME, temperature=0, num_predict=2048)
    tools = build_tools()
    return create_react_agent(llm, tools, prompt=SYSTEM_PROMPT)
