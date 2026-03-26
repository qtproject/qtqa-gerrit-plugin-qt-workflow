// Copyright (C) 2025 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.account.AccountOperations;
import com.google.gerrit.acceptance.testsuite.group.GroupOperationsImpl;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.server.restapi.group.AddMembers;
import com.google.inject.Inject;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
public class QtAddMembersIT extends QtCodeReviewIT {

  @Inject private AccountOperations accountOperations;

  @Inject private GroupOperationsImpl groupOperations;

  private String member1Id;
  private String member2Id;
  private String member3Id;

  @Before
  public void createTestAccounts() {
    member1Id =
        accountOperations
            .newAccount()
            .fullname("Developer 1")
            .username("user_1")
            .preferredEmail("dev1@atestuser.com")
            .active()
            .create()
            .toString();
    member2Id =
        accountOperations
            .newAccount()
            .fullname("Developer 2")
            .username("user_2")
            .preferredEmail("dev2@atestuser.com")
            .inactive()
            .create()
            .toString();
    member3Id =
        accountOperations
            .newAccount()
            .fullname("Developer 3")
            .username("user_3")
            .preferredEmail("dev3@atestuser.com")
            .active()
            .create()
            .toString();
  }

  @Test
  public void addMembersByID() throws Exception {
    AccountGroup.UUID group = groupOperations.newGroup().name("A New Group").create();

    List<String> toBeAdded = Arrays.asList(member1Id, member2Id, member3Id);
    AddMembers.Input input = AddMembers.Input.fromMembers(toBeAdded);
    RestResponse response = qtAddMembers(group.get(), input);
    response.assertOK();

    List<AccountInfo> members = gApi.groups().id(group.get()).members();
    assertThat(members.get(0)._accountId).isEqualTo(Integer.parseInt(member1Id));
    assertThat(members.get(1)._accountId).isEqualTo(Integer.parseInt(member2Id));
    assertThat(members.get(2)._accountId).isEqualTo(Integer.parseInt(member3Id));
  }

  @Test
  public void addMembersByUsename() throws Exception {
    AccountGroup.UUID group = groupOperations.newGroup().name("A New Group").create();

    List<String> toBeAdded = Arrays.asList("user_1", "user_2", "user_3");
    AddMembers.Input input = AddMembers.Input.fromMembers(toBeAdded);
    RestResponse response = qtAddMembers(group.get(), input);
    response.assertOK();

    List<AccountInfo> members = gApi.groups().id(group.get()).members();
    assertThat(members.get(0)._accountId).isEqualTo(Integer.parseInt(member1Id));
    assertThat(members.get(1)._accountId).isEqualTo(Integer.parseInt(member2Id));
    assertThat(members.get(2)._accountId).isEqualTo(Integer.parseInt(member3Id));
  }

  @Test
  public void addMembersNoVisibilityExpectFail() throws Exception {
    AccountGroup.UUID group = groupOperations.newGroup().name("A New Group").create();

    List<String> toBeAdded = Arrays.asList(member1Id, member2Id, member3Id);
    AddMembers.Input input = AddMembers.Input.fromMembers(toBeAdded);
    RestResponse response =
        userRestSession.post(
            "/groups/" + group.get() + "/gerrit-plugin-qt-workflow~members.add", input);
    response.assertNotFound();
  }

  @Test
  public void addMembersNoPermissionExpectFail() throws Exception {
    AccountGroup.UUID group = groupOperations.newGroup().name("A New Group").create();
    AccountGroup.UUID owner = groupOperations.newGroup().name("An Owner Group").create();
    gApi.groups().id(group.get()).owner(owner.get());

    // Add test user to the group to have visibility
    gApi.groups().id(group.get()).addMembers(user.username());

    List<String> toBeAdded = Arrays.asList(member1Id, member2Id, member3Id);
    AddMembers.Input input = AddMembers.Input.fromMembers(toBeAdded);
    RestResponse response =
        userRestSession.post(
            "/groups/" + group.get() + "/gerrit-plugin-qt-workflow~members.add", input);
    response.assertForbidden();
    assertThat(response.getEntityContent()).contains("Cannot add members to group");
  }

  private RestResponse qtAddMembers(String group, AddMembers.Input membersInput) throws Exception {
    return adminRestSession.post(
        "/groups/" + group + "/gerrit-plugin-qt-workflow~members.add", membersInput);
  }
}
