@echo off
REM ----------------------------------------------
REM build-plugin.bat
REM Vraagt welke Minecraft-versie en bouwt dan
REM de plugin met de bijbehorende pom-*.xml
REM ----------------------------------------------

:SELECT_VERSION
echo.
echo Kies de Minecraft-versie voor de build:
echo  1) 1.16
echo  2) 1.21
set /p VER="Voer 1 of 2 in en druk ENTER: "

if "%VER%"=="1" (
    set POM_FILE=pom-1.16.xml
) else if "%VER%"=="2" (
    set POM_FILE=pom-1.21.xml
) else (
    echo Ongeldige keuze "%VER%". Probeer opnieuw.
    goto SELECT_VERSION
)

echo.
echo Je hebt gekozen voor versie %VER% (bestand: %POM_FILE%).
echo.

:: Vraag om bevestiging
set /p CONFIRM="Wil je nu de plugin bouwen? [Y/N] "

if /I "%CONFIRM%"=="Y" (
    echo.
    echo Building plugin with %POM_FILE%...
    mvn -f "%POM_FILE%" clean install -U

    if %ERRORLEVEL% EQU 0 (
        echo.
        color 0A
        echo [BUILD SUCCESFUL]
    ) else (
        echo.
        color 0C
        echo [BUILD FAILED]
    )
    REM Reset console kleur naar standaard
    color 07
) else (
    echo.
    echo Build geannuleerd.
)

pause
