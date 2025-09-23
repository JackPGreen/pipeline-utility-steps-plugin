/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2021 Alexander Falkenstern
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.jenkinsci.plugins.pipeline.utility.steps.tar;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Label;
import hudson.model.Result;
import hudson.model.Run;
import java.io.IOException;
import java.util.Scanner;
import jenkins.util.VirtualFile;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for {@link TarStep}.
 *
 * @author Alexander Falkenstern &lt;Alexander.Falkenstern@gmail.com&gt;.
 */
@WithJenkins
class TarStepTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.createOnlineSlave(Label.get("slaves"));
    }

    @Test
    void configRoundTrip() throws Exception {
        TarStep step = new TarStep("target/my.tgz");
        step.setDir("base/");
        step.setGlob("**/*.tgz");
        step.setExclude("**/*.txt");
        step.setArchive(true);
        step.setCompress(true);
        step.setOverwrite(true);

        TarStep step2 = new StepConfigTester(j).configRoundTrip(step);
        j.assertEqualDataBoundBeans(step, step2);
    }

    @Test
    void simpleArchivedTar() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node('slaves') {
                          writeFile file: 'hello.txt', text: 'Hello Outer World!'
                          dir('hello') {
                            writeFile file: 'hello.txt', text: 'Hello World!'
                          }
                          tar file: 'hello.tar', dir: 'hello', archive: true, compress: false
                        }""",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        j.assertLogContains("Compress", run);
        j.assertLogContains("Archiving", run);
        verifyArchivedHello(run, "");
    }

    @Test
    void shouldNotPutOutputArchiveIntoItself() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node {\
                          writeFile file: 'hello.txt', text: 'Hello world'
                          tar file: 'output.tgz', dir: '', glob: '', archive: true, overwrite: true
                        }""",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        j.assertLogContains("Compress", run);
        verifyArchivedNotContainingItself(run);
    }

    @Test
    void shouldNotPutOutputArchiveIntoItself_nonCanonicalPath() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node {\
                          dir ('src') {
                           writeFile file: 'hello.txt', text: 'Hello world'
                          }
                          tar file: 'src/../src/output.tgz', dir: '', glob: '', archive: true
                        }""",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        j.assertLogContains("Compress", run);
        verifyArchivedNotContainingItself(run);
    }

    @Test
    void canArchiveFileWithSameName() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node {
                          dir ('src') {
                           writeFile file: 'hello.txt', text: 'Hello world'
                           writeFile file: 'output.tgz', text: 'not really a tar'
                          }
                          dir ('out') {
                            tar file: 'output.tgz', dir: '../src', glob: '', archive: true, overwrite: true
                          }
                        }
                        """,
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));

        j.assertLogContains("Compress", run);
        assertTrue(run.getHasArtifacts(), "Build should have artifacts");
        Run<WorkflowJob, WorkflowRun>.Artifact artifact = run.getArtifacts().get(0);
        assertEquals("output.tgz", artifact.getFileName());
        VirtualFile file = run.getArtifactManager().root().child(artifact.relativePath);
        try (GzipCompressorInputStream compressor = new GzipCompressorInputStream(file.open());
                TarArchiveInputStream tar = new TarArchiveInputStream(compressor)) {
            ArchiveEntry entry = tar.getNextEntry();
            while (entry != null && !entry.getName().equals("output.tgz")) {
                System.out.println("zip entry name is: " + entry.getName());
                entry = tar.getNextEntry();
            }
            assertNotNull(entry, "output.tgz should be included in the tgz");
            // we should have the tgz - but double check
            assertEquals("output.tgz", entry.getName());
            Scanner scanner = new Scanner(tar);
            assertTrue(scanner.hasNextLine());
            // the file that was not a tar should be included.
            assertEquals("not really a tar", scanner.nextLine());
        }
    }

    @Test
    void globbedArchivedTar() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node('slaves') {
                          writeFile file: 'hello.outer', text: 'Hello Outer World!'
                          dir('hello') {
                            writeFile file: 'hello.txt', text: 'Hello World!'
                          }
                          tar file: 'hello.tar', glob: '**/*.txt', archive: true, compress: false
                        }""",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        verifyArchivedHello(run, "hello/");
    }

    @Test
    void excludedPatternWithAll() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node('slaves') {
                          writeFile file: 'hello.txt', text: 'Hello World!'
                          writeFile file: 'goodbye.txt', text: 'Goodbye World!'
                          tar file: 'hello.tar', exclude: 'goodbye.txt', archive: true, compress: false
                        }""",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        verifyArchivedHello(run, "");
    }

    @Test
    void excludedPatternWithGlob() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node('slaves') {
                          writeFile file: 'hello.txt', text: 'Hello World!'
                          writeFile file: 'goodbye.txt', text: 'Goodbye World!'
                          tar file: 'hello.tar', glob: '*.txt', exclude: 'goodbye.txt', archive: true, compress: false
                        }""",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        verifyArchivedHello(run, "");
    }

    @Test
    void emptyTarFile() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node('slaves') {
                          tar file: '', glob: '**/*.txt', archive: true
                        }""",
                true));
        WorkflowRun run =
                j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains("Can not be empty", run);
    }

    @Test
    void existingTarFileWithoutOverwrite() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node('slaves') {
                          writeFile file: 'hello.tar.gz', text: 'Hello Tar!'
                          tar file: 'hello.tar.gz', glob: '**/*.txt', archive: true
                        }""",
                true));
        WorkflowRun run =
                j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains("hello.tar.gz exists.", run);
    }

    @Test
    void existingTarFileWithOverwrite() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node('slaves') {
                          writeFile file: 'hello.tgz', text: 'Hello Tar!'
                          writeFile file: 'hello.txt', text: 'Hello world'
                          tar file: 'hello.tgz', glob: '**/*.txt', archive: true, overwrite:true
                        }""",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        j.assertLogNotContains("hello.tgz exists.", run);
    }

    @Test
    void noExistingTarFileWithOverwrite() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node('slaves') {
                          writeFile file: 'hello.txt', text: 'Hello world'
                          tar file: 'hello.tgz', glob: '**/*.txt', overwrite:true
                        }""",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        j.assertLogNotContains("java.io.IOException", run);
        j.assertLogNotContains("Failed to delete", run);
        j.assertLogContains("Compressed 1 entries.", run);
    }

    private void verifyArchivedHello(WorkflowRun run, String basePath) throws IOException {
        assertTrue(run.getHasArtifacts(), "Build should have artifacts");
        Run<WorkflowJob, WorkflowRun>.Artifact artifact = run.getArtifacts().get(0);
        assertEquals("hello.tar", artifact.getFileName());

        VirtualFile file = run.getArtifactManager().root().child(artifact.relativePath);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(file.open())) {
            ArchiveEntry entry = tar.getNextEntry();
            while (entry.isDirectory()) {
                entry = tar.getNextEntry();
            }
            assertNotNull(entry);
            assertEquals(basePath + "hello.txt", entry.getName());
            try (Scanner scanner = new Scanner(tar)) {
                assertTrue(scanner.hasNextLine());
                assertEquals("Hello World!", scanner.nextLine());
                assertNull(tar.getNextEntry(), "There should be no more entries");
            }
        }
    }

    private void verifyArchivedNotContainingItself(WorkflowRun run) throws IOException {
        assertTrue(run.getHasArtifacts(), "Build should have artifacts");

        Run<WorkflowJob, WorkflowRun>.Artifact artifact = run.getArtifacts().get(0);
        VirtualFile file = run.getArtifactManager().root().child(artifact.relativePath);
        try (GzipCompressorInputStream compressor = new GzipCompressorInputStream(file.open());
                TarArchiveInputStream tar = new TarArchiveInputStream(compressor)) {
            for (ArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                assertNotEquals(entry.getName(), artifact.relativePath, "The zip output file shouldn't contain itself");
            }
        }
    }

    @Test
    void defaultExcludesPatternWithAll() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node('slaves') {
                          writeFile file: '.gitignore', text: '/target'
                          tar file: 'hello.tar', defaultExcludes: false , archive: true, compress: false
                        }""",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        verifyArchivedDefaultExcludes(run, "");
    }

    private void verifyArchivedDefaultExcludes(WorkflowRun run, String basePath) throws IOException {
        assertTrue(run.getHasArtifacts(), "Build should have artifacts");
        Run<WorkflowJob, WorkflowRun>.Artifact artifact = run.getArtifacts().get(0);
        assertEquals("hello.tar", artifact.getFileName());

        VirtualFile file = run.getArtifactManager().root().child(artifact.relativePath);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(file.open())) {
            ArchiveEntry entry = tar.getNextEntry();
            while (entry.isDirectory()) {
                entry = tar.getNextEntry();
            }
            assertNotNull(entry);
            assertEquals(basePath + ".gitignore", entry.getName());
            try (Scanner scanner = new Scanner(tar)) {
                assertTrue(scanner.hasNextLine());
                assertEquals("/target", scanner.nextLine());
                assertNull(tar.getNextEntry(), "There should be no more entries");
            }
        }
    }

    @Test
    void defaultExcludesPatternEnabled() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                        node {\
                          dir ('src') {
                           writeFile file: '.gitignore', text: '/target'
                          }
                          tar file: 'src/../src/output.tgz', defaultExcludes: true, dir: '', glob: '', archive: true
                        }""",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        verifyArchivedNotContainingItself(run);
    }
}
