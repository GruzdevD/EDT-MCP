/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotRegistry.Retained;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;

/** Renders immutable, cursor-bound manifest and diff pages from retained snapshots. */
final class ProjectSnapshotPages
{
    enum View { MANIFEST, DIFF }

    static final int MAX_PAGE_SIZE = 20;
    private static final int MAX_DIFF_INPUT = 128 * 1024;
    private static final int MAX_PATCH_BYTES = 16 * 1024;
    private static final int MAX_DIFF_LINES = 4000;
    private static final int MAX_PAGE_CHARS = 72_000;
    private static final int MAC_BYTES = 32;
    private static final int CURSOR_BYTES = Integer.BYTES + MAC_BYTES;
    private final byte[] key = new byte[MAC_BYTES];

    ProjectSnapshotPages()
    {
        new SecureRandom().nextBytes(key);
    }

    record Content(String contentId, int bytes) { }

    record Delta(String kind, String change, String patch, boolean complete, String reason) { }

    record Row(UUID entryId, String projectName, String path, Content head, Content index, Content disk,
        Content compareFrom, boolean staged, boolean unstaged, boolean rawDiskComparison, List<Delta> diffs) { }

    record Page(int total, List<Row> entries, String nextCursor) { }

    private record Key(String project, String path) { }

    private record Source(Entry entry, boolean raw) { }

    private record Selection(Key key, Source current, Source previous) { }

    Page read(Retained current, Retained previous, View view, UUID entryId, String cursor, int limit) throws IOException
    {
        if (limit < 1 || limit > MAX_PAGE_SIZE)
        {
            throw new IOException("Snapshot page limit must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        if (previous != null && !readScope(current).equals(readScope(previous)))
        {
            throw new IOException("Compare snapshots captured for the same project scope.");
        }
        List<Selection> selected = select(current, previous);
        if (entryId != null)
        {
            selected.removeIf(row -> !entryId.equals(entryId(current.id(), row.key())));
            if (selected.isEmpty())
            {
                throw new IOException("Snapshot entry is unavailable for this view.");
            }
        }
        String binding = current.id() + "\n" + (previous == null ? "" : previous.id()) + "\n" + view + "\n"
            + (entryId == null ? "" : entryId) + "\n" + limit;
        int start = cursor == null ? 0 : decode(cursor, binding);
        if (start < 0 || start > selected.size() || cursor != null && start == selected.size())
        {
            throw invalidCursor();
        }
        int indicesLimit = Math.min(start + limit, selected.size());
        List<Row> rows = new ArrayList<>(indicesLimit - start);
        int used = 0;
        int end = start;
        while (end < indicesLimit)
        {
            Row next = row(current.id(), selected.get(end), view == View.DIFF, previous != null);
            int size = GsonProvider.toJson(next).length() + 1;
            if (used + size > MAX_PAGE_CHARS && !rows.isEmpty())
            {
                break;
            }
            if (size > MAX_PAGE_CHARS)
            {
                throw new IOException("Snapshot entry exceeds the response size limit.");
            }
            rows.add(next);
            used += size;
            end++;
        }
        return new Page(selected.size(), List.copyOf(rows), end < selected.size() ? encode(end, binding) : null);
    }

    private static Set<SnapshotSourcePolicy.ReadScope> readScope(Retained snapshot)
    {
        return snapshot.scope().stream().map(SnapshotSourcePolicy.Grant::readScope)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static List<Selection> select(Retained current, Retained previous)
    {
        Comparator<Key> order = Comparator.comparing(Key::project).thenComparing(Key::path);
        Map<Key, Source> right = new TreeMap<>(order);
        Map<Key, Source> left = new TreeMap<>(order);
        for (Project project : current.snapshot().projects())
        {
            for (Entry entry : project.entries())
            {
                if (previous != null || entry.staged() || entry.unstaged())
                {
                    right.put(new Key(project.name(), entry.path()), new Source(entry, project.rawDiskComparison()));
                }
            }
        }
        if (previous != null)
        {
            for (Project project : previous.snapshot().projects())
            {
                for (Entry entry : project.entries())
                {
                    Key key = new Key(project.name(), entry.path());
                    left.put(key, new Source(entry, project.rawDiskComparison()));
                    right.putIfAbsent(key, new Source(new Entry(entry.path(), null, null, null), false));
                }
            }
        }
        List<Selection> rows = new ArrayList<>();
        for (Map.Entry<Key, Source> row : right.entrySet())
        {
            Source from = left.get(row.getKey());
            if (previous == null || !same(disk(from), row.getValue().entry().disk()))
            {
                rows.add(new Selection(row.getKey(), row.getValue(), from));
            }
        }
        return rows;
    }

    private static Row row(UUID id, Selection selection, boolean withDiffs, boolean comparing) throws IOException
    {
        Source right = selection.current();
        Source left = selection.previous();
        Entry entry = right.entry();
        boolean raw = right.raw() || left != null && left.raw();
        List<Delta> diffs = new ArrayList<>(2);
        if (withDiffs)
        {
            if (comparing)
            {
                diffs.add(patch("comparison", disk(left), entry.disk(), raw));
            }
            else
            {
                if (entry.staged())
                {
                    diffs.add(patch("staged", entry.head(), entry.index(), false));
                }
                if (entry.unstaged())
                {
                    diffs.add(patch("unstaged", entry.index(), entry.disk(), raw));
                }
            }
        }
        return new Row(entryId(id, selection.key()), selection.key().project(), entry.path(), content(entry.head()),
            content(entry.index()), content(entry.disk()), content(disk(left)), entry.staged(), entry.unstaged(), raw,
            withDiffs ? List.copyOf(diffs) : null);
    }

    private static Version disk(Source source)
    {
        return source == null ? null : source.entry().disk();
    }

    private static Content content(Version version)
    {
        return version == null ? null : new Content(version.contentId(), version.size());
    }

    private static boolean same(Version a, Version b)
    {
        return Objects.equals(a == null ? null : a.contentId(), b == null ? null : b.contentId());
    }

    static Delta patch(String kind, Version before, Version after, boolean raw) throws IOException
    {
        String change = before == null ? "added" : after == null ? "deleted" : "modified";
        if (size(before) + size(after) > MAX_DIFF_INPUT)
        {
            return new Delta(kind, change, null, false, "inputLimit");
        }
        byte[] oldBytes = before == null ? new byte[0] : before.bytes();
        byte[] newBytes = after == null ? new byte[0] : after.bytes();
        if (RawText.isBinary(oldBytes) || RawText.isBinary(newBytes) || !utf8(oldBytes) || !utf8(newBytes))
        {
            return new Delta(kind, change, null, false, "nonUtf8Text");
        }
        RawText a = new RawText(oldBytes);
        RawText b = new RawText(newBytes);
        if (a.size() + b.size() > MAX_DIFF_LINES)
        {
            return new Delta(kind, change, null, false, "lineLimit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(output))
        {
            HistogramDiff algorithm = new HistogramDiff();
            formatter.format(algorithm.diff(RawTextComparator.DEFAULT, a, b), a, b);
            formatter.flush();
        }
        String patch = output.toString(StandardCharsets.UTF_8);
        if (output.size() > MAX_PATCH_BYTES || GsonProvider.toJson(patch).length() > MAX_PATCH_BYTES)
        {
            return new Delta(kind, change, null, false, "outputLimit");
        }
        return new Delta(kind, change, patch, !raw,
            raw ? "diskNormalizationUnconfirmed" : null);
    }

    private static int size(Version version)
    {
        return version == null ? 0 : version.size();
    }

    private static boolean utf8(byte[] bytes)
    {
        try
        {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            return true;
        }
        catch (CharacterCodingException e)
        {
            return false;
        }
    }

    private static UUID entryId(UUID snapshot, Key entry)
    {
        return entryId(snapshot, entry.project(), entry.path());
    }

    static UUID entryId(UUID snapshot, String project, String path)
    {
        return UUID.nameUUIDFromBytes((snapshot + "\n" + project + "\n" + path)
            .getBytes(StandardCharsets.UTF_8));
    }

    String encode(int offset, String binding)
    {
        byte[] result = ByteBuffer.allocate(CURSOR_BYTES).putInt(offset).put(sign(offset, binding)).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
    }

    int decode(String cursor, String binding) throws IOException
    {
        if (cursor.length() != 48)
        {
            throw invalidCursor();
        }
        byte[] token;
        try
        {
            token = Base64.getUrlDecoder().decode(cursor);
        }
        catch (IllegalArgumentException e)
        {
            throw invalidCursor();
        }
        if (token.length != CURSOR_BYTES)
        {
            throw invalidCursor();
        }
        int offset = ByteBuffer.wrap(token).getInt();
        if (!MessageDigest.isEqual(Arrays.copyOfRange(token, Integer.BYTES, token.length), sign(offset, binding)))
        {
            throw invalidCursor();
        }
        return offset;
    }

    private byte[] sign(int offset, String binding)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(offset).array());
            return mac.doFinal(binding.getBytes(StandardCharsets.UTF_8));
        }
        catch (GeneralSecurityException e)
        {
            throw new IllegalStateException("Snapshot cursor signing is unavailable.", e);
        }
    }

    private static IOException invalidCursor()
    {
        return new IOException("Snapshot cursor is invalid for this request. Start at the first page.");
    }
}
