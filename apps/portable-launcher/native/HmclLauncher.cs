using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Automation;

internal static class HmclLauncher
{
    private const int SwMinimize = 6;
    private static readonly Regex HmclTitle = new Regex("HMCL|Hello Minecraft", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex LaunchText = new Regex("启动游戏|Launch Game", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly UTF8Encoding StrictUtf8 = new UTF8Encoding(false, true);
    private static readonly UTF8Encoding Utf8WithoutBom = new UTF8Encoding(false);
    private static readonly HashSet<string> CancelLabels = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
    {
        "Cancel", "取消", "稍后", "Not now", "Later"
    };

    private delegate bool EnumWindowsCallback(IntPtr window, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsCallback callback, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "GetWindowTextW")]
    private static extern int GetWindowTextNative(IntPtr window, StringBuilder text, int maximumCount);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr window);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll")]
    private static extern bool SetWindowPos(IntPtr window, IntPtr insertAfter, int x, int y, int width, int height, uint flags);

    [DllImport("user32.dll")]
    private static extern bool ShowWindowAsync(IntPtr window, int command);

    [DllImport("user32.dll")]
    private static extern bool ClipCursor(IntPtr rectangle);

    [DllImport("user32.dll")]
    private static extern bool ReleaseCapture();

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
            foundHandle = window;
            foundProcessId = unchecked((int)candidateProcessId);
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

    private static bool TryFindMinecraftWindowHandle(out IntPtr handle, out int processId)
    {
        return TryFindTopLevelWindow(delegate(string name)
        {
            return IsMinecraftGameTitle(name);
        }, out handle, out processId);
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

    private static Process StartHmcl(string launcherPath, string[] launcherArguments)
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
        info.UseShellExecute = true;
        info.WindowStyle = ProcessWindowStyle.Normal;
        return Process.Start(info);
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

    private static void BackgroundWindow(IntPtr handle)
    {
        // A synthetic WM_KILLFOCUS makes fullscreen GLFW minimize itself. The
        // Win32 capture release and bottom placement keep interaction in the
        // background without fabricating a focus transition.
        ReleaseCapture();
        ClipCursor(IntPtr.Zero);
        const uint flags = 0x0001 | 0x0002 | 0x0010;
        SetWindowPos(handle, (IntPtr)1, 0, 0, 0, 0, flags);
    }

    private static string EscapeJson(string value)
    {
        return value.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "\\r").Replace("\n", "\\n");
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
        try
        {
            if (args.Length == 2 && String.Equals(args[0], "self-test", StringComparison.OrdinalIgnoreCase))
            {
                return SelfTest(args[1]);
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
            StartHmcl(launcherPath, launcherArguments);

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
            stage = "background-minecraft-window";
            BackgroundWindow(gameHandle);
            IntPtr hmclHandle;
            int hmclProcessId;
            if (TryFindHmclWindowHandle(out hmclHandle, out hmclProcessId))
            {
                ShowWindowAsync(hmclHandle, SwMinimize);
            }
            BackgroundWindow(gameHandle);

            Console.WriteLine(
                "{\"launched\":true,\"exactInstance\":\"" + EscapeJson(expectedInstance)
                + "\",\"instanceMode\":\"direct-source\",\"settingsChanged\":"
                + (settingsChanged ? "true" : "false")
                + ",\"exactSelectionVerified\":true,\"minecraftWindowReady\":true"
                + ",\"minecraftProcessId\":" + gameProcessId
                + ",\"minecraftWindowHandle\":" + gameHandle.ToInt64()
                + ",\"mouseOrKeyboardInputUsed\":false}"
            );
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(stage + " [" + error.GetType().Name + "]: " + error.Message);
            return 1;
        }
    }
}
