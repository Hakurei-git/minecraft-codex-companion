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
    private const string WindowFocusAgentFileName = "MinecraftCodexWindowFocusAgent.jar";
    private const string WindowFocusPolicyVersion = "glfw-create-show-noactivate-and-explicit-focus-v3";
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

    private static string BuildFocusJavaToolOptions(string focusAgentPath, string focusAuditPath)
    {
        string inherited = Environment.GetEnvironmentVariable("JAVA_TOOL_OPTIONS") ?? String.Empty;
        string required = "-Dmc.codex.preventWindowFocus=true"
            + " -Dmc.codex.windowFocusAudit=" + EncodeBase64Url(focusAuditPath)
            + " " + QuoteJavaToolOption("-javaagent:" + focusAgentPath);
        return inherited.Trim().Length == 0 ? required : inherited.Trim() + " " + required;
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
                    || !entry.TryGetValue("policyVersion", out policyVersionValue)
                    || !String.Equals(
                        Convert.ToString(policyVersionValue),
                        WindowFocusPolicyVersion,
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
        string focusAuditPath
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
        info.EnvironmentVariables["JAVA_TOOL_OPTIONS"] = BuildFocusJavaToolOptions(focusAgentPath, focusAuditPath);
        return info;
    }

    private static Process StartHmcl(
        string launcherPath,
        string[] launcherArguments,
        string focusAgentPath,
        string focusAuditPath
    )
    {
        return Process.Start(BuildHmclStartInfo(
            launcherPath,
            launcherArguments,
            focusAgentPath,
            focusAuditPath
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
            string focusOptions = BuildFocusJavaToolOptions(
                Path.Combine(fixtureRoot, "runtime with spaces", WindowFocusAgentFileName),
                Path.Combine(stateDirectory, "window-focus-agent.jsonl")
            );
            if (!focusOptions.Contains("-Dmc.codex.preventWindowFocus=true")) return 5;
            if (!focusOptions.Contains("-javaagent:")) return 6;
            ProcessStartInfo focusStart = BuildHmclStartInfo(
                launcherPath,
                new[] { "--fixture" },
                Path.Combine(fixtureRoot, "runtime with spaces", WindowFocusAgentFileName),
                Path.Combine(stateDirectory, "window-focus-agent.jsonl")
            );
            if (focusStart.UseShellExecute) return 7;
            if (!focusStart.EnvironmentVariables["JAVA_TOOL_OPTIONS"].Contains("-javaagent:")) return 8;
            string auditPath = Path.Combine(stateDirectory, "focus-audit-fixture.jsonl");
            File.WriteAllText(
                auditPath,
                "{\"event\":\"instrumented\",\"pid\":4321,\"class\":\"org/lwjgl/glfw/GLFW\","
                    + "\"methodCount\":2,\"showMethodCount\":1,\"focusMethodCount\":1,"
                    + "\"noActivateStyleGuard\":true,"
                    + "\"policyVersion\":\"" + WindowFocusPolicyVersion + "\"}\n",
                Utf8WithoutBom
            );
            if (!HasVerifiedFocusInstrumentation(auditPath, 4321)) return 9;
            if (HasVerifiedFocusInstrumentation(auditPath, 4322)) return 10;
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
            StartHmcl(launcherPath, launcherArguments, focusAgentPath, focusAuditPath);

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
            stage = "minecraft-window-ready";
            Console.WriteLine(
                "{\"launched\":true,\"exactInstance\":\"" + EscapeJson(expectedInstance)
                + "\",\"instanceMode\":\"direct-source\",\"settingsChanged\":"
                + (settingsChanged ? "true" : "false")
                + ",\"exactSelectionVerified\":true,\"minecraftWindowReady\":true"
                + ",\"minecraftProcessId\":" + gameProcessId
                + ",\"minecraftWindowHandle\":" + gameHandle.ToInt64()
                + ",\"focusPolicy\":\"" + WindowFocusPolicyVersion + "\""
                + ",\"focusPolicyVerified\":true"
                + ",\"windowCreationHintsApplied\":true"
                + ",\"windowShowFocusGuardApplied\":true"
                + ",\"windowNoActivateStyleGuardApplied\":true"
                + ",\"windowNoActivateStyleRestoredAfterShow\":true"
                + ",\"explicitWindowFocusSuppressed\":true"
                + ",\"launcherWindowMutated\":false"
                + ",\"minecraftWindowMutated\":true"
                + ",\"minecraftWindowMutationTemporary\":true"
                + ",\"minecraftWindowMutationScope\":\"extended-style-during-native-show\""
                + ",\"minecraftWindowPersistentMutation\":false"
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
