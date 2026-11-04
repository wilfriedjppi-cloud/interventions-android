@echo off
rem Construit ConvertisseurWinRelais.exe (Windows 10, Python 3.9+ installe)
python -m pip install --upgrade pyinstaller || goto :err
python -m PyInstaller --noconfirm --onefile --windowed --name ConvertisseurWinRelais ^
  --add-data "bibliotheque.xrs;." --exclude-module matplotlib convertisseur.py || goto :err
echo.
echo OK : dist\ConvertisseurWinRelais.exe
pause
exit /b 0
:err
echo ECHEC de la construction.
pause
exit /b 1
