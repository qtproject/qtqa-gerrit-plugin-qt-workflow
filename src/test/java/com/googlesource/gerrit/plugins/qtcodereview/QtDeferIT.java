// Copyright (C) 2018 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.reviewdb.client.Change;
import org.apache.http.HttpStatus;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtDeferIT extends QtCodeReviewIT {

  @Before
  public void SetDefaultPermissions() throws Exception {
    grant(project, "refs/heads/master", Permission.ABANDON, false, REGISTERED_USERS);
  }

  @Test
  public void singleChange_Defer() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    RevCommit updatedHead = qtDefer(c, initialHead);
  }

  @Test
  public void singleChange_Defer_With_Input_Message() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    String changeId = c.getChangeId();

    AbandonInput abandonInput = new AbandonInput();
    abandonInput.message = "myabandonednote";

    String url = "/changes/" + changeId + "/gerrit-plugin-qt-workflow~defer";
    RestResponse response = userRestSession.post(url, abandonInput);
    response.assertOK();
    Change change = c.getChange().change();
    assertThat(change.getStatus()).isEqualTo(Change.Status.DEFERRED);
  }

  @Test
  public void errorDefer_No_Permission() throws Exception {
    deny(project, "refs/heads/master", Permission.ABANDON, REGISTERED_USERS);

    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    RestResponse response = qtDeferExpectFail(c, HttpStatus.SC_FORBIDDEN);
    assertThat(response.getEntityContent()).isEqualTo("abandon not permitted");

    grant(project, "refs/heads/master", Permission.ABANDON, false, REGISTERED_USERS);
  }

  @Test
  public void errorDefer_Wrong_Status() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    merge(c);

    RestResponse response = qtDeferExpectFail(c, HttpStatus.SC_CONFLICT);
    assertThat(response.getEntityContent()).isEqualTo("change is merged");
  }

  private RevCommit qtDefer(PushOneCommit.Result c, RevCommit initialHead) throws Exception {
    String masterRef = R_HEADS + "master";
    String stagingRef = R_STAGING + "master";

    RestResponse response = call_REST_API_Defer(c.getChangeId());
    response.assertOK();

    RevCommit masterHead = getRemoteHead(project, masterRef);
    assertThat(masterHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

    RevCommit stagingHead = getRemoteRefHead(project, stagingRef);
    if (stagingHead != null)
      assertThat(stagingHead.getId()).isEqualTo(initialHead.getId()); // staging is not updated

    assertRefUpdatedEvents(masterRef); // no events
    assertRefUpdatedEvents(stagingRef); // no events

    Change change = c.getChange().change();
    assertThat(change.getStatus()).isEqualTo(Change.Status.DEFERRED);

    return masterHead;
  }

  private RestResponse qtDeferExpectFail(PushOneCommit.Result c, int expectedStatus)
      throws Exception {
    RestResponse response = call_REST_API_Defer(c.getChangeId());
    response.assertStatus(expectedStatus);

    Change change = c.getChange().change();
    assertThat(change.getStatus()).isNotEqualTo(Change.Status.DEFERRED);

    return response;
  }
}
