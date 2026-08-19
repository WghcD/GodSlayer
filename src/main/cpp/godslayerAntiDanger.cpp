#include <Windows.h>
#include <winternl.h>
#include <ntstatus.h>
#include <stdio.h>
#include <string.h>

// ================== 调试宏 ==================
static char g_DebugBuf[512];
#define LOG_DEBUG(fmt, ...) \
    snprintf(g_DebugBuf, sizeof(g_DebugBuf), "[GodSlayer Native] " fmt "\n", ##__VA_ARGS__); \
    OutputDebugStringA(g_DebugBuf)

// ================== 底层内存保护函数（绕过 Hook）==================
typedef NTSTATUS (NTAPI *pNtProtectVirtualMemory)(
    HANDLE  ProcessHandle,
    PVOID   *BaseAddress,
    PSIZE_T NumberOfBytesToProtect,
    ULONG   NewAccessProtection,
    PULONG  OldAccessProtection
);
static pNtProtectVirtualMemory g_NtProtectVirtualMemory = NULL;

// 安全地修改页面属性（不会被 VirtualProtect Hook 拦截）
static BOOL SafeVirtualProtect(LPVOID lpAddress, SIZE_T dwSize, DWORD flNewProtect, PDWORD lpflOldProtect) {
    if (!g_NtProtectVirtualMemory) return FALSE;
    ULONG old;
    NTSTATUS st = g_NtProtectVirtualMemory(GetCurrentProcess(),
                                           &lpAddress, &dwSize, flNewProtect, &old);
    if (lpflOldProtect) *lpflOldProtect = old;
    return NT_SUCCESS(st);
}

// ================== 类型定义 ==================
typedef NTSTATUS (NTAPI *pNtOpenProcess)(
    PHANDLE ProcessHandle, ACCESS_MASK DesiredAccess,
    POBJECT_ATTRIBUTES ObjectAttributes, PCLIENT_ID ClientId
);
typedef NTSTATUS (NTAPI *pLdrLoadDll)(
    PWCHAR PathToFile, ULONG Flags,
    PUNICODE_STRING ModuleFileName, PHANDLE ModuleHandle
);
typedef BOOL (WINAPI *pVirtualProtect)(
    LPVOID lpAddress, SIZE_T dwSize, DWORD flNewProtect, PDWORD lpflOldProtect
);

// ================== 全局变量 ==================
static pNtOpenProcess    g_OrigNtOpenProcess  = NULL;
static pLdrLoadDll       g_OrigLdrLoadDll     = NULL;
static pVirtualProtect   g_OrigVirtualProtect = NULL;

static BYTE g_NtOpenProcessOrigBytes[12]  = {0};
static BYTE g_LdrLoadDllOrigBytes[12]     = {0};
static BYTE g_VirtualProtectOrigBytes[12] = {0};

static CRITICAL_SECTION g_HookLock;

// 受保护的页面基址（用于拒绝 VirtualProtect 修改）
static LPVOID g_ProtectedPages[6] = {0};
static int    g_ProtectedPageCount = 0;

// ================== 白名单（LdrLoadDll） ==================
static const WCHAR* g_Whitelist[] = {
    L"ADVAPI32.dll",
    L"amsi.dll",
    L"AUDIOSES.DLL",
    L"bcrypt.dll",
    L"bcryptPrimitives.dll",
    L"cfgmgr32.dll",
    L"clbcatq.dll",
    L"ColorAdapterClient.dll",
    L"combase.dll",
    L"COMCTL32.dll",
    L"CoreMessaging.dll",
    L"CoreUIComponents.dll",
    L"CRYPT32.dll",
    L"cryptbase.dll",
    L"cryptnet.dll",
    L"CRYPTSP.dll",
    L"dbgcore.DLL",
    L"DBGHELP.DLL",
    L"DEVOBJ.dll",
    L"dhcpcsvc.DLL",
    L"dhcpcsvc6.DLL",
    L"dinput8.dll",
    L"DNSAPI.dll",
    L"drvstore.dll",
    L"dwmapi.dll",
    L"dxcore.dll",
    L"extnet.dll",
    L"fwpuclnt.dll",
    L"GDI32.dll",
    L"gdi32full.dll",
    L"glfw.dll",
    L"GLU32.dll",
    L"godslayer.dll",
    L"godslayerAntiDanger.dll",
    L"icm32.dll",
    L"iertutil.dll",
    L"imagehlp.dll",
    L"IMM32.DLL",
    L"inputhost.dll",
    L"IPHLPAPI.DLL",
    L"java.dll",
    L"jemalloc.dll",
    L"jimage.dll",
    L"jli.dll",
    L"jna6733171783469832351.dll",
    L"jsvml.dll",
    L"jvm.dll",
    L"kernel.appcore.dll",
    L"KERNEL32.DLL",
    L"KERNELBASE.dll",
    L"lwjgl.dll",
    L"lwjgl_opengl.dll",
    L"lwjgl_stb.dll",
    L"management.dll",
    L"management_ext.dll",
    L"MMDevApi.dll",
    L"MSACM32.dll",
    L"msasn1.dll",
    L"mscms.dll",
    L"MSCTF.dll",
    L"msdmo.dll",
    L"msvcp140.dll",
    L"msvcp_win.dll",
    L"msvcrt.dll",
    L"mswsock.dll",
    L"napinsp.dll",
    L"ncrypt.dll",
    L"net.dll",
    L"netutils.dll",
    L"nio.dll",
    L"NLAapi.dll",
    L"NSI.dll",
    L"NTASN1.dll",
    L"ntdll.dll",
    L"ntmarta.dll",
    L"nvgpucomp64.dll",
    L"nvoglv64.dll",
    L"nvspcap64.dll",
    L"ole32.dll",
    L"OLEAUT32.dll",
    L"OpenAL.dll",
    L"opengl32.dll",
    L"Pdh.dll",
    L"perfos.dll",
    L"pnrpnsp.dll",
    L"POWRPROF.dll",
    L"profapi.dll",
    L"PROPSYS.dll",
    L"PSAPI.DLL",
    L"rasadhlp.dll",
    L"resourcepolicyclient.dll",
    L"RPCRT4.dll",
    L"rsaenh.dll",
    L"sapi.dll",
    L"sechost.dll",
    L"SETUPAPI.dll",
    L"SHCORE.dll",
    L"SHELL32.dll",
    L"shlwapi.dll",
    L"srvcli.dll",
    L"sunmscapi.dll",
    L"textinputframework.dll",
    L"ucrtbase.dll",
    L"UMPDC.dll",
    L"urlmon.dll",
    L"USER32.dll",
    L"USERENV.dll",
    L"uxtheme.dll",
    L"VCRUNTIME140.dll",
    L"vcruntime140_1.dll",
    L"verify.dll",
    L"VERSION.dll",
    L"win32u.dll",
    L"windows.storage.dll",
    L"WINHTTP.dll",
    L"WINMM.dll",
    L"winmmbase.dll",
    L"winrnr.dll",
    L"WINSTA.dll",
    L"WINTRUST.dll",
    L"wintypes.dll",
    L"Wldp.dll",
    L"WS2_32.dll",
    L"wshbth.dll",
    L"wshunix.dll",
    L"WTSAPI32.dll",
    L"xinput1_4.dll",
    L"zip.dll"
};
static const int g_WhitelistCount = sizeof(g_Whitelist) / sizeof(g_Whitelist[0]);

// ================== 辅助函数 ==================
static LPVOID GetPageBase(LPVOID addr) {
    return (LPVOID)((ULONG_PTR)addr & ~(ULONG_PTR)0xFFF);
}

static void AddProtectedPage(LPVOID addr) {
    LPVOID page = GetPageBase(addr);
    for (int i = 0; i < g_ProtectedPageCount; i++) {
        if (g_ProtectedPages[i] == page) return;
    }
    if (g_ProtectedPageCount < 6)
        g_ProtectedPages[g_ProtectedPageCount++] = page;
}

static BOOL IsProtectedAddress(LPVOID addr) {
    LPVOID page = GetPageBase(addr);
    for (int i = 0; i < g_ProtectedPageCount; i++)
        if (g_ProtectedPages[i] == page) return TRUE;
    return FALSE;
}

// ================== Inline Hook（使用 SafeVirtualProtect）==================
void WriteJump64(void* target, void* hook) {
    DWORD old;
    SafeVirtualProtect(target, 12, PAGE_EXECUTE_READWRITE, &old);
    BYTE code[12];
    code[0] = 0x48; code[1] = 0xB8;
    *(UINT64*)(code + 2) = (UINT64)hook;
    code[10] = 0xFF; code[11] = 0xE0;
    memcpy(target, code, 12);
    SafeVirtualProtect(target, 12, old, &old);
}

void RestoreBytes64(void* target, BYTE* orig) {
    DWORD old;
    SafeVirtualProtect(target, 12, PAGE_EXECUTE_READWRITE, &old);
    memcpy(target, orig, 12);
    SafeVirtualProtect(target, 12, old, &old);
}

// ================== 文件名检查 ==================
static BOOL IsInWhitelist(PUNICODE_STRING moduleName) {
    if (!moduleName || !moduleName->Buffer || !moduleName->Length) return FALSE;
    USHORT len = moduleName->Length / sizeof(WCHAR);
    PWSTR buffer = moduleName->Buffer, fileName = buffer;
    for (USHORT i = 0; i < len; i++)
        if (buffer[i] == L'\\' || buffer[i] == L'/') fileName = buffer + i + 1;
    for (int i = 0; i < g_WhitelistCount; i++)
        if (_wcsicmp(fileName, g_Whitelist[i]) == 0) return TRUE;
    return FALSE;
}

// ================== Hook 函数实现 ==================
static NTSTATUS NTAPI HookNtOpenProcess(PHANDLE ProcessHandle, ACCESS_MASK DesiredAccess,
                                        POBJECT_ATTRIBUTES ObjectAttributes, PCLIENT_ID ClientId) {
    UNREFERENCED_PARAMETER(ProcessHandle); UNREFERENCED_PARAMETER(DesiredAccess);
    UNREFERENCED_PARAMETER(ObjectAttributes); UNREFERENCED_PARAMETER(ClientId);
    return STATUS_ACCESS_DENIED;
}

static NTSTATUS NTAPI HookLdrLoadDll(PWCHAR PathToFile, ULONG Flags,
                                     PUNICODE_STRING ModuleFileName, PHANDLE ModuleHandle) {
    if (!IsInWhitelist(ModuleFileName))
        return STATUS_ACCESS_DENIED;

    NTSTATUS status;
    EnterCriticalSection(&g_HookLock);
    RestoreBytes64((void*)g_OrigLdrLoadDll, g_LdrLoadDllOrigBytes);
    status = g_OrigLdrLoadDll(PathToFile, Flags, ModuleFileName, ModuleHandle);
    WriteJump64((void*)g_OrigLdrLoadDll, (void*)HookLdrLoadDll);
    LeaveCriticalSection(&g_HookLock);
    return status;
}

static BOOL WINAPI HookVirtualProtect(LPVOID lpAddress, SIZE_T dwSize,
                                      DWORD flNewProtect, PDWORD lpflOldProtect) {
    if (IsProtectedAddress(lpAddress)) {
        SetLastError(ERROR_ACCESS_DENIED);
        return FALSE;
    }
    BOOL ret;
    EnterCriticalSection(&g_HookLock);
    RestoreBytes64((void*)g_OrigVirtualProtect, g_VirtualProtectOrigBytes);
    ret = g_OrigVirtualProtect(lpAddress, dwSize, flNewProtect, lpflOldProtect);
    WriteJump64((void*)g_OrigVirtualProtect, (void*)HookVirtualProtect);
    LeaveCriticalSection(&g_HookLock);
    return ret;
}

// ================== 初始化 / 卸载 ==================
static void InstallHooks() {
    HMODULE ntdll = GetModuleHandleA("ntdll.dll");
    HMODULE kernel32 = GetModuleHandleA("kernel32.dll");

    // 先获取底层 NtProtectVirtualMemory（必须在任何 Hook 之前）
    g_NtProtectVirtualMemory = (pNtProtectVirtualMemory)
        GetProcAddress(ntdll, "NtProtectVirtualMemory");

    g_OrigNtOpenProcess  = (pNtOpenProcess)GetProcAddress(ntdll, "NtOpenProcess");
    g_OrigLdrLoadDll     = (pLdrLoadDll)GetProcAddress(ntdll, "LdrLoadDll");
    g_OrigVirtualProtect = (pVirtualProtect)GetProcAddress(kernel32, "VirtualProtect");

    if (!g_OrigNtOpenProcess || !g_OrigLdrLoadDll || !g_OrigVirtualProtect || !g_NtProtectVirtualMemory)
        return;

    memcpy(g_NtOpenProcessOrigBytes,  (void*)g_OrigNtOpenProcess,  12);
    memcpy(g_LdrLoadDllOrigBytes,     (void*)g_OrigLdrLoadDll,     12);
    memcpy(g_VirtualProtectOrigBytes, (void*)g_OrigVirtualProtect, 12);

    WriteJump64((void*)g_OrigNtOpenProcess,  (void*)HookNtOpenProcess);
    WriteJump64((void*)g_OrigLdrLoadDll,     (void*)HookLdrLoadDll);
    WriteJump64((void*)g_OrigVirtualProtect, (void*)HookVirtualProtect);

    AddProtectedPage((LPVOID)g_OrigNtOpenProcess);
    AddProtectedPage((LPVOID)g_OrigLdrLoadDll);
    AddProtectedPage((LPVOID)g_OrigVirtualProtect);
    AddProtectedPage((LPVOID)HookNtOpenProcess);
    AddProtectedPage((LPVOID)HookLdrLoadDll);
    AddProtectedPage((LPVOID)HookVirtualProtect);

    LOG_DEBUG("Hooks installed safely.");
}

static void UninstallHooks() {
    EnterCriticalSection(&g_HookLock);
    if (g_OrigNtOpenProcess)  RestoreBytes64((void*)g_OrigNtOpenProcess,  g_NtOpenProcessOrigBytes);
    if (g_OrigLdrLoadDll)     RestoreBytes64((void*)g_OrigLdrLoadDll,     g_LdrLoadDllOrigBytes);
    if (g_OrigVirtualProtect) RestoreBytes64((void*)g_OrigVirtualProtect, g_VirtualProtectOrigBytes);
    LeaveCriticalSection(&g_HookLock);
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    UNREFERENCED_PARAMETER(hinstDLL); UNREFERENCED_PARAMETER(lpvReserved);
    switch (fdwReason) {
    case DLL_PROCESS_ATTACH:
		LOG_DEBUG("GodSlayerAntiDangerMoudle Loaded!");
        //DisableThreadLibraryCalls(hinstDLL);
        //InitializeCriticalSection(&g_HookLock);
        //InstallHooks();
        break;
    case DLL_PROCESS_DETACH:
        //UninstallHooks();
        //DeleteCriticalSection(&g_HookLock);
        break;
    }
    return TRUE;
}
