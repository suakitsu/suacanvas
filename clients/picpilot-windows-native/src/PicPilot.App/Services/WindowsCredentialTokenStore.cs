using PicPilot.Core;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace PicPilot.App.Services;

public sealed class WindowsCredentialTokenStore : ITokenStore
{
    private const string DefaultResource = "PicPilot.Windows.UserSession";
    private const string UserName = "current-user";
    private const uint CredentialTypeGeneric = 1;
    private const uint CredentialPersistLocalMachine = 2;
    private const int ErrorNotFound = 1168;
    private readonly object _sync = new();
    private readonly string _resource;

    public WindowsCredentialTokenStore(string? resource = null)
    {
        _resource = string.IsNullOrWhiteSpace(resource) ? DefaultResource : resource.Trim();
    }

    public Task<string> ReadAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_sync)
        {
            if (!CredRead(_resource, CredentialTypeGeneric, 0, out var pointer))
            {
                var error = Marshal.GetLastWin32Error();
                if (error == ErrorNotFound) return Task.FromResult("");
                throw new Win32Exception(error, "Windows 凭据库读取失败");
            }

            try
            {
                var credential = Marshal.PtrToStructure<NativeCredential>(pointer);
                if (credential.CredentialBlob == IntPtr.Zero || credential.CredentialBlobSize == 0) return Task.FromResult("");
                var bytes = new byte[credential.CredentialBlobSize];
                Marshal.Copy(credential.CredentialBlob, bytes, 0, bytes.Length);
                return Task.FromResult(Encoding.UTF8.GetString(bytes));
            }
            finally
            {
                CredFree(pointer);
            }
        }
    }

    public Task SaveAsync(string token, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_sync)
        {
            if (string.IsNullOrWhiteSpace(token))
            {
                DeleteCore();
                return Task.CompletedTask;
            }

            var bytes = Encoding.UTF8.GetBytes(token);
            var blob = Marshal.AllocCoTaskMem(bytes.Length);
            try
            {
                Marshal.Copy(bytes, 0, blob, bytes.Length);
                var credential = new NativeCredential
                {
                    Type = CredentialTypeGeneric,
                    TargetName = _resource,
                    CredentialBlobSize = (uint)bytes.Length,
                    CredentialBlob = blob,
                    Persist = CredentialPersistLocalMachine,
                    UserName = UserName
                };
                if (!CredWrite(ref credential, 0))
                {
                    var error = Marshal.GetLastWin32Error();
                    throw new Win32Exception(error, "Windows 凭据库写入失败");
                }
            }
            finally
            {
                CryptographicOperations.ZeroMemory(bytes);
                Marshal.FreeCoTaskMem(blob);
            }
        }
        return Task.CompletedTask;
    }

    public Task ClearAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_sync) DeleteCore();
        return Task.CompletedTask;
    }

    private void DeleteCore()
    {
        if (CredDelete(_resource, CredentialTypeGeneric, 0)) return;
        var error = Marshal.GetLastWin32Error();
        if (error != ErrorNotFound) throw new Win32Exception(error, "Windows 凭据库清理失败");
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NativeCredential
    {
        public uint Flags;
        public uint Type;
        [MarshalAs(UnmanagedType.LPWStr)] public string TargetName;
        [MarshalAs(UnmanagedType.LPWStr)] public string? Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist;
        public uint AttributeCount;
        public IntPtr Attributes;
        [MarshalAs(UnmanagedType.LPWStr)] public string? TargetAlias;
        [MarshalAs(UnmanagedType.LPWStr)] public string UserName;
    }

    [DllImport("Advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredWrite([In] ref NativeCredential userCredential, uint flags);

    [DllImport("Advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredRead(string target, uint type, uint reservedFlag, out IntPtr credentialPtr);

    [DllImport("Advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredDelete(string target, uint type, uint flags);

    [DllImport("Advapi32.dll", SetLastError = false)]
    private static extern void CredFree(IntPtr credential);
}
