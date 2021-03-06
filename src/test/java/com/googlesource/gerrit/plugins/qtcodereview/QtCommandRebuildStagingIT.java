// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;

import com.google.gerrit.common.data.Permission;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule"
)

@UseSsh
public class QtCommandRebuildStagingIT extends QtCodeReviewIT {

    @Before
    public void SetDefaultPermissions() throws Exception {
        grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
        grant(project, "refs/staging/*", Permission.PUSH, false, adminGroupUuid());
        grant(project, "refs/builds/*", Permission.CREATE, false, adminGroupUuid());
    }

    @Test
    public void multiChange_RebuildStaging() throws Exception {
        // Push 3 independent commits
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
        testRepo.reset(initialHead);
        PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
        testRepo.reset(initialHead);
        PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");
        approve(c1.getChangeId());
        approve(c2.getChangeId());
        approve(c3.getChangeId());
        QtStage(c1);
        QtStage(c2);
        QtStage(c3);
        RevCommit stagingExpected = getRemoteHead(project, R_STAGING + "master");

        RevCommit stagingHead = qtRebuildStaging("master", null, stagingExpected);
    }

    @Test
    public void multiChange_RebuildStaging_WhileBuilding() throws Exception {
        // Push 3 independent commits
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
        testRepo.reset(initialHead);
        PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
        testRepo.reset(initialHead);
        PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");
        approve(c1.getChangeId());
        approve(c2.getChangeId());
        approve(c3.getChangeId());
        QtStage(c1);
        QtStage(c2);
        QtNewBuild("master", "test-build-250");
        QtStage(c3);
        RevCommit stagingExpected = getRemoteHead(project, R_STAGING + "master");

        RevCommit stagingHead = qtRebuildStaging("master", null, stagingExpected);
    }

    @Test
    public void errorRebuildStaging_NoPermission() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        deny(project, "refs/heads/master", Permission.SUBMIT, REGISTERED_USERS);

        String commandStr;
        commandStr ="gerrit-plugin-qt-workflow staging-rebuild";
        commandStr += " --project " + project.get();
        commandStr += " --branch master";
        String resultStr = userSshSession.exec(commandStr);
        assertThat(userSshSession.getError()).contains("not authorized");

        grant(project, "refs/heads/master", Permission.SUBMIT, false, REGISTERED_USERS);
    }

    @Test
    public void errorRebuildStaging_RepoNotFound() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        String commandStr ="gerrit-plugin-qt-workflow staging-rebuild";
        commandStr +=      " --project notarepo --branch master";
        String resultStr = adminSshSession.exec(commandStr);
        assertThat(adminSshSession.getError()).contains("project not found");
    }

    @Test
    public void errorRebuildStaging_InvalidBranch() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        String resultStr = qtRebuildStagingExpectFail("invalidbranch");
        assertThat(resultStr).contains("branch staging ref not found");
    }

    private RevCommit qtRebuildStaging(String branch,
                                       PushOneCommit.Result expectedContent,
                                       RevCommit expectedStagingHead)
                                       throws Exception {
        String stagingRef = R_STAGING + branch;
        String branchRef = R_HEADS + branch;
        RevCommit initialHead = getRemoteHead(project, branchRef);
        RevCommit oldStagingHead = getRemoteHead(project, stagingRef);

        String commandStr;
        commandStr ="gerrit-plugin-qt-workflow staging-rebuild";
        commandStr += " --project " + project.get();
        commandStr += " --branch " + branch;
        String resultStr = adminSshSession.exec(commandStr);
        assertThat(adminSshSession.getError()).isNull();

        RevCommit updatedHead = getRemoteHead(project, branchRef);
        assertThat(updatedHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

        RevCommit stagingHead = getRemoteHead(project, stagingRef);
        assertReviewedByFooter(stagingHead, true);

        if (expectedStagingHead == null && expectedContent != null) {
            assertCherryPick(stagingHead, expectedContent.getCommit(), null);
            expectedStagingHead = stagingHead;
        } else {
            assertThat(stagingHead).isEqualTo(expectedStagingHead); // staging is updated
        }

        if (expectedStagingHead.equals(oldStagingHead)) {
            assertRefUpdatedEvents(stagingRef); // no events
        } else {
            assertRefUpdatedEvents(stagingRef, oldStagingHead, expectedStagingHead);
            resetEvents();
        }

        return stagingHead;
    }

    private String qtRebuildStagingExpectFail(String branch)
                                              throws Exception {
        String stagingRef = R_STAGING + branch;
        String branchRef = R_HEADS + branch;
        RevCommit initialHead = getRemoteHead(project, branchRef);
        RevCommit oldStagingHead = getRemoteHead(project, stagingRef);

        String commandStr;
        commandStr ="gerrit-plugin-qt-workflow staging-rebuild";
        commandStr += " --project " + project.get();
        commandStr += " --branch " + branch;
        String resultStr = adminSshSession.exec(commandStr);
        assertThat(adminSshSession.getError()).isNotNull();

        RevCommit updatedHead = getRemoteHead(project, branchRef);
        if (updatedHead != null) assertThat(updatedHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

        RevCommit stagingHead = getRemoteHead(project, stagingRef);
        if (stagingHead != null) assertThat(stagingHead.getId()).isEqualTo(oldStagingHead.getId()); // staging is not updated

        return adminSshSession.getError();
    }

}
