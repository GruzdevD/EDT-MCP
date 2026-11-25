/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Read-only, handle-relative Windows source access. Never falls back to a pathname read. */
final class WindowsSourceTree
{
    private static final int OBJ_CASE_INSENSITIVE = 0x40;
    private static final int OBJ_DONT_REPARSE = 0x1000;
    private static final int FILE_READ_DATA = 1; // FILE_LIST_DIRECTORY for directories
    private static final int FILE_TRAVERSE = 0x20;
    private static final int FILE_READ_ATTRIBUTES = 0x80;
    private static final int SYNCHRONIZE = 0x100000;
    private static final int FILE_SHARE_READ = 1;
    private static final int FILE_SHARE_WRITE = 2;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_OPEN = 1;
    private static final int STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034;
    private static final int FILE_DIRECTORY_FILE = 1;
    private static final int FILE_NON_DIRECTORY_FILE = 0x40;
    private static final int FILE_SYNCHRONOUS_IO_NONALERT = 0x20;
    private static final int FILE_OPEN_REPARSE_POINT = 0x200000;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
    private static final int FILE_TYPE_DISK = 1;
    private static final int DRIVE_REMOVABLE = 2;
    private static final int DRIVE_FIXED = 3;
    private static final int FILE_ID_BOTH_DIRECTORY_INFO = 10;
    private static final int FILE_ID_BOTH_DIRECTORY_RESTART_INFO = 11;
    private static final int ERROR_NO_MORE_FILES = 18;

    private final Kernel32 kernel;
    private final NtDll nt;

    private WindowsSourceTree(Kernel32 kernel, NtDll nt)
    {
        this.kernel = kernel;
        this.nt = nt;
    }

    static WindowsSourceTree load() throws IOException
    {
        if (!System.getProperty("os.name", "").startsWith("Windows")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            throw unavailable();
        }
        try
        {
            Kernel32 kernel = Native.load("kernel32", Kernel32.class); //$NON-NLS-1$
            NtDll nt = Native.load("ntdll", NtDll.class); //$NON-NLS-1$
            // Resolve the exports up front so a missing native entry point fails closed at admission.
            com.sun.jna.NativeLibrary.getInstance("ntdll").getFunction("NtCreateFile"); //$NON-NLS-1$ //$NON-NLS-2$
            return new WindowsSourceTree(kernel, nt);
        }
        catch (LinkageError | RuntimeException e)
        {
            throw unavailable();
        }
    }

    static IOException unavailable()
    {
        // Never include a path, the native error text, or a cause containing that text.
        return new IOException("Cannot safely open a local Windows snapshot source."); //$NON-NLS-1$
    }

    /** Drive letters only. UNC, drive-relative and Windows device namespace paths are not roots. */
    static boolean isDrivePath(String path)
    {
        if (path == null || path.length() < 3 || path.charAt(1) != ':' || path.charAt(2) != '\\')
        {
            return false;
        }
        char drive = path.charAt(0);
        return drive >= 'A' && drive <= 'Z' || drive >= 'a' && drive <= 'z';
    }

    /** Just one real component: never a namespace prefix, stream, separator, or relative hop. */
    static boolean isComponent(String name)
    {
        return name != null && !name.isEmpty() && name.length() <= 255
            && !name.endsWith(".") && !name.endsWith(" ") //$NON-NLS-1$ //$NON-NLS-2$
            && name.chars().noneMatch(c -> c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0); //$NON-NLS-1$
    }

    Directory openProject(String path) throws IOException
    {
        return openDirectory(path, false);
    }

    Directory openDirectoryIfPresent(String path) throws IOException
    {
        return openDirectory(path, true);
    }

    private Directory openDirectory(String path, boolean allowMissing) throws IOException
    {
        if (!isDrivePath(path) || path.length() <= 3)
        {
            throw unavailable();
        }
        String drive = path.substring(0, 3);
        int type = kernel.GetDriveTypeW(new WString(drive));
        char[] volume = new char[64];
        // A volume GUID is tied to a locally mounted volume, not an SMB mapping or a SUBST alias.
        if ((type != DRIVE_FIXED && type != DRIVE_REMOVABLE)
            || !kernel.GetVolumeNameForVolumeMountPointW(new WString(drive), volume, volume.length))
        {
            throw unavailable();
        }
        String volumeRoot = Native.toString(volume);
        if (!volumeRoot.startsWith("\\\\?\\Volume{") || !volumeRoot.endsWith("}\\") || volumeRoot.length() != 49 //$NON-NLS-1$ //$NON-NLS-2$
            || !volumeRoot.substring(11, 47).matches("[a-fA-F0-9-]{36}")) //$NON-NLS-1$
        {
            throw unavailable();
        }
        Pointer handle = kernel.CreateFileW(new WString(volumeRoot), FILE_READ_DATA | FILE_TRAVERSE
            | FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE, null, OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, null);
        Directory directory = new Directory(check(handle, true, null));
        boolean transferred = false;
        try
        {
            for (String name : path.substring(3).split("\\\\", -1)) //$NON-NLS-1$
            {
                Directory child = directory.child(name, allowMissing);
                if (child == null)
                {
                    return null;
                }
                child.parentToClose = directory;
                directory = child;
            }
            transferred = true;
            return directory;
        }
        catch (LinkageError | RuntimeException e)
        {
            throw unavailable();
        }
        finally
        {
            if (!transferred)
            {
                directory.close();
            }
        }
    }

    record Entry(String name, boolean directory, boolean reparsePoint) { }

    interface Handle extends AutoCloseable
    {
        @Override
        void close();
    }

    interface PinnedFile extends Handle
    {
        byte[] read(int maxBytes) throws IOException;
    }

    final class Directory implements Handle
    {
        private Opened opened;
        private Directory parentToClose;

        private Directory(Opened opened)
        {
            this.opened = opened;
        }

        Directory child(String name) throws IOException
        {
            return child(name, false);
        }

        Directory childIfPresent(String name) throws IOException
        {
            return child(name, true);
        }

        Directory gitWriteDirectory() throws IOException
        {
            // Atomic replacement of a child index requires write sharing on the parent directory.
            return new Directory(openChild(opened, ".git", true, false, FILE_SHARE_READ | FILE_SHARE_WRITE));
        }

        private Directory child(String name, boolean allowMissing) throws IOException
        {
            Opened child = openChild(opened, name, true, allowMissing);
            return child == null ? null : new Directory(child);
        }

        List<Entry> entries(int limit) throws IOException
        {
            List<Entry> entries = new ArrayList<>();
            try (Memory buffer = new Memory(65536))
            {
                int query = FILE_ID_BOTH_DIRECTORY_RESTART_INFO;
                while (true)
                {
                    if (!kernel.GetFileInformationByHandleEx(opened.handle, query, buffer, (int)buffer.size()))
                    {
                        if (Native.getLastError() == ERROR_NO_MORE_FILES)
                        {
                            return entries;
                        }
                        throw unavailable();
                    }
                    query = FILE_ID_BOTH_DIRECTORY_INFO;
                    // FILE_ID_BOTH_DIR_INFO.FileName starts at byte 104 (8-byte Win32 struct alignment).
                    long offset = 0;
                    long next;
                    do
                    {
                        next = Integer.toUnsignedLong(buffer.getInt(offset));
                        int attributes = buffer.getInt(offset + 56);
                        int size = buffer.getInt(offset + 60);
                        long end = next == 0 ? buffer.size() : offset + next;
                        if (size < 2 || size > 510 || (size & 1) != 0 || end > buffer.size()
                            || offset + 104 + size > end)
                        {
                            throw unavailable();
                        }
                        String name = new String(buffer.getByteArray(offset + 104, size), StandardCharsets.UTF_16LE);
                        if (!".".equals(name) && !"..".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                        {
                            if (entries.size() >= limit)
                            {
                                throw new IOException("Snapshot source exceeds the directory entry limit."); //$NON-NLS-1$
                            }
                            entries.add(new Entry(name, (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0,
                                (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0));
                        }
                        offset += next;
                    } while (next != 0 && offset + 104 <= buffer.size());
                    if (next != 0)
                    {
                        throw unavailable();
                    }
                }
            }
            catch (LinkageError | RuntimeException e)
            {
                throw unavailable();
            }
        }

        byte[] read(String name, int maxBytes) throws IOException
        {
            return read(name, maxBytes, false);
        }

        byte[] readIfPresent(String name, int maxBytes) throws IOException
        {
            return read(name, maxBytes, true);
        }

        PinnedFile pinFileIfPresent(String name) throws IOException
        {
            return openChild(opened, name, false, true);
        }

        private byte[] read(String name, int maxBytes, boolean allowMissing) throws IOException
        {
            try (Opened file = openChild(opened, name, false, allowMissing))
            {
                return file == null ? null : file.read(maxBytes);
            }
            catch (LinkageError | RuntimeException e)
            {
                throw unavailable();
            }
        }

        @Override
        public void close()
        {
            if (opened != null)
            {
                try
                {
                    opened.close();
                }
                finally
                {
                    opened = null;
                    if (parentToClose != null)
                    {
                        parentToClose.close();
                    }
                }
            }
        }
    }

    private Opened openChild(Opened parent, String name, boolean directory, boolean allowMissing) throws IOException
    {
        return openChild(parent, name, directory, allowMissing, FILE_SHARE_READ);
    }

    private Opened openChild(Opened parent, String name, boolean directory, boolean allowMissing, int sharing)
        throws IOException
    {
        if (parent == null || !isComponent(name))
        {
            throw unavailable();
        }
        byte[] bytes = name.getBytes(StandardCharsets.UTF_16LE);
        try (Memory chars = new Memory(bytes.length + 2L); Memory status = new Memory(2L * Native.POINTER_SIZE))
        {
            chars.clear();
            chars.write(0, bytes, 0, bytes.length);
            UnicodeString unicode = new UnicodeString();
            unicode.length = (short)bytes.length;
            unicode.maximumLength = (short)(bytes.length + 2);
            unicode.buffer = chars;
            unicode.write();
            ObjectAttributes attributes = new ObjectAttributes();
            attributes.length = attributes.size();
            attributes.root = parent.handle;
            attributes.name = unicode.getPointer();
            attributes.attributes = OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE;
            PointerByReference result = new PointerByReference();
            int access = FILE_READ_ATTRIBUTES | FILE_READ_DATA | SYNCHRONIZE | (directory ? FILE_TRAVERSE : 0);
            int options = FILE_OPEN_REPARSE_POINT | FILE_SYNCHRONOUS_IO_NONALERT
                | (directory ? FILE_DIRECTORY_FILE : FILE_NON_DIRECTORY_FILE);
            int openStatus = nt.NtCreateFile(result, access, attributes, status, null, 0, sharing, FILE_OPEN,
                options, null, 0);
            if (allowMissing && openStatus == STATUS_OBJECT_NAME_NOT_FOUND)
            {
                return null;
            }
            if (openStatus != 0)
            {
                throw unavailable();
            }
            return check(result.getValue(), directory, parent.volume);
        }
        catch (LinkageError | RuntimeException e)
        {
            throw unavailable();
        }
    }

    private Opened check(Pointer handle, boolean directory, Integer expectedVolume) throws IOException
    {
        if (handle == null || Pointer.nativeValue(handle) == -1)
        {
            throw unavailable();
        }
        boolean accepted = false;
        try (Memory info = new Memory(52)) // BY_HANDLE_FILE_INFORMATION, all fields DWORD-aligned
        {
            if (kernel.GetFileType(handle) != FILE_TYPE_DISK || !kernel.GetFileInformationByHandle(handle, info))
            {
                throw unavailable();
            }
            int attributes = info.getInt(0);
            int volume = info.getInt(28);
            long size = ((long)info.getInt(32) << 32) | Integer.toUnsignedLong(info.getInt(36));
            if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0
                || ((attributes & FILE_ATTRIBUTE_DIRECTORY) != 0) != directory
                || (expectedVolume != null && volume != expectedVolume)
                || (!directory && (info.getInt(40) != 1 || size < 0)))
            {
                throw unavailable();
            }
            Opened result = new Opened(handle, volume, size);
            accepted = true;
            return result;
        }
        finally
        {
            if (!accepted)
            {
                kernel.CloseHandle(handle);
            }
        }
    }

    private final class Opened implements PinnedFile
    {
        private final Pointer handle;
        private final int volume;
        private final long size;

        Opened(Pointer handle, int volume, long size)
        {
            this.handle = Objects.requireNonNull(handle);
            this.volume = volume;
            this.size = size;
        }

        @Override
        public byte[] read(int maxBytes) throws IOException
        {
            if (maxBytes < 0 || size > maxBytes)
            {
                throw new IOException("Snapshot source exceeds the file size limit."); //$NON-NLS-1$
            }
            try
            {
                byte[] buffer = new byte[8192];
                IntByReference count = new IntByReference();
                ByteArrayOutputStream content = new ByteArrayOutputStream((int)size);
                while (content.size() < size)
                {
                    int remaining = Math.min(buffer.length, (int)size - content.size());
                    if (!kernel.ReadFile(handle, buffer, remaining, count, null)
                        || count.getValue() <= 0 || count.getValue() > remaining)
                    {
                        throw unavailable();
                    }
                    content.write(buffer, 0, count.getValue());
                }
                return content.toByteArray();
            }
            catch (LinkageError | RuntimeException e)
            {
                throw unavailable();
            }
        }

        @Override
        public void close()
        {
            kernel.CloseHandle(handle);
        }
    }

    @Structure.FieldOrder({"length", "maximumLength", "buffer"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    public static final class UnicodeString extends Structure
    {
        public short length;
        public short maximumLength;
        public Pointer buffer;
    }

    @Structure.FieldOrder({"length", "root", "name", "attributes", "security", "quality"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    public static final class ObjectAttributes extends Structure
    {
        public int length;
        public Pointer root;
        public Pointer name;
        public int attributes;
        public Pointer security;
        public Pointer quality;
    }

    interface NtDll extends StdCallLibrary
    {
        int NtCreateFile(PointerByReference handle, int access, ObjectAttributes attributes, Pointer status,
            Pointer allocationSize, int fileAttributes, int share, int disposition, int options, Pointer ea,
            int eaLength);
    }

    interface Kernel32 extends StdCallLibrary
    {
        int GetDriveTypeW(WString root);
        boolean GetVolumeNameForVolumeMountPointW(WString root, char[] volume, int length);
        Pointer CreateFileW(WString name, int access, int share, Pointer security, int disposition, int flags,
            Pointer template);
        int GetFileType(Pointer handle);
        boolean GetFileInformationByHandle(Pointer handle, Pointer information);
        boolean GetFileInformationByHandleEx(Pointer handle, int informationClass, Pointer information, int size);
        boolean ReadFile(Pointer handle, byte[] bytes, int size, IntByReference read, Pointer overlapped);
        boolean CloseHandle(Pointer handle);
    }
}
