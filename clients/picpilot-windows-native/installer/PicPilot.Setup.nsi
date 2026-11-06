Unicode True
RequestExecutionLevel user
ManifestDPIAware true
SetCompressor /SOLID lzma

!include "MUI2.nsh"
!include "WinVer.nsh"

!define PRODUCT_NAME "PicPilot"
!define PRODUCT_VERSION "2.0.2"
!define PRODUCT_PUBLISHER "PicPilot"
!define PRODUCT_EXE "PicPilot.exe"
!define PRODUCT_UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\PicPilot"
!define PRODUCT_APP_PATH "Software\Microsoft\Windows\CurrentVersion\App Paths\PicPilot.exe"

!ifndef PUBLISH_DIR
  !error "PUBLISH_DIR is required"
!endif
!ifndef OUTPUT_FILE
  !error "OUTPUT_FILE is required"
!endif
!ifndef APP_ICON
  !error "APP_ICON is required"
!endif

Name "${PRODUCT_NAME}"
Caption "安装 ${PRODUCT_NAME}"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\PicPilot"
InstallDirRegKey HKCU "${PRODUCT_APP_PATH}" "Path"
Icon "${APP_ICON}"
UninstallIcon "${APP_ICON}"
BrandingText "PicPilot AI Commerce Studio"
VIProductVersion "2.0.2.0"
VIAddVersionKey /LANG=2052 "ProductName" "${PRODUCT_NAME}"
VIAddVersionKey /LANG=2052 "CompanyName" "${PRODUCT_PUBLISHER}"
VIAddVersionKey /LANG=2052 "FileDescription" "PicPilot Windows 安装程序"
VIAddVersionKey /LANG=2052 "FileVersion" "${PRODUCT_VERSION}"
VIAddVersionKey /LANG=2052 "ProductVersion" "${PRODUCT_VERSION}"
VIAddVersionKey /LANG=2052 "LegalCopyright" "Copyright PicPilot"

!define MUI_ABORTWARNING
!define MUI_ICON "${APP_ICON}"
!define MUI_UNICON "${APP_ICON}"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_RIGHT
!define MUI_FINISHPAGE_RUN "$INSTDIR\${PRODUCT_EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "立即打开 PicPilot"
!define MUI_FINISHPAGE_NOAUTOCLOSE
!define MUI_UNFINISHPAGE_NOAUTOCLOSE

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

!insertmacro MUI_LANGUAGE "SimpChinese"

Function .onInit
  retry:
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_OK|MB_ICONSTOP "PicPilot 需要 Windows 10 或更高版本。"
    Abort
  ${EndIf}
  FindWindow $0 "" "PicPilot"
  ${If} $0 != 0
    MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "请先关闭正在运行的 PicPilot，再继续安装。" IDRETRY retry IDCANCEL cancel
  ${EndIf}
  Return
  cancel:
    Abort
FunctionEnd

Section "PicPilot 主程序（必选）" SecMain
  SectionIn RO
  SetShellVarContext current
  SetOutPath "$INSTDIR"
  File /r "${PUBLISH_DIR}\*.*"

  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\PicPilot"
  CreateShortcut "$SMPROGRAMS\PicPilot\PicPilot.lnk" "$INSTDIR\${PRODUCT_EXE}" "" "$INSTDIR\Assets\picpilot.ico"
  CreateShortcut "$SMPROGRAMS\PicPilot\卸载 PicPilot.lnk" "$INSTDIR\Uninstall.exe"

  WriteRegStr HKCU "${PRODUCT_APP_PATH}" "" "$INSTDIR\${PRODUCT_EXE}"
  WriteRegStr HKCU "${PRODUCT_APP_PATH}" "Path" "$INSTDIR"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayName" "${PRODUCT_NAME}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayVersion" "${PRODUCT_VERSION}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "Publisher" "${PRODUCT_PUBLISHER}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayIcon" "$INSTDIR\Assets\picpilot.ico"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegDWORD HKCU "${PRODUCT_UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${PRODUCT_UNINST_KEY}" "NoRepair" 1
  WriteRegDWORD HKCU "${PRODUCT_UNINST_KEY}" "EstimatedSize" 235286
SectionEnd

Section /o "创建桌面快捷方式" SecDesktop
  SetShellVarContext current
  CreateShortcut "$DESKTOP\PicPilot.lnk" "$INSTDIR\${PRODUCT_EXE}" "" "$INSTDIR\Assets\picpilot.ico"
SectionEnd

LangString DESC_SecMain ${LANG_SIMPCHINESE} "安装 PicPilot 主程序、开始菜单入口与卸载程序。"
LangString DESC_SecDesktop ${LANG_SIMPCHINESE} "在当前用户桌面创建 PicPilot 快捷方式。"
!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} $(DESC_SecMain)
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} $(DESC_SecDesktop)
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Function un.onInit
  SetShellVarContext current
  FindWindow $0 "" "PicPilot"
  ${If} $0 != 0
    MessageBox MB_OKCANCEL|MB_ICONEXCLAMATION "请先关闭正在运行的 PicPilot，再继续卸载。" IDOK done IDCANCEL cancel
  ${EndIf}
  done:
    Return
  cancel:
    Abort
FunctionEnd

Section "Uninstall"
  SetShellVarContext current
  Delete "$DESKTOP\PicPilot.lnk"
  RMDir /r "$SMPROGRAMS\PicPilot"
  DeleteRegKey HKCU "${PRODUCT_APP_PATH}"
  DeleteRegKey HKCU "${PRODUCT_UNINST_KEY}"
  RMDir /r /REBOOTOK "$INSTDIR"
  SetAutoClose true
SectionEnd
