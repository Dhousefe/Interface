@echo off
chcp 65001 > nul
setlocal

set "INTERFACE_DIR=%~dp0.."
set "SERVER_JAR=%INTERFACE_DIR%\libs\server.jar"
set "BIN_DIR=%INTERFACE_DIR%\bin"
set "DIST_DIR=%INTERFACE_DIR%\dist"
set "OUTPUT_JAR=%DIST_DIR%\interface.ext.jar"
set "BRPROJECT_LIBS=D:\Brproject3.0\BrProject-2026\libs"

echo ======================================================================
echo  [BUILD] Compilando e Empacotando Interface_BrProject Extension
echo ======================================================================

if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
if not exist "%BIN_DIR%" mkdir "%BIN_DIR%"

echo [1/4] Compilando codigo Java com Java 21+...
javac -cp "%SERVER_JAR%" -d "%BIN_DIR%" "%INTERFACE_DIR%\java\mods\dhousefe\InterfaceExtension.java"
if errorlevel 1 (
    echo [X] Erro fatal na compilacao do InterfaceExtension.java.
    exit /b 1
)

echo [2/4] Sincronizando recursos (XML, INI, HTML)...
robocopy "%INTERFACE_DIR%\java\mods" "%BIN_DIR%\mods" /E /XF *.java /NFL /NDL /NJH /NJS /nc /ns /np > nul

echo [3/4] Gerando JAR da extensao (%OUTPUT_JAR%)...
if exist "%BIN_DIR%\mods\dhousefe\benchmark" rmdir /s /q "%BIN_DIR%\mods\dhousefe\benchmark"
jar -cf "%OUTPUT_JAR%" -C "%BIN_DIR%" mods
if errorlevel 1 (
    echo [X] Erro ao criar o pacote JAR.
    exit /b 1
)

echo [4/4] Implantando no servidor BrProject-2026 (%BRPROJECT_LIBS%)...
if exist "%BRPROJECT_LIBS%" (
    copy /y "%OUTPUT_JAR%" "%BRPROJECT_LIBS%\interface.ext.jar" > nul
    echo [OK] interface.ext.jar sincronizado com sucesso em %BRPROJECT_LIBS%!
) else (
    echo [!] Diretorio %BRPROJECT_LIBS% nao encontrado. Verifique o caminho.
)

echo ======================================================================
echo  [SUCESSO] Build e Deploy da Interface concluidos com exito!
echo ======================================================================
