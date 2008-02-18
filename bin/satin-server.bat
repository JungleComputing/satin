@echo off

if "%OS%"=="Windows_NT" @setlocal

rem %~dp0 is expanded pathname of the current script under NT

if "%SATIN_HOME%X"=="X" set SATIN_HOME=%~dp0..

set JAVACLASSPATH=%CLASSPATH%;
for %%i in ("%SATIN_HOME%\lib\*.jar") do call "%SATIN_HOME%\bin\AddToSatinClassPath.bat" %%i

set SERVER_ARGS=

:setupArgs

if ""%1""=="""" goto doneArgs

set SERVER_ARGS=%SERVER_ARGS% "%1"

shift
goto setupArgs

rem This label provides a place for the argument list loop to break out
rem and for NT handling to skip to.

:doneArgs

java -classpath "%JAVACLASSPATH%" -Dlog4j.configuration=file:"%SATIN_HOME%"\log4j.properties ibis.server.Server %SERVER_ARGS%

if "%OS%"=="Windows_NT" @endlocal
