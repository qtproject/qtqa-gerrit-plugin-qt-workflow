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
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtAbandonIT extends QtCodeReviewIT {

  @Before
  public void SetDefaultPermissions() throws Exception {
    grant(project, "refs/heads/master", Permission.ABANDON, false, REGISTERED_USERS);
  }

  @Test
  public void singleChange_Defer_QtAbandon() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    QtDefer(c);
    qtAbandon(c);
  }

  @Test
  public void singleChange_Defer_QtAbandon_With_Input_Messsage() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    String changeId = c.getChangeId();

    QtDefer(c);

    AbandonInput abandonInput = new AbandonInput();
    abandonInput.message = "myabandonednote";

    String url = "/changes/" + changeId + "/gerrit-plugin-qt-workflow~abandon";
    RestResponse response = userRestSession.post(url, abandonInput);
    response.assertOK();
    Change change = c.getChange().change();
    assertThat(change.getStatus()).isEqualTo(Change.Status.ABANDONED);
  }

  @Test
  public void errorQtAbandon_No_Permission() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    QtDefer(c);

    deny(project, "refs/heads/master", Permission.ABANDON, REGISTERED_USERS);
    RestResponse response = qtAbandonExpectFail(c, HttpStatus.SC_FORBIDDEN);
    assertThat(response.getEntityContent()).isEqualTo("abandon not permitted");
    grant(project, "refs/heads/master", Permission.ABANDON, false, REGISTERED_USERS);
  }

  @Test
  public void errorQtAbandon_Wrong_Status() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    RestResponse response = qtAbandonExpectFail(c, HttpStatus.SC_CONFLICT);
    assertThat(response.getEntityContent()).isEqualTo("change is new");
  }

  private void qtAbandon(PushOneCommit.Result c) throws Exception {
    RestResponse response = call_REST_API_QtAbandon(c.getChangeId());
    response.assertOK();

    Change change = c.getChange().change();
    assertThat(change.getStatus()).isEqualTo(Change.Status.ABANDONED);
  }

  private RestResponse qtAbandonExpectFail(PushOneCommit.Result c, int expectedStatus)
      throws Exception {
    RestResponse response = call_REST_API_QtAbandon(c.getChangeId());
    response.assertStatus(expectedStatus);

    Change change = c.getChange().change();
    assertThat(change.getStatus()).isNotEqualTo(Change.Status.ABANDONED);

    return response;
  }

  private RestResponse call_REST_API_QtAbandon(String changeId) throws Exception {
    String url = "/changes/" + changeId + "/gerrit-plugin-qt-workflow~abandon";
    RestResponse response = userRestSession.post(url);
    return response;
  }
}
