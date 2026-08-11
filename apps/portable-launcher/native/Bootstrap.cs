using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace MinecraftCodexCompanion
{
    // A deliberately small, conventional bootstrapper. It does not unpack files,
    // download code, invoke a command shell, or modify another executable.
    internal static class Bootstrap
    {
        [STAThread]
        private static int Main(string[] args)
        {
            try
            {
                string root = AppDomain.CurrentDomain.BaseDirectory;
                string node = Path.Combine(root, "runtime", "node.exe");
                string launcher = Path.Combine(root, "apps", "portable-launcher", "src", "launcher.cjs");
                RequireFile(node, "packaged Node.js runtime");
                RequireFile(launcher, "launcher source");

                List<string> childArguments = new List<string>();
                childArguments.Add(launcher);
                childArguments.AddRange(args);

                ProcessStartInfo start = new ProcessStartInfo();
                start.FileName = node;
                start.Arguments = JoinArguments(childArguments);
                start.WorkingDirectory = root;
                start.UseShellExecute = false;
                start.CreateNoWindow = true;
                start.RedirectStandardOutput = true;
                start.RedirectStandardError = true;

                using (Process child = Process.Start(start))
                {
                    if (child == null) throw new InvalidOperationException("Unable to start the local companion runtime.");
                    Task<string> stdout = child.StandardOutput.ReadToEndAsync();
                    Task<string> stderr = child.StandardError.ReadToEndAsync();
                    child.WaitForExit();
                    Task.WaitAll(stdout, stderr);
                    if (child.ExitCode != 0)
                    {
                        string detail = stderr.Result.Trim();
                        if (detail.Length == 0) detail = stdout.Result.Trim();
                        throw new InvalidOperationException(detail.Length == 0
                            ? "The companion runtime exited with code " + child.ExitCode + "."
                            : detail);
                    }
                    return 0;
                }
            }
            catch (Exception error)
            {
                MessageBox.Show(error.Message, "Minecraft Codex Companion", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }

        private static void RequireFile(string file, string label)
        {
            if (!File.Exists(file)) throw new FileNotFoundException("The portable package is incomplete: " + label + " is missing.", file);
        }

        private static string JoinArguments(IEnumerable<string> values)
        {
            StringBuilder commandLine = new StringBuilder();
            foreach (string value in values)
            {
                if (commandLine.Length > 0) commandLine.Append(' ');
                commandLine.Append(QuoteArgument(value ?? String.Empty));
            }
            return commandLine.ToString();
        }

        // Implements the Windows CommandLineToArgvW quoting rules without a shell.
        private static string QuoteArgument(string value)
        {
            if (value.Length > 0 && value.IndexOfAny(new[] { ' ', '\t', '\n', '\v', '"' }) < 0) return value;
            StringBuilder result = new StringBuilder("\"");
            int backslashes = 0;
            foreach (char character in value)
            {
                if (character == '\\')
                {
                    backslashes++;
                    continue;
                }
                if (character == '"')
                {
                    result.Append('\\', backslashes * 2 + 1);
                    result.Append('"');
                    backslashes = 0;
                    continue;
                }
                result.Append('\\', backslashes);
                backslashes = 0;
                result.Append(character);
            }
            result.Append('\\', backslashes * 2);
            result.Append('"');
            return result.ToString();
        }
    }
}
