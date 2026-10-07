@if "%DEBUG%"=="" @echo off
setlocal EnableExtensions
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
set GRADLE_USER_HOME=D:\game-build-cache\gradle-cyuvisual-fabric
if not exist "D:\game-build-cache\tmp" mkdir "D:\game-build-cache\tmp"
set TEMP=D:\game-build-cache\tmp
set TMP=D:\game-build-cache\tmp
if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
echo ERROR: JAVA_HOME is not set. 1>&2
"%COMSPEC%" /c exit 1
:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute
echo ERROR: JAVA_HOME is invalid: %JAVA_HOME% 1>&2
"%COMSPEC%" /c exit 1
:execute
endlocal & set "GRADLE_USER_HOME=D:\game-build-cache\gradle-cyuvisual-fabric" & set "TEMP=D:\game-build-cache\tmp" & set "TMP=D:\game-build-cache\tmp" & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -Djava.io.tmpdir=D:\game-build-cache\tmp -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %* & call :exitWithErrorLevel
:exitWithErrorLevel
"%COMSPEC%" /c exit %ERRORLEVEL%
