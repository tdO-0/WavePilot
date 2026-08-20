@echo off
REM WavePilot Full Mode Launcher (real API + local MATLAB + Milvus)
REM Prerequisites: Docker Desktop, Maven, JDK 17+
REM Setup: copy .env.example to .env and fill in DASHSCOPE_API_KEY / MATLAB_EXECUTABLE
cd /d "%~dp0"

REM Use IntelliJ bundled JDK 21 (system JDK 24 is incompatible)
set "JAVA_HOME=E:\Java_tools\Idea\IntelliJ IDEA 2024.3.5\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist .env (
    echo [ERROR] .env file not found.
    echo Copying .env.example to .env ...
    copy .env.example .env >nul
    echo Please edit .env with your DASHSCOPE_API_KEY and MATLAB_EXECUTABLE, then re-run.
    pause
    exit /b 1
)

echo [1/2] Starting Milvus via Docker...
docker compose -f vector-database.yml up -d

echo [2/2] Loading .env and starting WavePilot...
for /f "usebackq tokens=1,* delims==" %%a in (`findstr /v /b "#" .env`) do set "%%a=%%b"

echo.
echo App will be available at http://localhost:%SERVER_PORT%
echo Press Ctrl+C to stop.
mvn spring-boot:run
pause
