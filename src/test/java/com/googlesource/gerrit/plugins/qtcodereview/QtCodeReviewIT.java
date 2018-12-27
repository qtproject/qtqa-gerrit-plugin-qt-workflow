// Copyright (C) 2018 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;

import com.google.gerrit.reviewdb.client.Change;

import org.eclipse.jgit.revwalk.RevCommit;

import org.junit.Test;


@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule"
)

public class QtCodeReviewIT extends LightweightPluginDaemonTest {

    protected static final String R_HEADS = "refs/heads/";
    protected static final String R_STAGING = "refs/staging/";
    protected static final String R_PUSH = "refs/for/";

    @Test
    public void dummyTest() {

    }


// Helper functions

    protected void QtDefer(PushOneCommit.Result c) throws Exception {
        RestResponse response = call_REST_API_Defer(c.getChangeId());
        response.assertOK();
    }

    protected RestResponse call_REST_API_Defer(String changeId) throws Exception {
        String url = "/changes/"+changeId+"/gerrit-plugin-qt-workflow~defer";
        RestResponse response = userRestSession.post(url);
        return response;
    }

    protected PushOneCommit.Result pushCommit(String branch,
                                             String message,
                                             String file,
                                             String content)
                                             throws Exception {
        String pushRef = R_PUSH + branch;
        PushOneCommit.Result c = createUserChange(pushRef, message, file, content);
        Change change = c.getChange().change();
        assertThat(change.getStatus()).isEqualTo(Change.Status.NEW);
        return c;
    }

    protected PushOneCommit.Result createUserChange(String ref, String message, String file, String content) throws Exception {
        PushOneCommit push = pushFactory.create(db, user.getIdent(), testRepo, message, file, content);
        PushOneCommit.Result result = push.to(ref);
        result.assertOkStatus();
        return result;
    }

    protected void assertRefUpdatedEvents(String refName, RevCommit ... expected) throws Exception {
        eventRecorder.assertRefUpdatedEvents(project.get(), refName, expected);
    }

}
