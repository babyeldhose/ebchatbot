@echo off
echo ============================================================
echo  Llama Agent Backend
echo ============================================================
echo.

REM Check if venv exists, create if not
IF NOT EXIST venv (
    echo Creating virtual environment...
    python -m venv venv
)

REM Activate venv
call venv\Scripts\activate.bat

REM Install / upgrade dependencies
echo Installing dependencies...
pip install -r requirements.txt --quiet

echo.
echo Starting server on http://0.0.0.0:8000
echo WebSocket endpoint: ws://YOUR_PC_IP:8000/ws/chat
echo.
echo TIP: Find your IP with: ipconfig
echo      For Android Emulator use: ws://10.0.2.2:8000/ws/chat
echo.

python main.py
pause
