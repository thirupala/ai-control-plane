@echo off
echo Building all modules...
call mvn clean install -DskipTests

if %ERRORLEVEL% EQU 0 (
    echo Build successful!
    echo Starting Quarkus dev mode...
    call mvn quarkus:dev -pl decisionmesh-api
) else (
    echo Build failed!
    exit /b 1
)