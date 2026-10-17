using System;
using System.IO;
using System.Globalization;
using System.Management;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;

namespace MinecraftCodexCompanion
{
    // Native local credential operations; never launch a shell or access the network.
    internal static class SecretHelper
    {
        private const int MaximumInputCharacters = 1024 * 1024;

        private static int Main(string[] args)
        {
            try
            {
                if (args.Length == 1 && args[0] == "antigravity-self-test")
                {
                    const string sample = "22222222-2222-2222-2222-222222222222";
                    return ExtractCsrfToken("ls --csrf_token " + sample) == sample
                        && ExtractCsrfToken("ls --csrf_token=\"" + sample + "\"") == sample
                        && ExtractCsrfToken("ls --other_token " + sample) == null
                        && ExtractCsrfToken("ls --csrf_token invalid") == null ? 0 : 3;
                }
                if (args.Length == 3 && args[0] == "antigravity-process")
                    return ReadAntigravityProcess(args[1], args[2]);
                if (args.Length == 1 && args[0] == "self-test")
                {
                    byte[] sample = Encoding.UTF8.GetBytes("minecraft-codex-companion-dpapi-self-test");
                    byte[] protectedValue = ProtectedData.Protect(sample, null, DataProtectionScope.CurrentUser);
                    byte[] plain = ProtectedData.Unprotect(protectedValue, null, DataProtectionScope.CurrentUser);
                    return ConstantTimeEquals(sample, plain) ? 0 : 3;
                }
                if (args.Length != 1 || (args[0] != "protect" && args[0] != "unprotect")) return 2;
                string encoded = Console.In.ReadToEnd().Trim();
                if (encoded.Length == 0 || encoded.Length > MaximumInputCharacters) return 2;
                byte[] input = Convert.FromBase64String(encoded);
                byte[] output = args[0] == "protect"
                    ? ProtectedData.Protect(input, null, DataProtectionScope.CurrentUser)
                    : ProtectedData.Unprotect(input, null, DataProtectionScope.CurrentUser);
                Console.Out.Write(Convert.ToBase64String(output));
                return 0;
            }
            catch (Exception error)
            {
                Console.Error.Write(error.GetType().Name + ": " + error.Message);
                return 1;
            }
        }

        private static string ExtractCsrfToken(string commandLine)
        {
            Match match = Regex.Match(commandLine ?? "", "(?:^|\\s)--csrf_token(?:=|\\s+)\"?([0-9a-f-]{16,})\"?(?=\\s|$)", RegexOptions.IgnoreCase);
            return match.Success ? match.Groups[1].Value : null;
        }

        private static int ReadAntigravityProcess(string pidText, string expectedExecutable)
        {
            int pid;
            if (!Int32.TryParse(pidText, NumberStyles.None, CultureInfo.InvariantCulture, out pid) || pid <= 0
                || !Path.IsPathRooted(expectedExecutable)
                || !String.Equals(Path.GetFileName(expectedExecutable), "language_server.exe", StringComparison.OrdinalIgnoreCase)) return 2;
            Console.OutputEncoding = new UTF8Encoding(false);
            string expectedPath = Path.GetFullPath(expectedExecutable);
            using (var searcher = new ManagementObjectSearcher(
                "SELECT ProcessId,ExecutablePath,CommandLine FROM Win32_Process WHERE Name = 'language_server.exe' AND ProcessId = "
                + pid.ToString(CultureInfo.InvariantCulture)))
            using (var results = searcher.Get())
            {
                foreach (ManagementObject process in results)
                using (process)
                {
                    string actualPath = Convert.ToString(process["ExecutablePath"]);
                    if (String.IsNullOrEmpty(actualPath)
                        || !String.Equals(Path.GetFullPath(actualPath), expectedPath, StringComparison.OrdinalIgnoreCase)) continue;
                    string token = ExtractCsrfToken(Convert.ToString(process["CommandLine"]));
                    if (token == null) continue;
                    // Only return the required local token; unrelated arguments never leave this process.
                    Console.Out.Write(new JavaScriptSerializer().Serialize(new {
                        ProcessId = pid, ExecutablePath = actualPath, CsrfToken = token
                    }));
                    return 0;
                }
            }
            Console.Out.Write("null");
            return 0;
        }

        private static bool ConstantTimeEquals(byte[] left, byte[] right)
        {
            if (left.Length != right.Length) return false;
            int difference = 0;
            for (int index = 0; index < left.Length; index++) difference |= left[index] ^ right[index];
            return difference == 0;
        }
    }
}
