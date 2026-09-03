@echo off
setlocal

rem Portable launcher. Pass --jdk C:\path\to\jdk, or set JAVA_HOME.
set "JDK_DIR=%JAVA_HOME%"
set "FIRST_ARG=%~1"
if /I "%~1"=="--jdk" (
    if "%~2"=="" (
        echo error: --jdk requires a JDK directory 1>&2
        exit /b 2
    )
    set "JDK_DIR=%~2"
) else if /I "%FIRST_ARG:~0,7%"=="--jdk=" (
    set "JDK_DIR=%FIRST_ARG:~7%"
)

if "%JDK_DIR%"=="" (
    echo error: provide --jdk C:\path\to\jdk ^(or set JAVA_HOME^) 1>&2
    exit /b 2
)
if not exist "%JDK_DIR%\bin\java.exe" (
    echo error: "%JDK_DIR%\bin\java.exe" was not found 1>&2
    exit /b 2
)

rem Keep --jdk in the argument list; TestBenchmarkMain strips this launcher-only
rem option before handing the remaining arguments to JMH.
"%JDK_DIR%\bin\java.exe" --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -jar "%~dp0fastblake-test.jar" %*
exit /b %ERRORLEVEL%
