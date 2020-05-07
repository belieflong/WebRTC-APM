@echo off
echo --------------------------------------------------
echo  Copy the libs to the module
echo --------------------------------------------------
pause

cd jni
cmd /C ndk-build

cd ..
pause

set TAG="app"

set source="%cd%"
echo source: %source%

cd ..\%TAG%

set target="%cd%"
echo target: %target%

Xcopy "%source%\libs" "%target%\libs" /s /e /y /d

cd ..\webrtcApm-jni