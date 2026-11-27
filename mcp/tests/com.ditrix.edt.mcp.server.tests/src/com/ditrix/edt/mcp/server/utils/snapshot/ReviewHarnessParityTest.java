/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The oracle directory is an export of opencode-1c-harness a1f5d759, independent of the server. */
public class ReviewHarnessParityTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final UUID version = UUID.randomUUID();
    private final ProjectSnapshotPages pages = new ProjectSnapshotPages();
    private final List<ProjectSourceManifest.Entry> entries = new ArrayList<>();

    private record Output(int status, String text) { }

    private Output run(Path root, String... args) throws Exception
    {
        var pb = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true);
        pb.environment().put("PYTHONUTF8","1");
        var proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");
        return new Output(proc.waitFor(),out);
    }

    private void git(Path root, String... args) throws Exception
    {
        var command = new ArrayList<>(List.of("git","-c","user.name=Test","-c","user.email=t@example.invalid","-c","commit.gpgSign=false"));
        command.addAll(List.of(args));
        Output out = run(root,command.toArray(String[]::new));
        assertEquals(out.text(),0,out.status());
    }

    private JsonObject page(String side, String view, String file, String literal, String scope) throws IOException
    {
        var project = new ProjectSourceManifest.Project("P","refs/heads/main","head",entries,false);
        var query = new ProjectVersionReader.Query("repo","P",version,side,scope,view,file,literal,null);
        return JsonParser.parseString(ProjectVersionReader.read(pages,query,project).toJson()).getAsJsonObject();
    }

    private static void write(Path root,String file,byte[] bytes) throws IOException
    {
        Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.write(path,bytes);
    }

    private void export(Path target,String side) throws IOException
    {
        for (var row : page(side,"manifest",null,null,null).getAsJsonArray("entries"))
        {
            var entry = row.getAsJsonObject();
            var content = page(side,"content",entry.get("fileId").getAsString(),null,null);
            assertTrue(content.get("complete").getAsBoolean());
            write(target,entry.get("path").getAsString(),Base64.getDecoder().decode(content.get("base64").getAsString()));
        }
    }

    @Test
    public void exportedApiBytesReproducePrepassExclusionsUuidXmlAndLiveMarkers() throws Exception
    {
        String location = System.getenv("EDT_MCP_REVIEW_HARNESS");
        assumeTrue(location != null);
        var harness = Path.of(location);
        var oracle = tmp.newFolder("oracle").toPath();
        var exported = tmp.newFolder("api").toPath();
        String uuid1 = "38ecdf43-3c5f-49b7-b0aa-26d365a57ee6";
        String uuid2 = "57ecdf43-3c5f-49b7-b0aa-26d365a57ee6";
        String mdo = "src/Configuration/Configuration.mdo";
        String bsl = "src/CommonModules/Caller/Module.bsl";
        String dcs = "src/Template.dcs";
        String exclusion = "openspec/changes/feature/bsl-prepass-exclusions.txt";
        for (var root : List.of(oracle,exported))
        {
            git(root,"init","-q");
            git(root,"config","core.autocrlf","false");
        }
        for (var part : List.of(new String[]{mdo,"<Configuration uuid=\"" + uuid1 + "\"/>","<Configuration uuid=\"" + uuid2 + "\"/>"},
            new String[]{bsl,"// old\n","// — excluded\n// — finding\n// @skip-check\n"}, new String[]{dcs,"<xml/>","<broken>\n</bad>"}))
        {
            byte[] before = part[1].getBytes(StandardCharsets.UTF_8);
            byte[] after = part[2].getBytes(StandardCharsets.UTF_8);
            write(oracle,part[0],before);
            var head = new ProjectSourceManifest.Version(before);
            entries.add(new ProjectSourceManifest.Entry(part[0],head,head,new ProjectSourceManifest.Version(after)));
        }
        export(exported,"head");
        for (var root : List.of(oracle,exported)) { git(root,"add","."); git(root,"commit","-qm","base"); }
        for (var e : entries) { write(oracle,e.path(),e.disk().bytes()); }
        export(exported,"disk");
        write(oracle,exclusion,(bsl + ":1:em-dash approved\n").getBytes(StandardCharsets.UTF_8));
        var q = new ChangeArtifacts.Query("repo","P","refs/heads/main","feature",null);
        com.ditrix.edt.mcp.server.protocol.ToolResult artifact;
        try (var git = org.eclipse.jgit.api.Git.open(oracle.toFile()))
        {
            artifact = ChangeArtifacts.result(q,new ChangeArtifacts.Key("prepassExclusions",null),null,
            new ChangeArtifacts.Disk(WindowsSourceTree.load(),git.getRepository(),oracle.resolve("openspec/changes/feature")));
        }
        write(exported,exclusion,Base64.getDecoder().decode(JsonParser.parseString(artifact.toJson()).getAsJsonObject().get("base64").getAsString()));
        var bash = System.getenv("EDT_MCP_REVIEW_BASH");
        var python = System.getenv("EDT_MCP_REVIEW_PYTHON");
        var checks = List.of(new String[]{bash,harness.resolve("content/tools/bsl-prepass.sh").toString(),"--change","feature"},
            // The POSIX wrapper compares pwd -P with git's top-level path. Git for Windows prints a drive path.
            new String[]{bash,"-c","git() { if [[ $1 == rev-parse && $2 == --show-toplevel ]]; then "
                + "local top; top=$(command git \"$@\") || return; (cd \"$top\" && pwd -P); else command git \"$@\"; fi; }; "
                + "source \"$@\"","uuid-check",harness.resolve("content/skills/1c-edt-metadata/tools/edt-uuid-check.sh").toString(),mdo},
            new String[]{python,harness.resolve("content/skills/1c-edt-metadata/tools/dcs-check.py").toString(),dcs});
        for (int i = 0; i < checks.size(); i++)
        {
            Output expected = run(oracle,checks.get(i));
            assertEquals(expected.text(), 1, expected.status());
            assertEquals(expected,run(exported,checks.get(i)));
            switch (i)
            {
                case 0 -> { assertTrue(expected.text(),expected.text().contains(bsl + ":2:em-dash")); assertFalse(expected.text().contains(":1:em-dash")); }
                case 1 -> { assertTrue(expected.text(),expected.text().contains(uuid1)); assertTrue(expected.text().contains(uuid2)); }
                case 2 -> assertTrue(expected.text(),expected.text().contains("2"));
            }
        }
        var marker = page("disk","literalSearch",null,"@skip-check",null).getAsJsonArray("entries");
        assertEquals(1,marker.size());
        assertEquals(bsl,marker.get(0).getAsJsonObject().get("path").getAsString());
        var hit = marker.get(0).getAsJsonObject();
        byte[] fileBytes = Base64.getDecoder().decode(page("disk","content",hit.get("fileId").getAsString(),null,null)
            .get("base64").getAsString());
        String prefix = new String(fileBytes,0,hit.get("byteOffset").getAsInt(),StandardCharsets.UTF_8);
        long line = 1 + prefix.chars().filter(c -> c == 10).count();
        assertEquals(bsl + ":" + line + ":// @skip-check",run(oracle,"git","grep","-n","-F","@skip-check","--",bsl).text().trim());
        assertFalse(page("disk","literalSearch",null,"@skip-check","repository").get("complete").getAsBoolean());
    }
}
