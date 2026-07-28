@echo off
set JAVA_HOME=C:\Users\Wgh\.jdks\ms-17.0.19
set MINGW_HOME=C:\Program Files\RedPanda-Cpp\mingw64
set PATH=%MINGW_HOME%\bin;%JAVA_HOME%\bin;%PATH%

f:
cd F:\MinecraftDevelopement\forge-1.20.1-47.4.10-mdk\src\main\cpp

"%MINGW_HOME%\bin\g++.exe" -shared -o"F:\MinecraftDevelopement\forge-1.20.1-47.4.10-mdk\src\main\cpp\build\godslayer.dll" godslayer.cpp ^
    -I"%JAVA_HOME%\include" ^
    -I"%JAVA_HOME%\include\win32" ^
    -Wl,--export-all-symbols ^
    -O2 -static