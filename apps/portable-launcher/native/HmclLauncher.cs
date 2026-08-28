using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Automation;

internal static class HmclLauncher
{
    private const string WindowFocusAgentFileName = "MinecraftCodexWindowFocusAgent.jar";
    private const string WindowFocusAgentPolicyVersion = "glfw-create-show-held-noactivate-and-explicit-focus-v4";
    private const string WindowFocusPolicyVersion = "hmcl-startup-noactivate-through-runtime-takeover-v6";
    private const int STARTF_USESHOWWINDOW = 0x00000001;
    private const short SW_SHOWNOACTIVATE = 4;
    private const int GWL_EXSTYLE = -20;
    private const long WS_EX_NOACTIVATE = 0x08000000L;
    private const int STARTUP_GUARD_TAKEOVER_TIMEOUT_MILLISECONDS = 120000;
    private const int STARTUP_GUARD_TITLE_STABILITY_MILLISECONDS = 8000;
    private const int STARTUP_GUARD_POLL_MILLISECONDS = 100;
    private const int POST_RELEASE_AUDIT_MILLISECONDS = 2000;
    private const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private const uint WAIT_OBJECT_0 = 0x00000000;
    private const uint WAIT_TIMEOUT = 0x00000102;
    private const int MAX_JAVA_UNIX_TEMP_PATH_LENGTH = 40;
    private static readonly Regex HmclTitle = new Regex("HMCL|Hello Minecraft", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex LaunchText = new Regex("启动游戏|Launch Game", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly UTF8Encoding StrictUtf8 = new UTF8Encoding(false, true);
    private static readonly UTF8Encoding Utf8WithoutBom = new UTF8Encoding(false);
    private static readonly HashSet<string> CancelLabels = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
    {
        "Cancel", "取消", "稍后", "Not now", "Later"
    };

    private delegate bool EnumWindowsCallback(IntPtr window, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct StartupInfo
    {
        public int Size;
        public IntPtr Reserved;
        public IntPtr Desktop;
        public IntPtr Title;
        public int X;
        public int Y;
        public int XSize;
        public int YSize;
        public int XCountChars;
        public int YCountChars;
        public int FillAttribute;
        public int Flags;
        public short WindowShowCommand;
        public short ReservedByteCount;
        public IntPtr ReservedBytes;
        public IntPtr StandardInput;
        public IntPtr StandardOutput;
        public IntPtr StandardError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInformation
    {
        public IntPtr Process;
        public IntPtr Thread;
        public int ProcessId;
        public int ThreadId;
    }

    private sealed class NativeProcess : IDisposable
    {
        private IntPtr handle;

        internal NativeProcess(IntPtr handle, int processId)
        {
            this.handle = handle;
            ProcessId = processId;
        }

        internal int ProcessId { get; private set; }

        internal bool WaitForExit(int milliseconds)
        {
            if (handle == IntPtr.Zero) throw new ObjectDisposedException("NativeProcess");
            uint result = WaitForSingleObject(handle, unchecked((uint)milliseconds));
            if (result == WAIT_OBJECT_0) return true;
            if (result == WAIT_TIMEOUT) return false;
            throw new System.ComponentModel.Win32Exception(
                Marshal.GetLastWin32Error(),
                "等待无激活启动的进程失败"
            );
        }

        internal int ExitCode
        {
            get
            {
                if (handle == IntPtr.Zero) throw new ObjectDisposedException("NativeProcess");
                uint exitCode;
                if (!GetExitCodeProcess(handle, out exitCode))
                {
                    throw new System.ComponentModel.Win32Exception(
                        Marshal.GetLastWin32Error(),
                        "读取无激活启动的进程退出码失败"
                    );
                }
                return unchecked((int)exitCode);
            }
        }

        public void Dispose()
        {
            if (handle == IntPtr.Zero) return;
            CloseHandle(handle);
            handle = IntPtr.Zero;
        }
    }

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsCallback callback, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "GetWindowTextW")]
    private static extern int GetWindowTextNative(IntPtr window, StringBuilder text, int maximumCount);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr window);

    [DllImport("user32.dll")]
    private static extern bool IsWindow(IntPtr window);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern IntPtr GetWindowLongPtr64(IntPtr window, int index);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")]
    private static extern int GetWindowLong32(IntPtr window, int index);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
    private static extern IntPtr SetWindowLongPtr64(IntPtr window, int index, IntPtr value);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongW", SetLastError = true)]
    private static extern int SetWindowLong32(IntPtr window, int index, int value);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreateProcessW(
        string applicationName,
        StringBuilder commandLine,
        IntPtr processAttributes,
        IntPtr threadAttributes,
        bool inheritHandles,
        uint creationFlags,
        IntPtr environment,
        string currentDirectory,
        ref StartupInfo startupInfo,
        out ProcessInformation processInformation
    );

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern void GetStartupInfoW(out StartupInfo startupInfo);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetExitCodeProcess(IntPtr process, out uint exitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    private static string Quote(string value)
    {
        if (value.Length > 0 && value.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) return value;
        StringBuilder result = new StringBuilder();
        result.Append('"');
        int slashes = 0;
        foreach (char character in value)
        {
            if (character == '\\')
            {
                slashes++;
                continue;
            }
            if (character == '"')
            {
                result.Append('\\', slashes * 2 + 1);
                result.Append('"');
                slashes = 0;
                continue;
            }
            if (slashes > 0) result.Append('\\', slashes);
            slashes = 0;
            result.Append(character);
        }
        if (slashes > 0) result.Append('\\', slashes * 2);
        result.Append('"');
        return result.ToString();
    }

    private static string NormalizedDirectory(string value)
    {
        string full = Path.GetFullPath(value);
        string root = Path.GetPathRoot(full) ?? String.Empty;
        while (full.Length > root.Length && (full.EndsWith("\\", StringComparison.Ordinal) || full.EndsWith("/", StringComparison.Ordinal)))
        {
            full = full.Substring(0, full.Length - 1);
        }
        return full;
    }

    private static bool SameDirectory(string left, string right)
    {
        return String.Equals(NormalizedDirectory(left), NormalizedDirectory(right), StringComparison.OrdinalIgnoreCase);
    }

    private static Dictionary<string, object> ReadJsonObject(string filePath)
    {
        JavaScriptSerializer serializer = new JavaScriptSerializer();
        serializer.MaxJsonLength = 4 * 1024 * 1024;
        object parsed = serializer.DeserializeObject(File.ReadAllText(filePath, StrictUtf8));
        Dictionary<string, object> result = parsed as Dictionary<string, object>;
        if (result == null) throw new InvalidDataException(Path.GetFileName(filePath) + " 不是 JSON 对象");
        return result;
    }

    private static string StringValue(Dictionary<string, object> document, string key)
    {
        object value;
        return document.TryGetValue(key, out value) && value != null ? Convert.ToString(value) : String.Empty;
    }

    private static IEnumerable<object> ObjectSequence(object value)
    {
        if (value == null || value is string) yield break;
        IEnumerable sequence = value as IEnumerable;
        if (sequence == null) yield break;
        foreach (object item in sequence) yield return item;
    }

    private static void WriteSettingsAtomically(
        string settingsPath,
        Dictionary<string, object> settings,
        string stateDirectory
    )
    {
        string backupDirectory = Path.Combine(stateDirectory, "hmcl-settings-backups");
        Directory.CreateDirectory(backupDirectory);
        string backupPath = Path.Combine(
            backupDirectory,
            DateTime.UtcNow.ToString("yyyyMMdd-HHmmss-fff") + "-launcher-settings.json"
        );
        File.Copy(settingsPath, backupPath, false);

        JavaScriptSerializer serializer = new JavaScriptSerializer();
        serializer.MaxJsonLength = 4 * 1024 * 1024;
        string temporary = settingsPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllText(temporary, serializer.Serialize(settings), Utf8WithoutBom);
            File.Replace(temporary, settingsPath, null, true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    private static bool SelectExactInstance(
        string launcherPath,
        string minecraftRoot,
        string expectedInstance,
        string stateDirectory
    )
    {
        string instanceDirectory = Path.Combine(minecraftRoot, "versions", expectedInstance);
        if (!Directory.Exists(instanceDirectory)
            || !File.Exists(Path.Combine(instanceDirectory, expectedInstance + ".json"))
            || !File.Exists(Path.Combine(instanceDirectory, expectedInstance + ".jar")))
        {
            throw new InvalidOperationException("所选 HMCL 源实例缺少版本目录、JSON 或 JAR");
        }

        string launcherDirectory = Path.GetDirectoryName(launcherPath);
        string configDirectory = Path.Combine(launcherDirectory, ".hmcl", "config");
        string directoriesPath = Path.Combine(configDirectory, "game-directories.json");
        string settingsPath = Path.Combine(configDirectory, "launcher-settings.json");
        if (!File.Exists(directoriesPath) || !File.Exists(settingsPath))
        {
            throw new InvalidOperationException("HMCL 缺少可安全选择精确实例的配置文件");
        }

        Dictionary<string, object> directories = ReadJsonObject(directoriesPath);
        Dictionary<string, object> settings = ReadJsonObject(settingsPath);
        object rawDirectories;
        if (!directories.TryGetValue("directories", out rawDirectories))
        {
            throw new InvalidDataException("HMCL 游戏目录配置缺少 directories");
        }

        string selectedDirectoryId = StringValue(settings, "selectedGameDirectory");
        List<string> matchingIds = new List<string>();
        foreach (object rawEntry in ObjectSequence(rawDirectories))
        {
            Dictionary<string, object> entry = rawEntry as Dictionary<string, object>;
            if (entry == null) continue;
            string id = StringValue(entry, "id").Trim();
            string configuredPath = StringValue(entry, "path").Trim();
            if (id.Length == 0 || configuredPath.Length == 0) continue;
            string resolved = Path.IsPathRooted(configuredPath)
                ? configuredPath
                : Path.Combine(launcherDirectory, configuredPath);
            if (SameDirectory(resolved, minecraftRoot)) matchingIds.Add(id);
        }
        if (matchingIds.Count == 0)
        {
            throw new InvalidOperationException("HMCL 尚未登记所选 Minecraft 根目录");
        }
        string directoryId = matchingIds.Contains(selectedDirectoryId) ? selectedDirectoryId : matchingIds[0];

        object rawSelectedInstances;
        Dictionary<string, object> selectedInstances = null;
        if (settings.TryGetValue("selectedInstance", out rawSelectedInstances))
        {
            selectedInstances = rawSelectedInstances as Dictionary<string, object>;
        }
        if (selectedInstances == null)
        {
            selectedInstances = new Dictionary<string, object>(StringComparer.Ordinal);
            settings["selectedInstance"] = selectedInstances;
        }

        object currentInstance;
        string current = selectedInstances.TryGetValue(directoryId, out currentInstance) && currentInstance != null
            ? Convert.ToString(currentInstance)
            : String.Empty;
        bool changed = !String.Equals(selectedDirectoryId, directoryId, StringComparison.Ordinal)
            || !String.Equals(current, expectedInstance, StringComparison.Ordinal);
        if (!changed) return false;

        settings["selectedGameDirectory"] = directoryId;
        selectedInstances[directoryId] = expectedInstance;
        WriteSettingsAtomically(settingsPath, settings, stateDirectory);

        Dictionary<string, object> verified = ReadJsonObject(settingsPath);
        object verifiedMapValue;
        Dictionary<string, object> verifiedMap = verified.TryGetValue("selectedInstance", out verifiedMapValue)
            ? verifiedMapValue as Dictionary<string, object>
            : null;
        object verifiedInstanceValue;
        if (!String.Equals(StringValue(verified, "selectedGameDirectory"), directoryId, StringComparison.Ordinal)
            || verifiedMap == null
            || !verifiedMap.TryGetValue(directoryId, out verifiedInstanceValue)
            || !String.Equals(Convert.ToString(verifiedInstanceValue), expectedInstance, StringComparison.Ordinal))
        {
            throw new IOException("HMCL 精确实例选择写入后校验失败");
        }
        return true;
    }

    private static AutomationElement FindTopLevel(Predicate<string> predicate)
    {
        try
        {
            AutomationElementCollection windows = AutomationElement.RootElement.FindAll(
                TreeScope.Children,
                Condition.TrueCondition
            );
            foreach (AutomationElement window in windows)
            {
                try
                {
                    if (predicate(window.Current.Name ?? String.Empty)) return window;
                }
                catch (ElementNotAvailableException) { }
                catch (InvalidOperationException) { }
                catch (COMException) { }
            }
        }
        catch (ElementNotAvailableException) { }
        catch (InvalidOperationException) { }
        catch (COMException) { }
        return null;
    }

    private static bool TryFindTopLevelWindow(
        Predicate<string> predicate,
        Predicate<int> processPredicate,
        out IntPtr handle,
        out int processId
    )
    {
        IntPtr foundHandle = IntPtr.Zero;
        int foundProcessId = 0;
        EnumWindowsCallback callback = delegate(IntPtr window, IntPtr unused)
        {
            if (!IsWindowVisible(window)) return true;
            StringBuilder title = new StringBuilder(1024);
            if (GetWindowTextNative(window, title, title.Capacity) <= 0) return true;
            if (!predicate(title.ToString())) return true;
            uint candidateProcessId;
            GetWindowThreadProcessId(window, out candidateProcessId);
            if (candidateProcessId == 0) return true;
            int candidateProcessIdValue = unchecked((int)candidateProcessId);
            if (processPredicate != null && !processPredicate(candidateProcessIdValue)) return true;
            foundHandle = window;
            foundProcessId = candidateProcessIdValue;
            return false;
        };
        EnumWindows(callback, IntPtr.Zero);
        handle = foundHandle;
        processId = foundProcessId;
        return handle != IntPtr.Zero && processId > 0;
    }

    private static AutomationElement FindHmclWindow()
    {
        return FindTopLevel(delegate(string name) { return HmclTitle.IsMatch(name); });
    }

    private static bool TryFindHmclWindowHandle(out IntPtr handle, out int processId)
    {
        return TryFindTopLevelWindow(
            delegate(string name) { return HmclTitle.IsMatch(name); },
            null,
            out handle,
            out processId
        );
    }

    private static bool IsMinecraftGameTitle(string name)
    {
        return name.StartsWith("Minecraft", StringComparison.OrdinalIgnoreCase)
            && !name.StartsWith("Minecraft Codex Companion", StringComparison.OrdinalIgnoreCase)
            && !name.StartsWith("Minecraft Launcher", StringComparison.OrdinalIgnoreCase);
    }

    private static string ReadWindowTitle(IntPtr window)
    {
        StringBuilder title = new StringBuilder(1024);
        return GetWindowTextNative(window, title, title.Capacity) > 0
            ? title.ToString()
            : String.Empty;
    }

    private static bool IsMinecraftRuntimeTakeoverTitle(string initialTitle, string currentTitle)
    {
        if (!IsMinecraftGameTitle(currentTitle)
            || currentTitle.IndexOf("Loading", StringComparison.OrdinalIgnoreCase) >= 0)
        {
            return false;
        }
        return currentTitle.IndexOf("Forge", StringComparison.OrdinalIgnoreCase) >= 0
            || !String.Equals(initialTitle, currentTitle, StringComparison.Ordinal);
    }

    private static bool IsJavaGameProcess(int processId)
    {
        try
        {
            using (Process process = Process.GetProcessById(processId))
            {
                return String.Equals(process.ProcessName, "java", StringComparison.OrdinalIgnoreCase)
                    || String.Equals(process.ProcessName, "javaw", StringComparison.OrdinalIgnoreCase);
            }
        }
        catch (ArgumentException) { return false; }
        catch (InvalidOperationException) { return false; }
        catch (System.ComponentModel.Win32Exception) { return false; }
    }

    private static bool TryFindMinecraftWindowHandle(out IntPtr handle, out int processId)
    {
        return TryFindTopLevelWindow(delegate(string name)
        {
            return IsMinecraftGameTitle(name);
        }, IsJavaGameProcess, out handle, out processId);
    }

    private static long GetExtendedWindowStyle(IntPtr window)
    {
        return IntPtr.Size == 8
            ? GetWindowLongPtr64(window, GWL_EXSTYLE).ToInt64()
            : GetWindowLong32(window, GWL_EXSTYLE);
    }

    private static void SetExtendedWindowStyle(IntPtr window, long style)
    {
        if (IntPtr.Size == 8)
        {
            SetWindowLongPtr64(window, GWL_EXSTYLE, new IntPtr(style));
        }
        else
        {
            SetWindowLong32(window, GWL_EXSTYLE, unchecked((int)style));
        }
    }

    private static void ReleaseMinecraftStartupNoActivateGuard(IntPtr window)
    {
        if (window == IntPtr.Zero || !IsWindow(window))
        {
            throw new InvalidOperationException("Minecraft 窗口在释放防激活启动保护前已失效");
        }
        long guardedStyle = GetExtendedWindowStyle(window);
        if ((guardedStyle & WS_EX_NOACTIVATE) == 0)
        {
            throw new InvalidOperationException("Minecraft 窗口没有保持防激活启动保护，已拒绝报告成功");
        }
        SetExtendedWindowStyle(window, guardedStyle & ~WS_EX_NOACTIVATE);
        if ((GetExtendedWindowStyle(window) & WS_EX_NOACTIVATE) != 0)
        {
            throw new System.ComponentModel.Win32Exception(
                Marshal.GetLastWin32Error(),
                "Minecraft 防激活启动保护未能安全释放"
            );
        }
    }

    private static bool IsProcessForeground(int processId)
    {
        IntPtr foreground = GetForegroundWindow();
        if (foreground == IntPtr.Zero) return false;
        uint foregroundProcessId;
        return GetWindowThreadProcessId(foreground, out foregroundProcessId) != 0
            && foregroundProcessId == unchecked((uint)processId);
    }

    private static void VerifyMinecraftStartupGuard(IntPtr window, int expectedProcessId)
    {
        if (window == IntPtr.Zero || !IsWindow(window))
        {
            throw new InvalidOperationException("Minecraft 窗口在正式渲染接管前已失效");
        }
        uint actualProcessId;
        if (GetWindowThreadProcessId(window, out actualProcessId) == 0
            || actualProcessId != unchecked((uint)expectedProcessId))
        {
            throw new InvalidOperationException("Minecraft 窗口在正式渲染接管前更换了所属进程");
        }
        if ((GetExtendedWindowStyle(window) & WS_EX_NOACTIVATE) == 0)
        {
            throw new InvalidOperationException("Minecraft 窗口在正式渲染接管前丢失了防激活启动保护");
        }
        if (IsProcessForeground(expectedProcessId))
        {
            throw new InvalidOperationException("Minecraft 在正式渲染接管保护期间取得了前台");
        }
    }

    private static void WaitForMinecraftRuntimeTakeover(
        IntPtr window,
        int processId,
        out string initialTitle,
        out string runtimeTitle,
        out int waitMilliseconds
    )
    {
        DateTime startedAt = DateTime.UtcNow;
        DateTime deadline = startedAt.AddMilliseconds(STARTUP_GUARD_TAKEOVER_TIMEOUT_MILLISECONDS);
        DateTime stableSince = DateTime.MinValue;
        string stableTitle = String.Empty;
        initialTitle = ReadWindowTitle(window);
        runtimeTitle = String.Empty;
        waitMilliseconds = 0;

        while (DateTime.UtcNow < deadline)
        {
            VerifyMinecraftStartupGuard(window, processId);
            string currentTitle = ReadWindowTitle(window);
            if (IsMinecraftRuntimeTakeoverTitle(initialTitle, currentTitle))
            {
                if (!String.Equals(stableTitle, currentTitle, StringComparison.Ordinal))
                {
                    stableTitle = currentTitle;
                    stableSince = DateTime.UtcNow;
                }
                else if ((DateTime.UtcNow - stableSince).TotalMilliseconds
                    >= STARTUP_GUARD_TITLE_STABILITY_MILLISECONDS)
                {
                    runtimeTitle = currentTitle;
                    waitMilliseconds = checked((int)(DateTime.UtcNow - startedAt).TotalMilliseconds);
                    return;
                }
            }
            else
            {
                stableTitle = String.Empty;
                stableSince = DateTime.MinValue;
            }
            Thread.Sleep(STARTUP_GUARD_POLL_MILLISECONDS);
        }
        throw new TimeoutException("Minecraft 正式渲染窗口未在防激活保护期内完成稳定接管");
    }

    private static void AuditMinecraftAfterGuardRelease(int processId)
    {
        DateTime deadline = DateTime.UtcNow.AddMilliseconds(POST_RELEASE_AUDIT_MILLISECONDS);
        while (DateTime.UtcNow < deadline)
        {
            if (IsProcessForeground(processId))
            {
                throw new InvalidOperationException("Minecraft 在防激活启动保护释放后取得了前台");
            }
            Thread.Sleep(STARTUP_GUARD_POLL_MILLISECONDS);
        }
    }

    private static void CloseExistingHmcl()
    {
        IntPtr window;
        int processId;
        if (!TryFindHmclWindowHandle(out window, out processId)) return;
        Process process = Process.GetProcessById(processId);
        if (!process.CloseMainWindow()) throw new InvalidOperationException("正在运行的 HMCL 无法正常关闭，请先保存其操作后重试");
        DateTime deadline = DateTime.UtcNow.AddSeconds(20);
        while (!process.HasExited && DateTime.UtcNow < deadline) Thread.Sleep(200);
        if (!process.HasExited) throw new InvalidOperationException("正在运行的 HMCL 未在限定时间内正常退出");
    }

    private static string EncodeBase64Url(string value)
    {
        return Convert.ToBase64String(Encoding.UTF8.GetBytes(value))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
    }

    private static string QuoteJavaToolOption(string value)
    {
        if (value.IndexOf('"') >= 0 || value.IndexOf('\r') >= 0 || value.IndexOf('\n') >= 0)
        {
            throw new InvalidDataException("Java 启动策略路径包含不支持的字符");
        }
        return value.IndexOfAny(new[] { ' ', '\t' }) < 0 ? value : "\"" + value + "\"";
    }

    private static string BuildFocusJavaToolOptions(
        string focusAgentPath,
        string focusAuditPath,
        string runtimeTempPath
    )
    {
        string inherited = Environment.GetEnvironmentVariable("JAVA_TOOL_OPTIONS") ?? String.Empty;
        string required = QuoteJavaToolOption("-Djava.io.tmpdir=" + runtimeTempPath)
            + " " + QuoteJavaToolOption("-Djdk.net.unixdomain.tmpdir=" + runtimeTempPath)
            + " -Dmc.codex.preventWindowFocus=true"
            + " -Dmc.codex.windowFocusAudit=" + EncodeBase64Url(focusAuditPath)
            + " " + QuoteJavaToolOption("-javaagent:" + focusAgentPath);
        return inherited.Trim().Length == 0 ? required : inherited.Trim() + " " + required;
    }

    private static string CreateIsolatedRuntimeTempDirectory(string tempRoot)
    {
        SecurityIdentifier currentUser = WindowsIdentity.GetCurrent().User;
        if (currentUser == null) throw new InvalidOperationException("无法识别当前 Windows 用户");

        DirectorySecurity security = new DirectorySecurity();
        security.SetAccessRuleProtection(true, false);
        security.SetOwner(currentUser);
        InheritanceFlags inheritance = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
        security.AddAccessRule(new FileSystemAccessRule(
            currentUser,
            FileSystemRights.FullControl,
            inheritance,
            PropagationFlags.None,
            AccessControlType.Allow
        ));
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl,
            inheritance,
            PropagationFlags.None,
            AccessControlType.Allow
        ));
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            FileSystemRights.FullControl,
            inheritance,
            PropagationFlags.None,
            AccessControlType.Allow
        ));

        string[] roots = new[] {
            Environment.GetEnvironmentVariable("PUBLIC") ?? String.Empty,
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            tempRoot
        };
        Exception lastError = null;
        HashSet<string> visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string candidate in roots)
        {
            if (String.IsNullOrWhiteSpace(candidate)) continue;
            string root;
            try { root = NormalizedDirectory(candidate); }
            catch (Exception error) { lastError = error; continue; }
            if (!visited.Add(root) || !Directory.Exists(root)) continue;
            for (int attempt = 0; attempt < 8; attempt++)
            {
                string directory = Path.Combine(
                    root,
                    "m" + Guid.NewGuid().ToString("N").Substring(0, 16)
                );
                if (directory.Length > MAX_JAVA_UNIX_TEMP_PATH_LENGTH) break;
                try
                {
                    if (Directory.Exists(directory)) continue;
                    Directory.CreateDirectory(directory, security);
                    return directory;
                }
                catch (UnauthorizedAccessException error) { lastError = error; }
                catch (IOException error) { lastError = error; }
                catch (System.Security.SecurityException error) { lastError = error; }
            }
        }
        throw new IOException("无法创建长度安全的独立 Java 临时目录", lastError);
    }

    private static bool HasVerifiedFocusInstrumentation(string auditPath, int processId)
    {
        if (processId <= 0 || !File.Exists(auditPath)) return false;
        try
        {
            JavaScriptSerializer serializer = new JavaScriptSerializer();
            serializer.MaxJsonLength = 1024 * 1024;
            foreach (string line in File.ReadAllLines(auditPath, StrictUtf8))
            {
                if (String.IsNullOrWhiteSpace(line)) continue;
                Dictionary<string, object> entry = serializer.DeserializeObject(line) as Dictionary<string, object>;
                if (entry == null) continue;
                object eventValue;
                object processValue;
                object classValue;
                object methodCountValue;
                object showMethodCountValue;
                object focusMethodCountValue;
                object noActivateStyleGuardValue;
                object noActivateStyleHeldValue;
                object policyVersionValue;
                if (!entry.TryGetValue("event", out eventValue)
                    || !String.Equals(Convert.ToString(eventValue), "instrumented", StringComparison.Ordinal)
                    || !entry.TryGetValue("pid", out processValue)
                    || Convert.ToInt64(processValue) != processId
                    || !entry.TryGetValue("class", out classValue)
                    || !String.Equals(Convert.ToString(classValue), "org/lwjgl/glfw/GLFW", StringComparison.Ordinal)
                    || !entry.TryGetValue("methodCount", out methodCountValue)
                    || Convert.ToInt32(methodCountValue) < 2
                    || !entry.TryGetValue("showMethodCount", out showMethodCountValue)
                    || Convert.ToInt32(showMethodCountValue) < 1
                    || !entry.TryGetValue("focusMethodCount", out focusMethodCountValue)
                    || Convert.ToInt32(focusMethodCountValue) < 1
                    || !entry.TryGetValue("noActivateStyleGuard", out noActivateStyleGuardValue)
                    || !Convert.ToBoolean(noActivateStyleGuardValue)
                    || !entry.TryGetValue("noActivateStyleHeldForLauncherRelease", out noActivateStyleHeldValue)
                    || !Convert.ToBoolean(noActivateStyleHeldValue)
                    || !entry.TryGetValue("policyVersion", out policyVersionValue)
                    || !String.Equals(
                        Convert.ToString(policyVersionValue),
                        WindowFocusAgentPolicyVersion,
                        StringComparison.Ordinal
                    ))
                {
                    continue;
                }
                return true;
            }
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        catch (InvalidOperationException) { }
        catch (FormatException) { }
        catch (OverflowException) { }
        return false;
    }

    private static ProcessStartInfo BuildHmclStartInfo(
        string launcherPath,
        string[] launcherArguments,
        string focusAgentPath,
        string focusAuditPath,
        string runtimeTempPath
    )
    {
        ProcessStartInfo info = new ProcessStartInfo();
        if (String.Equals(Path.GetExtension(launcherPath), ".jar", StringComparison.OrdinalIgnoreCase))
        {
            info.FileName = "javaw.exe";
            List<string> args = new List<string>();
            args.Add("-jar");
            args.Add(launcherPath);
            args.AddRange(launcherArguments);
            info.Arguments = String.Join(" ", args.ConvertAll(Quote).ToArray());
        }
        else
        {
            info.FileName = launcherPath;
            info.Arguments = String.Join(" ", new List<string>(launcherArguments).ConvertAll(Quote).ToArray());
        }
        info.WorkingDirectory = Path.GetDirectoryName(launcherPath);
        info.UseShellExecute = false;
        info.WindowStyle = ProcessWindowStyle.Normal;
        info.EnvironmentVariables["TEMP"] = runtimeTempPath;
        info.EnvironmentVariables["TMP"] = runtimeTempPath;
        info.EnvironmentVariables["TMPDIR"] = runtimeTempPath;
        info.EnvironmentVariables["JAVA_TOOL_OPTIONS"] = BuildFocusJavaToolOptions(
            focusAgentPath,
            focusAuditPath,
            runtimeTempPath
        );
        return info;
    }

    private static byte[] BuildEnvironmentBlock(ProcessStartInfo info)
    {
        List<KeyValuePair<string, string>> entries = new List<KeyValuePair<string, string>>();
        foreach (DictionaryEntry entry in info.EnvironmentVariables)
        {
            string key = Convert.ToString(entry.Key) ?? String.Empty;
            string value = Convert.ToString(entry.Value) ?? String.Empty;
            if (key.Length == 0 || key.IndexOf('\0') >= 0 || value.IndexOf('\0') >= 0)
            {
                throw new InvalidDataException("进程环境变量包含无效名称或空字符");
            }
            entries.Add(new KeyValuePair<string, string>(key, value));
        }
        entries.Sort(delegate(KeyValuePair<string, string> left, KeyValuePair<string, string> right)
        {
            return StringComparer.OrdinalIgnoreCase.Compare(left.Key, right.Key);
        });

        StringBuilder block = new StringBuilder();
        foreach (KeyValuePair<string, string> entry in entries)
        {
            block.Append(entry.Key).Append('=').Append(entry.Value).Append('\0');
        }
        block.Append('\0');
        return Encoding.Unicode.GetBytes(block.ToString());
    }

    private static NativeProcess StartNoActivate(ProcessStartInfo info)
    {
        if (info == null) throw new ArgumentNullException("info");
        if (info.UseShellExecute) throw new InvalidOperationException("无激活启动不支持 ShellExecute");

        string command = Quote(info.FileName);
        if (!String.IsNullOrWhiteSpace(info.Arguments)) command += " " + info.Arguments;
        StringBuilder mutableCommand = new StringBuilder(command);
        byte[] environmentBytes = BuildEnvironmentBlock(info);
        IntPtr environment = IntPtr.Zero;
        ProcessInformation created = new ProcessInformation();
        StartupInfo startup = new StartupInfo();
        startup.Size = Marshal.SizeOf(typeof(StartupInfo));
        startup.Flags = STARTF_USESHOWWINDOW;
        startup.WindowShowCommand = SW_SHOWNOACTIVATE;
        try
        {
            environment = Marshal.AllocHGlobal(environmentBytes.Length);
            Marshal.Copy(environmentBytes, 0, environment, environmentBytes.Length);
            bool started = CreateProcessW(
                null,
                mutableCommand,
                IntPtr.Zero,
                IntPtr.Zero,
                false,
                CREATE_UNICODE_ENVIRONMENT,
                environment,
                String.IsNullOrWhiteSpace(info.WorkingDirectory) ? null : info.WorkingDirectory,
                ref startup,
                out created
            );
            if (!started)
            {
                throw new System.ComponentModel.Win32Exception(
                    Marshal.GetLastWin32Error(),
                    "无法以 SW_SHOWNOACTIVATE 启动 HMCL"
                );
            }
            CloseHandle(created.Thread);
            created.Thread = IntPtr.Zero;
            NativeProcess result = new NativeProcess(created.Process, created.ProcessId);
            created.Process = IntPtr.Zero;
            return result;
        }
        finally
        {
            if (created.Thread != IntPtr.Zero) CloseHandle(created.Thread);
            if (created.Process != IntPtr.Zero) CloseHandle(created.Process);
            if (environment != IntPtr.Zero) Marshal.FreeHGlobal(environment);
        }
    }

    private static NativeProcess StartHmcl(
        string launcherPath,
        string[] launcherArguments,
        string focusAgentPath,
        string focusAuditPath,
        string runtimeTempPath
    )
    {
        return StartNoActivate(BuildHmclStartInfo(
            launcherPath,
            launcherArguments,
            focusAgentPath,
            focusAuditPath,
            runtimeTempPath
        ));
    }

    private static void DismissUpdateDialog(AutomationElement hmcl)
    {
        AutomationElementCollection descendants;
        try
        {
            descendants = hmcl.FindAll(TreeScope.Descendants, Condition.TrueCondition);
        }
        catch (ElementNotAvailableException) { return; }
        catch (InvalidOperationException) { return; }
        catch (COMException) { return; }
        foreach (AutomationElement element in descendants)
        {
            try
            {
                string name = element.Current.Name ?? String.Empty;
                if (!CancelLabels.Contains(name.Trim())) continue;
                object pattern;
                if (element.TryGetCurrentPattern(InvokePattern.Pattern, out pattern))
                {
                    ((InvokePattern)pattern).Invoke();
                    Thread.Sleep(350);
                    return;
                }
            }
            catch (ElementNotAvailableException) { }
            catch (InvalidOperationException) { }
            catch (COMException) { }
        }
    }

    private static bool ContainsExactInstance(AutomationElement element, string expectedInstance)
    {
        AutomationElementCollection descendants;
        try
        {
            descendants = element.FindAll(TreeScope.Descendants, Condition.TrueCondition);
        }
        catch (ElementNotAvailableException) { return false; }
        catch (InvalidOperationException) { return false; }
        catch (COMException) { return false; }
        foreach (AutomationElement descendant in descendants)
        {
            try
            {
                if (String.Equals(descendant.Current.Name, expectedInstance, StringComparison.OrdinalIgnoreCase)) return true;
            }
            catch (ElementNotAvailableException) { }
            catch (InvalidOperationException) { }
            catch (COMException) { }
        }
        return false;
    }

    private static InvokePattern FindExactLaunchPattern(AutomationElement hmcl, string expectedInstance)
    {
        AutomationElementCollection descendants;
        try
        {
            descendants = hmcl.FindAll(TreeScope.Descendants, Condition.TrueCondition);
        }
        catch (ElementNotAvailableException) { return null; }
        catch (InvalidOperationException) { return null; }
        catch (COMException) { return null; }
        foreach (AutomationElement element in descendants)
        {
            try
            {
                if (!LaunchText.IsMatch(element.Current.Name ?? String.Empty)) continue;
                AutomationElement candidate = element;
                for (int depth = 0; depth < 7 && candidate != null; depth++)
                {
                    object pattern;
                    if (candidate.TryGetCurrentPattern(InvokePattern.Pattern, out pattern)
                        && ContainsExactInstance(candidate, expectedInstance))
                    {
                        return (InvokePattern)pattern;
                    }
                    candidate = TreeWalker.ControlViewWalker.GetParent(candidate);
                }
            }
            catch (ElementNotAvailableException) { }
            catch (InvalidOperationException) { }
            catch (COMException) { }
        }
        return null;
    }

    private static string EscapeJson(string value)
    {
        return value.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "\\r").Replace("\n", "\\n");
    }

    private static int StartupInfoProbe(string outputPath)
    {
        StartupInfo startup;
        GetStartupInfoW(out startup);
        string marker = Environment.GetEnvironmentVariable("MC_CODEX_STARTUP_PROBE") ?? String.Empty;
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outputPath)));
        File.WriteAllText(
            outputPath,
            startup.Flags + "|" + startup.WindowShowCommand + "|" + marker,
            Utf8WithoutBom
        );
        return 0;
    }

    private static int SelfTest(string outputPath)
    {
        string fixtureRoot = Path.Combine(Path.GetTempPath(), "mc-codex-hmcl-launcher-" + Guid.NewGuid().ToString("N"));
        try
        {
            string launcherPath = Path.Combine(fixtureRoot, "HMCL.exe");
            string minecraftRoot = Path.Combine(fixtureRoot, "game");
            string instance = "1.21.1-NeoForge";
            string configDirectory = Path.Combine(fixtureRoot, ".hmcl", "config");
            string versionDirectory = Path.Combine(minecraftRoot, "versions", instance);
            string stateDirectory = Path.Combine(fixtureRoot, "state");
            Directory.CreateDirectory(configDirectory);
            Directory.CreateDirectory(versionDirectory);
            File.WriteAllText(launcherPath, "fixture", Utf8WithoutBom);
            File.WriteAllText(Path.Combine(versionDirectory, instance + ".json"), "{}", Utf8WithoutBom);
            File.WriteAllText(Path.Combine(versionDirectory, instance + ".jar"), "fixture", Utf8WithoutBom);

            JavaScriptSerializer serializer = new JavaScriptSerializer();
            Dictionary<string, object> directory = new Dictionary<string, object>();
            directory["id"] = "game-directory:test";
            directory["path"] = minecraftRoot;
            Dictionary<string, object> directories = new Dictionary<string, object>();
            directories["directories"] = new object[] { directory };
            File.WriteAllText(Path.Combine(configDirectory, "game-directories.json"), serializer.Serialize(directories), Utf8WithoutBom);
            Dictionary<string, object> settings = new Dictionary<string, object>();
            settings["selectedGameDirectory"] = "game-directory:old";
            settings["selectedInstance"] = new Dictionary<string, object>();
            File.WriteAllText(Path.Combine(configDirectory, "launcher-settings.json"), serializer.Serialize(settings), Utf8WithoutBom);

            if (!SelectExactInstance(launcherPath, minecraftRoot, instance, stateDirectory)) return 2;
            if (SelectExactInstance(launcherPath, minecraftRoot, instance, stateDirectory)) return 3;
            Dictionary<string, object> verified = ReadJsonObject(Path.Combine(configDirectory, "launcher-settings.json"));
            if (!String.Equals(StringValue(verified, "selectedGameDirectory"), "game-directory:test", StringComparison.Ordinal)) return 4;
            string productionRuntimeTempPath = CreateIsolatedRuntimeTempDirectory(
                Path.Combine(fixtureRoot, "isolated runtime temp")
            );
            try
            {
                if (productionRuntimeTempPath.Length > MAX_JAVA_UNIX_TEMP_PATH_LENGTH) return 24;
                if (!Directory.GetAccessControl(productionRuntimeTempPath).AreAccessRulesProtected) return 25;
            }
            finally
            {
                if (Directory.Exists(productionRuntimeTempPath))
                {
                    Directory.Delete(productionRuntimeTempPath, true);
                }
            }
            string runtimeTempPath = Path.Combine(fixtureRoot, "isolated runtime temp");
            Directory.CreateDirectory(runtimeTempPath);
            string focusOptions = BuildFocusJavaToolOptions(
                Path.Combine(fixtureRoot, "runtime with spaces", WindowFocusAgentFileName),
                Path.Combine(stateDirectory, "window-focus-agent.jsonl"),
                runtimeTempPath
            );
            if (!focusOptions.Contains("-Dmc.codex.preventWindowFocus=true")) return 5;
            if (!focusOptions.Contains("-javaagent:")) return 6;
            if (!focusOptions.Contains("-Djava.io.tmpdir=")) return 19;
            if (!focusOptions.Contains("-Djdk.net.unixdomain.tmpdir=")) return 20;
            ProcessStartInfo focusStart = BuildHmclStartInfo(
                launcherPath,
                new[] { "--fixture" },
                Path.Combine(fixtureRoot, "runtime with spaces", WindowFocusAgentFileName),
                Path.Combine(stateDirectory, "window-focus-agent.jsonl"),
                runtimeTempPath
            );
            if (focusStart.UseShellExecute) return 7;
            if (!focusStart.EnvironmentVariables["JAVA_TOOL_OPTIONS"].Contains("-javaagent:")) return 8;
            if (!String.Equals(focusStart.EnvironmentVariables["TEMP"], runtimeTempPath, StringComparison.Ordinal)) return 21;
            if (!String.Equals(focusStart.EnvironmentVariables["TMP"], runtimeTempPath, StringComparison.Ordinal)) return 22;
            if (!String.Equals(focusStart.EnvironmentVariables["TMPDIR"], runtimeTempPath, StringComparison.Ordinal)) return 23;
            string startupProbePath = Path.Combine(fixtureRoot, "startup-info-probe.txt");
            string startupProbeMarker = Guid.NewGuid().ToString("N");
            ProcessStartInfo startupProbe = new ProcessStartInfo();
            startupProbe.FileName = Process.GetCurrentProcess().MainModule.FileName;
            startupProbe.Arguments = Quote("startup-info-probe") + " " + Quote(startupProbePath);
            startupProbe.WorkingDirectory = fixtureRoot;
            startupProbe.UseShellExecute = false;
            startupProbe.EnvironmentVariables["MC_CODEX_STARTUP_PROBE"] = startupProbeMarker;
            using (NativeProcess child = StartNoActivate(startupProbe))
            {
                if (!child.WaitForExit(10000)) return 9;
                if (child.ExitCode != 0) return 10;
            }
            if (!File.Exists(startupProbePath)) return 11;
            string startupProbeResult = File.ReadAllText(startupProbePath, StrictUtf8).Trim();
            if (!String.Equals(
                startupProbeResult,
                STARTF_USESHOWWINDOW + "|" + SW_SHOWNOACTIVATE + "|" + startupProbeMarker,
                StringComparison.Ordinal
            )) return 12;
            string auditPath = Path.Combine(stateDirectory, "focus-audit-fixture.jsonl");
            File.WriteAllText(
                auditPath,
                "{\"event\":\"instrumented\",\"pid\":4321,\"class\":\"org/lwjgl/glfw/GLFW\","
                    + "\"methodCount\":2,\"showMethodCount\":1,\"focusMethodCount\":1,"
                    + "\"noActivateStyleGuard\":true,"
                    + "\"noActivateStyleHeldForLauncherRelease\":true,"
                    + "\"policyVersion\":\"" + WindowFocusAgentPolicyVersion + "\"}\n",
                Utf8WithoutBom
            );
            if (!HasVerifiedFocusInstrumentation(auditPath, 4321)) return 13;
            if (HasVerifiedFocusInstrumentation(auditPath, 4322)) return 14;
            if (IsMinecraftRuntimeTakeoverTitle("Minecraft* 1.20.1", "Minecraft* 1.20.1")) return 15;
            if (!IsMinecraftRuntimeTakeoverTitle("Minecraft* 1.20.1", "Minecraft* Forge 1.20.1")) return 16;
            if (IsMinecraftRuntimeTakeoverTitle("Minecraft: NeoForge Loading...", "Minecraft: NeoForge Loading...")) return 17;
            if (!IsMinecraftRuntimeTakeoverTitle("Minecraft: NeoForge Loading...", "Minecraft* 1.21.1")) return 18;
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outputPath)));
            File.WriteAllText(outputPath, "ok", Utf8WithoutBom);
            return 0;
        }
        finally
        {
            if (Directory.Exists(fixtureRoot)) Directory.Delete(fixtureRoot, true);
        }
    }

    private static int Main(string[] args)
    {
        string stage = "startup";
        IntPtr guardedMinecraftWindow = IntPtr.Zero;
        bool minecraftStartupGuardReleased = false;
        try
        {
            if (args.Length == 2 && String.Equals(args[0], "self-test", StringComparison.OrdinalIgnoreCase))
            {
                return SelfTest(args[1]);
            }
            if (args.Length == 2 && String.Equals(args[0], "startup-info-probe", StringComparison.OrdinalIgnoreCase))
            {
                return StartupInfoProbe(args[1]);
            }
            if (args.Length < 4)
            {
                throw new ArgumentException("用法：MinecraftCodexHmclLauncher.exe <HMCL> <Minecraft 根目录> <精确实例名> <状态目录> [启动器参数...]");
            }
            string launcherPath = Path.GetFullPath(args[0]);
            string minecraftRoot = Path.GetFullPath(args[1]);
            string expectedInstance = args[2].Trim();
            string stateDirectory = Path.GetFullPath(args[3]);
            if (!File.Exists(launcherPath)) throw new FileNotFoundException("HMCL 启动器不存在", launcherPath);
            if (!Directory.Exists(minecraftRoot)) throw new DirectoryNotFoundException("Minecraft 根目录不存在");
            if (expectedInstance.Length == 0 || expectedInstance.Length > 120 || expectedInstance.IndexOfAny(new[] { '\\', '/', '\r', '\n', '\0' }) >= 0)
            {
                throw new ArgumentException("精确实例名无效");
            }
            string focusAgentPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, WindowFocusAgentFileName);
            if (!File.Exists(focusAgentPath))
            {
                throw new FileNotFoundException("防止 Minecraft 启动时抢占前台的本地策略代理不存在", focusAgentPath);
            }
            Directory.CreateDirectory(stateDirectory);
            string focusAuditPath = Path.Combine(stateDirectory, "window-focus-agent.jsonl");
            if (File.Exists(focusAuditPath)) File.Delete(focusAuditPath);
            IntPtr existingGameHandle;
            int existingGameProcessId;
            if (TryFindMinecraftWindowHandle(out existingGameHandle, out existingGameProcessId))
            {
                throw new InvalidOperationException("已有 Minecraft 游戏窗口，已停止以避免启动第二个游戏端");
            }

            stage = "select-exact-instance";
            string[] launcherArguments = new string[Math.Max(0, args.Length - 4)];
            if (launcherArguments.Length > 0) Array.Copy(args, 4, launcherArguments, 0, launcherArguments.Length);
            CloseExistingHmcl();
            bool settingsChanged = SelectExactInstance(launcherPath, minecraftRoot, expectedInstance, stateDirectory);
            stage = "start-hmcl";
            string runtimeTempPath = CreateIsolatedRuntimeTempDirectory(Path.GetTempPath());
            using (NativeProcess startedHmcl = StartHmcl(
                launcherPath,
                launcherArguments,
                focusAgentPath,
                focusAuditPath,
                runtimeTempPath
            )) { }

            stage = "find-hmcl-window";
            DateTime hmclDeadline = DateTime.UtcNow.AddSeconds(90);
            AutomationElement hmcl = null;
            while (hmcl == null && DateTime.UtcNow < hmclDeadline)
            {
                Thread.Sleep(200);
                hmcl = FindHmclWindow();
            }
            if (hmcl == null) throw new TimeoutException("HMCL 主窗口未在限定时间内出现");
            DismissUpdateDialog(hmcl);

            stage = "invoke-exact-instance";
            DateTime selectionDeadline = DateTime.UtcNow.AddSeconds(45);
            bool launchInvoked = false;
            while (!launchInvoked && DateTime.UtcNow < selectionDeadline)
            {
                hmcl = FindHmclWindow();
                InvokePattern launch = hmcl == null ? null : FindExactLaunchPattern(hmcl, expectedInstance);
                if (launch != null)
                {
                    try
                    {
                        launch.Invoke();
                        launchInvoked = true;
                        break;
                    }
                    catch (ElementNotAvailableException) { }
                    catch (InvalidOperationException) { }
                    catch (COMException) { }
                }
                Thread.Sleep(250);
            }
            if (!launchInvoked) throw new InvalidOperationException("HMCL 启动按钮未显示所选的精确源实例，已拒绝启动");

            stage = "find-minecraft-window";
            DateTime gameDeadline = DateTime.UtcNow.AddSeconds(300);
            IntPtr gameHandle = IntPtr.Zero;
            int gameProcessId = 0;
            while (gameHandle == IntPtr.Zero && DateTime.UtcNow < gameDeadline)
            {
                Thread.Sleep(100);
                TryFindMinecraftWindowHandle(out gameHandle, out gameProcessId);
            }
            if (gameHandle == IntPtr.Zero || gameProcessId <= 0)
            {
                throw new TimeoutException("Minecraft 游戏窗口未在限定时间内出现");
            }
            guardedMinecraftWindow = gameHandle;
            stage = "verify-no-focus-policy";
            DateTime focusPolicyDeadline = DateTime.UtcNow.AddSeconds(5);
            while (!HasVerifiedFocusInstrumentation(focusAuditPath, gameProcessId)
                && DateTime.UtcNow < focusPolicyDeadline)
            {
                Thread.Sleep(50);
            }
            if (!HasVerifiedFocusInstrumentation(focusAuditPath, gameProcessId))
            {
                throw new InvalidOperationException("Minecraft 游戏进程未验证启动防抢焦点策略，已拒绝报告成功");
            }
            stage = "wait-for-minecraft-runtime-takeover";
            string startupWindowTitle;
            string runtimeWindowTitle;
            int startupGuardWaitMilliseconds;
            WaitForMinecraftRuntimeTakeover(
                gameHandle,
                gameProcessId,
                out startupWindowTitle,
                out runtimeWindowTitle,
                out startupGuardWaitMilliseconds
            );
            bool foregroundBeforeGuardRelease = IsProcessForeground(gameProcessId);
            stage = "release-minecraft-noactivate-guard";
            ReleaseMinecraftStartupNoActivateGuard(gameHandle);
            minecraftStartupGuardReleased = true;
            if (foregroundBeforeGuardRelease)
            {
                throw new InvalidOperationException("Minecraft 在防激活启动保护释放前取得了前台，已拒绝报告成功");
            }
            stage = "audit-after-minecraft-noactivate-release";
            AuditMinecraftAfterGuardRelease(gameProcessId);
            stage = "minecraft-window-ready";
            Console.WriteLine(
                "{\"launched\":true,\"exactInstance\":\"" + EscapeJson(expectedInstance)
                + "\",\"instanceMode\":\"direct-source\",\"settingsChanged\":"
                + (settingsChanged ? "true" : "false")
                + ",\"exactSelectionVerified\":true,\"minecraftWindowReady\":true"
                + ",\"minecraftProcessId\":" + gameProcessId
                + ",\"minecraftWindowHandle\":" + gameHandle.ToInt64()
                + ",\"focusPolicy\":\"" + WindowFocusPolicyVersion + "\""
                + ",\"focusAgentPolicy\":\"" + WindowFocusAgentPolicyVersion + "\""
                + ",\"focusPolicyVerified\":true"
                + ",\"launcherStartupNoActivateApplied\":true"
                + ",\"launcherStartupShowMode\":\"SW_SHOWNOACTIVATE\""
                + ",\"isolatedRuntimeTempApplied\":true"
                + ",\"isolatedRuntimeTempScope\":\"hmcl-and-descendant-java-processes\""
                + ",\"runtimeTempPathLengthSafe\":true"
                + ",\"runtimeTempAccessRestricted\":true"
                + ",\"foregroundRestorationUsed\":false"
                + ",\"windowCreationHintsApplied\":true"
                + ",\"windowShowFocusGuardApplied\":true"
                + ",\"windowNoActivateStyleGuardApplied\":true"
                + ",\"windowNoActivateStyleHeldUntilLauncherRelease\":true"
                + ",\"windowNoActivateStyleReleasedByLauncher\":true"
                + ",\"startupGuardReleaseCriterion\":\"runtime-title-takeover-stable\""
                + ",\"startupWindowTitle\":\"" + EscapeJson(startupWindowTitle) + "\""
                + ",\"runtimeWindowTitle\":\"" + EscapeJson(runtimeWindowTitle) + "\""
                + ",\"startupGuardWaitMilliseconds\":" + startupGuardWaitMilliseconds
                + ",\"startupGuardTitleStabilityMilliseconds\":" + STARTUP_GUARD_TITLE_STABILITY_MILLISECONDS
                + ",\"postReleaseAuditMilliseconds\":" + POST_RELEASE_AUDIT_MILLISECONDS
                + ",\"explicitWindowFocusSuppressed\":true"
                + ",\"launcherWindowMutated\":false"
                + ",\"minecraftWindowMutated\":true"
                + ",\"minecraftWindowMutationTemporary\":true"
                + ",\"minecraftWindowMutationScope\":\"extended-style-through-startup-settle\""
                + ",\"minecraftWindowPersistentMutation\":false"
                + ",\"mouseOrKeyboardInputUsed\":false}"
            );
            return 0;
        }
        catch (Exception error)
        {
            if (!minecraftStartupGuardReleased && guardedMinecraftWindow != IntPtr.Zero)
            {
                try
                {
                    long style = GetExtendedWindowStyle(guardedMinecraftWindow);
                    if ((style & WS_EX_NOACTIVATE) != 0)
                    {
                        SetExtendedWindowStyle(guardedMinecraftWindow, style & ~WS_EX_NOACTIVATE);
                    }
                }
                catch { }
            }
            Console.Error.WriteLine(stage + " [" + error.GetType().Name + "]: " + error.Message);
            return 1;
        }
    }
}
