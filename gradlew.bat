@echo off
setlocal
set DIR=%~dp0
if defined JAVA_HOME (
  set JAVACMD=%JAVA_HOME%\bin\java.exe
) else (
  set JAVACMD=java
)
"%JAVACMD%" -jar "%DIR%gradle\wrapper\gradle-wrapper.jar" %*
