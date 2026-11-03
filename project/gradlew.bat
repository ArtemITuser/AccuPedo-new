@echo off
setlocal
set DIR=%~dp0
if not exist "%DIR%gradle\wrapper\gradle-wrapper.jar" (
  echo Gradle wrapper JAR is missing. Bootstrapping the pinned Gradle wrapper...
  powershell -NoProfile -ExecutionPolicy Bypass -File "%DIR%tools\bootstrap-gradle-wrapper.ps1"
  if errorlevel 1 exit /b %errorlevel%
)
if not exist "%DIR%gradle\wrapper\gradle-wrapper.jar" (
  echo ERROR: gradle-wrapper.jar was not created.
  exit /b 1
)
if defined JAVA_HOME (
  if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JAVA_HOME does not point to a valid JDK.
    exit /b 1
  )
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  where java >nul 2>nul
  if errorlevel 1 (
    echo ERROR: JDK 17 is required. Install JDK 17 or set JAVA_HOME.
    exit /b 1
  )
  set "JAVA_EXE=java.exe"
)
set "CLASSPATH=%DIR%gradle\wrapper\gradle-wrapper.jar"
"%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %errorlevel%
