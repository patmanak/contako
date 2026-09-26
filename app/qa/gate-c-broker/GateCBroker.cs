using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace Contako.Qa.GateC
{
    public sealed class BrokerResult
    {
        public bool AckPassed { get; internal set; }
        public bool SecondConnectionRejected { get; internal set; }
        public bool UsernameBufferZeroed { get; internal set; }
        public bool PasswordBufferZeroed { get; internal set; }
        public bool UnmanagedPasswordReleased { get; internal set; }
        public string FailureCategory { get; internal set; }
    }

    public sealed class ScanResult
    {
        public int ExitCode { get; internal set; }
        public int SyntheticCanaryMatches { get; internal set; }
    }

    public static class GateCBroker
    {
        private static readonly byte[] Magic = { 0x43, 0x54, 0x4b, 0x47, 0x43, 0x30, 0x31, 0x00 };
        private static readonly byte[] PassAck = { 0x43, 0x54, 0x4b, 0x5a, 0x50, 0x41, 0x53, 0x53 };
        private const int ProtocolVersion = 1;

        private sealed class BrokerProtocolException : IOException
        {
            public BrokerProtocolException() : base("BROKER_PROTOCOL") { }
        }

        public static bool RunFailureClassificationContract()
        {
            return ClassifyFailure(new EndOfStreamException()) == "EOF" &&
                ClassifyFailure(new SocketException((int)SocketError.TimedOut)) == "TIMEOUT" &&
                ClassifyFailure(new BrokerProtocolException()) == "PROTOCOL" &&
                ClassifyFailure(new IOException()) == "IO" &&
                ClassifyFailure(
                    new IOException("REDACTED", new SocketException((int)SocketError.ConnectionRefused))
                ) == "CONNECTION";
        }

        public static BrokerResult SendSynthetic(int port, string runId)
        {
            byte[] username = DeriveSynthetic(runId, false);
            byte[] password = DeriveSynthetic(runId, true);
            BrokerResult result = NewBrokerResult();
            try
            {
                try
                {
                    Send(port, runId, username, password, result);
                }
                catch (Exception error)
                {
                    result.FailureCategory = ClassifyFailure(error);
                }
                return result;
            }
            finally
            {
                Clear(username);
                Clear(password);
                result.UsernameBufferZeroed = IsZero(username);
                result.PasswordBufferZeroed = IsZero(password);
            }
        }

        public static BrokerResult SendSyntheticSecureString(int port, string runId)
        {
            byte[] usernameBytes = DeriveSynthetic(runId, false);
            byte[] passwordBytes = DeriveSynthetic(runId, true);
            SecureString password = new SecureString();
            string username = null;
            try
            {
                username = Encoding.UTF8.GetString(usernameBytes);
                for (int index = 0; index < passwordBytes.Length; index++)
                {
                    password.AppendChar((char)passwordBytes[index]);
                }
                password.MakeReadOnly();
                Clear(usernameBytes);
                Clear(passwordBytes);
                return SendVault(port, runId, username, password);
            }
            finally
            {
                Clear(usernameBytes);
                Clear(passwordBytes);
                password.Dispose();
                username = null;
            }
        }

        public static BrokerResult SendVault(
            int port,
            string runId,
            string username,
            SecureString password)
        {
            return SendSecureCredential(port, runId, username, password, 20000);
        }

        public static BrokerResult SendVaultLive(
            int port,
            string runId,
            string username,
            SecureString password)
        {
            return SendSecureCredential(port, runId, username, password, 180000);
        }

        private static BrokerResult SendSecureCredential(
            int port,
            string runId,
            string username,
            SecureString password,
            int receiveTimeoutMillis)
        {
            if (username == null) throw new ArgumentNullException("username");
            if (password == null) throw new ArgumentNullException("password");

            IntPtr unmanaged = IntPtr.Zero;
            char[] passwordChars = null;
            byte[] usernameBytes = null;
            byte[] passwordBytes = null;
            BrokerResult result = NewBrokerResult();
            try
            {
                unmanaged = Marshal.SecureStringToGlobalAllocUnicode(password);
                passwordChars = new char[password.Length];
                Marshal.Copy(unmanaged, passwordChars, 0, passwordChars.Length);
                usernameBytes = Encoding.UTF8.GetBytes(username);
                passwordBytes = Encoding.UTF8.GetBytes(passwordChars);
                Clear(passwordChars);
                Marshal.ZeroFreeGlobalAllocUnicode(unmanaged);
                unmanaged = IntPtr.Zero;
                result.UnmanagedPasswordReleased = true;
                try
                {
                    Send(port, runId, usernameBytes, passwordBytes, result, receiveTimeoutMillis);
                }
                catch (Exception error)
                {
                    result.FailureCategory = ClassifyFailure(error);
                }
                return result;
            }
            finally
            {
                Clear(passwordChars);
                Clear(usernameBytes);
                Clear(passwordBytes);
                result.UsernameBufferZeroed = IsZero(usernameBytes);
                result.PasswordBufferZeroed = IsZero(passwordBytes) && IsZero(passwordChars);
                if (unmanaged != IntPtr.Zero)
                {
                    Marshal.ZeroFreeGlobalAllocUnicode(unmanaged);
                    result.UnmanagedPasswordReleased = true;
                }
            }
        }

        public static ScanResult ScanProcessForSyntheticCanary(
            string executable,
            string[] arguments,
            string runId)
        {
            byte[] output = null;
            byte[] username = DeriveSynthetic(runId, false);
            byte[] password = DeriveSynthetic(runId, true);
            try
            {
                ProcessStartInfo start = new ProcessStartInfo();
                start.FileName = executable;
                start.Arguments = JoinArguments(arguments);
                start.UseShellExecute = false;
                start.CreateNoWindow = true;
                start.RedirectStandardOutput = true;
                start.RedirectStandardError = true;
                using (Process process = new Process())
                {
                    process.StartInfo = start;
                    if (!process.Start()) throw new InvalidOperationException("SCAN_START");
                    using (MemoryStream memory = new MemoryStream())
                    {
                        process.StandardOutput.BaseStream.CopyTo(memory);
                        process.StandardError.ReadToEnd();
                        if (!process.WaitForExit(20000))
                        {
                            try { process.Kill(); } catch { }
                            throw new TimeoutException("SCAN_TIMEOUT");
                        }
                        output = memory.ToArray();
                    }
                    return new ScanResult
                    {
                        ExitCode = process.ExitCode,
                        SyntheticCanaryMatches = CountOccurrences(output, username) + CountOccurrences(output, password),
                    };
                }
            }
            finally
            {
                Clear(output);
                Clear(username);
                Clear(password);
            }
        }

        public static int CountSyntheticCanariesInText(string text, string runId)
        {
            byte[] output = null;
            byte[] username = DeriveSynthetic(runId, false);
            byte[] password = DeriveSynthetic(runId, true);
            try
            {
                output = Encoding.UTF8.GetBytes(text ?? string.Empty);
                return CountOccurrences(output, username) + CountOccurrences(output, password);
            }
            finally
            {
                Clear(output);
                Clear(username);
                Clear(password);
            }
        }

        private static void Send(
            int port,
            string runId,
            byte[] username,
            byte[] password,
            BrokerResult result,
            int receiveTimeoutMillis = 20000)
        {
            if (port < 1 || port > 65535) throw new ArgumentOutOfRangeException("port");
            if (string.IsNullOrEmpty(runId)) throw new ArgumentException("RUN_ID");
            byte[] runIdBytes = Encoding.UTF8.GetBytes(runId);
            try
            {
                using (TcpClient client = ConnectWithRetry(port))
                {
                    client.NoDelay = true;
                    client.ReceiveTimeout = receiveTimeoutMillis;
                    client.SendTimeout = 5000;
                    using (NetworkStream stream = client.GetStream())
                    {
                        try
                        {
                            WriteFrame(stream, runIdBytes, username, password);
                        }
                        finally
                        {
                            // The host no longer needs clear credential bytes once the frame has
                            // been flushed. Do not retain them while waiting for the device ACK.
                            Clear(username);
                            Clear(password);
                            result.UsernameBufferZeroed = IsZero(username);
                            result.PasswordBufferZeroed = IsZero(password);
                        }
                        result.SecondConnectionRejected = ProbeSecondConnectionRejected(port);
                        byte[] ack = new byte[PassAck.Length];
                        try
                        {
                            ReadExactly(stream, ack);
                            if (!EqualBytes(ack, PassAck)) throw new BrokerProtocolException();
                            result.AckPassed = true;
                        }
                        finally
                        {
                            Clear(ack);
                        }
                    }
                }
            }
            finally
            {
                Clear(runIdBytes);
            }
        }

        private static void WriteFrame(Stream stream, byte[] runId, byte[] username, byte[] password)
        {
            byte[] scratch = new byte[8];
            try
            {
                stream.Write(Magic, 0, Magic.Length);
                WriteInt32(stream, ProtocolVersion, scratch);
                WriteInt32(stream, runId.Length, scratch);
                stream.Write(runId, 0, runId.Length);
                WriteInt64(stream, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + 15000L, scratch);
                WriteInt32(stream, username.Length, scratch);
                WriteInt32(stream, password.Length, scratch);
                stream.Write(username, 0, username.Length);
                stream.Write(password, 0, password.Length);
                stream.Flush();
            }
            finally
            {
                Clear(scratch);
            }
        }

        private static TcpClient ConnectWithRetry(int port)
        {
            Exception last = null;
            for (int attempt = 0; attempt < 100; attempt++)
            {
                TcpClient client = new TcpClient(AddressFamily.InterNetwork);
                try
                {
                    client.Connect(IPAddress.Loopback, port);
                    return client;
                }
                catch (Exception error)
                {
                    last = error;
                    client.Close();
                    Thread.Sleep(50);
                }
            }
            throw new IOException("CONNECT_FAILED", last);
        }

        private static bool ProbeSecondConnectionRejected(int port)
        {
            try
            {
                using (TcpClient second = new TcpClient(AddressFamily.InterNetwork))
                {
                    second.ReceiveTimeout = 1500;
                    second.SendTimeout = 1500;
                    second.Connect(IPAddress.Loopback, port);
                    using (NetworkStream stream = second.GetStream())
                    {
                        stream.WriteByte(0x00);
                        stream.Flush();
                        int value = stream.ReadByte();
                        return value < 0;
                    }
                }
            }
            catch
            {
                return true;
            }
        }

        private static byte[] DeriveSynthetic(string runId, bool password)
        {
            byte[] source = Encoding.UTF8.GetBytes((password ? "password:" : "username:") + runId);
            byte[] hash = null;
            try
            {
                using (SHA512 sha = SHA512.Create()) hash = sha.ComputeHash(source);
                int size = password ? 48 : 32;
                byte[] result = new byte[size];
                for (int index = 0; index < result.Length; index++)
                {
                    int value = hash[index % hash.Length];
                    result[index] = (byte)(password ? 0x21 + (value % 90) : 0x61 + (value % 26));
                }
                return result;
            }
            finally
            {
                Clear(source);
                Clear(hash);
            }
        }

        private static void WriteInt32(Stream stream, int value, byte[] scratch)
        {
            scratch[0] = (byte)((value >> 24) & 0xff);
            scratch[1] = (byte)((value >> 16) & 0xff);
            scratch[2] = (byte)((value >> 8) & 0xff);
            scratch[3] = (byte)(value & 0xff);
            stream.Write(scratch, 0, 4);
            Array.Clear(scratch, 0, 4);
        }

        private static void WriteInt64(Stream stream, long value, byte[] scratch)
        {
            for (int index = 7; index >= 0; index--)
            {
                scratch[7 - index] = (byte)((value >> (index * 8)) & 0xff);
            }
            stream.Write(scratch, 0, 8);
            Array.Clear(scratch, 0, 8);
        }

        private static void ReadExactly(Stream stream, byte[] destination)
        {
            int offset = 0;
            while (offset < destination.Length)
            {
                int read = stream.Read(destination, offset, destination.Length - offset);
                if (read <= 0) throw new EndOfStreamException("ACK_TRUNCATED");
                offset += read;
            }
        }

        private static BrokerResult NewBrokerResult()
        {
            return new BrokerResult { FailureCategory = "NONE" };
        }

        private static string ClassifyFailure(Exception error)
        {
            for (Exception current = error; current != null; current = current.InnerException)
            {
                if (current is BrokerProtocolException) return "PROTOCOL";
                if (current is EndOfStreamException) return "EOF";
                SocketException socket = current as SocketException;
                if (socket != null)
                {
                    if (socket.SocketErrorCode == SocketError.TimedOut) return "TIMEOUT";
                    if (socket.SocketErrorCode == SocketError.ConnectionRefused ||
                        socket.SocketErrorCode == SocketError.ConnectionReset ||
                        socket.SocketErrorCode == SocketError.NotConnected ||
                        socket.SocketErrorCode == SocketError.HostUnreachable ||
                        socket.SocketErrorCode == SocketError.NetworkUnreachable)
                    {
                        return "CONNECTION";
                    }
                }
            }
            return error is IOException ? "IO" : "UNKNOWN";
        }

        private static int CountOccurrences(byte[] haystack, byte[] needle)
        {
            if (haystack == null || needle == null || needle.Length == 0 || haystack.Length < needle.Length) return 0;
            int matches = 0;
            for (int start = 0; start <= haystack.Length - needle.Length; start++)
            {
                bool equal = true;
                for (int index = 0; index < needle.Length; index++)
                {
                    if (haystack[start + index] != needle[index])
                    {
                        equal = false;
                        break;
                    }
                }
                if (equal) matches++;
            }
            return matches;
        }

        private static bool EqualBytes(byte[] left, byte[] right)
        {
            if (left == null || right == null || left.Length != right.Length) return false;
            int difference = 0;
            for (int index = 0; index < left.Length; index++) difference |= left[index] ^ right[index];
            return difference == 0;
        }

        private static string JoinArguments(string[] arguments)
        {
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < arguments.Length; index++)
            {
                if (index > 0) builder.Append(' ');
                builder.Append(QuoteArgument(arguments[index]));
            }
            return builder.ToString();
        }

        private static string QuoteArgument(string value)
        {
            if (value == null) return "\"\"";
            return "\"" + value.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";
        }

        private static void Clear(byte[] value)
        {
            if (value != null) Array.Clear(value, 0, value.Length);
        }

        private static void Clear(char[] value)
        {
            if (value != null) Array.Clear(value, 0, value.Length);
        }

        private static bool IsZero(byte[] value)
        {
            if (value == null) return true;
            for (int index = 0; index < value.Length; index++) if (value[index] != 0) return false;
            return true;
        }

        private static bool IsZero(char[] value)
        {
            if (value == null) return true;
            for (int index = 0; index < value.Length; index++) if (value[index] != '\0') return false;
            return true;
        }
    }
}
