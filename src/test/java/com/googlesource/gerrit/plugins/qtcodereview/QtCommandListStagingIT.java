// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;

import com.google.gerrit.common.data.Permission;

import org.eclipse.jgit.revwalk.RevCommit;

import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule"
)

@UseSsh
public class QtCommandListStagingIT extends QtCodeReviewIT {

    @Before
    public void SetDefaultPermissions() throws Exception {
        grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
        grant(project, "refs/staging/*", Permission.PUSH, false, adminGroupUuid());
        grant(project, "refs/builds/*", Permission.CREATE, false, adminGroupUuid());
    }

    @Test
    public void multiChange_ListStaging() throws Exception {
        // Push 3 independent commits
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
        testRepo.reset(initialHead);
        PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
        testRepo.reset(initialHead);
        PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");

        approve(c1.getChangeId());
        QtStage(c1);
        RevCommit stagingHead1 = getRemoteHead(project, R_STAGING + "master");

        approve(c2.getChangeId());
        QtStage(c2);
        RevCommit stagingHead2 = getRemoteHead(project, R_STAGING + "master");

        approve(c3.getChangeId());
        QtStage(c3);
        RevCommit stagingHead3 = getRemoteHead(project, R_STAGING + "master");

        String result = qtListStaging("refs/staging/master", "master");
        assertThat(result).contains(stagingHead1.getId().name());
        assertThat(result).contains(stagingHead2.getId().name());
        assertThat(result).contains(stagingHead3.getId().name());

        QtNewBuild("master", "test-build-251");
        result = qtListStaging("refs/staging/master", "refs/heads/master");
        assertThat(result).contains(stagingHead1.getId().name());
        assertThat(result).contains(stagingHead2.getId().name());
        assertThat(result).contains(stagingHead3.getId().name());

        result = qtListStaging("refs/builds/test-build-251", "master");
        assertThat(result).contains(stagingHead1.getId().name());
        assertThat(result).contains(stagingHead2.getId().name());
        assertThat(result).contains(stagingHead3.getId().name());
    }

    @Test
    public void errorListStaging_RepoNotFound() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        String commandStr;
        commandStr ="gerrit-plugin-qt-workflow staging-ls";
        commandStr += " --project notarepo" ;
        commandStr += " --branch refs/staging/master";
        commandStr += " --destination master";
        String resultStr = adminSshSession.exec(commandStr);
        assertThat(adminSshSession.getError()).contains("project not found");
    }

    @Test
    public void errorListStaging_InvalidBranch() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        String resultStr = qtListStagingExpectFail("refs/staging/invalidref", "master");
        assertThat(resultStr).contains("branch ref not found");
    }

    private String qtListStaging(String ref,
                                 String destination)
                                 throws Exception {
        String commandStr;
        commandStr ="gerrit-plugin-qt-workflow staging-ls";
        commandStr += " --project " + project.get();
        commandStr += " --branch " + ref;
        commandStr += " --destination " + destination;
        String resultStr = adminSshSession.exec(commandStr);
        assertThat(adminSshSession.getError()).isNull();

        return resultStr;
    }

    private String qtListStagingExpectFail(String ref,
                                           String destination)
                                           throws Exception {
        String commandStr;
        commandStr ="gerrit-plugin-qt-workflow staging-ls";
        commandStr += " --project " + project.get();
        commandStr += " --branch " + ref;
        commandStr += " --destination " + destination;
        String resultStr = adminSshSession.exec(commandStr);
        assertThat(adminSshSession.getError()).isNotNull();

        return adminSshSession.getError();
    }

}
