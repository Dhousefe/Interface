@echo off
chcp 65001 > nul
setlocal

echo ======================================================================
echo  [BENCHMARK] Compilando e Executando Microbenchmarks do Projeto Interface
echo ======================================================================

set "BIN_DIR=%~dp0..\bin_bench"
if not exist "%BIN_DIR%" mkdir "%BIN_DIR%"

javac -d "%BIN_DIR%" "%~dp0..\java\mods\dhousefe\benchmark\InterfaceThroughputBenchmark.java"
if errorlevel 1 (
    echo [X] Erro na compilacao do benchmark.
    exit /b 1
)

java -cp "%BIN_DIR%" mods.dhousefe.benchmark.InterfaceThroughputBenchmark

rmdir /s /q "%BIN_DIR%" 2>nul
