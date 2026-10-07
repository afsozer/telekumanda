@echo off
echo Running Android Unit Tests...
cd %~dp0android
call .\gradlew.bat :app:testDebugUnitTest
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Android unit tests failed!
    exit /b %ERRORLEVEL%
)
echo [SUCCESS] All Android unit tests passed!
exit /b 0
