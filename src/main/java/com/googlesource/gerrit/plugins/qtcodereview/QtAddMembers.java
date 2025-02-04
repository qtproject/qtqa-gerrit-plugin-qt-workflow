//
// Copyright (C) 2025 The Qt Company
// Modified from
// https://gerrit.googlesource.com/gerrit/+/refs/tags/v3.8.8/java/com/google/gerrit/server/restapi/group/AddMembers.java
// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.GroupDescription;
import com.google.gerrit.exceptions.NoSuchGroupException;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.DefaultInput;
import com.google.gerrit.extensions.restapi.MethodNotAllowedException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.UserInitiated;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountLoader;
import com.google.gerrit.server.account.AccountManager;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.account.AccountResolver.UnresolvableAccountException;
import com.google.gerrit.server.account.AuthRequest;
import com.google.gerrit.server.account.GroupControl;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.group.GroupResource;
import com.google.gerrit.server.group.db.GroupDelta;
import com.google.gerrit.server.group.db.GroupsUpdate;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.qtcodereview.QtAddMembers.Input;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;

@Singleton
public class QtAddMembers implements RestModifyView<GroupResource, Input> {
  public static class Input {
    @DefaultInput String _oneMember;

    List<String> members;

    public static Input fromMembers(List<String> members) {
      Input in = new Input();
      in.members = members;
      return in;
    }

    static Input init(Input in) {
      if (in == null) {
        in = new Input();
      }
      if (in.members == null) {
        in.members = Lists.newArrayListWithCapacity(1);
      }
      if (!Strings.isNullOrEmpty(in._oneMember)) {
        in.members.add(in._oneMember);
      }
      return in;
    }
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final AccountResolver accountResolver;
  private final AccountLoader.Factory infoFactory;
  private final Provider<GroupsUpdate> groupsUpdateProvider;

  @Inject
  QtAddMembers(
      AccountManager accountManager,
      AuthConfig authConfig,
      AccountResolver accountResolver,
      AccountCache accountCache,
      AccountLoader.Factory infoFactory,
      @UserInitiated Provider<GroupsUpdate> groupsUpdateProvider,
      AuthRequest.Factory authRequestFactory) {
    this.accountResolver = accountResolver;
    this.infoFactory = infoFactory;
    this.groupsUpdateProvider = groupsUpdateProvider;
  }

  @Override
  public Response<List<AccountInfo>> apply(GroupResource resource, Input input)
      throws AuthException, MethodNotAllowedException, UnprocessableEntityException, IOException,
          ConfigInvalidException, ResourceNotFoundException, PermissionBackendException {
    GroupDescription.Internal internalGroup =
        resource
            .asInternalGroup()
            .orElseThrow(() -> new MethodNotAllowedException("not a Gerrit internal group"));
    input = Input.init(input);

    GroupControl control = resource.getControl();
    if (!control.canAddMember()) {
      logger.atInfo().log("Cannot add members to group");
      throw new AuthException("Cannot add members to group " + internalGroup.getName());
    }

    Set<Account.Id> newMemberIds = new LinkedHashSet<>();
    for (String nameOrEmailOrId : input.members) {
      Account a = findAccountIncludeInactive(nameOrEmailOrId);
      /* Allow adding inactive members to a group
      if (!a.isActive()) {
        throw new UnprocessableEntityException(
            String.format("Account Inactive: %s", nameOrEmailOrId));
      }
      */
      newMemberIds.add(a.id());
    }

    AccountGroup.UUID groupUuid = internalGroup.getGroupUUID();
    try {
      addMembers(groupUuid, newMemberIds);
    } catch (NoSuchGroupException e) {
      throw new ResourceNotFoundException(String.format("Group %s not found", groupUuid), e);
    }
    return Response.ok(toAccountInfoList(newMemberIds));
  }

  private List<AccountInfo> toAccountInfoList(Set<Account.Id> accountIds)
      throws PermissionBackendException {
    List<AccountInfo> result = new ArrayList<>();
    AccountLoader loader = infoFactory.create(true);
    for (Account.Id accId : accountIds) {
      result.add(loader.get(accId));
    }
    loader.fill();
    return result;
  }

  Account findAccountIncludeInactive(String nameOrEmailOrId)
      throws UnprocessableEntityException, IOException, ConfigInvalidException {
    AccountResolver.Result result = accountResolver.resolveIncludeInactive(nameOrEmailOrId);
    try {
      return result.asUnique().account();
    } catch (UnresolvableAccountException e) {
      throw e;
    }
  }

  public void addMembers(AccountGroup.UUID groupUuid, Set<Account.Id> newMemberIds)
      throws IOException, NoSuchGroupException, ConfigInvalidException {
    GroupDelta groupDelta =
        GroupDelta.builder()
            .setMemberModification(memberIds -> Sets.union(memberIds, newMemberIds))
            .build();
    groupsUpdateProvider.get().updateGroup(groupUuid, groupDelta);
  }
}
