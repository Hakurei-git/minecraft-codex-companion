using System;
using System.Security.Cryptography;
using System.Text;

namespace MinecraftCodexCompanion
{
    // Narrow DPAPI helper used instead of generating or running PowerShell code.
    // Input and output are base64 on standard streams; it never reads files or the network.
    internal static class SecretHelper
    {
        private const int MaximumInputCharacters = 1024 * 1024;

        private static int Main(string[] args)
        {
            try
            {
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

        private static bool ConstantTimeEquals(byte[] left, byte[] right)
        {
            if (left.Length != right.Length) return false;
            int difference = 0;
            for (int index = 0; index < left.Length; index++) difference |= left[index] ^ right[index];
            return difference == 0;
        }
    }
}
