/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 CloudBees Inc.
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

package org.jenkinsci.plugins.pipeline.utility.steps.fs;

import hudson.model.Label;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.WithTimeout;

public class JackTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();
    private WorkflowJob p;

    @Before
    public void setup() throws Exception {
        j.createOnlineSlave(Label.get("slaves"));
        p = j.jenkins.createProject(WorkflowJob.class, "p");
    }

    @WithTimeout(0)
    @Test
    public void listSomeWithExclusions() throws Exception {
        String flow = 
                "node('slaves') {\n" +
                        "  dir('/Users/jgreen/git/hazelcast-mono/hazelcast/hazelcast-tpc-engine') {\n" +
                        "    def files = findFiles(glob: '**/*')\n" +
                        "    echo \"${files.length} files\"\n" +
                        "    for(int i = 0; i < files.length; i++) {\n" +
                        "        if (files[i].isDirectory()) {\n" + 
                        "          continue\n" + 
                        "        }\n" + 
                        "        def text2 = readFile file: files[i].path\n" + 
                        "        def text3 = text2.replaceFirst(\"(?<=https?://(www\\\\.)?hazelcast\\\\.com/.{1,999}/hazelcast-.{1,999}-)\\\\Q5.5\\\\E(?=\\\\.xsd)\\n\", \"5.6\")\n" + 
                        //"        writeFile file: files[i].path, text: text3, encoding: \"UTF-8\"\n" + 
                        "    }\n" +
                        //"    for(int i = 0; i < files.length; i++) {\n" +
                        //"      echo \"F: ${files[i].path.replace('\\\\', '/')}\"\n" +
                        //"    }\n" +
                        "  }\n" +
                        "}";
        p.setDefinition(new CpsFlowDefinition(flow, true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));

        System.out.println(JenkinsRule.getLog(run));
    }
}
