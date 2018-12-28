// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;

import com.google.gerrit.common.data.Permission;
import com.google.gerrit.extensions.api.changes.RestoreInput;
import com.google.gerrit.reviewdb.client.Change;

import org.eclipse.jgit.revwalk.RevCommit;

import org.junit.Before;
import org.junit.Test;

import org.apache.http.HttpStatus;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule"
)

@UseSsh
public class QtReOpenIT extends QtCodeReviewIT {

    @Before
    public void SetDefaultPermissions() throws Exception {
        grant(project, "refs/heads/master", Permission.ABANDON, false, REGISTERED_USERS);
    }

    @Test
    public void singleChange_New_Defer_ReOpen() throws Exception {
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

        QtDefer(c);
        RevCommit updatedHead = qtReOpen(c, initialHead);
    }

    @Test
    public void singleChange_Defer_ReOpen_With_Input_Messsage() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        String changeId = c.getChangeId();

        QtDefer(c);

        RestoreInput restoreInput = new RestoreInput();
        restoreInput.message = "myrestorenote";

        String url = "/changes/" + changeId + "/gerrit-plugin-qt-workflow~reopen";
        RestResponse response = adminRestSession.post(url, restoreInput);
        response.assertOK();
        Change change = c.getChange().change();
        assertThat(change.getStatus()).isEqualTo(Change.Status.NEW);
    }

    @Test
    public void errorReOpen_No_Permission() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        QtDefer(c);

        deny(project, "refs/heads/master", Permission.ABANDON, REGISTERED_USERS);
        RestResponse response = qtReOpenExpectFail(c, HttpStatus.SC_FORBIDDEN);
        assertThat(response.getEntityContent()).isEqualTo("restore not permitted");
        grant(project, "refs/heads/master", Permission.ABANDON, false, REGISTERED_USERS);
    }

    @Test
    public void errorReOpen_Wrong_Status() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        RestResponse response = qtReOpenExpectFail(c, HttpStatus.SC_CONFLICT);
        assertThat(response.getEntityContent()).isEqualTo("change is new");
    }

    private RevCommit qtReOpen(PushOneCommit.Result c,
                               RevCommit initialHead)
                               throws Exception {
        String masterRef = R_HEADS + "master";
        String stagingRef = R_STAGING + "master";

        RestResponse response = call_REST_API_ReOpen(c.getChangeId());
        response.assertOK();

        RevCommit masterHead = getRemoteHead(project, masterRef);
        assertThat(masterHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

        RevCommit stagingHead = getRemoteHead(project, stagingRef);
        if (stagingHead != null) assertThat(stagingHead.getId()).isEqualTo(initialHead.getId()); // staging is not updated

        assertRefUpdatedEvents(masterRef);   // no events
        assertRefUpdatedEvents(stagingRef); // no events

        Change change = c.getChange().change();
        assertThat(change.getStatus()).isEqualTo(Change.Status.NEW);

        return masterHead;
    }

    private RestResponse qtReOpenExpectFail(PushOneCommit.Result c,
                                            int expectedStatus)
                                            throws Exception {
        RestResponse response = call_REST_API_ReOpen(c.getChangeId());
        response.assertStatus(expectedStatus);
        return response;
    }

    private RestResponse call_REST_API_ReOpen(String changeId) throws Exception {
        String url = "/changes/" + changeId + "/gerrit-plugin-qt-workflow~reopen";
        RestResponse response = userRestSession.post(url);
        return response;
    }

}
